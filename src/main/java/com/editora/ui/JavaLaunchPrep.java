package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * The pure decisions a Java debug launch makes before it starts (review A2, A16, B3): which folder a program
 * outside any build project belongs to, which unsaved files have to be written first, and how a build's
 * compile errors are printed in the Debug console. Pure.
 */
final class JavaLaunchPrep {

    private JavaLaunchPrep() {}

    /** Whether {@code file} is in the folder tree of {@code root} (normalized; null-safe). */
    static boolean inside(Path file, @Nullable Path root) {
        if (file == null || root == null || file.getFileSystem() != root.getFileSystem()) {
            return false;
        }
        return file.toAbsolutePath()
                .normalize()
                .startsWith(root.toAbsolutePath().normalize());
    }

    /**
     * The folder a Java file belongs to for a launch: its Maven/Gradle project ({@code buildRoot}), else the
     * window's project when the file is in it (a plain-folder project, review A16), else none.
     */
    static @Nullable Path projectOf(Path file, @Nullable Path buildRoot, @Nullable Path windowProject) {
        if (buildRoot != null) {
            return buildRoot;
        }
        return windowProject != null && inside(file, windowProject)
                ? windowProject.toAbsolutePath().normalize()
                : null;
    }

    /**
     * Whether an unsaved buffer at {@code path} (of {@code language}) must be saved before a Java launch from
     * {@code launched}: every Java file of the same project — the launch compiles them all, and a breakpoint
     * in an unsaved one would sit on lines the program does not have (review B3). Without a project, the
     * launched file's own folder tree. Other file types are left alone.
     */
    static boolean saveBeforeLaunch(@Nullable Path path, String language, Path launched, @Nullable Path project) {
        if (path == null || !"java".equals(language)) {
            return false;
        }
        if (path.equals(launched)) {
            return true;
        }
        Path scope = project != null ? project : launched == null ? null : launched.getParent();
        return inside(path, scope);
    }

    // --- review 3, A201/A203/choice 10: what a launch does after jdtls's build ---------------------------------

    /** What a Java launch does once jdtls's build before it has finished. */
    enum AfterBuild {
        /** Launch jdtls's output: its build brought it up to date. */
        LAUNCH_BUILT,
        /** Compile the launched file with javac from its declared source root, and launch that (or say why not). */
        COMPILE_WITH_JAVAC,
        /** List the build's compile errors; nothing is launched. */
        REFUSE
    }

    /**
     * What a launch does after jdtls's build ended with {@code outcome}. A Maven or Gradle project ({@code loose}
     * false) is refused on compile errors, as its build tool would refuse it, and otherwise launched (a build that
     * could not run leaves the launch to check its class files). A file without a build tool — a loose file or a
     * plain-folder project — runs jdtls's output only when that build succeeded; on errors, or with no build, javac
     * compiles it from the source root its {@code package} line declares, and only javac's own errors stop it.
     * jdtls guesses a plain folder's source roots and can nest one inside another, so a two-package folder that
     * javac compiles was refused for "does not match the expected package" (review 3, A201). javac also compiles
     * only what the launched file reaches, so a broken unrelated file, or a JUnit test without JUnit on the class
     * path, no longer stops a {@code main} (choice 10), and jdtls's "Unresolved compilation problem" classes never
     * run (A203). Pure.
     */
    static AfterBuild afterBuild(com.editora.lsp.LspManager.BuildOutcome outcome, boolean loose) {
        if (outcome == com.editora.lsp.LspManager.BuildOutcome.SUCCEEDED) {
            return AfterBuild.LAUNCH_BUILT;
        }
        if (loose) {
            return AfterBuild.COMPILE_WITH_JAVAC;
        }
        return outcome == com.editora.lsp.LspManager.BuildOutcome.WITH_ERRORS
                ? AfterBuild.REFUSE
                : AfterBuild.LAUNCH_BUILT;
    }

    // --- review 2, A103/A104: the build errors a launch shows -------------------------------------------------

    /** Whether {@code file} is a Java source file: what a compile error of a Java build is reported against. */
    static boolean javaSource(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && name.toString().endsWith(".java");
    }

    /**
     * The files whose compile errors stop a launch from {@code launched}: those of its {@code project} (a Maven
     * or Gradle project, or the window's plain-folder project), as for every build project; with neither — a
     * loose file in a window without a project — only those beside it in its own folder (its package). jdtls
     * builds the whole folder a loose file was opened from, subfolders and all, and an unrelated broken file
     * there must not stop it (review 2, A101).
     */
    static java.util.function.Predicate<Path> errorScope(Path launched, @Nullable Path project) {
        if (project != null) {
            return file -> inside(file, project);
        }
        Path folder =
                launched == null ? null : launched.toAbsolutePath().normalize().getParent();
        return file -> folder != null
                && file != null
                && folder.equals(file.toAbsolutePath().normalize().getParent());
    }

    /**
     * A build's compile errors in Java files {@code inScope} ({@link #errorScope}), as Debug console lines
     * {@code path:line: error: message} (javac's form, which the console makes links of), at most {@code limit}.
     * The path is relative to {@code shownFrom} (the window's project, where the console's links resolve) when
     * the file is inside it, else absolute: an absolute path filled the console's width, and the message after it
     * was off screen (review 2, A104). Problems of other files — another language's, a build file's — are not this
     * launch's compile errors.
     */
    static List<String> javaErrorLines(
            Map<Path, List<LspDiagnostic>> errors,
            java.util.function.Predicate<Path> inScope,
            @Nullable Path shownFrom,
            int limit) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, List<LspDiagnostic>> e : new java.util.TreeMap<>(errors).entrySet()) {
            Path file = e.getKey();
            if (!javaSource(file) || !inScope.test(file)) {
                continue;
            }
            String shown = shownFrom != null && inside(file, shownFrom)
                    ? shownFrom
                            .toAbsolutePath()
                            .normalize()
                            .relativize(file.toAbsolutePath().normalize())
                            .toString()
                    : file.toString();
            for (LspDiagnostic d : e.getValue()) {
                if (d.severity() == LspDiagnostic.Severity.ERROR && out.size() < limit) {
                    out.add(shown + ":" + (d.startLine() + 1) + ": error: " + d.message());
                }
            }
        }
        return out;
    }

    /** How many compile errors {@link #javaErrorLines} would list without a limit. */
    static int javaErrorCount(Map<Path, List<LspDiagnostic>> errors, java.util.function.Predicate<Path> inScope) {
        int n = 0;
        for (Map.Entry<Path, List<LspDiagnostic>> e : errors.entrySet()) {
            if (javaSource(e.getKey()) && inScope.test(e.getKey())) {
                n += (int) e.getValue().stream()
                        .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                        .count();
            }
        }
        return n;
    }

    /**
     * The errors jdtls reported against the project folder or its build file rather than a source file — a failed
     * Maven or Gradle import ("Can't use Java 27 and Gradle 9.7.1 to import Gradle project …"), each message once,
     * in order. They used to be listed and counted as compile errors (review 2, A103).
     */
    static List<String> importErrors(Map<Path, List<LspDiagnostic>> errors, @Nullable Path project) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Map.Entry<Path, List<LspDiagnostic>> e : new java.util.TreeMap<>(errors).entrySet()) {
            Path file = e.getKey();
            boolean ours = project == null
                    ? file != null && javaBuildFile(file)
                    : inside(file, project) && (javaBuildFile(file) || isFolder(file, project));
            if (javaSource(file) || !ours) {
                continue;
            }
            for (LspDiagnostic d : e.getValue()) {
                if (d.severity() == LspDiagnostic.Severity.ERROR
                        && d.message() != null
                        && !d.message().isBlank()) {
                    out.add(d.message().strip());
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean javaBuildFile(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString();
        return n.equals("pom.xml") || n.startsWith("build.gradle") || n.startsWith("settings.gradle");
    }

    /** {@code file} is the project folder itself (jdtls reports an import failure there). */
    private static boolean isFolder(Path file, Path project) {
        return file.toAbsolutePath().normalize().equals(project.toAbsolutePath().normalize());
    }

    /**
     * The project name shown for a main class in the picker: jdtls's own name, without the hash it gives the
     * "invisible project" of a folder with no build file ({@code project_b3763c83} → {@code project}), which said
     * nothing to the user (review 2, A107). Pure.
     */
    static String projectLabel(@Nullable String jdtlsName) {
        if (jdtlsName == null) {
            return "";
        }
        return INVISIBLE_PROJECT.matcher(jdtlsName).matches()
                ? jdtlsName.substring(0, jdtlsName.length() - 9)
                : jdtlsName;
    }

    /** jdtls's name for an invisible project: the folder's name, {@code _}, eight hex digits of a hash. */
    private static final java.util.regex.Pattern INVISIBLE_PROJECT = java.util.regex.Pattern.compile(".+_[0-9a-f]{8}");
}
