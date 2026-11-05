package com.editora.dap;

import org.jspecify.annotations.Nullable;

/**
 * The decisions behind debugging a Java file that has no Maven or Gradle build: a loose file, or a file of a
 * plain-folder project (Java debug review 2, A101 and B102). Pure.
 *
 * <p>jdtls keeps such files in an "invisible project". With autobuild off it builds that project's output once,
 * at import, and never again on its own: a launch that reused whatever class file was there ran old code after
 * every edit, and a file with a compile error ran as {@code java.lang.Error: Unresolved compilation problem}.
 * Every launch of such a file is therefore built first, as a build project is, and a launch whose build could not
 * run compiles the file itself instead of trusting old output.
 *
 * <p>The program runs on the JDK Run uses for the same file — the Settings JDK, else the {@code java} on PATH —
 * and jdtls is given that JDK as its default runtime, so the classes it builds (for hot code replace) and the JDK
 * sources it shows for library frames match the program.
 */
public final class LooseJavaLaunch {

    private LooseJavaLaunch() {}

    /** Where a launch of a file without a build project takes its classes from. */
    public enum Classes {
        /** jdtls's output, which the build before this launch has just brought up to date. */
        JDTLS_OUTPUT,
        /** A fresh {@code javac} of the file into a folder of the session's own. */
        COMPILE_HERE
    }

    /**
     * Pure: the classes to launch. jdtls's output only when its build ran for this very launch and holds the main
     * class; anything else — no build (no server yet, a remote host's), or a class jdtls does not place (a file
     * outside its source folders) — compiles the file here. An existing class file alone is never enough: it may
     * be from any earlier build.
     */
    public static Classes classesFor(boolean builtForThisLaunch, boolean mainClassFilePresent) {
        return builtForThisLaunch && mainClassFilePresent ? Classes.JDTLS_OUTPUT : Classes.COMPILE_HERE;
    }

    /**
     * Pure: the {@code java} a file without a build project runs on — what Run uses for it: the Settings JDK's
     * ({@code selectedJava}, blank for none), else PATH's ({@code pathJava}, null when there is none), else blank
     * (the adapter's own choice).
     */
    public static String launchJava(@Nullable String selectedJava, @Nullable String pathJava) {
        if (selectedJava != null && !selectedJava.isBlank()) {
            return selectedJava.strip();
        }
        // A bare "java" is what PATH resolution leaves when it found none: the adapter's own choice then.
        return pathJava == null || pathJava.isBlank() || !(pathJava.contains("/") || pathJava.contains("\\"))
                ? ""
                : pathJava.strip();
    }
}
