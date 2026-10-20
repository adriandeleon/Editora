package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * How Run and Debug start one Kotlin file. A file is one of three shapes:
 *
 * <ul>
 *   <li>a <b>script</b> ({@code .kts}, {@code .main.kts}): {@code kotlinc -script <file> args…}. kotlinc compiles
 *       and runs it in its own JVM, which reads {@code JAVA_OPTS} ({@link JvmScriptRun#jvmOptionsVariable});
 *   <li>a <b>program</b> ({@code .kt} with a top-level {@code fun main}): {@code kotlinc <file> -include-runtime -d
 *       <jar>} into a jar named by the file and its content, then {@code java [options] -jar <jar> args…} on the
 *       Settings JDK. The compile is skipped while the jar for this content is still there;
 *   <li>a <b>build script</b> ({@code build.gradle.kts}, any {@code *.gradle.kts}): not run here, Gradle runs it.
 * </ul>
 *
 * <p>Only {@code kotlinc} is used, never {@code kotlin}: JetBrains' Kotlin toolchain CLI is also called
 * {@code kotlin}, and the two read their arguments differently ({@link #isCompilerVersion} tells them apart).
 *
 * <p>Pure.
 */
public final class KotlinRun {

    private KotlinRun() {}

    /** The tool every Kotlin run goes through. */
    public static final String TOOL = "kotlinc";

    /** How a Kotlin file runs. */
    public enum Shape {
        SCRIPT,
        PROGRAM,
        /** Not run as a file: a Gradle build script, or a file of another language. */
        NONE
    }

    /** The shape of the file called {@code fileName}. */
    public static Shape shape(String fileName) {
        if (fileName == null) {
            return Shape.NONE;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".kts")) {
            return isBuildScript(fileName) ? Shape.NONE : Shape.SCRIPT;
        }
        return lower.endsWith(".kt") ? Shape.PROGRAM : Shape.NONE;
    }

    /** Whether {@code fileName} is a Gradle build script ({@code build.gradle.kts}, {@code settings.gradle.kts}). */
    public static boolean isBuildScript(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".gradle.kts");
    }

    /**
     * A top-level {@code main}: {@code fun main(} at the start of a line, optionally {@code suspend} or
     * {@code public} first. Indented ones are members (an {@code object}'s needs {@code @JvmStatic} and a jar's
     * manifest names the file class), so they do not count.
     */
    private static final Pattern TOP_LEVEL_MAIN =
            Pattern.compile("^(?:public\\s+|internal\\s+)?(?:suspend\\s+)?fun\\s+main\\s*\\(");

    /**
     * The gutter Run line of a Kotlin file, or -1 for none: a script runs from line 0, a program from its
     * top-level {@code fun main}; a build script, and a {@code .kt} without a top-level main, have none.
     */
    public static int runLine(String fileName, String text) {
        return switch (shape(fileName)) {
            case SCRIPT -> 0;
            case PROGRAM -> mainLine(text);
            case NONE -> -1;
        };
    }

    /** The line of {@code text}'s top-level {@code fun main}, or -1. */
    public static int mainLine(String text) {
        if (text == null) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (TOP_LEVEL_MAIN.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    /** {@code kotlinc -script <file> args…}. */
    public static List<String> scriptArgv(String kotlinc, String file, @Nullable List<String> args) {
        List<String> argv = new ArrayList<>(List.of(kotlinc, "-script", file));
        if (args != null) {
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

    /**
     * {@code kotlinc <file> -include-runtime -d <jar>}: the program and the Kotlin standard library in one jar,
     * whose manifest names the file's class ({@code HelloKt}) as its main class. Kotlin keeps line numbers and
     * local variables in class files by default, so the jar debugs as it is.
     */
    public static List<String> compileArgv(String kotlinc, String file, String jar) {
        return List.of(kotlinc, file, "-include-runtime", "-d", jar);
    }

    /** {@code java <jvmOptions…> -jar <jar> args…}. */
    public static List<String> programArgv(String java, List<String> jvmOptions, String jar, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(java);
        if (jvmOptions != null) {
            argv.addAll(jvmOptions);
        }
        argv.add("-jar");
        argv.add(jar);
        if (args != null) {
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

    /**
     * Where the compiled jars are kept: {@code <config>/cache/kotlin-run}, else (no config folder, as in a
     * test) {@code kotlin-run} under {@code tmp}.
     */
    public static Path cacheDir(Path configDir, Path tmp) {
        return configDir != null ? configDir.resolve("cache").resolve("kotlin-run") : tmp.resolve("editora-kotlin-run");
    }

    /**
     * The jar a program compiles to: {@code <name>-<file key>-<content key>.jar}, where the file key names the
     * source file (its path) and the content key what was compiled (the text and the compiler). An unchanged
     * file reuses its jar; {@link #isStaleJar} finds the older jars of the same file to delete.
     */
    public static String jarName(String sourcePath, String content, String kotlinc) {
        String name = Path.of(sourcePath).getFileName().toString();
        String base = name.endsWith(".kt") ? name.substring(0, name.length() - 3) : name;
        return base + "-" + fileKey(sourcePath) + "-" + contentKey(content, kotlinc) + ".jar";
    }

    /**
     * Whether {@code candidate} (a file name in the cache) is an older jar of the same source as
     * {@code current}: same name and file key, other content. The partial file of an interrupted compile counts.
     */
    public static boolean isStaleJar(String current, String candidate) {
        if (current == null || candidate == null || current.equals(candidate)) {
            return false;
        }
        int cut = current.lastIndexOf('-');
        return cut > 0 && candidate.startsWith(current.substring(0, cut + 1));
    }

    /** The name the compile writes before it is complete: renamed to the jar's own name once kotlinc succeeds. */
    public static String partialName(String jarName) {
        return jarName.substring(0, jarName.length() - ".jar".length()) + ".partial.jar";
    }

    private static String fileKey(String sourcePath) {
        return hex(sourcePath).substring(0, 8);
    }

    private static String contentKey(String content, String kotlinc) {
        return hex((kotlinc == null ? "" : kotlinc) + "\u0000" + (content == null ? "" : content))
                .substring(0, 16);
    }

    private static String hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** {@code info: kotlinc-jvm 2.4.21 (JRE 25.0.4+7-LTS)} — {@code kotlinc -version}. */
    private static final Pattern KOTLINC_VERSION =
            Pattern.compile("kotlinc-jvm (\\d+\\.\\d+(?:\\.\\d+)?\\S*)(?:\\s+\\(JRE ([^)\\s]+)[^)]*\\))?");

    /** {@code Kotlin version 2.4.21 (JRE 25.0.4+7-LTS)} — the compiler distribution's {@code kotlin -version}. */
    private static final Pattern KOTLIN_RUNNER_VERSION =
            Pattern.compile("^Kotlin version (\\d+\\.\\d+(?:\\.\\d+)?\\S*)(?:\\s+\\(JRE ([^)\\s]+)[^)]*\\))?");

    /**
     * The compiler's version line, as {@code [kotlin version, JRE version or null]}, or null when
     * {@code output} is not the Kotlin compiler's — JetBrains' Kotlin toolchain CLI, also named {@code kotlin},
     * prints something else.
     */
    public static String @Nullable [] compilerVersion(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String l = line.strip();
            Matcher m = KOTLINC_VERSION.matcher(l);
            if (m.find()) {
                return new String[] {m.group(1), jre(m.group(2))};
            }
            m = KOTLIN_RUNNER_VERSION.matcher(l);
            if (m.find()) {
                return new String[] {m.group(1), jre(m.group(2))};
            }
        }
        return null;
    }

    /** Whether {@code output} (of {@code -version}) is the Kotlin compiler's, not the toolchain CLI's. */
    public static boolean isCompilerVersion(String output) {
        return compilerVersion(output) != null;
    }

    /** {@code 25.0.4+7-LTS} → {@code 25.0.4}: the build suffix says nothing a reader needs. */
    private static @Nullable String jre(String version) {
        if (version == null) {
            return null;
        }
        int plus = version.indexOf('+');
        return plus > 0 ? version.substring(0, plus) : version;
    }
}
