package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static com.editora.i18n.Messages.tr;

/**
 * What deleting untracked rows of the Git panel really removes. Git lists a wholly untracked folder as one
 * row ({@code newdir/}), and {@code git clean -f -- newdir/} deletes every file below it: the confirmation
 * counted that row as one "file". This turns the rows plus the files Git says are under them into the
 * confirmation text and the list of files to copy into Local History first. Pure.
 */
final class GitUntrackedDelete {

    /**
     * How many files one delete copies into Local History. A pre-delete copy is exempt from the history
     * limits for months, so an untracked build or dependency folder must not be able to fill the store.
     */
    static final int MAX_HISTORY_CAPTURES = 200;

    private GitUntrackedDelete() {}

    /**
     * Whether an untracked pathspec names a folder. Git writes a folder's status row with a trailing slash;
     * the Project tree's Revert passes the folder's own path, which has none, so the disk is asked too. A
     * symbolic link to a folder is a single file to {@code git clean}.
     */
    static boolean isFolder(Path root, String pathspec) {
        if (pathspec == null) {
            return false;
        }
        if (pathspec.endsWith("/")) {
            return true;
        }
        try {
            return root != null && Files.isDirectory(root.resolve(pathspec), LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    /** How many of {@code pathspecs} are folders ({@link #isFolder}). */
    static int folders(Path root, List<String> pathspecs) {
        return (int) pathspecs.stream().filter(spec -> isFolder(root, spec)).count();
    }

    /**
     * The confirmation for deleting {@code untracked} rows of which {@code folders} (at least one) are
     * folders, together with discarding {@code tracked} tracked files (0 for none). {@code files} are the
     * files Git lists under the rows; {@code historyOn} says whether Local History will keep copies, so the
     * text can say when it will not keep all of them.
     */
    static String prompt(int tracked, List<String> untracked, int folders, List<String> files, boolean historyOn) {
        String text;
        if (tracked > 0) {
            text = tr("dialog.discard.mixedWithFolders", tracked, files.size(), folders);
        } else if (untracked.size() == 1) {
            text = tr("dialog.discard.untrackedFolder", untracked.get(0), files.size());
        } else {
            text = tr("dialog.discard.untrackedWithFolders", files.size(), folders);
        }
        if (historyOn && files.size() > MAX_HISTORY_CAPTURES) {
            text += "\n\n" + tr("dialog.discard.historyLimit", MAX_HISTORY_CAPTURES);
        }
        return text;
    }

    /** The files to copy into Local History before the delete: the first {@link #MAX_HISTORY_CAPTURES}. */
    static List<Path> captures(Path root, List<String> files) {
        return files.stream().limit(MAX_HISTORY_CAPTURES).map(root::resolve).toList();
    }
}
