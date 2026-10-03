package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.editora.config.WorkspaceState;

/**
 * Pure decisions about which saved open-file entries survive a session save.
 *
 * <p>The open-file list used to be exactly "the tabs that exist at clean exit", and a saved file that could
 * not be read at startup simply got no tab. Together those meant that launching once while a project's volume
 * was not mounted erased the whole session: every tab was filtered out, and the next close wrote the empty
 * list back.
 */
final class SessionEntries {

    private SessionEntries() {}

    /**
     * Whether a saved entry whose file cannot be read right now stays in the session.
     *
     * <p>It is dropped only when the file is known to be gone: its folder is there and the file is not —
     * someone deleted or renamed it, and keeping the entry would resurrect a tab nobody wants. When the
     * folder itself is missing or cannot be examined (an unmounted volume, a network share that is down, a
     * permission that was revoked) nothing has been learned about the file, so the entry is kept.
     *
     * @param file the resolved path, or {@code null} when the entry could not be resolved at all (a remote
     *     file whose connection is not open) — those are not restorable and are dropped, as before
     */
    static boolean keepUnreadable(Path file, Predicate<Path> isDirectory, Predicate<Path> notExists) {
        if (file == null) {
            return false;
        }
        Path parent = file.toAbsolutePath().getParent();
        boolean knownDeleted = parent != null && isDirectory.test(parent) && notExists.test(file);
        return !knownDeleted;
    }

    /**
     * The list to persist: the live tabs in tab order, then every retained entry that has no tab. An entry
     * the user has since opened by hand is represented by its tab, not twice.
     */
    static List<WorkspaceState.OpenFile> withMissing(
            List<WorkspaceState.OpenFile> open, List<WorkspaceState.OpenFile> missing) {
        if (missing.isEmpty()) {
            return open;
        }
        Set<String> present = new HashSet<>();
        List<WorkspaceState.OpenFile> out = new ArrayList<>(open.size() + missing.size());
        for (WorkspaceState.OpenFile f : open) {
            present.add(f.getPath());
            out.add(f);
        }
        for (WorkspaceState.OpenFile f : missing) {
            if (present.add(f.getPath())) {
                out.add(f);
            }
        }
        return out;
    }
}
