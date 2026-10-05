package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The decisions behind a Java debug launch that need no session: reading what jdtls / java-debug answered
 * and shaping what is sent back. Pure apart from the bounded file reads noted on each method (unit-tested).
 */
final class JavaLaunchSupport {

    private JavaLaunchSupport() {}

    /** The JDT option that says a project compiles with {@code --enable-preview}. */
    static final String PREVIEW_OPTION = "org.eclipse.jdt.core.compiler.problem.enablePreviewFeatures";

    private static final String ENABLE_PREVIEW = "--enable-preview";

    /**
     * The class part of a main class as jdtls names it. For a class in a named module
     * {@code vscode.java.resolveMainClass} answers {@code <module>/<class>} — the form the launch itself
     * needs — while everything the user typed or clicked (a saved configuration, the gutter) carries the
     * plain fully-qualified name.
     */
    static String className(String mainClass) {
        if (mainClass == null) {
            return null;
        }
        return mainClass.substring(mainClass.indexOf('/') + 1);
    }

    /**
     * Whether a path reported by jdtls and a buffer's path name the same file. jdtls reports the real
     * (symlink-resolved) path, so a project opened through a symlink is compared by real path; a file that
     * cannot be resolved (it does not exist) falls back to the normalized absolute path.
     */
    static boolean sameFile(String reported, Path file) {
        if (reported == null || file == null) {
            return false;
        }
        try {
            return Objects.equals(real(Path.of(reported)), real(file));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** A top-level type declaration; group 1 is the type's name. */
    private static final Pattern TYPE_NAME =
            Pattern.compile("\\b(?:class|record|enum|interface)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)");

    /**
     * The zero-based {@code {line, column}} of the first declared type's name in {@code lines}, or null when
     * there is none (a compact source). The position is what {@code vscode.java.resolveElementAtSelection}
     * needs to name the project a file belongs to.
     */
    static int[] typeNamePosition(List<String> lines) {
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String t = raw.strip();
            if (inBlockComment) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("import ") || t.startsWith("package ")) {
                continue;
            }
            Matcher m = TYPE_NAME.matcher(raw);
            if (m.find()) {
                return new int[] {i, m.start(1)};
            }
        }
        return null;
    }

    /** {@link #typeNamePosition(List)} for a file on disk (one bounded read); null when unreadable. */
    static int[] typeNamePosition(Path file) {
        try (java.util.stream.Stream<String> s = Files.lines(file)) {
            return typeNamePosition(s.limit(500).toList());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The {@code projectName} in a java-debug reply: {@code vscode.java.resolveElementAtSelection}'s single
     * {@code {declaringType, projectName, hasMainMethod}}, or the first entry of {@code
     * vscode.java.resolveMainMethod}'s {@code [{range, mainClass, projectName}, …]}. Null when the reply
     * carries none. Reads plain collections as well as gson elements, like the other reply parsers.
     */
    static String projectName(Object reply) {
        Object o = reply;
        if (o instanceof List<?> list) {
            o = list.isEmpty() ? null : list.get(0);
        } else if (o instanceof JsonElement el && el.isJsonArray()) {
            o = el.getAsJsonArray().isEmpty() ? null : el.getAsJsonArray().get(0);
        }
        String name = null;
        if (o instanceof Map<?, ?> m) {
            name = m.get("projectName") instanceof String s ? s : null;
        } else if (o instanceof JsonObject obj) {
            JsonElement e = obj.get("projectName");
            name = e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        }
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * Whether {@code mainClass}'s class file exists in one of the directories on {@code paths}. jdtls gives
     * a file with no build project an "invisible project" whose output folder it never fills (Editora runs
     * it with autobuild off), so launching on that classpath can only end in {@code ClassNotFoundException}.
     * Jars are not opened: a source file being debugged is not compiled into one.
     */
    static boolean classFilePresent(List<String> paths, String mainClass) {
        String cls = className(mainClass);
        if (cls == null || cls.isBlank() || paths == null) {
            return false;
        }
        String relative = cls.replace('.', '/') + ".class";
        for (String entry : paths) {
            try {
                if (entry != null && Files.isRegularFile(Path.of(entry).resolve(relative))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // an entry that is not a path on this machine holds no class file
            }
        }
        return false;
    }

    /**
     * The argument of {@code vscode.java.checkProjectSettings} asking whether the project compiles with
     * preview features. java-debug takes it as one JSON <em>string</em> (it casts the argument to
     * {@code String} before parsing), not as an object.
     */
    static String previewSettingsQuery(String mainClass, String projectName) {
        JsonObject expected = new JsonObject();
        expected.addProperty(PREVIEW_OPTION, "enabled");
        JsonObject q = new JsonObject();
        q.addProperty("className", className(mainClass));
        q.addProperty("projectName", projectName == null ? "" : projectName);
        q.addProperty("inheritedOptions", true);
        q.add("expectedOptions", expected);
        return q.toString();
    }

    /** A boolean reply ({@code Boolean}, gson primitive or its text); anything else is false. */
    static boolean isTrue(Object reply) {
        if (reply instanceof Boolean b) {
            return b;
        }
        if (reply instanceof JsonElement el) {
            return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
        }
        return reply != null && "true".equals(String.valueOf(reply));
    }

    /**
     * {@code vmArgs} with {@code --enable-preview} added when the project needs it and the user's own
     * arguments do not already carry it. A class compiled with preview features does not load without it.
     */
    static String withPreview(String vmArgs, boolean enablePreview) {
        String args = vmArgs == null ? "" : vmArgs;
        if (!enablePreview || List.of(args.strip().split("\\s+")).contains(ENABLE_PREVIEW)) {
            return args;
        }
        return args.isBlank() ? ENABLE_PREVIEW : ENABLE_PREVIEW + " " + args;
    }

    /** Windows refuses a command line longer than 32,767 characters ({@code CreateProcess}, error 206). */
    static final int WINDOWS_COMMAND_LIMIT = 32_767;

    /** Linux refuses a single argument longer than 128 KiB ({@code MAX_ARG_STRLEN}); macOS allows more. */
    static final int POSIX_ARGUMENT_LIMIT = 131_072;

    /** Room left for what java-debug adds itself (the JDWP agent option, encoding flags, quoting). */
    private static final int HEADROOM = 2_048;

    /**
     * The {@code shortenCommandLine} value a launch needs, or null when the plain command line fits (the
     * usual case — nothing is sent and java-debug keeps its default). java-debug puts the whole class path
     * in one {@code -cp} argument; a project with a few hundred dependency jars overruns the limit above and
     * the debuggee never starts. {@code argfile} moves the paths into a {@code @file}, which needs a JDK 9+
     * launcher; an older one gets {@code jarmanifest}.
     */
    static String shortenCommandLine(
            Map<String, Object> launch, boolean windows, java.util.function.Predicate<String> supportsArgFiles) {
        long classPath = joinedLength(launch.get("classPaths"));
        long modulePath = joinedLength(launch.get("modulePaths"));
        boolean tooLong;
        if (windows) {
            long total = classPath + modulePath + HEADROOM;
            for (String key : List.of("javaExec", "vmArgs", "mainClass", "args")) {
                total += launch.get(key) instanceof String s ? s.length() + 1 : 0;
            }
            tooLong = total > WINDOWS_COMMAND_LIMIT;
        } else {
            tooLong = Math.max(classPath, modulePath) + HEADROOM > POSIX_ARGUMENT_LIMIT;
        }
        if (!tooLong) {
            return null;
        }
        return supportsArgFiles.test(launch.get("javaExec") instanceof String s ? s : null) ? "argfile" : "jarmanifest";
    }

    private static long joinedLength(Object paths) {
        long n = 0;
        if (paths instanceof List<?> list) {
            for (Object p : list) {
                n += String.valueOf(p).length() + 1;
            }
        }
        return n;
    }

    /**
     * Whether the JDK that owns {@code javaExec} understands {@code @argfiles} (JDK 9+), read from the
     * {@code release} file beside its {@code bin} folder. Unknown — a blank executable (java-debug then uses
     * the project's own runtime), no release file, an unreadable version — counts as modern: JDK 8 is the
     * exception that has to be recognized, not the default.
     */
    static boolean supportsArgFiles(String javaExec) {
        if (javaExec == null || javaExec.isBlank()) {
            return true;
        }
        try {
            Path bin = Path.of(javaExec).toAbsolutePath().getParent();
            Path release = bin == null || bin.getParent() == null
                    ? null
                    : bin.getParent().resolve("release");
            if (release == null || !Files.isRegularFile(release)) {
                return true;
            }
            for (String line : Files.readAllLines(release)) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return !line.substring("JAVA_VERSION=".length())
                            .replace("\"", "")
                            .strip()
                            .startsWith("1.");
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable: treated as modern, see above
        }
        return true;
    }
}
