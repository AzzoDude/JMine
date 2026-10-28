package com.jmine.texture;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DDS file header builder for BC-compressed textures.
 *
 * Creates valid DDS headers with DX10 extension for BC1/BC3/BC7 formats.
 */
public class DDSHeader {

    // DDS magic number
    private static final byte[] DDS_MAGIC = {(byte)'D', (byte)'D', (byte)'S', (byte)' '};

    // DDS flags
    private static final int DDS_FLAGS_MIPMAP = 0x00020000;
    private static final int DDS_FLAGS_LINEAR = 0x00000004;

    // DDS header size
    private static final int DDS_HEADER_SIZE = 128;

    // DXGI formats for BC compression
    public static final int DXGI_BC1_UNORM = 77;  // BC1 (DXT1) - RGB, 4 bpp
    public static final int DXGI_BC3_UNORM = 79;  // BC3 (DXT5) - RGBA, 8 bpp
    public static final int DXGI_BC7_UNORM = 98;  // BC7 - high quality RGBA, 8 bpp

    /**
     * Build a complete DDS header for BC-compressed textures.
     *
     * @param width Texture width (will be aligned to 4-pixel boundary)
     * @param height Texture height (will be aligned to 4-pixel boundary)
     * @param formatType DXGI format (DXGI_BC1_UNORM, DXGI_BC3_UNORM, DXGI_BC7_UNORM)
     * @param hasMipmaps Whether to include mip map levels
     * @return ByteBuffer containing the DDS header (128 bytes) + DX10 extension (20 bytes)
     */
    public static ByteBuffer buildHeader(int width, int height, int formatType, boolean hasMipmaps) {
        // Align dimensions to 4-pixel boundary (required for BC formats)
        width = ((width + 3) / 4) * 4;
        height = ((height + 3) / 4) * 4;

        // Calculate number of mip levels
        int numMips = 1;
        if (hasMipmaps) {
            int maxDim = Math.max(width, height);
            numMips = Math.max(1, (int) (Math.log(maxDim) / Math.log(2)) + 1);
        }

        int pitch = ((width + 3) / 4) * 16; // 16 bytes per 4x4 block for BC formats

        int flags = hasMipmaps ? DDS_FLAGS_MIPMAP : 0;
        flags |= DDS_FLAGS_LINEAR;

        ByteBuffer header = ByteBuffer.allocate(DDS_HEADER_SIZE + 20);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // Main header
        header.putInt(DDS_HEADER_SIZE - 4); // dwSize
        header.putInt(flags);               // dwFlags
        header.putInt(height);              // dwHeight
        header.putInt(width);               // dwWidth
        header.putInt(pitch);               // dwPitchOrLinearSize
        header.putInt(0);                   // dwDepth (0 for 2D)
        header.putInt(hasMipmaps ? numMips - 1 : 0); // dwMipMapCount

        // dwReserved1[11]
        for (int i = 0; i < 11; i++) {
            header.putInt(0);
        }

        // Pixel format structure (32 bytes)
        header.putInt(32);                  // dwSize
        header.putInt(0);                   // dwFlags
        header.put((byte)'D');              // dwFourCC = "DX10"
        header.put((byte)'X');
        header.put((byte)'1');
        header.put((byte)'0');
        header.put(new byte[]{0, 0, 0, 0}); // padding to complete FourCC
        header.putInt(0);                   // dwRGBBitCount
        header.putInt(0);                   // dwRBitMask
        header.putInt(0);                   // dwGBitMask
        header.putInt(0);                   // dwBBitMask
        header.putInt(0);                   // dwABitMask

        // Caps
        header.putInt(0x00000400);          // dwCaps (TEXTURE)
        header.putInt(0x00000008);          // dwCaps2 (CUBEMAP_POSITIVEX - required for DX10)
        header.putInt(0);                   // dwCaps3
        header.putInt(0);                   // dwCaps4
        header.putInt(0);                   // dwReserved2

        // DX10 extension header (20 bytes)
        header.putInt(formatType);          // DXGI format
        header.putInt(3);                   // D3D10_RESOURCE_DIMENSION_TEXTURE2D
        header.putInt(0);                   // miscFlag
        header.putInt(1);                   // arraySize
        header.putInt(0);                   // miscFlags2

        header.flip();
        return header;
    }

    /**
     * Build a complete DDS file with header and pixel data.
     *
     * @param width Texture width
     * @param height Texture height
     * @param formatType DXGI format
     * @param pixelData Compressed pixel data (BC blocks)
     * @param hasMipmaps Whether to include mip maps
     * @return ByteBuffer containing the complete DDS file
     */
    public static ByteBuffer buildDDS(int width, int height, int formatType, byte[] pixelData, boolean hasMipmaps) {
        ByteBuffer header = buildHeader(width, height, formatType, hasMipmaps);
        int headerSize = header.remaining();

        ByteBuffer dds = ByteBuffer.allocate(headerSize + pixelData.length);
        dds.order(ByteOrder.LITTLE_ENDIAN);

        // Magic
        dds.put(DDS_MAGIC);
        // Header
        while (header.hasRemaining()) {
            dds.put(header.get());
        }
        // Pixel data
        dds.put(pixelData);

        dds.flip();
        return dds;
    }

    /**
     * Calculate the size of compressed data for a given texture dimension.
     *
     * @param width Texture width
     * @param height Texture height
     * @param formatType DXGI format
     * @return Size in bytes of the compressed data
     */
    public static int calculateCompressedSize(int width, int height, int formatType) {
        // Align to 4-pixel boundary
        width = ((width + 3) / 4) * 4;
        height = ((height + 3) / 4) * 4;

        int bytesPerBlock;
        switch (formatType) {
            case DXGI_BC1_UNORM:
                bytesPerBlock = 8;
                break;
            case DXGI_BC3_UNORM:
            case DXGI_BC7_UNORM:
            default:
                bytesPerBlock = 16;
                break;
        }

        int blocksX = width / 4;
        int blocksY = height / 4;
        return blocksX * blocksY * bytesPerBlock;
    }
}
