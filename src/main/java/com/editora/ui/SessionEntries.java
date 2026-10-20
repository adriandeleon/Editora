package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.editora.config.WorkspaceState;
import org.jspecify.annotations.Nullable;

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
     * Whether an entry the startup scan could not open is kept for later. A local one always is (its volume
     * may be back next time). A remote one that did not even resolve, because its host is not connected, is
     * kept only by the window of a project on that same host: that window is going to connect, and its tabs
     * are waiting for it. In any other window the entry would wait for a connection nobody is going to
     * make, invisibly and forever, so it is dropped, as it always was.
     *
     * @param stored the entry's stored path
     * @param resolves whether {@code stored} resolves to a path right now
     * @param projectRoot the window's project root as stored, or null
     */
    static boolean retainUnreachable(String stored, boolean resolves, @Nullable String projectRoot) {
        if (resolves || !com.editora.vfs.Vfs.isRemoteUri(stored)) {
            return true;
        }
        com.editora.vfs.SftpUri entry = com.editora.vfs.SftpUri.parse(stored);
        com.editora.vfs.SftpUri root = projectRoot == null ? null : com.editora.vfs.SftpUri.parse(projectRoot);
        return entry != null && root != null && entry.authority().equals(root.authority());
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
