package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.lsp.RootResolver;
import org.jspecify.annotations.Nullable;

/**
 * Finds the Maven/Gradle project root that owns a file — the working directory (and gate) for running or
 * debugging a project main class. Walks up from the file to the nearest directory holding a Maven or Gradle
 * build marker ({@code pom.xml} / {@code build.gradle[.kts]} / {@code settings.gradle[.kts]}), matched as a
 * regular <i>file</i> (a directory merely named like a marker doesn't count). Returns {@code null} when the
 * file is outside any such project.
 */
final class JavaProjectRoot {

    private JavaProjectRoot() {}

    /** The nearest Maven/Gradle project root above {@code file}, or {@code null} if there is none. */
    static @Nullable Path find(Path file) {
        if (file == null) {
            return null;
        }
        return FOUND.get(file);
    }

    /** Looks for {@code file}'s root now and keeps it (off the FX thread), so its FX askers answer from memory. */
    static void warm(Path file) {
        FOUND.lookAgain(file);
    }

    /** Starts a look for {@code file}'s root on a thread of its own when nothing is kept, so a later ask finds it. */
    static void warmLater(Path file) {
        FOUND.warm(file);
    }

    /**
     * For a menu row: whether {@code file} is in a project, or not known yet. A file no look has filled counts as
     * in one (the row stays offered; the command waits for the look) and is looked at behind (PERF-01, Pauta 6.0.1).
     */
    static boolean inProjectOrUnknown(Path file) {
        if (file == null) {
            return false;
        }
        if (!com.editora.perf.Looked.allKnown(() -> FOUND.get(file))) {
            FOUND.warm(file);
            return true;
        }
        return FOUND.get(file) != null;
    }

    /** The walk up the folders: off the FX thread by a launch's plan, answered from memory on it (PERF-01). */
    private static final com.editora.perf.Looked<Path, Path> FOUND =
            new com.editora.perf.Looked<>("JavaProjectRoot.find", file -> {
                List<String> markers = new ArrayList<>();
                markers.addAll(BuildTool.MAVEN.markers());
                markers.addAll(BuildTool.GRADLE.markers());
                return RootResolver.findMarkerRoot(file, markers, true);
            });
}
