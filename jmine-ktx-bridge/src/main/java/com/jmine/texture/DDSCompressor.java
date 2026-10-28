package com.jmine.texture;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * BC texture compressor — converts RGBA pixel data to BC1/BC3/BC7 compressed blocks.
 *
 * Ported from the Python implementation in jmine_texture_cache.py.
 * Uses software compression for BC1/BC3/BC7 formats.
 */
public class DDSCompressor {

    // Pixel data: RGBA, 4 bytes per pixel, 0-255
    // Each block is 4x4 pixels = 16 pixels

    /**
     * Compress a 4x4 block of RGBA pixels to BC7 format (16 bytes).
     * Uses mode 0 (2-color interpolation) — simplified but effective for Minecraft textures.
     *
     * @param pixels 16 RGBA values (64 bytes: R,G,B,A per pixel, 0-255)
     * @param offset offset into the pixels array
     * @return 16-byte BC7 compressed block
     */
    public static byte[] compressBC7Block(byte[] pixels, int offset) {
        // Extract 16 pixels
        int[] r = new int[16], g = new int[16], b = new int[16], a = new int[16];
        int avgR = 0, avgG = 0, avgB = 0, avgA = 0;

        for (int i = 0; i < 16; i++) {
            int idx = offset + i * 4;
            r[i] = pixels[idx] & 0xFF;
            g[i] = pixels[idx + 1] & 0xFF;
            b[i] = pixels[idx + 2] & 0xFF;
            a[i] = pixels[idx + 3] & 0xFF;
            avgR += r[i];
            avgG += g[i];
            avgB += b[i];
            avgA += a[i];
        }
        avgR /= 16;
        avgG /= 16;
        avgB /= 16;
        avgA /= 16;

        // Find min/max colors for endpoints
        int minIdx = 0, maxIdx = 0;
        int minDist = Integer.MAX_VALUE, maxDist = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            int dist = (r[i] - avgR) * (r[i] - avgR) +
                       (g[i] - avgG) * (g[i] - avgG) +
                       (b[i] - avgB) * (b[i] - avgB);
            if (dist < minDist) { minDist = dist; minIdx = i; }
            if (dist > maxDist) { maxDist = dist; maxIdx = i; }
        }

        int c0r = r[minIdx], c0g = g[minIdx], c0b = b[minIdx];
        int c1r = r[maxIdx], c1g = g[maxIdx], c1b = b[maxIdx];
        int a0 = Arrays.stream(a).min().getAsInt();
        int a1 = Arrays.stream(a).max().getAsInt();

        // Selection indices
        byte[] sel = new byte[16];
        for (int i = 0; i < 16; i++) {
            int d0 = (r[i] - c0r) * (r[i] - c0r) + (g[i] - c0g) * (g[i] - c0g) + (b[i] - c0b) * (b[i] - c0b);
            int d1 = (r[i] - c1r) * (r[i] - c1r) + (g[i] - c1g) * (g[i] - c1g) + (b[i] - c1b) * (b[i] - c1b);
            sel[i] = (byte) (d0 < d1 ? 0 : 15);
        }

        // Pack BC7 block (mode 0)
        byte[] block = new byte[16];
        int partition = 0;

        block[0] = (byte) (partition & 0x3F);
        block[1] = (byte) ((partition >> 2) & 0x3F);
        block[2] = (byte) (((partition >> 6) & 0x03) | ((c0r >> 2) << 2));
        block[3] = (byte) (((c0r & 0x03) << 6) | (c0g >> 2));
        block[4] = (byte) (((c0g & 0x03) << 6) | (c0b >> 2));
        block[5] = (byte) (((c0b & 0x03) << 6) | ((c1r >> 2) << 2));
        block[6] = (byte) (((c1r & 0x03) << 6) | (c1g >> 2));
        block[7] = (byte) (((c1g & 0x03) << 6) | (c1b >> 2));
        block[8] = (byte) (((c1b & 0x03) << 6) | (a0 << 2));
        block[9] = (byte) (((a0 & 0x03) << 6) | (a1 >> 2));
        block[10] = (byte) ((a1 & 0x03) << 6);

        // Selection indices (2-bit per pixel for mode 0)
        for (int i = 0; i < 16; i += 4) {
            int idx = 0;
            for (int j = 0; j < 4; j++) {
                idx |= (sel[i + j] & 0x03) << (j * 2);
            }
            block[11 + i / 4] = (byte) idx;
        }

        return block;
    }

    /**
     * Compress a 4x4 block of RGBA pixels to BC3 format (16 bytes).
     * BC3 (DXT5) separates color and alpha channels.
     *
     * @param pixels 16 RGBA values (64 bytes)
     * @param offset offset into the pixels array
     * @return 16-byte BC3 compressed block
     */
    public static byte[] compressBC3Block(byte[] pixels, int offset) {
        // Alpha block (DXT3-style: 4-bit alpha pairs)
        byte[] alphaBlock = new byte[8];
        for (int i = 0; i < 8; i++) {
            int idx = offset + i * 2 * 4;
            int a0 = (pixels[idx + 3] & 0xFF) >> 4;
            int a1 = (pixels[idx + 3 + 4] & 0xFF) >> 4;
            alphaBlock[i] = (byte) (a0 | (a1 << 4));
        }

        // Color block (DXT1-style)
        int[] cr = new int[16], cg = new int[16], cb = new int[16];
        int avgR = 0, avgG = 0, avgB = 0;
        for (int i = 0; i < 16; i++) {
            int idx = offset + i * 4;
            cr[i] = pixels[idx] & 0xFF;
            cg[i] = pixels[idx + 1] & 0xFF;
            cb[i] = pixels[idx + 2] & 0xFF;
            avgR += cr[i];
            avgG += cg[i];
            avgB += cb[i];
        }
        avgR /= 16; avgG /= 16; avgB /= 16;

        int minIdx = 0, maxIdx = 0;
        int minDist = Integer.MAX_VALUE, maxDist = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            int dist = (cr[i] - avgR) * (cr[i] - avgR) +
                       (cg[i] - avgG) * (cg[i] - avgG) +
                       (cb[i] - avgB) * (cb[i] - avgB);
            if (dist < minDist) { minDist = dist; minIdx = i; }
            if (dist > maxDist) { maxDist = dist; maxIdx = i; }
        }

        int c0r = cr[minIdx], c0g = cg[minIdx], c0b = cb[minIdx];
        int c1r = cr[maxIdx], c1g = cg[maxIdx], c1b = cb[maxIdx];

        byte[] colorBlock = new byte[8];
        int color0 = ((c0r >> 3) << 11) | ((c0g >> 2) << 5) | (c0b >> 3);
        int color1 = ((c1r >> 3) << 11) | ((c1g >> 2) << 5) | (c1b >> 3);
        colorBlock[0] = (byte) (color0 & 0xFF);
        colorBlock[1] = (byte) ((color0 >> 8) & 0xFF);
        colorBlock[2] = (byte) (color1 & 0xFF);
        colorBlock[3] = (byte) ((color1 >> 8) & 0xFF);

        for (int i = 0; i < 16; i += 4) {
            int idx = 0;
            for (int j = 0; j < 4; j++) {
                int pIdx = i + j;
                int d0 = (cr[pIdx] - c0r) * (cr[pIdx] - c0r) +
                         (cg[pIdx] - c0g) * (cg[pIdx] - c0g) +
                         (cb[pIdx] - c0b) * (cb[pIdx] - c0b);
                int d1 = (cr[pIdx] - c1r) * (cr[pIdx] - c1r) +
                         (cg[pIdx] - c1g) * (cg[pIdx] - c1g) +
                         (cb[pIdx] - c1b) * (cb[pIdx] - c1b);
                idx |= (d0 < d1 ? 0 : 3) << (j * 2);
            }
            colorBlock[4 + i / 4] = (byte) idx;
        }

        // Combine alpha + color
        byte[] block = new byte[16];
        System.arraycopy(alphaBlock, 0, block, 0, 8);
        System.arraycopy(colorBlock, 0, block, 8, 8);
        return block;
    }

    /**
     * Compress a 4x4 block of RGB pixels to BC1 format (8 bytes).
     * BC1 (DXT1) — RGB only, no alpha, smallest size.
     *
     * @param pixels 16 RGBA values (64 bytes)
     * @param offset offset into the pixels array
     * @return 8-byte BC1 compressed block
     */
    public static byte[] compressBC1Block(byte[] pixels, int offset) {
        int[] cr = new int[16], cg = new int[16], cb = new int[16];
        int avgR = 0, avgG = 0, avgB = 0;
        for (int i = 0; i < 16; i++) {
            int idx = offset + i * 4;
            cr[i] = pixels[idx] & 0xFF;
            cg[i] = pixels[idx + 1] & 0xFF;
            cb[i] = pixels[idx + 2] & 0xFF;
            avgR += cr[i];
            avgG += cg[i];
            avgB += cb[i];
        }
        avgR /= 16; avgG /= 16; avgB /= 16;

        int minIdx = 0, maxIdx = 0;
        int minDist = Integer.MAX_VALUE, maxDist = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            int dist = (cr[i] - avgR) * (cr[i] - avgR) +
                       (cg[i] - avgG) * (cg[i] - avgG) +
                       (cb[i] - avgB) * (cb[i] - avgB);
            if (dist < minDist) { minDist = dist; minIdx = i; }
            if (dist > maxDist) { maxDist = dist; maxIdx = i; }
        }

        int c0r = cr[minIdx], c0g = cg[minIdx], c0b = cb[minIdx];
        int c1r = cr[maxIdx], c1g = cg[maxIdx], c1b = cb[maxIdx];

        int color0 = ((c0r >> 3) << 11) | ((c0g >> 2) << 5) | (c0b >> 3);
        int color1 = ((c1r >> 3) << 11) | ((c1g >> 2) << 5) | (c1b >> 3);

        byte[] block = new byte[8];
        block[0] = (byte) (color0 & 0xFF);
        block[1] = (byte) ((color0 >> 8) & 0xFF);
        block[2] = (byte) (color1 & 0xFF);
        block[3] = (byte) ((color1 >> 8) & 0xFF);

        for (int i = 0; i < 16; i += 4) {
            int idx = 0;
            for (int j = 0; j < 4; j++) {
                int pIdx = i + j;
                int d0 = (cr[pIdx] - c0r) * (cr[pIdx] - c0r) +
                         (cg[pIdx] - c0g) * (cg[pIdx] - c0g) +
                         (cb[pIdx] - c0b) * (cb[pIdx] - c0b);
                int d1 = (cr[pIdx] - c1r) * (cr[pIdx] - c1r) +
                         (cg[pIdx] - c1g) * (cg[pIdx] - c1g) +
                         (cb[pIdx] - c1b) * (cb[pIdx] - c1b);
                idx |= (d0 < d1 ? 0 : 3) << (j * 2);
            }
            block[4 + i / 4] = (byte) idx;
        }

        return block;
    }

    /**
     * Compress an entire texture (RGBA pixel array) to DDS format.
     *
     * @param pixels RGBA pixel data (width * height * 4 bytes)
     * @param width Texture width
     * @param height Texture height
     * @param formatType DXGI format (BC1/BC3/BC7)
     * @param hasMipmaps Whether to generate mip maps
     * @return Complete DDS file as byte array
     */
    public static byte[] compressTexture(byte[] pixels, int width, int height,
                                          int formatType, boolean hasMipmaps) {
        // Align dimensions
        int alignedW = ((width + 3) / 4) * 4;
        int alignedH = ((height + 3) / 4) * 4;

        // Pad pixels if needed
        byte[] padded = new byte[alignedW * alignedH * 4];
        System.arraycopy(pixels, 0, padded, 0, Math.min(pixels.length, padded.length));

        // Calculate mip levels
        int numMips = 1;
        if (hasMipmaps) {
            int maxDim = Math.max(alignedW, alignedH);
            numMips = Math.max(1, (int) (Math.log(maxDim) / Math.log(2)) + 1);
        }

        // Choose bytes per block
        int bytesPerBlock;
        switch (formatType) {
            case DDSHeader.DXGI_BC1_UNORM:
                bytesPerBlock = 8;
                break;
            case DDSHeader.DXGI_BC3_UNORM:
            case DDSHeader.DXGI_BC7_UNORM:
            default:
                bytesPerBlock = 16;
                break;
        }

        // Calculate total data size
        int totalSize = 0;
        int curW = alignedW, curH = alignedH;
        for (int mip = 0; mip < numMips; mip++) {
            int blocksX = curW / 4;
            int blocksY = curH / 4;
            totalSize += blocksX * blocksY * bytesPerBlock;
            curW = Math.max(4, curW / 2);
            curH = Math.max(4, curH / 2);
        }

        byte[] data = new byte[totalSize];
        int dataOffset = 0;

        // Compress each mip level
        byte[] currentPixels = padded;
        curW = alignedW;
        curH = alignedH;

        for (int mip = 0; mip < numMips; mip++) {
            int blocksX = curW / 4;
            int blocksY = curH / 4;

            for (int by = 0; by < blocksY; by++) {
                for (int bx = 0; bx < blocksX; bx++) {
                    // Extract 4x4 block
                    byte[] blockPixels = new byte[64]; // 16 pixels * 4 bytes
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dx = 0; dx < 4; dx++) {
                            int px = Math.min(bx * 4 + dx, curW - 1);
                            int py = Math.min(by * 4 + dy, curH - 1);
                            int srcIdx = (py * curW + px) * 4;
                            int dstIdx = (dy * 4 + dx) * 4;
                            System.arraycopy(currentPixels, srcIdx, blockPixels, dstIdx, 4);
                        }
                    }

                    // Compress block
                    byte[] compressed;
                    switch (formatType) {
                        case DDSHeader.DXGI_BC7_UNORM:
                            compressed = compressBC7Block(blockPixels, 0);
                            break;
                        case DDSHeader.DXGI_BC3_UNORM:
                            compressed = compressBC3Block(blockPixels, 0);
                            break;
                        case DDSHeader.DXGI_BC1_UNORM:
                        default:
                            compressed = compressBC1Block(blockPixels, 0);
                            break;
                    }

                    System.arraycopy(compressed, 0, data, dataOffset, compressed.length);
                    dataOffset += compressed.length;
                }
            }

            // Generate next mip level (simple box filter)
            if (mip < numMips - 1) {
                int newW = Math.max(4, curW / 2);
                int newH = Math.max(4, curH / 2);
                currentPixels = generateMipLevel(currentPixels, curW, curH, newW, newH);
                curW = newW;
                curH = newH;
            }
        }

        // Build DDS file
        ByteBuffer dds = DDSHeader.buildDDS(width, height, formatType, data, hasMipmaps);
        byte[] result = new byte[dds.remaining()];
        dds.get(result);
        return result;
    }

    /**
     * Generate a mip level by downsampling 2x2 to 1x1.
     */
    private static byte[] generateMipLevel(byte[] pixels, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * 4];
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                int r = 0, g = 0, b = 0, a = 0;
                int count = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min((x * 2) + dx, srcW - 1);
                        int sy = Math.min((y * 2) + dy, srcH - 1);
                        int idx = (sy * srcW + sx) * 4;
                        r += pixels[idx] & 0xFF;
                        g += pixels[idx + 1] & 0xFF;
                        b += pixels[idx + 2] & 0xFF;
                        a += pixels[idx + 3] & 0xFF;
                        count++;
                    }
                }
                int dstIdx = (y * dstW + x) * 4;
                dst[dstIdx] = (byte) (r / count);
                dst[dstIdx + 1] = (byte) (g / count);
                dst[dstIdx + 2] = (byte) (b / count);
                dst[dstIdx + 3] = (byte) (a / count);
            }
        }
        return dst;
    }
}
