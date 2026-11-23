package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

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
    static boolean isFolder(@Nullable Path root, String pathspec) {
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
    static int folders(@Nullable Path root, List<String> pathspecs) {
        return (int) pathspecs.stream().filter(spec -> isFolder(root, spec)).count();
    }

    /**
     * What the disk says about untracked rows: how many are folders, and which of those folders are Git
     * repositories of their own (a {@code .git} inside). Git lists a nested repository as an untracked folder,
     * and {@code git clean -f} leaves it alone while reporting success: such a row is not deleted, and the user
     * is told so.
     */
    record Look(int folders, List<String> nested) {
        static final Look NONE = new Look(0, List.of());
    }

    /** {@link Look} for {@code pathspecs} under {@code root}: off the FX thread, it looks at the disk. */
    static Look look(@Nullable Path root, List<String> pathspecs) {
        int folders = 0;
        List<String> nested = new java.util.ArrayList<>();
        for (String spec : pathspecs) {
            if (!isFolder(root, spec)) {
                continue;
            }
            folders++;
            if (isRepository(root, spec)) {
                nested.add(spec);
            }
        }
        return new Look(folders, List.copyOf(nested));
    }

    /** Whether the folder {@code pathspec} under {@code root} holds a {@code .git} (a folder or a gitdir file). */
    static boolean isRepository(@Nullable Path root, String pathspec) {
        if (root == null || pathspec == null) {
            return false;
        }
        try {
            return Files.exists(root.resolve(pathspec).resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    /**
     * The sentence added to a confirmation when Local History keeps only some of the {@code deleted} files
     * ({@code ""} when it keeps every one, or keeps none because it is off).
     */
    static String historyNote(int deleted, boolean historyOn) {
        return historyOn && deleted > MAX_HISTORY_CAPTURES
                ? "\n\n" + tr("dialog.discard.historyLimit", MAX_HISTORY_CAPTURES)
                : "";
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
        return text + historyNote(files.size(), historyOn);
    }

    /** The files to copy into Local History before the delete: the first {@link #MAX_HISTORY_CAPTURES}. */
    static List<Path> captures(Path root, List<String> files) {
        return files.stream().limit(MAX_HISTORY_CAPTURES).map(root::resolve).toList();
    }
}
