package com.editora.git;

import java.util.Locale;

import com.editora.process.ProcessRunner;

/**
 * How {@code git pull} brings the remote's commits into the current branch — the "Pull mode" setting.
 *
 * <p>Fast-forward only is the default and what Editora always did: it never rewrites or merges anything, so
 * it simply fails once the local branch and its upstream each have a commit the other lacks. The other two
 * are the ways out of that state.
 */
public enum GitPullMode {
    /** {@code git pull --ff-only}: move the branch forward, or fail when it has diverged. */
    FF_ONLY("ff-only", "pull", "--ff-only"),
    /**
     * {@code git pull --rebase --autostash}: replay the local commits on top of the remote's. A rebase
     * refuses to start on a dirty working tree, so uncommitted changes are stashed for the duration and
     * applied back ({@link #autostash} reads how that went).
     */
    REBASE("rebase", "pull", "--rebase", "--autostash"),
    /**
     * {@code git pull --no-rebase --no-edit --autostash}: merge the remote's commits (fast-forwarding when
     * that is possible). {@code --no-edit} takes git's merge message — there is no terminal for an editor;
     * {@code --autostash} carries uncommitted changes across the merge, as for a rebase.
     */
    MERGE("merge", "pull", "--no-rebase", "--no-edit", "--autostash");

    private final String id;
    private final String[] args;

    GitPullMode(String id, String... args) {
        this.id = id;
        this.args = args;
    }

    /** The value stored in {@code settings.json}. */
    public String id() {
        return id;
    }

    /** The git arguments of a pull in this mode. */
    public String[] args() {
        return args.clone();
    }

    /** The mode a stored value names; anything unknown, blank or null is {@link #FF_ONLY}. */
    public static GitPullMode of(String id) {
        String wanted = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        for (GitPullMode mode : values()) {
            if (mode.id.equals(wanted)) {
                return mode;
            }
        }
        return FF_ONLY;
    }

    /** What became of the uncommitted changes an {@code --autostash} pull set aside. */
    public enum Autostash {
        /** Nothing was stashed (a clean tree), or git said nothing about it. */
        NONE,
        /** The stash was applied back: the working tree has the user's changes again. */
        APPLIED,
        /**
         * Applying it back conflicted. Git keeps the changes as an ordinary stash entry ("Your changes are
         * safe in the stash") and leaves the conflicted files in the working tree.
         */
        KEPT_IN_STASH
    }

    /** Reads {@link Autostash} from a finished pull's English output. Pure. */
    public static Autostash autostash(ProcessRunner.Result result) {
        if (result == null) {
            return Autostash.NONE;
        }
        String text = (result.err() == null ? "" : result.err()) + '\n' + (result.out() == null ? "" : result.out());
        if (text.contains("Applying autostash resulted in conflicts") || text.contains("safe in the stash")) {
            return Autostash.KEPT_IN_STASH;
        }
        return text.contains("Applied autostash") ? Autostash.APPLIED : Autostash.NONE;
    }

    /**
     * Whether a failed fast-forward-only pull failed <em>because the branches diverged</em> — the one failure
     * a rebase or a merge fixes, as opposed to no network, no upstream or local changes in the way. Git's
     * replies are read in English (the message language is pinned for every command Editora runs): 2.33+
     * says "Not possible to fast-forward, aborting.", and from 2.38 adds a "Diverging branches can't be
     * fast-forwarded" hint. Pure.
     */
    public static boolean diverged(ProcessRunner.Result result) {
        if (result == null || result.ok() || result.cancelled() || result.timedOut()) {
            return false;
        }
        String text = (result.err() == null ? "" : result.err()) + '\n' + (result.out() == null ? "" : result.out());
        return text.contains("Not possible to fast-forward") || text.contains("Diverging branches can't be");
    }
}
