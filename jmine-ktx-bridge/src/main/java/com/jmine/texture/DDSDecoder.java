package com.jmine.texture;

import java.io.*;
import java.nio.*;

/**
 * DDS file decoder — reads DDS files with BC1/BC3/BC7 compression
 * and decodes them back to RGBA pixels for Minecraft's texture loader.
 *
 * This is the reverse of DDSCompressor: it takes a DDS file and produces
 * the raw RGBA pixel data that Minecraft's texture atlas system expects.
 */
public class DDSDecoder {

    // DDS magic number
    private static final int DDS_MAGIC = 0x20534D44; // "DDS "

    // DXGI formats
    private static final int DXGI_BC1_UNORM = 77;
    private static final int DXGI_BC3_UNORM = 79;
    private static final int DXGI_BC7_UNORM = 98;

    /**
     * Decoded DDS image data.
     */
    public static class DDSImage {
        public final byte[] pixels;  // RGBA, 4 bytes per pixel
        public final int width;
        public final int height;
        public final int format;     // DXGI format used

        DDSImage(byte[] pixels, int width, int height, int format) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    /**
     * Decode a complete DDS file to RGBA pixels.
     *
     * @param ddsData Full DDS file bytes
     * @return Decoded RGBA image (base mip level only)
     * @throws IOException if the DDS file is invalid
     */
    public static DDSImage decode(byte[] ddsData) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(ddsData).order(ByteOrder.LITTLE_ENDIAN);

        // Check magic
        int magic = buf.getInt();
        if (magic != DDS_MAGIC) {
            throw new IOException("Not a valid DDS file: invalid magic number");
        }

        // Read main header (124 bytes after magic)
        buf.getInt(); // dwSize
        int flags = buf.getInt(); // dwFlags
        int height = buf.getInt();
        int width = buf.getInt();
        int pitchOrLinearSize = buf.getInt();
        buf.getInt(); // dwDepth
        int mipMapCount = buf.getInt();

        // Skip reserved1[11]
        buf.position(buf.position() + 44);

        // Pixel format
        int pfSize = buf.getInt();
        int pfFlags = buf.getInt();
        int fourCC = buf.getInt();
        buf.position(buf.position() + 16); // Skip remaining pf fields

        // Skip caps and reserved2
        buf.position(buf.position() + 20);

        // Check for DX10 extension
        int formatType = 0;
        if (fourCC == 0x30315844) { // "DX10"
            // Read DX10 extension header
            formatType = buf.getInt();
            buf.getInt(); // resourceDim
            buf.getInt(); // miscFlag
            buf.getInt(); // arraySize
            buf.getInt(); // miscFlags2
        }

        if (formatType == 0) {
            throw new IOException("Unsupported DDS format: no DX10 extension");
        }

        // Determine bytes per block
        int bytesPerBlock;
        switch (formatType) {
            case DXGI_BC1_UNORM:
                bytesPerBlock = 8;
                break;
            case DXGI_BC3_UNORM:
            case DXGI_BC7_UNORM:
                bytesPerBlock = 16;
                break;
            default:
                throw new IOException("Unsupported BC format: " + formatType);
        }

        // Align dimensions
        int alignedW = ((width + 3) / 4) * 4;
        int alignedH = ((height + 3) / 4) * 4;

        // Calculate number of blocks
        int blocksX = alignedW / 4;
        int blocksY = alignedH / 4;

        // Allocate output buffer
        byte[] pixels = new byte[alignedW * alignedH * 4];

        // Decode each 4x4 block
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                byte[] block = new byte[bytesPerBlock];
                buf.get(block);

                decodeBlock(block, formatType, pixels, bx * 4, by * 4, width, height);
            }
        }

        return new DDSImage(pixels, width, height, formatType);
    }

    /**
     * Decode a single BC block to RGBA pixels.
     */
    private static void decodeBlock(byte[] block, int format, byte[] output,
                                     int startX, int startY, int imgWidth, int imgHeight) {
        switch (format) {
            case DXGI_BC1_UNORM:
                decodeBC1Block(block, output, startX, startY, imgWidth, imgHeight);
                break;
            case DXGI_BC3_UNORM:
                decodeBC3Block(block, output, startX, startY, imgWidth, imgHeight);
                break;
            case DXGI_BC7_UNORM:
                decodeBC7Block(block, output, startX, startY, imgWidth, imgHeight);
                break;
        }
    }

    /**
     * Decode a BC1 (DXT1) block — 8 bytes to 16 RGBA pixels.
     */
    private static void decodeBC1Block(byte[] block, byte[] output,
                                        int startX, int startY, int imgWidth, int imgHeight) {
        // Extract two 16-bit colors
        int color0 = (block[1] & 0xFF) << 8 | (block[0] & 0xFF);
        int color1 = (block[3] & 0xFF) << 8 | (block[2] & 0xFF);

        // Decode 5-6-5 RGB to 8-8-8
        int[] colors = new int[4];
        colors[0] = decode565(color0);
        colors[1] = decode565(color1);

        if (color0 > color1) {
            // Three color mode: interpolate
            colors[2] = (int) (((colors[0] * 2 + colors[1]) / 3) & 0xFFFFFF) | 0xFF000000;
            colors[3] = (int) (((colors[0] + colors[1] * 2) / 3) & 0xFFFFFF) | 0xFF000000;
        } else {
            // Two color mode + transparency
            colors[2] = (int) (((colors[0] + colors[1]) / 2) & 0xFFFFFF) | 0xFF000000;
            colors[3] = 0x00000000; // Transparent
        }

        // Selection indices
        int[] indices = new int[16];
        for (int i = 0; i < 4; i++) {
            int idxByte = block[4 + i] & 0xFF;
            for (int j = 0; j < 4; j++) {
                indices[i * 4 + j] = (idxByte >> (j * 2)) & 0x3;
            }
        }

        // Write pixels
        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 4; px++) {
                int x = startX + px;
                int y = startY + py;
                if (x >= imgWidth || y >= imgHeight) continue;

                int idx = indices[py * 4 + px];
                int color = colors[idx];
                int outIdx = (y * imgWidth + x) * 4;
                output[outIdx] = (byte) ((color >> 16) & 0xFF); // R
                output[outIdx + 1] = (byte) ((color >> 8) & 0xFF); // G
                output[outIdx + 2] = (byte) (color & 0xFF); // B
                output[outIdx + 3] = (byte) ((color >> 24) & 0xFF); // A
            }
        }
    }

    /**
     * Decode a BC3 (DXT5) block — 16 bytes to 16 RGBA pixels.
     */
    private static void decodeBC3Block(byte[] block, byte[] output,
                                        int startX, int startY, int imgWidth, int imgHeight) {
        // Alpha channel (8 bytes): two 8-bit alpha endpoints + interpolation
        int alpha0 = block[0] & 0xFF;
        int alpha1 = block[1] & 0xFF;
        int[] alphas;

        if (alpha0 > alpha1) {
            // 6-level interpolation
            alphas = new int[8];
            alphas[0] = alpha0;
            alphas[1] = alpha1;
            alphas[2] = (alpha0 * 6 + alpha1 * 2) / 8;
            alphas[3] = (alpha0 * 5 + alpha1 * 3) / 8;
            alphas[4] = (alpha0 * 4 + alpha1 * 4) / 8;
            alphas[5] = (alpha0 * 3 + alpha1 * 5) / 8;
            alphas[6] = (alpha0 * 2 + alpha1 * 6) / 8;
            alphas[7] = (alpha0 + alpha1 * 7) / 8;
        } else {
            // 4-level interpolation
            alphas = new int[8];
            alphas[0] = alpha0;
            alphas[1] = alpha1;
            alphas[2] = (alpha0 * 4 + alpha1 * 2) / 6;
            alphas[3] = (alpha0 * 3 + alpha1 * 3) / 6;
            alphas[4] = (alpha0 * 2 + alpha1 * 4) / 6;
            alphas[5] = (alpha0 + alpha1 * 5) / 6;
            alphas[6] = 0;
            alphas[7] = 255;
        }

        // Alpha indices (2 bits per pixel, packed)
        int[] alphaIndices = new int[16];
        for (int i = 0; i < 8; i++) {
            int val = block[i] & 0xFF;
            alphaIndices[i * 2] = val & 0x7;
            alphaIndices[i * 2 + 1] = (val >> 3) & 0x7;
        }

        // Color channel (8 bytes): same as BC1
        int color0 = (block[9] & 0xFF) << 8 | (block[8] & 0xFF);
        int color1 = (block[11] & 0xFF) << 8 | (block[10] & 0xFF);

        int[] colors = new int[4];
        colors[0] = decode565(color0);
        colors[1] = decode565(color1);

        if (color0 > color1) {
            colors[2] = (int) (((colors[0] * 2 + colors[1]) / 3) & 0xFFFFFF) | 0xFF000000;
            colors[3] = (int) (((colors[0] + colors[1] * 2) / 3) & 0xFFFFFF) | 0xFF000000;
        } else {
            colors[2] = (int) (((colors[0] + colors[1]) / 2) & 0xFFFFFF) | 0xFF000000;
            colors[3] = 0x00000000;
        }

        // Color indices
        int[] colorIndices = new int[16];
        for (int i = 0; i < 4; i++) {
            int idxByte = block[12 + i] & 0xFF;
            for (int j = 0; j < 4; j++) {
                colorIndices[i * 4 + j] = (idxByte >> (j * 2)) & 0x3;
            }
        }

        // Write pixels
        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 4; px++) {
                int x = startX + px;
                int y = startY + py;
                if (x >= imgWidth || y >= imgHeight) continue;

                int ci = colorIndices[py * 4 + px];
                int ai = alphaIndices[py * 4 + px];
                int color = colors[ci];
                int alpha = alphas[ai];

                int outIdx = (y * imgWidth + x) * 4;
                output[outIdx] = (byte) ((color >> 16) & 0xFF); // R
                output[outIdx + 1] = (byte) ((color >> 8) & 0xFF); // G
                output[outIdx + 2] = (byte) (color & 0xFF); // B
                output[outIdx + 3] = (byte) alpha; // A
            }
        }
    }

    /**
     * Decode a BC7 block — 16 bytes to 16 RGBA pixels.
     * Supports mode 0 (2-color RGBA interpolation).
     */
    private static void decodeBC7Block(byte[] block, byte[] output,
                                        int startX, int startY, int imgWidth, int imgHeight) {
        // Parse mode from first 3 bits
        int mode = block[0] & 0x7;

        if (mode == 0) {
            decodeBC7Mode0(block, output, startX, startY, imgWidth, imgHeight);
        } else {
            // Fallback: treat as BC3-like for unsupported modes
            decodeBC7Fallback(block, output, startX, startY, imgWidth, imgHeight);
        }
    }

    /**
     * Decode BC7 mode 0 — 2-color RGBA interpolation with 2-bit selection indices.
     */
    private static void decodeBC7Mode0(byte[] block, byte[] output,
                                        int startX, int startY, int imgWidth, int imgHeight) {
        // Parse partition (6 bits from bytes 0-1)
        int partition = (block[0] & 0x3F) | ((block[1] & 0x3F) << 6) |
                        ((block[2] & 0x03) << 12);

        // Parse 8-bit color endpoints (10 bytes for 2 RGBA colors)
        int p0 = (block[2] >> 2) & 0xFF;
        int p1 = ((block[3] & 0xFC) | (block[2] >> 6)) & 0xFF;
        int p2 = (block[4] & 0xFC) | (block[3] >> 6);
        int p3 = (block[5] & 0xFF);
        int p4 = ((block[6] & 0xFC) | (block[5] >> 6)) & 0xFF;
        int p5 = (block[7] & 0xFC) | (block[6] >> 6);
        int p6 = (block[8] & 0xFF);
        int p7 = ((block[9] & 0xFC) | (block[8] >> 6)) & 0xFF;

        int r0 = p0, g0 = p1, b0 = p2, a0 = p3;
        int r1 = p4, g1 = p5, b1 = p6, a1 = p7;

        // Selection indices (2 bits per pixel, 4 pixels per byte)
        int[] indices = new int[16];
        for (int i = 0; i < 8; i++) {
            int val = block[10 + i] & 0xFF;
            for (int j = 0; j < 2; j++) {
                indices[i * 2 + j] = (val >> (j * 4)) & 0xF;
            }
        }

        // Write pixels with interpolation
        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 4; px++) {
                int x = startX + px;
                int y = startY + py;
                if (x >= imgWidth || y >= imgHeight) continue;

                int idx = indices[py * 4 + px] & 0x3; // 2-bit index for mode 0
                float t = idx / 3.0f;

                int r = (int) (r0 * (1 - t) + r1 * t);
                int g = (int) (g0 * (1 - t) + g1 * t);
                int b = (int) (b0 * (1 - t) + b1 * t);
                int a = (int) (a0 * (1 - t) + a1 * t);

                int outIdx = (y * imgWidth + x) * 4;
                output[outIdx] = (byte) Math.min(255, r);
                output[outIdx + 1] = (byte) Math.min(255, g);
                output[outIdx + 2] = (byte) Math.min(255, b);
                output[outIdx + 3] = (byte) Math.min(255, a);
            }
        }
    }

    /**
     * Fallback BC7 decoder for unsupported modes — simple 2-color interpolation.
     */
    private static void decodeBC7Fallback(byte[] block, byte[] output,
                                           int startX, int startY, int imgWidth, int imgHeight) {
        // Extract first two bytes as simple color endpoints
        int r0 = block[2] & 0xFF, g0 = block[3] & 0xFF, b0 = block[4] & 0xFF, a0 = block[5] & 0xFF;
        int r1 = block[6] & 0xFF, g1 = block[7] & 0xFF, b1 = block[8] & 0xFF, a1 = block[9] & 0xFF;

        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 4; px++) {
                int x = startX + px;
                int y = startY + py;
                if (x >= imgWidth || y >= imgHeight) continue;

                int idx = (py * 4 + px) % 2;
                int outIdx = (y * imgWidth + x) * 4;
                output[outIdx] = (byte) (idx == 0 ? r0 : r1);
                output[outIdx + 1] = (byte) (idx == 0 ? g0 : g1);
                output[outIdx + 2] = (byte) (idx == 0 ? b0 : b1);
                output[outIdx + 3] = (byte) (idx == 0 ? a0 : a1);
            }
        }
    }

    /**
     * Decode a 5-6-5 RGB value to 8-8-8 with alpha.
     */
    private static int decode565(int rgb565) {
        int r5 = (rgb565 >> 11) & 0x1F;
        int g6 = (rgb565 >> 5) & 0x3F;
        int b5 = rgb565 & 0x1F;

        // Expand to 8-bit
        int r = (r5 << 3) | (r5 >> 2);
        int g = (g6 << 2) | (g6 >> 4);
        int b = (b5 << 3) | (b5 >> 2);

        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Check if a byte array is a valid DDS file.
     */
    public static boolean isDDS(byte[] data) {
        if (data == null || data.length < 128) return false;
        int magic = (data[3] & 0xFF) << 24 | (data[2] & 0xFF) << 16 |
                    (data[1] & 0xFF) << 8 | (data[0] & 0xFF);
        return magic == DDS_MAGIC;
    }
}
