package com.jmine.texture;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;

/**
 * Resource pack wrapper — wraps Minecraft's resource pack to intercept
 * texture file reads and serve cached DDS textures.
 *
 * This is the runtime integration layer: when Minecraft reads a texture
 * from a resource pack, this wrapper intercepts the request, checks the
 * DDS cache, and serves the cached texture if available.
 *
 * The wrapper works by creating a proxy around the resource pack's
 * getInputStream method. When a PNG texture is requested, it checks
 * the DDS cache and returns the decoded PNG data instead.
 */
public class ResourcePackWrapper {

    private static final Logger logger = Logger.getLogger("JMine.ResourcePackWrapper");

    /**
     * Wrap a resource pack object to intercept texture loading.
     * Uses Java dynamic proxies for interface-based resource packs.
     *
     * @param resourcePack The original resource pack object
     * @return Wrapped resource pack or the original if wrapping failed
     */
    public static Object wrap(Object resourcePack) {
        if (resourcePack == null) {
            return null;
        }

        // Check if the hook is active
        String hookActive = System.getProperty("jmine.texture.hook.active");
        if (!"true".equals(hookActive)) {
            return resourcePack;
        }

        // Try to wrap using dynamic proxy if the class implements an interface
        Class<?>[] interfaces = resourcePack.getClass().getInterfaces();
        if (interfaces.length > 0) {
            try {
                ClassLoader cl = resourcePack.getClass().getClassLoader();
                Class<?> proxyInterface = findResourcePackInterface(interfaces);
                if (proxyInterface != null) {
                    Object proxy = Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{proxyInterface},
                        new TextureLoadingHandler(resourcePack)
                    );
                    logger.info("Wrapped resource pack: " + resourcePack.getClass().getName());
                    return proxy;
                }
            } catch (Exception e) {
                logger.fine("Failed to create proxy: " + e.getMessage());
            }
        }

        // Fallback: try to wrap via reflection
        return wrapViaReflection(resourcePack);
    }

    /**
     * Find the resource pack interface from the list of interfaces.
     */
    private static Class<?> findResourcePackInterface(Class<?>[] interfaces) {
        for (Class<?> iface : interfaces) {
            String name = iface.getName();
            if (name.contains("ResourcePack") || name.contains("Resource")) {
                return iface;
            }
        }
        return null;
    }

    /**
     * Wrap via reflection — modify the resource pack's internal file access.
     */
    private static Object wrapViaReflection(Object resourcePack) {
        // Try to find and wrap the internal InputStream provider
        try {
            Class<?> clazz = resourcePack.getClass();

            // Look for common fields that hold file access
            String[] fieldNames = {"rootPath", "pack", "fileSystem", "ioSupplier", "packResources"};
            for (String fieldName : fieldNames) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object value = field.get(resourcePack);
                    if (value instanceof Path) {
                        // Path-based resource pack — wrap the path
                        logger.fine("Found path field: " + fieldName);
                    }
                } catch (NoSuchFieldException e) {
                    // Field doesn't exist, try next
                }
            }
        } catch (Exception e) {
            logger.fine("Reflection wrap failed: " + e.getMessage());
        }

        return resourcePack;
    }

    /**
     * Invocation handler for dynamic proxy — intercepts getInputStream calls.
     */
    private static class TextureLoadingHandler implements InvocationHandler {

        private final Object target;

        TextureLoadingHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            // Intercept getInputStream and similar methods
            if (methodName.equals("getInputStream") ||
                methodName.equals("open") ||
                methodName.equals("getSourceStream")) {

                if (args != null && args.length > 0) {
                    Object arg = args[0];
                    String path = extractPath(arg);

                    if (path != null && path.toLowerCase().endsWith(".png")) {
                        // Check DDS cache
                        byte[] cachedPNG = TextureLoaderHook.getCachedTextureAsPNG(path);
                        if (cachedPNG != null) {
                            logger.fine("Hooked texture load: " + path);
                            return new ByteArrayInputStream(cachedPNG);
                        }
                    }
                }
            }

            // For listResourceNames or similar, also check for DDS files
            if (methodName.equals("listResourceNames") ||
                methodName.equals("getNames") ||
                methodName.equals("findResources")) {

                try {
                    Object result = method.invoke(target, args);
                    // Don't modify the list — just let it pass through
                    return result;
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }

            // Default: pass through to the original method
            return method.invoke(target, args);
        }

        /**
         * Extract the file path from various argument types.
         */
        private String extractPath(Object arg) {
            if (arg == null) return null;

            // ResourceLocation (Minecraft's path type)
            try {
                Method pathMethod = arg.getClass().getMethod("getPath");
                return (String) pathMethod.invoke(arg);
            } catch (Exception e) {
                // Not a ResourceLocation
            }

            // String path
            if (arg instanceof String) {
                return (String) arg;
            }

            // Path object
            if (arg instanceof Path) {
                return ((Path) arg).toString();
            }

            // File object
            if (arg instanceof File) {
                return ((File) arg).toString();
            }

            // Try toString as fallback
            return arg.toString();
        }
    }

    /**
     * Wrap an InputStream to intercept PNG data and replace with cached DDS.
     *
     * @param original The original InputStream
     * @param path The file path being read
     * @return Wrapped InputStream or the original
     */
    public static InputStream wrapInputStream(InputStream original, String path) {
        if (path == null || !path.toLowerCase().endsWith(".png")) {
            return original;
        }

        byte[] cachedPNG = TextureLoaderHook.getCachedTextureAsPNG(path);
        if (cachedPNG != null) {
            logger.fine("InputStream wrapped for: " + path);
            return new ByteArrayInputStream(cachedPNG);
        }

        return original;
    }

    /**
     * Check if the hook is currently active.
     */
    public static boolean isActive() {
        return "true".equals(System.getProperty("jmine.texture.hook.active"));
    }
}
