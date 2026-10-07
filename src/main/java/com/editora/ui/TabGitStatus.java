package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.collections.ObservableList;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitFileStatus;

/**
 * Tints each editor tab's title by the Git status of its file — modified, added, untracked, renamed,
 * conflicted — with the style classes the Project tree and the Commit window use, so a file reads the same
 * in all three places.
 *
 * <p>The class goes on the {@link Tab}, not on its header node: the header is rebuilt whenever the title
 * changes (dirty, pinned, renamed) and the tab's own classes survive that. Applied on every status refresh
 * with whatever map Git produced; an empty map (Git off, Simple UI mode, not a repository) clears every tab.
 * A tab whose status did not change is not touched, so a refresh restyles nothing.
 */
final class TabGitStatus {

    private TabGitStatus() {}

    /** The style class for {@code file}'s status, or {@code null} for a clean, ignored or pathless file. Pure. */
    static String classFor(Path file, Map<Path, GitFileStatus> byPath) {
        if (file == null || byPath == null || byPath.isEmpty()) {
            return null;
        }
        if (file.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            return null; // a remote file is never in a local repository
        }
        GitFileStatus status = byPath.get(file.toAbsolutePath().normalize());
        return status == null ? null : status.cssClass();
    }

    static void apply(List<Tab> tabs, Map<Path, GitFileStatus> byPath) {
        for (Tab tab : tabs) {
            String wanted = tab.getUserData() instanceof EditorBuffer b ? classFor(b.getPath(), byPath) : null;
            ObservableList<String> classes = tab.getStyleClass();
            String current = null;
            for (String c : classes) {
                if (c.startsWith("git-status-")) {
                    current = c;
                    break;
                }
            }
            if (java.util.Objects.equals(current, wanted)) {
                continue;
            }
            if (current != null) {
                classes.remove(current);
            }
            if (wanted != null) {
                classes.add(wanted);
            }
        }
    }
}
