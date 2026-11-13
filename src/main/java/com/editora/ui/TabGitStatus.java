package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.collections.ObservableList;
import javafx.scene.control.Tab;

import com.editora.git.GitFileStatus;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Tints each file tab's title by the Git status of its file — modified, added, untracked, renamed,
 * conflicted — with the style classes the Project tree and the Commit window use, so a file reads the same
 * in all three places, and names the status in the tab's tooltip, so the color is not its only sign.
 *
 * <p>The class goes on the {@link Tab}, not on its header node: the header is rebuilt whenever the title
 * changes (dirty, pinned, renamed) and the tab's own classes survive that. Applied on every status refresh
 * with whatever map Git produced; an empty map (Git off, Simple UI mode, not a repository) clears every tab.
 * A tab whose status did not change is not touched, so a refresh restyles nothing.
 */
final class TabGitStatus {

    private TabGitStatus() {}

    /** The style class for {@code file}'s status, or {@code null} for a clean, ignored or pathless file. Pure. */
    static @Nullable String classFor(@Nullable Path file, Map<Path, GitFileStatus> byPath) {
        GitFileStatus status = statusFor(file, byPath);
        return status == null ? null : status.cssClass();
    }

    /** {@code file}'s status, or {@code null} for a clean, ignored or pathless file. Pure. */
    static @Nullable GitFileStatus statusFor(@Nullable Path file, Map<Path, GitFileStatus> byPath) {
        if (file == null || byPath == null || byPath.isEmpty()) {
            return null;
        }
        if (file.getFileSystem() != byPath.keySet().iterator().next().getFileSystem()) {
            return null; // a file on another filesystem than the repository's is never in it (and cannot be asked)
        }
        return byPath.get(file.toAbsolutePath().normalize());
    }

    /** The status in words for a tab's tooltip, "Git: modified". */
    static String words(GitFileStatus status) {
        return tr("tab.tooltip.gitStatus", tr("git.fileStatus." + status.name().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * Gives every tab that shows a file (an editor's, a viewer's, a restored one not built yet) its status's
     * class and, in its tooltip, the status in words.
     */
    static void apply(List<Tab> tabs, Map<Path, GitFileStatus> byPath) {
        for (Tab tab : tabs) {
            GitFileStatus status = statusFor(MainController.tabPath(tab), byPath);
            String wanted = status == null ? null : status.cssClass();
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
            TabHeaders.gitStatus(tab, status == null ? null : words(status));
        }
    }
}
