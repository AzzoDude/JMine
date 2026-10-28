package com.jmine.texture;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * JMine Texture Agent — Java agent that intercepts Minecraft startup,
 * compresses all PNG textures to DDS format, and caches them.
 *
 * Usage: -javaagent:jmine-ktx-bridge.jar
 *
 * The agent runs in a background thread during Minecraft's initialization phase.
 * It scans all resource packs, compresses PNGs to DDS (BC7/BC3/BC1), and caches
 * them in ~/.jmine_cache/textures/. On subsequent launches, cached DDS files are
 * used directly, making startup much faster.
 */
public class TextureAgent {

    private static final Logger logger = Logger.getLogger("JMine.TextureAgent");

    /**
     * Java agent entry point — called when the JVM starts with -javaagent.
     *
     * @param args Agent arguments (unused)
     * @param loader Class loader
     * @throws Exception if agent fails to initialize
     */
    private static java.lang.instrument.Instrumentation instrumentationInstance;

    public static void premain(String args, java.lang.instrument.Instrumentation loader) {
        logger.info("JMine Texture Agent initializing...");
        instrumentationInstance = loader;

        // Load the native KTX library via Project Panama FFM — no JNI needed
        boolean nativeLoaded = KTXNative.loadNativeLibrary();
        if (nativeLoaded) {
            logger.info("KTX native library loaded via Panama FFM — using fast native compression");
        } else {
            logger.info("KTX native library not available via Panama — using Java fallback compression");
        }

        // Detect the active Minecraft framework (OptiFine, Embeddium, Vanilla, etc.)
        FrameworkDetector.Framework framework = FrameworkDetector.detect();
        logger.info("Framework detected: " + framework + " (DDS support: " + framework.supportsDDS() + ")");

        // Install the loader hook immediately — it will intercept texture loads
        // even while compression is still running in the background
        try {
            TextureLoaderHook.initialize(loader);
            logger.info("Texture loader hook installed");
        } catch (Exception e) {
            logger.severe("Failed to install loader hook: " + e.getMessage());
            e.printStackTrace();
        }

        // Run texture processing in a background thread
        Thread processor = new Thread(() -> {
            try {
                processTextures();
                logger.info("Texture compression complete — loader hook is active");
            } catch (Exception e) {
                logger.severe("Texture agent failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "JMine-TextureProcessor");
        processor.setDaemon(true);
        processor.setPriority(Thread.NORM_PRIORITY);
        processor.start();

        logger.info("JMine Texture Agent started background processor");
    }

    /**
     * Main processing logic — scans resource packs and compresses textures.
     */
    private static void processTextures() throws Exception {
        long startTime = System.currentTimeMillis();

        // Find resource packs from Minecraft's arguments
        List<File> resourcePacks = findResourcePacks();

        if (resourcePacks.isEmpty()) {
            logger.info("No resource packs found, skipping texture processing");
            return;
        }

        logger.info("Found " + resourcePacks.size() + " resource pack(s)");

        int totalProcessed = 0;
        int totalCached = 0;
        int totalErrors = 0;

        // Process each resource pack
        for (File pack : resourcePacks) {
            try {
                var stats = processPack(pack);
                totalProcessed += stats.processed;
                totalCached += stats.cached;
                totalErrors += stats.errors;
                logger.info("Pack " + pack.getName() + ": " + stats.processed + " processed, "
                           + stats.cached + " cached, " + stats.errors + " errors");
            } catch (Exception e) {
                logger.severe("Failed to process pack " + pack + ": " + e.getMessage());
                totalErrors++;
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Texture processing complete: " + totalProcessed + " processed, "
                    + totalCached + " cached, " + totalErrors + " errors in " + elapsed + "ms");
    }

    /**
     * Find resource packs from Minecraft's command line arguments.
     * Looks for --assetIndex, --assetsDir, and resource pack paths.
     */
    private static List<File> findResourcePacks() {
        List<File> packs = new ArrayList<>();

        // Check for resource packs in common locations
        String gameDir = System.getProperty("user.dir");
        String homeDir = System.getProperty("user.home");

        // Minecraft resource packs directory
        File mcDir = new File(homeDir, ".minecraft");
        File resourcePacksDir = new File(mcDir, "resourcepacks");
        if (resourcePacksDir.exists()) {
            File[] files = resourcePacksDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".zip") || name.toLowerCase().endsWith(".jar"));
            if (files != null) {
                for (File f : files) {
                    packs.add(f);
                }
            }
        }

        // Vanilla assets
        File assetsDir = new File(mcDir, "assets");
        if (assetsDir.exists()) {
            // Scan for asset indexes
            File objectsDir = new File(assetsDir, "objects");
            if (objectsDir.exists()) {
                // The vanilla assets are embedded in the game, not a separate pack
                // We'll handle them via the game's asset index
            }
        }

        // Check command line for resource pack paths
        String[] args = getCommandLineArgs();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--resourcePack") && i + 1 < args.length) {
                    File pack = new File(args[i + 1]);
                    if (pack.exists()) {
                        packs.add(pack);
                    }
                }
            }
        }

        return packs;
    }

    /**
     * Process a single resource pack.
     */
    private static PackStats processPack(File pack) throws IOException {
        logger.info("Scanning resource pack: " + pack.getName());

        List<ResourcePackScanner.PNGEntry> pngs = ResourcePackScanner.scan(pack);
        if (pngs.isEmpty()) {
            logger.info("No PNG textures found in " + pack.getName());
            return new PackStats(0, 0, 0);
        }

        logger.info("Found " + pngs.size() + " PNG textures in " + pack.getName());

        // Get pack hash for cache
        String packHash = pngs.get(0).packHash;
        TextureCache cache = new TextureCache(packHash);

        // Use atomic counters for thread-safe counting
        java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger cached = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        // Process in parallel
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (ResourcePackScanner.PNGEntry png : pngs) {
            futures.add(executor.submit(() -> {
                try {
                    String pngHash = png.dataHash();

                    // Check cache
                    if (cache.isCached(png.internalPath, pngHash)) {
                        cached.incrementAndGet();
                        return;
                    }

                    // Decode PNG
                    ResourcePackScanner.RGBAImage image = ResourcePackScanner.decodePNG(png.data);

                    // Select format
                    int format = ResourcePackScanner.selectFormat(image);

                    // Compress to DDS using KTXNative (native or Java fallback)
                    byte[] ddsData;
                    switch (format) {
                        case 1: // BC1
                            ddsData = KTXNative.compressBC1(image.pixels, image.width, image.height);
                            break;
                        case 3: // BC3
                            ddsData = KTXNative.compressBC3(image.pixels, image.width, image.height);
                            break;
                        case 7: // BC7 (default)
                        default:
                            ddsData = KTXNative.compressBC7(image.pixels, image.width, image.height);
                            break;
                    }

                    // Save to cache
                    cache.saveDDS(png.internalPath, ddsData, pngHash, format);
                    processed.incrementAndGet();

                } catch (Exception e) {
                    logger.warning("Failed to process " + png.internalPath + ": " + e.getMessage());
                    errors.incrementAndGet();
                }
            }));
        }

        // Wait for all tasks
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }
        executor.shutdown();

        // Save metadata
        cache.saveMetadata();

        return new PackStats(processed.get(), cached.get(), errors.get());
    }

    /**
     * Statistics for a single pack.
     */
    private static class PackStats {
        final int processed;
        final int cached;
        final int errors;

        PackStats(int processed, int cached, int errors) {
            this.processed = processed;
            this.cached = cached;
            this.errors = errors;
        }
    }

    /**
     * Get command line arguments (reflection-based, may not work in all cases).
     */
    private static String[] getCommandLineArgs() {
        try {
            // Try to get args from Launcher
            Class<?> launcherClass = Class.forName("net.minecraft.launchwrapper.Launch");
            java.lang.reflect.Field field = launcherClass.getDeclaredField("args");
            field.setAccessible(true);
            return (String[]) field.get(null);
        } catch (Exception e) {
            // Fallback: try sun.misc.Launcher
            try {
                Class<?> appClass = Class.forName("sun.misc.Launcher");
                Object launcher = appClass.getMethod("getAppClassLoader").invoke(null);
                Class<?> lcClass = launcher.getClass();
                java.lang.reflect.Field field = lcClass.getDeclaredField("modulePath");
                field.setAccessible(true);
                // This is a best-effort approach
            } catch (Exception e2) {
                // Ignore
            }
        }
        return null;
    }
}
