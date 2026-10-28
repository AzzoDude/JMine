package com.jmine.texture;

import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Scans resource pack ZIP files for PNG textures and processes them.
 */
public class ResourcePackScanner {

    /**
     * Entry point for a PNG file found in a resource pack.
     */
    public static class PNGEntry {
        final String internalPath;  // e.g., "assets/minecraft/block/stone.png"
        final byte[] data;          // PNG file data
        final String packHash;      // SHA-256 of the pack ZIP

        PNGEntry(String internalPath, byte[] data, String packHash) {
            this.internalPath = internalPath;
            this.data = data;
            this.packHash = packHash;
        }

        /**
         * Get the output DDS path relative to the cache directory.
         */
        public String getDDSPath() {
            String name = internalPath;
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                name = name.substring(0, dot);
            }
            return name + ".dds";
        }

        /**
         * Compute SHA-256 hash of this PNG's data.
         */
        public String dataHash() {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return bytesToHex(md.digest(data));
            } catch (Exception e) {
                return "";
            }
        }
    }

    /**
     * Scan a resource pack ZIP for PNG texture files.
     *
     * @param packPath Path to the resource pack ZIP
     * @return List of PNG entries
     */
    public static List<PNGEntry> scan(File packPath) throws IOException {
        List<PNGEntry> result = new ArrayList<>();

        String packHash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = new FileInputStream(packPath)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            packHash = bytesToHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            packHash = "unknown";
        }

        try (ZipFile zf = new ZipFile(packPath)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();

                // Skip non-PNG files
                if (!name.endsWith(".png")) continue;

                // Skip rarely used variants
                if (name.contains("_nocolor") || name.contains("_overlay")) continue;

                // Skip non-texture directories
                if (!name.startsWith("assets/")) continue;

                try (InputStream is = zf.getInputStream(entry)) {
                    byte[] data = is.readAllBytes();
                    result.add(new PNGEntry(entry.getName(), data, packHash));
                }
            }
        }

        return result;
    }

    /**
     * Decode PNG data to RGBA pixels using Java's built-in ImageIO.
     *
     * @param pngData PNG file bytes
     * @return RGBA pixel array (width * height * 4 bytes)
     * @throws IOException if PNG cannot be decoded
     */
    public static RGBAImage decodePNG(byte[] pngData) throws IOException {
        javax.imageio.ImageIO.setUseCache(false);
        try (ByteArrayInputStream bis = new ByteArrayInputStream(pngData)) {
            var image = javax.imageio.ImageIO.read(bis);
            if (image == null) {
                throw new IOException("Failed to decode PNG");
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int[] rgb = new int[width * height];
            image.getRGB(0, 0, width, height, rgb, 0, width);

            // Convert int[] RGB to byte[] RGBA
            byte[] pixels = new byte[width * height * 4];
            for (int i = 0; i < rgb.length; i++) {
                pixels[i * 4] = (byte) ((rgb[i] >> 16) & 0xFF);     // R
                pixels[i * 4 + 1] = (byte) ((rgb[i] >> 8) & 0xFF);   // G
                pixels[i * 4 + 2] = (byte) (rgb[i] & 0xFF);          // B
                pixels[i * 4 + 3] = (byte) ((rgb[i] >> 24) & 0xFF);  // A
            }

            return new RGBAImage(pixels, width, height);
        }
    }

    /**
     * RGBA image data.
     */
    public static class RGBAImage {
        public final byte[] pixels;
        public final int width;
        public final int height;

        RGBAImage(byte[] pixels, int width, int height) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Select the best BC format based on image characteristics.
     */
    public static int selectFormat(RGBAImage image) {
        // Sample alpha channel
        boolean hasTransparency = false;
        int uniqueAlphas = 0;
        boolean[] alphaSeen = new boolean[256];

        for (int i = 3; i < image.pixels.length; i += 4) {
            int a = image.pixels[i] & 0xFF;
            if (a < 255) {
                hasTransparency = true;
            }
            if (!alphaSeen[a]) {
                alphaSeen[a] = true;
                uniqueAlphas++;
            }
        }

        if (!hasTransparency) {
            return DDSHeader.DXGI_BC1_UNORM;  // Opaque — smallest
        } else if (uniqueAlphas > 10) {
            return DDSHeader.DXGI_BC3_UNORM;  // Complex alpha
        } else {
            return DDSHeader.DXGI_BC7_UNORM;  // Simple alpha — highest quality
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
