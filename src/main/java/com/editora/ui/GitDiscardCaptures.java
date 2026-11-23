package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
        // The specs as a set, each status row matched by looking its path and its folders up in it: one pass over
        // the status, not one per selected row (Ctrl+A on a long list made that quadratic, on the FX thread).
        Set<String> specs = new HashSet<>();
        boolean all = false;
        for (String spec : trackedSpecs) {
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            if (".".equals(spec)) {
                all = true;
                continue;
            }
            out.add(root.resolve(spec).normalize());
            specs.add(spec.endsWith("/") ? spec.substring(0, spec.length() - 1) : spec);
        }
        if (status == null || (!all && specs.isEmpty())) {
            return new ArrayList<>(out);
        }
        for (GitStatus.FileEntry entry : status.files()) {
            String path = entry.path();
            if (path != null && !entry.untracked() && (all || selected(specs, path))) {
                out.add(root.resolve(path).normalize());
            }
        }
        return new ArrayList<>(out);
    }

    /** Whether {@code path} or a folder it is in is one of {@code specs}: {@link GitCoordinator#selects} for a set. */
    static boolean selected(Set<String> specs, String path) {
        for (String p = path; ; ) {
            if (specs.contains(p)) {
                return true;
            }
            int slash = p.lastIndexOf('/');
            if (slash <= 0) {
                return false;
            }
            p = p.substring(0, slash);
        }
    }
}
