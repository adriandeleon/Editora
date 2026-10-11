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
     * Whether a saved entry whose file cannot be read right now stays in the session. The rule lives with
     * the scan that applies it, {@link com.editora.config.SessionScan#keepUnreadable}; kept here for its
     * callers and tests.
     */
    static boolean keepUnreadable(Path file, Predicate<Path> isDirectory, Predicate<Path> notExists) {
        return com.editora.config.SessionScan.keepUnreadable(file, isDirectory, notExists);
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
