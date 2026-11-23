package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.git.GitStatus;
import org.jspecify.annotations.Nullable;

/**
 * Which files a Rollback of tracked changes overwrites, so that Local History can copy them first: each pathspec
 * itself, and — for a folder — every changed, tracked file the status lists under it. Pure; whether each is a
 * regular file now is looked at later, off the FX thread.
 */
final class GitDiscardCaptures {

    private GitDiscardCaptures() {}

    /**
     * The absolute paths under {@code root} that {@code trackedSpecs} (repository-relative pathspecs, files or
     * folders) put back from the index or the last commit, in order and without repeats. Untracked rows of the
     * status are not among them: {@code checkout} and {@code restore} leave those alone.
     */
    static List<Path> overwritten(Path root, @Nullable GitStatus status, List<String> trackedSpecs) {
        Set<Path> out = new LinkedHashSet<>();
        for (String spec : trackedSpecs) {
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            if (!".".equals(spec)) {
                out.add(root.resolve(spec).normalize());
            }
            if (status == null) {
                continue;
            }
            for (GitStatus.FileEntry entry : status.files()) {
                String path = entry.path();
                if (path != null && !entry.untracked() && GitCoordinator.selects(spec, path)) {
                    out.add(root.resolve(path).normalize());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
