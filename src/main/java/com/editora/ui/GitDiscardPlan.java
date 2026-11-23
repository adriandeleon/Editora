package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.jspecify.annotations.Nullable;

/**
 * What "revert this path" has to run, decided from the path's actual status.
 *
 * <p>Reverting used to be {@code git checkout -- <path>} whatever the path's state. That restores the work
 * tree from the <em>index</em>, so a change that was already staged survived it untouched — after a prompt
 * that warned it could not be undone and a status line that said "Discarded"; an untracked file is unknown to
 * checkout (a pathspec error); and an unmerged path is refused by git. Pure.
 *
 * @param kind what to do
 * @param specs the literal pathspecs of the command (for {@link Kind#HEAD}: the path plus the old name of a
 *     staged rename under it, which has to come back too)
 * @param subject the path to name to the user — for {@link Kind#CONFLICT} the unmerged one
 * @param added files under the path that are in the index but in no commit — added with {@code git add -N}
 *     (intent to add), or, when nothing else under the path is staged, added outright. Restoring them from the
 *     index would empty an intent-to-add file ({@code checkout --} writes its empty index entry), and restoring
 *     them from HEAD fails in a repository with no commit yet; so they are taken out of the index
 *     ({@code git rm --cached}) and deleted like untracked files, with the same Local History copy first.
 */
record GitDiscardPlan(
        Kind kind, List<String> specs, @Nullable String subject, List<String> added) {

    GitDiscardPlan(Kind kind, List<String> specs, @Nullable String subject) {
        this(kind, specs, subject, List.of());
    }

    enum Kind {
        /** No change under the path: nothing to run, nothing to confirm. */
        NOTHING,
        /** An unmerged path: reverting it would silently pick a side of the merge. Refused. */
        CONFLICT,
        /** An untracked file: {@code git clean -f}. */
        UNTRACKED,
        /** Only unstaged changes: restore the work tree from the index ({@code git checkout --}). */
        WORKTREE,
        /** Staged changes too: restore index and work tree from HEAD (a newly added file is removed). */
        HEAD,
        /** Only files in no commit ({@link #added}): out of the index, then deleted. */
        ADDED
    }

    /**
     * Whether {@code entry} was added with {@code git add -N}: in the index as an empty placeholder, its content
     * only in the work tree (porcelain v2 {@code .A}). Pure.
     */
    static boolean intentToAdd(FileEntry entry) {
        return entry.index() == '.' && entry.worktree() == 'A';
    }

    /** Whether {@code entry} is a file added to the index and in no commit, not a rename or copy. Pure. */
    static boolean plainAdd(FileEntry entry) {
        return entry.index() == 'A' && entry.origPath() == null && !entry.unmerged();
    }

    /**
     * @param status the last status of the repository {@code pathspec} is relative to
     * @param pathspec a repo-relative file or folder
     */
    static GitDiscardPlan of(GitStatus status, @Nullable String pathspec) {
        if (status == null || pathspec == null || pathspec.isBlank()) {
            return new GitDiscardPlan(Kind.NOTHING, List.of(), pathspec);
        }
        boolean staged = false;
        boolean unstaged = false;
        List<String> renamedFrom = new ArrayList<>();
        List<String> intentToAdd = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        for (FileEntry entry : status.files()) {
            if (entry.untracked()) {
                // An untracked folder is reported as one entry ("dir/") covering everything inside it.
                String path = entry.path();
                boolean folderEntry = path != null && path.endsWith("/");
                if (path != null && (path.equals(pathspec) || (folderEntry && (pathspec + "/").startsWith(path)))) {
                    return new GitDiscardPlan(Kind.UNTRACKED, List.of(pathspec), pathspec);
                }
                continue; // an untracked file inside a tracked folder is not what "revert the folder" means
            }
            if (!GitCoordinator.selects(pathspec, entry.path())) {
                continue;
            }
            if (entry.unmerged()) {
                return new GitDiscardPlan(Kind.CONFLICT, List.of(), entry.path());
            }
            if (intentToAdd(entry)) {
                intentToAdd.add(entry.path());
                continue;
            }
            if (entry.staged()) {
                if (plainAdd(entry)) {
                    adds.add(entry.path());
                    continue;
                }
                staged = true;
                if (entry.origPath() != null && !GitCoordinator.selects(pathspec, entry.origPath())) {
                    renamedFrom.add(entry.origPath());
                }
            }
            unstaged |= entry.unstaged();
        }
        // Files only added (in no commit) leave the index and are deleted: that works whether or not HEAD exists.
        // Next to another staged change they go back with it to HEAD, which then exists, as they always did.
        List<String> added = new ArrayList<>(intentToAdd);
        if (!staged) {
            added.addAll(adds);
        }
        if (staged) {
            List<String> specs = new ArrayList<>(renamedFrom.size() + 1);
            specs.add(pathspec);
            specs.addAll(renamedFrom);
            return new GitDiscardPlan(Kind.HEAD, List.copyOf(specs), pathspec, List.copyOf(intentToAdd));
        }
        if (unstaged) {
            return new GitDiscardPlan(Kind.WORKTREE, List.of(pathspec), pathspec, List.copyOf(added));
        }
        return added.isEmpty()
                ? new GitDiscardPlan(Kind.NOTHING, List.of(), pathspec)
                : new GitDiscardPlan(Kind.ADDED, List.of(), pathspec, List.copyOf(added));
    }
}
