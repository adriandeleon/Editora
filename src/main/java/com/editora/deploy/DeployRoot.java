package com.editora.deploy;

import java.util.Set;

/**
 * Whether a folder on a host may be a deployment's: a deploy writes into it and may remove files it put there,
 * so a slip of the keyboard must not aim it at the whole machine or the account. Pure.
 */
public final class DeployRoot {

    private DeployRoot() {}

    /** Why {@code root} cannot be a deployment folder. */
    public enum Problem {
        /** Blank. */
        EMPTY,
        /** Not starting with {@code /}: relative to what the host happens to choose. */
        NOT_ABSOLUTE,
        /** A {@code .} or {@code ..} step, or a character a listing cannot carry. */
        NOT_PLAIN,
        /** {@code /}, a system folder, the folder of all homes, or the account's home itself. */
        TOO_WIDE
    }

    /** The system folders that may hold a deployment folder below them but never be one. */
    private static final Set<String> SYSTEM = Set.of(
            "/bin",
            "/boot",
            "/dev",
            "/etc",
            "/home",
            "/lib",
            "/lib32",
            "/lib64",
            "/libx32",
            "/media",
            "/mnt",
            "/opt",
            "/proc",
            "/root",
            "/run",
            "/sbin",
            "/srv",
            "/sys",
            "/tmp",
            "/usr",
            "/usr/local",
            "/var",
            "/var/tmp",
            "/var/www",
            "/Users",
            "/Volumes",
            "/Applications",
            "/Library",
            "/System",
            "/private",
            "/private/tmp",
            "/private/var",
            "/nix",
            "/snap",
            "/data");

    /** {@code root} without a trailing {@code /} (but {@code /} stays {@code /}). */
    public static String normalize(String root) {
        String s = root == null ? "" : root.strip();
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * What is wrong with {@code root} as a deployment folder on a host whose account's home is {@code home}
     * ({@code null} when unknown), or null when nothing is.
     */
    public static Problem problem(String root, String home) {
        String s = normalize(root);
        if (s.isEmpty()) {
            return Problem.EMPTY;
        }
        if (!s.startsWith("/")) {
            return Problem.NOT_ABSOLUTE;
        }
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\0') >= 0) {
            return Problem.NOT_PLAIN;
        }
        for (String segment : s.substring(1).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || (segment.isEmpty() && s.length() > 1)) {
                return Problem.NOT_PLAIN;
            }
        }
        if (s.equals("/") || SYSTEM.contains(s)) {
            return Problem.TOO_WIDE;
        }
        String h = home == null ? "" : normalize(home);
        if (!h.isEmpty() && (s.equals(h) || h.startsWith(s + "/"))) {
            return Problem.TOO_WIDE; // the home itself, or a folder above it
        }
        return null;
    }
}
