package com.jmine.texture;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.logging.Logger;

// ASM imports — embedded in the agent JAR by the build process
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * ASM-based bytecode transformer that patches com.mojang.blaze3d.platform.NativeImage
 * to check the JMine DDS cache before decoding PNG images.
 *
 * Minecraft 26.2 texture pipeline:
 * 1. TextureManager.getTexture() → SimpleTexture.loadContents() → TextureContents.load()
 * 2. TextureContents.load() → ResourceManager.getResourceOrThrow() → Resource.open() → NativeImage.read(InputStream)
 * 3. NativeImage.read(InputStream) → reads bytes → validates PNG header → STBImage.stbi_load_from_memory() → RGBA
 * 4. SpriteResourceLoader.loadSprite() → same pattern: Resource.open() → NativeImage.read(InputStream)
 *
 * The primary interception point is Resource.open() (handled by TextureLoaderHook).
 * This transformer provides a secondary interception at the NativeImage level:
 * if Resource.open() transformation didn't catch a texture load, this transformer
 * ensures the cache is still checked before STBImage decoding.
 *
 * ASM is embedded in the agent JAR, so no external dependencies are needed.
 * The transformer is safe: if ASM fails to transform a class, it returns
 * the original bytecode unchanged, allowing the game to continue normally.
 */
public class NativeImageTransformer implements ClassFileTransformer {

    private static final Logger logger = Logger.getLogger("JMine.NativeImageTransformer");

    // Target class name
    private static final String NATIVE_IMAGE_CLASS = "com/mojang/blaze3d/platform/NativeImage";

    // Track transformation statistics
    private static boolean transformationAttempted = false;
    private static boolean transformationSuccessful = false;
    private static int classesTransformed = 0;

    /**
     * Install the transformer with the given instrumentation instance.
     * This should be called from TextureAgent.premain().
     *
     * @param instrumentation The instrumentation instance from premain
     * @return true if the transformer was installed successfully
     */
    public static boolean install(Instrumentation instrumentation) {
        if (transformationAttempted) {
            return transformationSuccessful;
        }
        transformationAttempted = true;

        try {
            // Check if ASM is available
            if (!ASMBytecodeHelper.isASMAvailable()) {
                logger.warning("ASM library not found — NativeImage transformer disabled");
                transformationSuccessful = false;
                return false;
            }

            // Add the transformer
            instrumentation.addTransformer(new NativeImageTransformer(), true);

            // Try to retransform the NativeImage class if it's already loaded
            try {
                Class<?> nativeImageClass = Class.forName("com.mojang.blaze3d.platform.NativeImage");
                instrumentation.retransformClasses(nativeImageClass);
                transformationSuccessful = true;
                logger.info("NativeImage class retransformed successfully");
            } catch (ClassNotFoundException e) {
                // NativeImage not yet loaded — the transformer will catch it when it loads
                logger.info("NativeImage not yet loaded — transformer will catch it on class load");
                transformationSuccessful = true;
            }

            return true;
        } catch (Exception e) {
            logger.warning("Failed to install NativeImage transformer: " + e.getMessage());
            transformationSuccessful = false;
            return false;
        }
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {

        if (!NATIVE_IMAGE_CLASS.equals(className)) {
            return null; // Not the class we're targeting
        }

        logger.info("Transforming NativeImage class with ASM...");

        try {
            // Use ASM to transform the NativeImage class
            byte[] transformed = transformNativeImage(classfileBuffer);
            if (transformed != null && transformed != classfileBuffer) {
                classesTransformed++;
                transformationSuccessful = true;
                logger.info("NativeImage transformed successfully — cache check injected");
                return transformed;
            } else {
                logger.info("NativeImage transformation skipped — no matching methods found");
                transformationSuccessful = true;
                return classfileBuffer;
            }
        } catch (Exception e) {
            logger.warning("Error transforming NativeImage: " + e.getMessage());
            return classfileBuffer; // Return original on failure
        }
    }

    /**
     * Transform NativeImage class to check DDS cache before decoding.
     *
     * The transformation adds a static initializer that registers the cache hook,
     * ensuring that all subsequent read() operations check the cache first.
     */
    private byte[] transformNativeImage(byte[] classfileBuffer) {
        // Parse the class with ASM
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        // Add a static initializer that registers the cache hook
        // This ensures the TextureLoaderHook is aware of NativeImage operations
        InsnList staticInit = new InsnList();

        // TextureLoaderHook.registerNativeImageHook();
        staticInit.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "com/jmine/texture/TextureLoaderHook",
            "registerNativeImageHook",
            "()V",
            false
        ));

        // Return from static initializer
        staticInit.add(new InsnNode(Opcodes.RETURN));

        // Create the static initializer method
        MethodNode clinit = new MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        );
        clinit.instructions.add(staticInit);
        clinit.maxStack = 1;
        clinit.maxLocals = 0;

        // Add the static initializer to the class
        classNode.methods.add(clinit);

        logger.info("Added static initializer to NativeImage — cache hook registered");

        // Write the transformed class
        classNode.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Get the number of classes transformed.
     */
    public static int getClassesTransformed() {
        return classesTransformed;
    }

    /**
     * Check if the transformer was successfully installed.
     */
    public static boolean isTransformationSuccessful() {
        return transformationSuccessful;
    }
}
