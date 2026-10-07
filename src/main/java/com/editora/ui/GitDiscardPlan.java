package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;

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
 */
record GitDiscardPlan(Kind kind, List<String> specs, String subject) {

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
        HEAD
    }

    /**
     * @param status the last status of the repository {@code pathspec} is relative to
     * @param pathspec a repo-relative file or folder
     */
    static GitDiscardPlan of(GitStatus status, String pathspec) {
        if (status == null || pathspec == null || pathspec.isBlank()) {
            return new GitDiscardPlan(Kind.NOTHING, List.of(), pathspec);
        }
        boolean staged = false;
        boolean unstaged = false;
        List<String> renamedFrom = new ArrayList<>();
        for (FileEntry entry : status.files()) {
            if (entry.untracked()) {
                // An untracked folder is reported as one entry ("dir/") covering everything inside it.
                boolean folderEntry = entry.path().endsWith("/");
                if (entry.path().equals(pathspec) || (folderEntry && (pathspec + "/").startsWith(entry.path()))) {
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
            if (entry.staged()) {
                staged = true;
                if (entry.origPath() != null && !GitCoordinator.selects(pathspec, entry.origPath())) {
                    renamedFrom.add(entry.origPath());
                }
            }
            unstaged |= entry.unstaged();
        }
        if (staged) {
            List<String> specs = new ArrayList<>(renamedFrom.size() + 1);
            specs.add(pathspec);
            specs.addAll(renamedFrom);
            return new GitDiscardPlan(Kind.HEAD, List.copyOf(specs), pathspec);
        }
        return unstaged
                ? new GitDiscardPlan(Kind.WORKTREE, List.of(pathspec), pathspec)
                : new GitDiscardPlan(Kind.NOTHING, List.of(), pathspec);
    }
}
