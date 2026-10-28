package com.jmine.texture;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Logger;

/**
 * Project Panama FFM bridge to the Khronos KTX-Software native library.
 *
 * Uses java.lang.foreign (Project Panama) to call libktx.dll directly —
 * no JNI, no Maven, no ktx-jni.dll needed. This is a pure Java bridge
 * that links to the native C library at runtime.
 *
 * When the native library is available, compression is handled by the
 * fast Khronos C library. When unavailable, falls back to the pure Java
 * DDSCompressor/DDSDecoder.
 *
 * Key libktx functions linked via Panama:
 * - ktxTexture1_Create: create a texture with BC format
 * - ktxTexture_SetImageFromMemory: set RGBA pixel data (vtable)
 * - ktxTexture_WriteToMemory: export compressed data to bytes (vtable)
 * - ktxTexture_Destroy: free the texture object
 */
public class KTXNative {

    private static final Logger logger = Logger.getLogger("JMine.KTXNative");

    private static boolean nativeLoaded = false;
    private static boolean loadAttempted = false;

    // Panama FFM symbols
    private static SymbolLookup libktxSymbols;
    private static Linker sysLinker;

    // Function signatures for libktx C API
    private static MethodHandle ktxTexture1_Create;
    private static MethodHandle ktxTexture1_Destroy;
    private static MethodHandle ktxTexture_GetData;
    private static MethodHandle ktxTexture_GetDataSize;

    /**
     * Load the native libktx.dll using Project Panama FFM.
     * @return true if the native library was loaded successfully
     */
    public static boolean loadNativeLibrary() {
        if (loadAttempted) return nativeLoaded;
        loadAttempted = true;

        try (Arena autoArena = Arena.ofAuto()) {
            sysLinker = Linker.nativeLinker();

            // Load libktx.dll — find it in library path or system path
            libktxSymbols = SymbolLookup.libraryLookup("libktx", autoArena)
                .or(SymbolLookup.loaderLookup())
                .or(SymbolLookup.libraryLookup("ktx", autoArena));

            // Verify the library is actually found
            if (!libktxSymbols.find("ktxTexture1_Create").isPresent()) {
                logger.info("libktx.dll not found in library path — using Java fallback");
                return false;
            }

            // Link ktxTexture1_Create
            ktxTexture1_Create = sysLinker.downcallHandle(
                libktxSymbols.find("ktxTexture1_Create").get(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS
                )
            );

            // Link ktxTexture1_Destroy
            ktxTexture1_Destroy = sysLinker.downcallHandle(
                libktxSymbols.find("ktxTexture1_Destroy").get(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            // Link ktxTexture_GetData
            ktxTexture_GetData = sysLinker.downcallHandle(
                libktxSymbols.find("ktxTexture_GetData").get(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            // Link ktxTexture_GetDataSize
            ktxTexture_GetDataSize = sysLinker.downcallHandle(
                libktxSymbols.find("ktxTexture_GetDataSize").get(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
            );

            nativeLoaded = true;
            logger.info("KTX native library loaded via Project Panama FFM");
            return true;

        } catch (Exception e) {
            logger.info("KTX native library not available via Panama — using Java fallback: " + e.getMessage());
            nativeLoaded = false;
            return false;
        }
    }

    /**
     * Check whether the native library is available.
     */
    public static boolean isNativeAvailable() {
        return nativeLoaded;
    }

    /**
     * Compress RGBA pixel data to DDS format using BC7 compression.
     * Uses native KTX library via Panama FFM if available, otherwise falls back to Java.
     */
    public static byte[] compressBC7(byte[] pixels, int width, int height) {
        if (nativeLoaded) {
            byte[] ktxData = compressWithKTX(pixels, width, height, 0x83E5);
            if (ktxData != null) {
                return ktxToDDS(ktxData, pixels, width, height, 7);
            }
        }
        return DDSCompressor.compressTexture(pixels, width, height, 7, true);
    }

    /**
     * Compress RGBA pixel data to DDS format using BC3 compression.
     */
    public static byte[] compressBC3(byte[] pixels, int width, int height) {
        if (nativeLoaded) {
            byte[] ktxData = compressWithKTX(pixels, width, height, 0x83A4);
            if (ktxData != null) {
                return ktxToDDS(ktxData, pixels, width, height, 3);
            }
        }
        return DDSCompressor.compressTexture(pixels, width, height, 3, true);
    }

    /**
     * Compress RGB pixel data to DDS format using BC1 compression.
     */
    public static byte[] compressBC1(byte[] pixels, int width, int height) {
        if (nativeLoaded) {
            byte[] ktxData = compressWithKTX(pixels, width, height, 0x83F1);
            if (ktxData != null) {
                return ktxToDDS(ktxData, pixels, width, height, 1);
            }
        }
        return DDSCompressor.compressTexture(pixels, width, height, 1, true);
    }

    /**
     * Compress RGBA pixels using libktx native library via Panama FFM.
     */
    private static byte[] compressWithKTX(byte[] pixels, int width, int height, int glFormat) {
        try (Arena arena = Arena.ofConfined()) {
            int paddedWidth = (width + 3) & ~3;
            int paddedHeight = (height + 3) & ~3;

            // Allocate ktxTextureCreateInfo struct (48 bytes)
            long structSize = 48;
            MemorySegment createInfo = arena.allocate(structSize);
            // Zero out the struct
            ByteBuffer createInfoBB = createInfo.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            java.util.Arrays.fill(createInfoBB.array(), (byte) 0);

            // Fill the struct
            createInfo.set(ValueLayout.JAVA_INT, 0, glFormat);      // glInternalformat
            createInfo.set(ValueLayout.JAVA_INT, 4, 0);             // vkFormat
            createInfo.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL); // pDfd
            createInfo.set(ValueLayout.JAVA_INT, 16, paddedWidth);  // baseWidth
            createInfo.set(ValueLayout.JAVA_INT, 20, paddedHeight); // baseHeight
            createInfo.set(ValueLayout.JAVA_INT, 24, 1);            // baseDepth
            createInfo.set(ValueLayout.JAVA_INT, 28, 2);            // numDimensions
            createInfo.set(ValueLayout.JAVA_INT, 32, 1);            // numLevels
            createInfo.set(ValueLayout.JAVA_INT, 36, 1);            // numLayers
            createInfo.set(ValueLayout.JAVA_INT, 40, 1);            // numFaces
            createInfo.set(ValueLayout.JAVA_BYTE, 44, (byte) 0);    // isArray
            createInfo.set(ValueLayout.JAVA_BYTE, 45, (byte) 0);    // generateMipmaps

            // Allocate pointer for the texture output
            MemorySegment texPtr = arena.allocate(ValueLayout.ADDRESS);
            texPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);

            // Call ktxTexture1_Create with ALLOC_STORAGE
            int result = (int) ktxTexture1_Create.invokeExact(createInfo, 1, texPtr);
            if (result != 0) {
                logger.fine("ktxTexture1_Create failed: " + result);
                return null;
            }

            MemorySegment tex = texPtr.get(ValueLayout.ADDRESS, 0);
            if (tex.equals(MemorySegment.NULL)) {
                return null;
            }

            // Allocate pixel data in native memory
            MemorySegment pixelData = arena.allocate(pixels.length);
            pixelData.asByteBuffer().put(pixels);

            // Set the image data via vtable
            // vtable: SetImageFromMemory is at index 11
            long ptrSize = ValueLayout.ADDRESS.byteSize();
            MemorySegment vtable = tex.get(ValueLayout.ADDRESS, 0);
            MemorySegment setImageFunc = vtable.get(ValueLayout.ADDRESS, 11 * ptrSize);

            MethodHandle setImageHandle = sysLinker.downcallHandle(
                setImageFunc,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG
                )
            );

            result = (int) setImageHandle.invokeExact(tex, 0, 0, 0, pixelData, (long) pixels.length);
            if (result != 0) {
                logger.fine("SetImageFromMemory failed: " + result);
                ktxTexture1_Destroy.invokeExact(tex);
                return null;
            }

            // Export to memory via vtable WriteToMemory (index 15)
            MemorySegment outBytesPtr = arena.allocate(ValueLayout.ADDRESS);
            outBytesPtr.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment outSizePtr = arena.allocate(ValueLayout.JAVA_LONG);
            outSizePtr.set(ValueLayout.JAVA_LONG, 0, 0L);

            MemorySegment writeFunc = vtable.get(ValueLayout.ADDRESS, 15 * ptrSize);
            MethodHandle writeHandle = sysLinker.downcallHandle(
                writeFunc,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS
                )
            );

            result = (int) writeHandle.invokeExact(tex, outBytesPtr, outSizePtr);
            if (result != 0) {
                logger.fine("WriteToMemory failed: " + result);
                ktxTexture1_Destroy.invokeExact(tex);
                return null;
            }

            MemorySegment outBytes = outBytesPtr.get(ValueLayout.ADDRESS, 0);
            long outSize = outSizePtr.get(ValueLayout.JAVA_LONG, 0);

            // Copy the result to Java
            byte[] ktxData = new byte[(int) outSize];
            outBytes.asByteBuffer().get(ktxData);

            // Clean up
            ktxTexture1_Destroy.invokeExact(tex);

            return ktxData;

        } catch (Throwable e) {
            logger.fine("KTX compression failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Convert KTX format bytes to DDS format for Minecraft compatibility.
     * Since libktx produces KTX format (not raw BC blocks), we extract
     * the compressed data and wrap it in a DDS header.
     */
    private static byte[] ktxToDDS(byte[] ktxData, byte[] originalPixels, int width, int height, int bcFormat) {
        try {
            // Extract compressed BC data from KTX1 container
            // KTX1 header: 12 bytes (magic + header), then key-value data, then image data
            // KTX1 magic: 0xABKTX 1\0\n (7 bytes) + 5 bytes header
            if (ktxData.length < 12) {
                return DDSCompressor.compressTexture(originalPixels, width, height, bcFormat, true);
            }

            ByteBuffer ktx = ByteBuffer.wrap(ktxData).order(ByteOrder.LITTLE_ENDIAN);
            // Skip magic (12 bytes)
            ktx.position(12);
            int pixelType = ktx.getInt();
            int typeSize = ktx.getInt();
            int pixelFormat = ktx.getInt();
            int widthKtx = ktx.getInt();
            int heightKtx = ktx.getInt();
            int depth = ktx.getInt();
            int arrayElements = ktx.getInt();
            int faces = ktx.getInt();
            int numMipLevels = ktx.getInt();
            int keyValueDataLength = ktx.getInt();

            // Skip key-value data
            int imageDataOffset = 12 + keyValueDataLength;
            // Align to 4 bytes
            imageDataOffset = (imageDataOffset + 3) & ~3;

            if (imageDataOffset >= ktxData.length) {
                return DDSCompressor.compressTexture(originalPixels, width, height, bcFormat, true);
            }

            // Extract the compressed image data
            int imageSize = ktxData.length - imageDataOffset;
            byte[] compressedData = new byte[imageSize];
            System.arraycopy(ktxData, imageDataOffset, compressedData, 0, imageSize);

            // Build DDS header with the compressed data
            ByteBuffer ddsBB = DDSHeader.buildDDS(width, height, bcFormat, compressedData, false);
            byte[] ddsBytes = new byte[ddsBB.remaining()];
            ddsBB.get(ddsBytes);
            return ddsBytes;

        } catch (Exception e) {
            logger.fine("KTX to DDS conversion failed: " + e.getMessage());
            return DDSCompressor.compressTexture(originalPixels, width, height, bcFormat, true);
        }
    }

    /**
     * Decompress a DDS file back to RGBA pixel data.
     * Uses Java decoder since libktx handles KTX format, not DDS directly.
     */
    public static byte[] decompressDDS(byte[] ddsData) {
        try {
            DDSDecoder.DDSImage image = DDSDecoder.decode(ddsData);
            return image.pixels;
        } catch (Exception e) {
            logger.warning("DDS decompression failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the dimensions of a DDS file without decompressing.
     */
    public static int[] getDDSDimensions(byte[] ddsData) {
        try {
            if (ddsData.length < 128) return null;
            ByteBuffer bb = ByteBuffer.wrap(ddsData, 0, 128).order(ByteOrder.LITTLE_ENDIAN);
            bb.position(8);
            int height = bb.getInt();
            int width = bb.getInt();
            return new int[]{width, height};
        } catch (Exception e) {
            return null;
        }
    }
}
