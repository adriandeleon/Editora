package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.editora.config.Project;

/** Resolves a recent local file to the most specific known project that contains it. */
final class RecentProject {
    private RecentProject() {}

    static Optional<Project> containing(Path file, List<Project> projects) {
        if (file == null || projects == null) {
            return Optional.empty();
        }
        Path absoluteFile;
        try {
            absoluteFile = file.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        Project best = null;
        int bestDepth = -1;
        for (Project project : projects) {
            if (project == null || project.root().isBlank()) {
                continue;
            }
            try {
                Path root = Path.of(project.root()).toAbsolutePath().normalize();
                if (absoluteFile.startsWith(root) && root.getNameCount() > bestDepth) {
                    best = project;
                    bestDepth = root.getNameCount();
                }
            } catch (RuntimeException ignored) {
                // A malformed or non-local saved root cannot own a local recent file.
            }
        }
        return Optional.ofNullable(best);
    }
}
