package com.editora.ui;

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

    /** Whether an untracked status row names a folder: Git writes those with a trailing slash. */
    static boolean isFolder(String pathspec) {
        return pathspec != null && pathspec.endsWith("/");
    }

    static boolean anyFolder(List<String> pathspecs) {
        return pathspecs.stream().anyMatch(GitUntrackedDelete::isFolder);
    }

    /**
     * The confirmation for deleting {@code untracked} rows of which at least one is a folder, together with
     * discarding {@code tracked} tracked files (0 for none). {@code files} are the files Git lists under the
     * rows; {@code historyOn} says whether Local History will keep copies, so the text can say when it will
     * not keep all of them.
     */
    static String prompt(int tracked, List<String> untracked, List<String> files, boolean historyOn) {
        int folders =
                (int) untracked.stream().filter(GitUntrackedDelete::isFolder).count();
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
