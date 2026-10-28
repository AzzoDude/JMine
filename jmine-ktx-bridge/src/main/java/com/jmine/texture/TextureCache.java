package com.jmine.texture;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Texture cache manager — handles cache directory, metadata, and cache invalidation.
 *
 * Cache structure:
 * ~/.jmine_cache/textures/<pack_hash>/
 *   ├── cache.json
 *   └── textures/
 *       └── assets/minecraft/block/stone.dds
 */
public class TextureCache {

    private static final Logger logger = Logger.getLogger("JMine.TextureCache");
    private static final String CACHE_BASE = System.getProperty("user.home") + File.separator + ".jmine_cache";

    private final File cacheDir;
    private final File textureDir;
    private final File metadataFile;
    private final Map<String, CacheEntry> metadata = new HashMap<>();

    /**
     * Cache entry for a single texture.
     */
    public static class CacheEntry {
        String ddsPath;
        String pngHash;
        int format;
        long timestamp;

        CacheEntry(String ddsPath, String pngHash, int format, long timestamp) {
            this.ddsPath = ddsPath;
            this.pngHash = pngHash;
            this.format = format;
            this.timestamp = timestamp;
        }
    }

    /**
     * Create a texture cache for a resource pack.
     *
     * @param packHash SHA-256 hash of the resource pack ZIP
     */
    public TextureCache(String packHash) {
        this.cacheDir = new File(CACHE_BASE + File.separator + "textures" + File.separator + packHash);
        this.textureDir = new File(cacheDir, "textures");
        this.metadataFile = new File(cacheDir, "cache.json");
        loadMetadata();
    }

    /**
     * Check if a texture is cached and valid.
     *
     * @param internalPath PNG path (e.g., "assets/minecraft/block/stone.png")
     * @param pngHash SHA-256 hash of the PNG data
     * @return true if cached and hash matches
     */
    public boolean isCached(String internalPath, String pngHash) {
        CacheEntry entry = metadata.get(internalPath);
        if (entry == null) return false;
        if (!entry.pngHash.equals(pngHash)) return false;

        File ddsFile = new File(textureDir, entry.ddsPath);
        return ddsFile.exists();
    }

    /**
     * Get cached DDS data.
     *
     * @param internalPath PNG path
     * @return DDS byte array or null if not cached
     */
    public byte[] getCachedDDS(String internalPath) {
        CacheEntry entry = metadata.get(internalPath);
        if (entry == null) return null;

        File ddsFile = new File(textureDir, entry.ddsPath);
        if (!ddsFile.exists()) return null;

        try {
            return Files.readAllBytes(ddsFile.toPath());
        } catch (IOException e) {
            logger.warning("Failed to read cached DDS: " + ddsFile);
            return null;
        }
    }

    /**
     * Save DDS data to cache.
     *
     * @param internalPath PNG path
     * @param ddsData DDS byte array
     * @param pngHash SHA-256 hash of the original PNG
     * @param format DXGI format used
     */
    public void saveDDS(String internalPath, byte[] ddsData, String pngHash, int format) {
        String ddsRel = getDDSRelativePath(internalPath);

        File ddsFile = new File(textureDir, ddsRel);
        ddsFile.getParentFile().mkdirs();

        try {
            Files.write(ddsFile.toPath(), ddsData);
        } catch (IOException e) {
            logger.severe("Failed to save DDS: " + ddsFile);
            return;
        }

        metadata.put(internalPath, new CacheEntry(ddsRel, pngHash, format, System.currentTimeMillis()));
    }

    /**
     * Save metadata to disk.
     */
    public void saveMetadata() {
        cacheDir.mkdirs();
        try (Writer writer = new FileWriter(metadataFile)) {
            // Simple JSON-like format
            writer.write("{\n");
            boolean first = true;
            for (Map.Entry<String, CacheEntry> entry : metadata.entrySet()) {
                if (!first) writer.write(",\n");
                first = false;
                writer.write("  \"" + escapeJson(entry.getKey()) + "\": {");
                writer.write("\"dds\":\"" + escapeJson(entry.getValue().ddsPath) + "\",");
                writer.write("\"hash\":\"" + entry.getValue().pngHash + "\",");
                writer.write("\"format\":" + entry.getValue().format + "}");
            }
            writer.write("\n}\n");
        } catch (IOException e) {
            logger.warning("Failed to save metadata: " + e.getMessage());
        }
    }

    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        int count = metadata.size();
        long totalSize = 0;

        for (CacheEntry entry : metadata.values()) {
            File ddsFile = new File(textureDir, entry.ddsPath);
            if (ddsFile.exists()) {
                totalSize += ddsFile.length();
            }
        }

        return new CacheStats(count, totalSize);
    }

    /**
     * Clean the cache.
     */
    public void clean() {
        deleteRecursive(cacheDir);
        metadata.clear();
    }

    /**
     * Get the cache directory path.
     */
    public File getCacheDir() {
        return cacheDir;
    }

    /**
     * Cache statistics.
     */
    public static class CacheStats {
        public final int cachedTextures;
        public final long compressedSize;

        CacheStats(int cachedTextures, long compressedSize) {
            this.cachedTextures = cachedTextures;
            this.compressedSize = compressedSize;
        }
    }

    private void loadMetadata() {
        if (!metadataFile.exists()) return;

        try (Reader reader = new FileReader(metadataFile)) {
            // Simple JSON parser for our format
            String content = new String(Files.readAllBytes(metadataFile.toPath()));
            // Parse key-value pairs
            int i = 1; // Skip opening {
            while (i < content.length()) {
                // Find key
                int keyStart = content.indexOf('"', i);
                if (keyStart < 0) break;
                int keyEnd = content.indexOf('"', keyStart + 1);
                if (keyEnd < 0) break;
                String key = content.substring(keyStart + 1, keyEnd);

                // Find dds path
                int ddsStart = content.indexOf("\"dds\":\"", keyEnd);
                if (ddsStart < 0) break;
                ddsStart += 6;
                int ddsEnd = content.indexOf('"', ddsStart);
                if (ddsEnd < 0) break;
                String ddsPath = content.substring(ddsStart, ddsEnd);

                // Find hash
                int hashStart = content.indexOf("\"hash\":\"", ddsEnd);
                if (hashStart < 0) break;
                hashStart += 7;
                int hashEnd = content.indexOf('"', hashStart);
                if (hashEnd < 0) break;
                String hash = content.substring(hashStart, hashEnd);

                // Find format
                int formatStart = content.indexOf("\"format\":", hashEnd);
                if (formatStart < 0) break;
                formatStart += 9;
                int formatEnd = content.indexOf(',', formatStart);
                if (formatEnd < 0) formatEnd = content.indexOf('}', formatStart);
                if (formatEnd < 0) break;
                int format = Integer.parseInt(content.substring(formatStart, formatEnd).trim());

                metadata.put(key, new CacheEntry(ddsPath, hash, format, 0));
                i = formatEnd + 1;
            }
        } catch (Exception e) {
            logger.warning("Failed to load metadata: " + e.getMessage());
            metadata.clear();
        }
    }

    private String getDDSRelativePath(String internalPath) {
        int dot = internalPath.lastIndexOf('.');
        String name = dot >= 0 ? internalPath.substring(0, dot) : internalPath;
        return name + ".dds";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
