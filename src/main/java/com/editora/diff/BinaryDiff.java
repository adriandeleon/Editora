package com.editora.diff;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded binary sniffing and a stable, human-readable comparison surrogate. */
public final class BinaryDiff {

    private BinaryDiff() {}

    public static boolean isProbablyBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || hasUnicodeBom(bytes)) {
            return false;
        }
        int limit = Math.min(bytes.length, 8 * 1024);
        int controls = 0;
        for (int i = 0; i < limit; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) {
                return true;
            }
            if (value < 0x09 || (value > 0x0d && value < 0x20)) {
                controls++;
            }
        }
        return controls > limit / 20;
    }

    public static String describe(byte[] bytes) {
        byte[] safe = bytes == null ? new byte[0] : bytes;
        return "⟦Binary " + formatSize(safe.length) + " · " + format(safe) + " · SHA-256 " + digest(safe) + "⟧";
    }

    private static boolean hasUnicodeBom(byte[] b) {
        return b.length >= 2
                        && ((b[0] == (byte) 0xff && b[1] == (byte) 0xfe)
                                || (b[0] == (byte) 0xfe && b[1] == (byte) 0xff))
                || b.length >= 3 && b[0] == (byte) 0xef && b[1] == (byte) 0xbb && b[2] == (byte) 0xbf;
    }

    private static String format(byte[] b) {
        if (starts(b, 0x89, 0x50, 0x4e, 0x47)) return "PNG image";
        if (starts(b, 0xff, 0xd8, 0xff)) return "JPEG image";
        if (starts(b, 0x47, 0x49, 0x46, 0x38)) return "GIF image";
        if (starts(b, 0x25, 0x50, 0x44, 0x46)) return "PDF";
        if (starts(b, 0x50, 0x4b, 0x03, 0x04)) return "ZIP archive";
        return "binary data";
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xff) != prefix[i]) return false;
        }
        return true;
    }

    private static String formatSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            return "unknown";
        }
    }
}
