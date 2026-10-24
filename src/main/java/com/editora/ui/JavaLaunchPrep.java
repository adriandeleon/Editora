package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.editor.lsp.LspDiagnostic;
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

    /**
     * A build's compile errors in {@code project} as Debug console lines, {@code path:line: error: message} —
     * javac's form, which the console turns into links — sorted by file, at most {@code limit} of them
     * ({@link #errorCount} says how many there are). Errors outside the project are not this launch's.
     */
    static List<String> errorLines(Map<Path, List<LspDiagnostic>> errors, @Nullable Path project, int limit) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Path, List<LspDiagnostic>> e : new java.util.TreeMap<>(errors).entrySet()) {
            if (project != null && !inside(e.getKey(), project)) {
                continue;
            }
            for (LspDiagnostic d : e.getValue()) {
                if (d.severity() != LspDiagnostic.Severity.ERROR) {
                    continue;
                }
                if (out.size() < limit) {
                    out.add(e.getKey() + ":" + (d.startLine() + 1) + ": error: " + d.message());
                }
            }
        }
        return out;
    }

    /** How many error diagnostics of {@code errors} are in {@code project}. */
    static int errorCount(Map<Path, List<LspDiagnostic>> errors, @Nullable Path project) {
        int n = 0;
        for (Map.Entry<Path, List<LspDiagnostic>> e : errors.entrySet()) {
            if (project == null || inside(e.getKey(), project)) {
                n += (int) e.getValue().stream()
                        .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                        .count();
            }
        }
        return n;
    }
}
