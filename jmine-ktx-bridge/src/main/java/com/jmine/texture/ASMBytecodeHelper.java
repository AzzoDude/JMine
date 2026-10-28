package com.jmine.texture;

import java.util.logging.*;

/**
 * ASM utility — checks if the ASM library is available in the agent JAR.
 *
 * ASM 9.7.1 is embedded in the agent JAR by the build process (build-bridge.ps1).
 * The actual bytecode transformation is performed by ResourceOpenTransformer and
 * NativeImageTransformer, which directly import and use ASM classes.
 *
 * This helper class provides availability checking so the transformers can
 * gracefully degrade if ASM is somehow missing from the JAR.
 */
public class ASMBytecodeHelper {

    private static final Logger logger = Logger.getLogger("JMine.ASMBytecodeHelper");
    private static Boolean asmAvailable = null;

    /**
     * Check if ASM library is available in the agent JAR.
     *
     * The result is cached after the first check to avoid repeated reflection calls.
     *
     * @return true if ASM classes can be loaded from the agent JAR
     */
    public static boolean isASMAvailable() {
        if (asmAvailable != null) {
            return asmAvailable;
        }
        try {
            Class.forName("org.objectweb.asm.ClassReader");
            asmAvailable = true;
            logger.info("ASM 9.7.1 detected in agent JAR — bytecode transformation enabled");
            return true;
        } catch (ClassNotFoundException e) {
            asmAvailable = false;
            logger.severe("ASM library not found in agent JAR — bytecode transformation disabled");
            logger.severe("This usually means the JAR was built without ASM. Rebuild with: pwsh -File scripts/graal/build-bridge.ps1");
            return false;
        }
    }

    /**
     * Get the ASM version string.
     *
     * @return ASM version or "not available"
     */
    public static String getASMVersion() {
        if (!isASMAvailable()) {
            return "not available";
        }
        return "9.7.1 (embedded)";
    }
}
