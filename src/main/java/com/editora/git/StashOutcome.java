package com.editora.git;

import java.util.Locale;

/**
 * What became of a {@code git stash apply} / {@code pop} / {@code branch}, read from its exit code and
 * output. Git exits 1 for all of these with nothing but its text to tell them apart, and they need different
 * answers: after a conflict the changes <em>are</em> in the working tree (with markers) and — for a pop — the
 * stash has not been dropped; after an "would be overwritten" refusal nothing was applied at all. The
 * commands run with English messages ({@code LC_MESSAGES=C}), which is what makes the text matchable. Pure.
 */
public enum StashOutcome {
    /** Applied cleanly. */
    APPLIED,
    /** Applied, leaving merge conflicts in the working tree; the stash entry is kept. */
    CONFLICT,
    /** Refused: uncommitted changes to the same files would have been overwritten. Nothing was applied. */
    WOULD_OVERWRITE,
    /** The tracked changes were applied but an untracked file of the stash already exists; the stash is kept. */
    UNTRACKED_EXISTS,
    /** There was no stash to apply ("No stash entries found"). */
    EMPTY,
    /** Anything else git reported. */
    FAILED;

    public static StashOutcome classify(boolean ok, String out, String err) {
        if (ok) {
            return APPLIED;
        }
        String text = ((out == null ? "" : out) + "\n" + (err == null ? "" : err)).toLowerCase(Locale.ROOT);
        // A stash also reports "Merge conflict in <path>" on its own, and "needs merge" for a path that
        // was already unmerged.
        if (GitConflicts.mentionsConflict(text) || text.contains("merge conflict in") || text.contains("needs merge")) {
            return CONFLICT;
        }
        if (text.contains("would be overwritten")) {
            return WOULD_OVERWRITE;
        }
        if (text.contains("no stash entries found")) {
            return EMPTY;
        }
        if (text.contains("already exists, no checkout") || text.contains("could not restore untracked files")) {
            return UNTRACKED_EXISTS;
        }
        return FAILED;
    }

    /** Whether the stash entry is certainly still in the list afterwards (a pop only drops on success). */
    public boolean stashKept() {
        return this != APPLIED;
    }
}
