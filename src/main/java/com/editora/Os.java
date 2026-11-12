package com.editora;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Which operating system Editora runs on: the one class that reads the {@code os.name} property. Every other
 * class asks here, so the places where Editora differs by OS are the callers of these methods, and a test can
 * run as any of the three through {@link #override} (or the {@value #PROPERTY} system property for a whole run).
 *
 * <p>Ask at the moment the answer is needed. A class that keeps it in a {@code static final} holds the OS it
 * was loaded under, which is right for a running app and wrong for a test that changes the override afterwards.
 */
public final class Os {

    /** The three operating systems Editora is built for. Anything else Unix-like is treated as Linux. */
    public enum Kind {
        WINDOWS,
        MAC,
        LINUX
    }

    /** A system property that runs Editora as another OS: {@code windows}, {@code mac} or {@code linux}. */
    public static final String PROPERTY = "editora.os";

    private static volatile @Nullable Kind override;

    private Os() {}

    /** The OS this run behaves as: the override, else the {@value #PROPERTY} property, else the one it runs on. */
    public static Kind current() {
        Kind forced = override;
        if (forced != null) {
            return forced;
        }
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return parse(property);
        }
        return parse(name());
    }

    /**
     * The kind an OS name stands for: {@code Mac OS X}, {@code Darwin} and {@code mac} are {@link Kind#MAC},
     * {@code Windows 11} and {@code windows} are {@link Kind#WINDOWS}, and the rest is {@link Kind#LINUX}. Pure.
     */
    public static Kind parse(@Nullable String name) {
        String os = name == null ? "" : name.toLowerCase(Locale.ROOT).strip();
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return Kind.MAC;
        }
        if (os.startsWith("win")) {
            return Kind.WINDOWS;
        }
        return Kind.LINUX;
    }

    public static boolean isWindows() {
        return current() == Kind.WINDOWS;
    }

    public static boolean isMac() {
        return current() == Kind.MAC;
    }

    public static boolean isLinux() {
        return current() == Kind.LINUX;
    }

    /** The OS's own name as the JVM reports it ({@code Linux}, {@code Mac OS X}, {@code Windows 11}): for display. */
    public static String name() {
        return System.getProperty("os.name", "");
    }

    /** The OS's version as the JVM reports it: for display. */
    public static String version() {
        return System.getProperty("os.version", "");
    }

    /** The processor architecture as the JVM reports it ({@code amd64}, {@code aarch64}). */
    public static String arch() {
        return System.getProperty("os.arch", "");
    }

    /** Name, version and architecture on one line, for About, Doctor and a problem report. */
    public static String description() {
        return name() + " " + version() + " (" + arch() + ")";
    }

    /** For tests: behave as {@code kind} until reset with {@code null}. */
    public static void override(@Nullable Kind kind) {
        override = kind;
    }
}
