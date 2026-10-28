package com.jmine.texture;

import java.util.logging.Logger;

/**
 * Detects which Minecraft optimization framework is currently running.
 *
 * This class checks for the presence of known optimization mods by testing
 * whether their key classes exist on the classpath. The result is cached
 * after the first detection to avoid repeated class lookups.
 *
 * Detection order (highest priority first):
 * 1. OptiFine — supports DDS textures natively
 * 2. Embeddium — supports DDS via OptiFine compatibility layer
 * 3. Sodium — no direct DDS support, but indicates optimization mod
 * 4. Vanilla — no optimization mod detected
 */
public class FrameworkDetector {

    private static final Logger logger = Logger.getLogger("JMine.FrameworkDetector");

    /**
     * Minecraft optimization framework types.
     */
    public enum Framework {
        /** No optimization mod — vanilla Minecraft */
        VANILLA(false),
        /** Sodium — optimization mod without direct DDS support */
        SODIUM(false),
        /** Embeddium — Sodium fork with OptiFine compatibility, supports DDS */
        EMBEDDIUM(true),
        /** OptiFine — full optimization mod with native DDS support */
        OPTIFINE(true);

        private final boolean supportsDDS;

        Framework(boolean supportsDDS) {
            this.supportsDDS = supportsDDS;
        }

        /**
         * @return true if this framework can load DDS textures directly
         */
        public boolean supportsDDS() {
            return supportsDDS;
        }
    }

    private static Framework detectedFramework = null;
    private static boolean detectionAttempted = false;

    /**
     * Detect the active Minecraft optimization framework.
     * The result is cached after the first call.
     *
     * @return the detected framework type
     */
    public static Framework detect() {
        if (detectionAttempted) {
            return detectedFramework;
        }
        detectionAttempted = true;

        // Check for OptiFirst (highest priority)
        if (isClassPresent("optifine.OptiFineForgeEnv")) {
            detectedFramework = Framework.OPTIFINE;
            logger.info("Detected framework: OptiFine (DDS support enabled)");
            return detectedFramework;
        }

        // Check for Embeddium (Sodium fork with OptiFine compatibility)
        if (isClassPresent("me.jellysquid.mods.sodium.client.SodiumClientMod")) {
            // Distinguish between Sodium and Embeddium
            if (isClassPresent("me.modmuss50.embeddium.Embeddium")) {
                detectedFramework = Framework.EMBEDDIUM;
                logger.info("Detected framework: Embeddium (DDS support enabled)");
            } else {
                detectedFramework = Framework.SODIUM;
                logger.info("Detected framework: Sodium (no DDS support)");
            }
            return detectedFramework;
        }

        // Check for other OptiFine class names
        if (isClassPresent("optifine.OptiFine") || 
            isClassPresent("optifine.Installer")) {
            detectedFramework = Framework.OPTIFINE;
            logger.info("Detected framework: OptiFine (DDS support enabled)");
            return detectedFramework;
        }

        // Default to vanilla
        detectedFramework = Framework.VANILLA;
        logger.info("Detected framework: Vanilla Minecraft (DDS fallback to PNG)");
        return detectedFramework;
    }

    /**
     * Get the currently detected framework without re-detecting.
     *
     * @return the detected framework, or VANILLA if not yet detected
     */
    public static Framework getDetectedFramework() {
        if (!detectionAttempted) {
            return detect();
        }
        return detectedFramework;
    }

    /**
     * Check whether the current framework supports DDS textures directly.
     *
     * @return true if DDS files can be served directly to the GPU
     */
    public static boolean supportsDDSTextures() {
        return getDetectedFramework().supportsDDS();
    }

    /**
     * Check if a specific class exists on the classpath.
     *
     * @param className fully qualified class name
     * @return true if the class can be loaded
     */
    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Reset the detection cache. Useful for testing.
     */
    public static void reset() {
        detectedFramework = null;
        detectionAttempted = false;
    }
}
