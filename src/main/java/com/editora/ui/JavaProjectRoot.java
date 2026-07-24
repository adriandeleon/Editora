package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.lsp.RootResolver;

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
    static Path find(Path file) {
        if (file == null) {
            return null;
        }
        List<String> markers = new ArrayList<>();
        markers.addAll(BuildTool.MAVEN.markers());
        markers.addAll(BuildTool.GRADLE.markers());
        return RootResolver.findMarkerRoot(file, markers, true);
    }
}
