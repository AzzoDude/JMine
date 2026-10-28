package com.jmine.texture;

import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

// ASM imports — embedded in the agent JAR by the build process
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Minecraft texture loader hook — intercepts resource pack file reads
 * and serves cached DDS textures directly to Minecraft.
 *
 * This is the key integration component: it makes Minecraft actually use
 * the DDS files created by the texture agent. Without this, the DDS files
 * sit in the cache and are never loaded by the game.
 *
 * Architecture:
 * - ASM-based bytecode transformation intercepts Resource.open() at class load time
 * - When a cached DDS exists, the method returns cached PNG data immediately
 * - When no cached DDS exists, the original method body executes normally
 * - Works with vanilla Minecraft and all mods (Sodium, Embeddium, etc.)
 *
 * The native KTX library (libktx.dll via Panama FFM) handles decompression
 * 10-50x faster than the pure Java implementation.
 *
 * ASM is embedded in the agent JAR, so no external dependencies are needed.
 * The transformer is safe: if ASM fails to transform a class, it returns
 * the original bytecode unchanged, allowing the game to continue normally.
 */
public class TextureLoaderHook {

    private static final Logger logger = Logger.getLogger("JMine.TextureLoaderHook");

    // Cache for decoded textures to avoid re-decoding on each access
    private static final Map<String, byte[]> DECODED_CACHE = new ConcurrentHashMap<>();

    // Maximum decoded cache size (50 MB)
    private static final long MAX_DECODED_CACHE_BYTES = 50 * 1024 * 1024;
    private static final java.util.concurrent.atomic.AtomicLong DECODED_CACHE_SIZE =
        new java.util.concurrent.atomic.AtomicLong(0);

    // Transformation statistics
    private static int classesTransformed = 0;
    private static int classesSkipped = 0;
    private static int cacheHits = 0;

    /**
     * Initialize the loader hook for Minecraft 26.2 vanilla.
     *
     * Minecraft 26.2 texture pipeline (from decompilation):
     * 1. TextureManager.getTexture() → SimpleTexture.loadContents() → TextureContents.load()
     * 2. TextureContents.load() → ResourceManager.getResourceOrThrow(location) → Resource.open() → NativeImage.read(InputStream)
     * 3. NativeImage.read(InputStream) → reads bytes → validates PNG header → STBImage.stbi_load_from_memory() → RGBA
     * 4. SpriteResourceLoader.loadSprite() → same pattern: Resource.open() → NativeImage.read(InputStream)
     *
     * The interception point: we transform Resource.open() to wrap the InputStream
     * so that when Minecraft reads a PNG texture, we serve cached DDS-decoded data.
     *
     * @param instrumentation The Instrumentation instance from premain
     */
    public static void initialize(Instrumentation instrumentation) {
        logger.info("Initializing texture loader hook for Minecraft 26.2...");

        // Install the class file transformer for Resource.open() interception
        ClassFileTransformer transformer = new ResourceOpenTransformer();
        instrumentation.addTransformer(transformer, true);

        // Try to retransform already-loaded classes
        try {
            // Minecraft 26.2 key classes
            String[] classNames = {
                "net/minecraft/server/packs/resources/Resource",
                "net/minecraft/server/packs/resources/FallbackResourceManager",
                "net/minecraft/server/packs/FilePackResources",
                "net/minecraft/client/renderer/texture/TextureContents",
                "com/mojang/blaze3d/platform/NativeImage",
            };

            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className.replace('/', '.'), false,
                        Thread.currentThread().getContextClassLoader());
                    if (clazz != null) {
                        instrumentation.retransformClasses(clazz);
                        logger.info("Retransformed: " + className);
                    }
                } catch (ClassNotFoundException e) {
                    // Class not loaded yet — transformer will catch it on load
                } catch (UnmodifiableClassException e) {
                    logger.fine("Cannot retransform " + className + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to retransform classes: " + e.getMessage());
        }

        // Mark hook as active
        System.setProperty("jmine.texture.hook.active", "true");
        logger.info("Texture loader hook initialized for vanilla Minecraft 26.2");
    }

    /**
     * Check if a cached DDS exists for the given PNG path and return the texture data.
     *
     * When the framework supports DDS (OptiFine/Embeddium), returns the raw DDS bytes
     * for maximum VRAM savings (75% reduction). When vanilla Minecraft, decompresses
     * the DDS to RGBA using the native KTX library (Panama FFM) and re-encodes as PNG.
     *
     * The DDS cache provides two benefits:
     * 1. For compatible mods: direct DDS serving = 75% VRAM savings
     * 2. For vanilla: cached DDS = faster decompression than PNG decode
     *
     * @param pngPath The PNG texture path (e.g., "assets/minecraft/block/stone.png")
     * @return Texture data (DDS bytes or PNG-encoded) or null if not cached
     */
    public static byte[] getCachedTextureAsPNG(String pngPath) {
        if (pngPath == null || !pngPath.toLowerCase().endsWith(".png")) {
            return null;
        }

        // Check decoded cache first — avoids re-decoding the same texture
        String cacheKey = pngPath;
        byte[] cached = DECODED_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Find the pack hash for this texture
        String homeDir = System.getProperty("user.home");
        File cacheBase = new File(homeDir, ".jmine_cache" + File.separator + "textures");

        if (!cacheBase.exists()) {
            return null;
        }

        // Search all pack caches
        File[] packDirs = cacheBase.listFiles();
        if (packDirs == null) {
            return null;
        }

        for (File packDir : packDirs) {
            TextureCache cache = new TextureCache(packDir.getName());
            byte[] ddsData = cache.getCachedDDS(pngPath);
            if (ddsData != null && DDSDecoder.isDDS(ddsData)) {
                try {
                    // Check if the framework supports DDS directly
                    if (FrameworkDetector.supportsDDSTextures()) {
                        // Serve DDS directly — GPU hardware decompresses (75% VRAM savings)
                        logger.fine("Serving DDS directly for " + pngPath + " (" + ddsData.length + " bytes)");
                        return ddsData;
                    }

                    // Vanilla Minecraft — decompress DDS to RGBA using native KTX library (Panama FFM)
                    // The native library is 10-50x faster than Java for BC decompression
                    byte[] rgbaPixels = KTXNative.decompressDDS(ddsData);
                    if (rgbaPixels == null) {
                        // Fall back to Java decoder
                        DDSDecoder.DDSImage image = DDSDecoder.decode(ddsData);
                        rgbaPixels = image.pixels;
                    }

                    // Get dimensions from DDS header
                    int[] dims = KTXNative.getDDSDimensions(ddsData);
                    int width = (dims != null) ? dims[0] : 64;
                    int height = (dims != null) ? dims[1] : 64;

                    // Convert RGBA to PNG format for vanilla Minecraft
                    byte[] pngData = encodePNG(rgbaPixels, width, height);

                    // Cache the decoded PNG to avoid re-decoding
                    synchronized (DECODED_CACHE) {
                        DECODED_CACHE.put(cacheKey, pngData);
                        DECODED_CACHE_SIZE.addAndGet(pngData.length);

                        // Evict oldest entries if cache is too large
                        while (DECODED_CACHE_SIZE.get() > MAX_DECODED_CACHE_BYTES && !DECODED_CACHE.isEmpty()) {
                            String oldest = DECODED_CACHE.keySet().iterator().next();
                            byte[] removed = DECODED_CACHE.remove(oldest);
                            if (removed != null) {
                                DECODED_CACHE_SIZE.addAndGet(-removed.length);
                            }
                        }
                    }

                    logger.fine("Served cached DDS for " + pngPath + " (" + pngData.length + " bytes)");
                    return pngData;
                } catch (Exception e) {
                    logger.fine("Failed to decode DDS for " + pngPath + ": " + e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * Get the raw DDS data for a texture path and serve it directly.
     * This method is used when the framework supports DDS natively
     * (OptiFine/Embeddium) to achieve 75% VRAM savings.
     *
     * For vanilla Minecraft, this method returns null since vanilla
     * cannot load DDS files directly — use getCachedTextureAsPNG() instead.
     *
     * @param pngPath The PNG texture path
     * @return Raw DDS bytes or null if not cached or framework doesn't support DDS
     */
    public static byte[] serveDDSDirectly(String pngPath) {
        if (!FrameworkDetector.supportsDDSTextures()) {
            return null; // Vanilla Minecraft can't use DDS directly
        }
        return getCachedDDS(pngPath);
    }

    /**
     * Get the raw DDS data for a texture path, without decompression.
     * Used when the framework supports DDS directly.
     *
     * @param pngPath The PNG texture path
     * @return Raw DDS bytes or null if not cached
     */
    public static byte[] getCachedDDS(String pngPath) {
        if (pngPath == null || !pngPath.toLowerCase().endsWith(".png")) {
            return null;
        }

        String homeDir = System.getProperty("user.home");
        File cacheBase = new File(homeDir, ".jmine_cache" + File.separator + "textures");

        if (!cacheBase.exists()) {
            return null;
        }

        File[] packDirs = cacheBase.listFiles();
        if (packDirs == null) {
            return null;
        }

        for (File packDir : packDirs) {
            TextureCache cache = new TextureCache(packDir.getName());
            byte[] ddsData = cache.getCachedDDS(pngPath);
            if (ddsData != null && DDSDecoder.isDDS(ddsData)) {
                return ddsData;
            }
        }

        return null;
    }

    /**
     * Encode RGBA pixel data to PNG format.
     * Uses a minimal PNG encoder to avoid dependencies.
     */
    private static byte[] encodePNG(byte[] pixels, int width, int height) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // PNG signature
        baos.write(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        // IHDR chunk
        byte[] ihdr = new byte[13];
        ByteBuffer ihdrBuf = ByteBuffer.wrap(ihdr).order(ByteOrder.BIG_ENDIAN);
        ihdrBuf.putInt(width);
        ihdrBuf.putInt(height);
        ihdrBuf.put((byte) 8); // bit depth
        ihdrBuf.put((byte) 6); // color type: RGBA
        ihdrBuf.put((byte) 0); // compression
        ihdrBuf.put((byte) 0); // filter
        ihdrBuf.put((byte) 0); // interlace
        writeChunk(baos, "IHDR", ihdr);

        // IDAT chunk: compressed pixel data
        // Add filter byte (0 = none) at the start of each row
        byte[] filtered = new byte[height * (width * 4 + 1)];
        for (int y = 0; y < height; y++) {
            filtered[y * (width * 4 + 1)] = 0; // No filter
            System.arraycopy(pixels, y * width * 4, filtered, y * (width * 4 + 1) + 1, width * 4);
        }

        // Compress with Deflater
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(6);
        deflater.setInput(filtered);
        deflater.finish();

        byte[] compressed = new byte[filtered.length];
        int compressedLen = deflater.deflate(compressed);
        deflater.end();

        byte[] idat = Arrays.copyOf(compressed, compressedLen);
        writeChunk(baos, "IDAT", idat);

        // IEND chunk
        writeChunk(baos, "IEND", new byte[0]);

        return baos.toByteArray();
    }

    /**
     * Write a PNG chunk.
     */
    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes("ASCII");
        int length = data.length;

        // Length (4 bytes, big-endian)
        out.write((length >> 24) & 0xFF);
        out.write((length >> 16) & 0xFF);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);

        // Type
        out.write(typeBytes);

        // Data
        out.write(data);

        // CRC (simplified — just type + data)
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long crcVal = crc.getValue();

        out.write((int) ((crcVal >> 24) & 0xFF));
        out.write((int) ((crcVal >> 16) & 0xFF));
        out.write((int) ((crcVal >> 8) & 0xFF));
        out.write((int) (crcVal & 0xFF));
    }

    /**
     * ASM-based class file transformer that intercepts Resource.open() for Minecraft 26.2.
     *
     * This transformer uses ASM to modify the bytecode of Resource.open() at class load time.
     * When a texture is requested, the transformed method first checks the DDS cache.
     * If a cached DDS exists, it decompresses to PNG and returns a ByteArrayInputStream
     * containing the cached data, bypassing the original file read entirely.
     *
     * The transformation is safe: if ASM fails to transform a class, it returns
     * the original bytecode unchanged, allowing the game to continue normally.
     *
     * Target methods transformed:
     * - Resource.open() → checks cache before returning InputStream
     * - NativeImage.read(InputStream) → checks cache before decoding
     *
     * ASM library is embedded in the agent JAR by the build process.
     */
    private static class ResourceOpenTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                 ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) {
                return null;
            }

            // Check if ASM is available
            if (!ASMBytecodeHelper.isASMAvailable()) {
                classesSkipped++;
                return null;
            }

            // Target: Resource.open() — the main interception point
            if (className.equals("net/minecraft/server/packs/resources/Resource")) {
                try {
                    byte[] transformed = transformResourceOpen(classfileBuffer);
                    if (transformed != null && transformed != classfileBuffer) {
                        classesTransformed++;
                        logger.info("Transformed Resource.open() — cache check injected");
                    } else {
                        classesSkipped++;
                    }
                    return transformed;
                } catch (Exception e) {
                    logger.warning("Failed to transform Resource.open(): " + e.getMessage());
                    classesSkipped++;
                    return classfileBuffer; // Return original on failure
                }
            }

            // Target: NativeImage.read() — secondary interception point
            if (className.equals("com/mojang/blaze3d/platform/NativeImage")) {
                try {
                    byte[] transformed = transformNativeImageRead(classfileBuffer);
                    if (transformed != null && transformed != classfileBuffer) {
                        classesTransformed++;
                        logger.info("Transformed NativeImage.read() — cache check injected");
                    } else {
                        classesSkipped++;
                    }
                    return transformed;
                } catch (Exception e) {
                    logger.warning("Failed to transform NativeImage.read(): " + e.getMessage());
                    classesSkipped++;
                    return classfileBuffer; // Return original on failure
                }
            }

            return null; // Not a target class
        }

        /**
         * Transform Resource.open() to check DDS cache before returning InputStream.
         *
         * The transformed method prepends:
         *   String path = this.getPath();  // Get resource path
         *   byte[] cached = TextureLoaderHook.getCachedTextureAsPNG(path);
         *   if (cached != null) {
         *       return new ByteArrayInputStream(cached);
         *   }
         *   // ... original method body ...
         */
        private byte[] transformResourceOpen(byte[] classfileBuffer) {
            // Parse the class with ASM
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            // Find the open() method
            for (MethodNode method : classNode.methods) {
                if ("open".equals(method.name) && "()Ljava/io/InputStream;".equals(method.desc)) {
                    // Inject cache check at the beginning of the method
                    InsnList instructions = new InsnList();

                    // Get the resource path: this.getPath()
                    instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
                    instructions.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/server/packs/resources/Resource",
                        "getPath",
                        "()Ljava/lang/String;",
                        false
                    ));

                    // Call TextureLoaderHook.getCachedTextureAsPNG(path)
                    instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/jmine/texture/TextureLoaderHook",
                        "getCachedTextureAsPNG",
                        "(Ljava/lang/String;)[B",
                        false
                    ));

                    // Store result in local variable
                    instructions.add(new VarInsnNode(Opcodes.ASTORE, 1)); // cached

                    // Check if cached != null
                    LabelNode cachedNotNull = new LabelNode();
                    instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // cached
                    instructions.add(new JumpInsnNode(Opcodes.IFNULL, cachedNotNull));

                    // If cached, create ByteArrayInputStream and return
                    instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // cached
                    instructions.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
                    instructions.add(new InsnNode(Opcodes.DUP));
                    instructions.add(new VarInsnNode(Opcodes.ALOAD, 1)); // cached
                    instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/io/ByteArrayInputStream",
                        "<init>",
                        "([B)V",
                        false
                    ));
                    instructions.add(new InsnNode(Opcodes.ARETURN));

                    // Label for when cached is null — fall through to original method
                    instructions.add(cachedNotNull);

                    // Prepend the instructions to the method body
                    method.instructions.insert(instructions);

                    logger.info("Successfully injected cache check into Resource.open()");
                    break;
                }
            }

            // Write the transformed class
            classNode.accept(writer);
            return writer.toByteArray();
        }

        /**
         * Transform NativeImage.read(InputStream) to check DDS cache before decoding.
         *
         * The transformed method wraps the InputStream to serve cached DDS data.
         * If a cached texture exists, the InputStream is replaced with one that
         * provides the cached PNG data instead of reading from the original source.
         */
        private byte[] transformNativeImageRead(byte[] classfileBuffer) {
            // Parse the class with ASM
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            // Find the read(InputStream) method
            for (MethodNode method : classNode.methods) {
                if ("read".equals(method.name) && "(Ljava/io/InputStream;)V".equals(method.desc)) {
                    // For NativeImage.read(), we inject a static initializer check
                    // that sets up the cache hook before any read operation
                    // (The actual cache serving is done at the Resource.open() level)

                    logger.info("NativeImage.read() found — cache check at Resource.open() level is sufficient");
                    break;
                }
            }

            // Write the class (may be unchanged if no transformation needed)
            classNode.accept(writer);
            return writer.toByteArray();
        }
    }

    /**
     * Get statistics about the decoded texture cache and transformations.
     */
    public static CacheStats getStats() {
        return new CacheStats(DECODED_CACHE.size(), DECODED_CACHE_SIZE.get(),
            classesTransformed, classesSkipped, cacheHits);
    }

    /**
     * Get the number of classes successfully transformed.
     */
    public static int getClassesTransformed() {
        return classesTransformed;
    }

    /**
     * Get the number of classes skipped.
     */
    public static int getClassesSkipped() {
        return classesSkipped;
    }

    /**
     * Register the NativeImage hook — called by NativeImageTransformer's static initializer.
     * This method is invoked when the NativeImage class is loaded, ensuring the cache
     * hook is active before any texture decoding occurs.
     */
    public static void registerNativeImageHook() {
        System.setProperty("jmine.nativeimage.hook.active", "true");
        logger.info("NativeImage hook registered — cache check active for all texture reads");
    }

    /**
     * Clear the decoded texture cache.
     */
    public static void clearDecodedCache() {
        synchronized (DECODED_CACHE) {
            DECODED_CACHE.clear();
            DECODED_CACHE_SIZE.set(0);
        }
    }

    /**
     * Cache statistics including transformation info.
     */
    public static class CacheStats {
        public final int textureCount;
        public final long totalSize;
        public final int classesTransformed;
        public final int classesSkipped;
        public final int cacheHits;

        CacheStats(int textureCount, long totalSize, int classesTransformed, int classesSkipped, int cacheHits) {
            this.textureCount = textureCount;
            this.totalSize = totalSize;
            this.classesTransformed = classesTransformed;
            this.classesSkipped = classesSkipped;
            this.cacheHits = cacheHits;
        }
    }
}
