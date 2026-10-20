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
import org.jspecify.annotations.Nullable;

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
    static @Nullable String className(@Nullable String mainClass) {
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
    static boolean sameFile(@Nullable String reported, Path file) {
        if (reported == null || file == null) {
            return false;
        }
        try {
            return Objects.equals(real(Path.of(reported)), real(file));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** As {@link #sameFile(String, Path)}, for a path {@code reported} by an adapter that names files by {@code paths}. */
    static boolean sameFile(@Nullable String reported, Path file, DapPaths paths) {
        if (paths == DapPaths.LOCAL) {
            return sameFile(reported, file);
        }
        Path named = reported == null ? null : paths.fromAdapter(reported);
        return named != null
                && file != null
                && com.editora.config.PathKeys.samePath(
                        named.toAbsolutePath().normalize(),
                        file.toAbsolutePath().normalize());
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
    static int @Nullable [] typeNamePosition(List<String> lines) {
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
    static int @Nullable [] typeNamePosition(Path file) {
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
    static @Nullable String projectName(Object reply) {
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
    static boolean classFilePresent(List<String> paths, @Nullable String mainClass) {
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
    static String previewSettingsQuery(@Nullable String mainClass, String projectName) {
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

    /**
     * javac's report of a failed compile, as the Debug console shows it: its output with the temporary copy a
     * script was compiled from ({@code compiled}) named as the user's file ({@code original}), so a
     * {@code File.java:3: error:} line opens the file the user edits. Pure.
     */
    static String compilerReport(String out, @Nullable String err, Path compiled, Path original) {
        String text = ((out == null ? "" : out) + "\n" + (err == null ? "" : err)).strip();
        if (compiled != null && original != null && !compiled.equals(original)) {
            text = text.replace(compiled.toString(), original.toString());
        }
        return text;
    }

    /** A {@code package} declaration at the start of a line; group 1 is the name. */
    private static final Pattern PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\s*\\.\\s*[\\p{L}_$][\\p{L}\\p{N}_$]*)*)\\s*;");

    /**
     * The package {@code lines} declare ({@code ""} for none), skipping comments the way
     * {@link #typeNamePosition(List)} does. Pure.
     */
    static String declaredPackage(List<String> lines) {
        boolean inBlockComment = false;
        for (String raw : lines) {
            String t = raw.strip();
            if (inBlockComment) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                inBlockComment = !t.contains("*/");
                continue;
            }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("@")) {
                continue;
            }
            Matcher m = PACKAGE.matcher(raw);
            return m.find() ? m.group(1).replaceAll("\\s+", "") : "";
        }
        return "";
    }

    /** {@link #declaredPackage(List)} for a file on disk (one bounded read); {@code ""} when unreadable. */
    static String declaredPackage(Path file) {
        try (java.util.stream.Stream<String> s = Files.lines(file)) {
            return declaredPackage(s.limit(500).toList());
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * The source root {@code file} belongs under given the package it declares: its folder with one level
     * removed per package segment ({@code src/demo/Main.java} in {@code demo} → {@code src}). The file's own
     * folder for the default package; null when the folders do not spell the package (the file is misplaced,
     * and no root makes it right). Pure.
     */
    static @Nullable Path sourceRoot(Path file, String declaredPackage) {
        Path dir = file == null ? null : file.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return null;
        }
        if (declaredPackage == null || declaredPackage.isBlank()) {
            return dir;
        }
        String[] segments = declaredPackage.split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (dir == null
                    || dir.getFileName() == null
                    || !dir.getFileName().toString().equals(segments[i])) {
                return null;
            }
            dir = dir.getParent();
        }
        return dir;
    }

    /**
     * Which of jdtls's source folders ({@code current}) are wrong for a file whose package puts it under
     * {@code root}: those holding the file below that root, where jdtls would name its class without (part
     * of) its package. A folder above the root is left alone — other files may be in its default package.
     * Pure.
     */
    static List<Path> wrongSourceRoots(List<Path> current, Path file, Path root) {
        if (current == null || file == null || root == null) {
            return List.of();
        }
        Path f = file.toAbsolutePath().normalize();
        Path r = root.toAbsolutePath().normalize();
        return current.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .filter(p -> !p.equals(r) && p.startsWith(r) && f.startsWith(p))
                .toList();
    }

    /**
     * {@code className}'s simple name in {@code declaredPackage}: the class a file declaring that package
     * compiles to, whatever package jdtls's guess put it in. Pure.
     */
    static @Nullable String qualified(@Nullable String className, String declaredPackage) {
        String cls = className(className);
        if (cls == null) {
            return null;
        }
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return declaredPackage == null || declaredPackage.isBlank() ? simple : declaredPackage + "." + simple;
    }

    /** The folders of a {@code java.project.listSourcePaths} reply ({@code {data: [{path, …}], status}}). */
    static List<Path> sourcePaths(Object reply) {
        JsonElement el = toJson(reply);
        if (el == null || !el.isJsonObject() || !el.getAsJsonObject().has("data")) {
            return List.of();
        }
        JsonElement data = el.getAsJsonObject().get("data");
        if (!data.isJsonArray()) {
            return List.of();
        }
        List<Path> out = new java.util.ArrayList<>();
        for (JsonElement entry : data.getAsJsonArray()) {
            if (entry.isJsonObject() && entry.getAsJsonObject().has("path")) {
                try {
                    out.add(Path.of(entry.getAsJsonObject().get("path").getAsString()));
                } catch (RuntimeException ignored) {
                    // not a path of this machine
                }
            }
        }
        return List.copyOf(out);
    }

    /** Whether a jdtls project command answered {@code {status: true, …}}. */
    static boolean statusOk(Object reply) {
        JsonElement el = toJson(reply);
        if (el == null || !el.isJsonObject()) {
            return false;
        }
        JsonElement status = el.getAsJsonObject().get("status");
        return status != null
                        && status.isJsonPrimitive()
                        && status.getAsJsonPrimitive().isBoolean()
                ? status.getAsBoolean()
                : status != null && "true".equals(status.getAsString());
    }

    private static @Nullable JsonElement toJson(Object reply) {
        if (reply == null) {
            return null;
        }
        if (reply instanceof JsonElement el) {
            return el;
        }
        try {
            return new com.google.gson.Gson().toJsonTree(reply);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether a launch's class files are there and current for its main class's source. */
    enum ClassState {
        /** The main class's class file is in a class folder and not older than its source. */
        CURRENT,
        /** The class file is older than the source file: the program would run code the user has changed. */
        STALE,
        /** No class folder on the path holds the class: the launch can only end in ClassNotFoundException. */
        MISSING,
        /** Nothing to judge by (the class may be in a jar, or the source is unknown). */
        UNKNOWN
    }

    /**
     * Judges the class file of {@code mainClass} on {@code paths} against {@code source} (its {@code .java}),
     * by modification time. A path of jars only says nothing (the class may come from one). Bounded file reads.
     */
    static ClassState classState(List<String> paths, @Nullable String mainClass, Path source) {
        String cls = className(mainClass);
        if (cls == null || cls.isBlank() || paths == null) {
            return ClassState.UNKNOWN;
        }
        String relative = cls.replace('.', '/') + ".class";
        boolean anyFolder = false;
        for (String entry : paths) {
            try {
                Path dir = entry == null ? null : Path.of(entry);
                if (dir == null || !Files.isDirectory(dir)) {
                    continue;
                }
                anyFolder = true;
                Path classFile = dir.resolve(relative);
                if (Files.isRegularFile(classFile)) {
                    if (source == null || !Files.isRegularFile(source)) {
                        return ClassState.CURRENT;
                    }
                    return Files.getLastModifiedTime(source).compareTo(Files.getLastModifiedTime(classFile)) > 0
                            ? ClassState.STALE
                            : ClassState.CURRENT;
                }
            } catch (IOException | RuntimeException ignored) {
                // an entry that is not a readable path on this machine says nothing
            }
        }
        // A class compiled from a source file of the project goes to one of its class folders, never a jar.
        boolean fromSource = source != null && Files.isRegularFile(source);
        boolean onlyFolders = paths.stream().allMatch(p -> p != null && !p.endsWith(".jar"));
        return anyFolder && (fromSource || onlyFolders) ? ClassState.MISSING : ClassState.UNKNOWN;
    }

    /**
     * {@code text} without java-debug's three-line notice that jdtls's JVM and the program's differ ("[Warn] The
     * debugger and the debuggee are running in different versions of JVMs…", then both versions): printed at the
     * top of every attach and Debug Test, where the two are different JDKs as a matter of course, and alarming
     * for nothing (review A17). The same object when there is nothing to drop. Pure.
     */
    static String withoutJvmVersionWarning(String text) {
        if (text == null
                || !(text.contains("running in different versions of JVMs")
                        || text.contains("Debugger JVM version:")
                        || text.contains("Debuggee JVM version:"))) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("(?<=\n)")) {
            String t = line.strip();
            boolean notice = t.startsWith("[Warn] The debugger and the debuggee are running in different versions")
                    || t.startsWith("Debugger JVM version:")
                    || t.startsWith("Debuggee JVM version:");
            if (!notice) {
                kept.append(line);
            }
        }
        return kept.toString();
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
    static @Nullable String shortenCommandLine(
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

    private static long joinedLength(@Nullable Object paths) {
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
