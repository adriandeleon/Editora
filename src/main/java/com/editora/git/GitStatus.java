package com.editora.git;

import java.util.List;

/**
 * A snapshot of {@code git status} for one repository: the current branch, its upstream and
 * ahead/behind counts, and the changed files. Produced by {@link StatusParser} from
 * {@code git status --porcelain=v2 --branch}.
 *
 * <p>{@link #NOT_A_REPO} is the sentinel returned when the active file isn't inside a Git work tree
 * (or Git isn't installed); callers check {@link #isRepo()} before showing any Git UI.
 */
public record GitStatus(boolean repo, String branch, String upstream, int ahead, int behind, List<FileEntry> files) {

    /** Sentinel for "no repository here" — all Git UI stays hidden. */
    public static final GitStatus NOT_A_REPO = new GitStatus(false, "", "", 0, 0, List.of());

    public boolean isRepo() {
        return repo;
    }

    /** True when the work tree is clean (no staged, unstaged, or untracked changes). */
    public boolean isClean() {
        return files.isEmpty();
    }

    /**
     * One changed path. {@code index}/{@code worktree} are the porcelain-v2 status letters
     * ({@code M A D R C}, or {@code .} for unchanged on that side); untracked files use {@code '?'}.
     *
     * @param path     repo-relative path (forward slashes, as Git reports it)
     * @param index    staged-side status letter (or {@code '.'})
     * @param worktree worktree-side status letter (or {@code '.'})
     * @param origPath original path for a rename/copy, else {@code null}
     */
    public record FileEntry(String path, char index, char worktree, String origPath) {

        public boolean untracked() {
            return index == '?';
        }

        /**
         * An unmerged (conflicted) path — porcelain's {@code u} entries: {@code UU AU UA DU UD AA DD}. Its index
         * holds the merge's stage 1/2/3 blobs, not a staged change, so it must never be offered for unstaging:
         * {@code git reset HEAD -- <path>} drops those stages and Git forgets the path was ever conflicted.
         */
        public boolean unmerged() {
            return index == 'U'
                    || worktree == 'U'
                    || (index == 'A' && worktree == 'A')
                    || (index == 'D' && worktree == 'D');
        }

        /** Has staged changes (something different between HEAD and the index). Never an unmerged path. */
        public boolean staged() {
            return !untracked() && !unmerged() && index != '.';
        }

        /** Has unstaged worktree changes (something different between the index and the work tree). */
        public boolean unstaged() {
            return !untracked() && worktree != '.';
        }
    }
}
