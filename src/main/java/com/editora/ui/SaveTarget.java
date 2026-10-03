package com.editora.ui;

/**
 * What the filesystem says about a save target immediately before a write attempt.
 *
 * <p>{@code Files.exists} and {@code Files.notExists} are <b>not</b> complements: both are false when the
 * path cannot be examined at all — a dropped sshfs/NFS mount, a folder that may no longer be searched, a
 * name too long for the filesystem. The save loop used to read "does not exist" as "absent", stage a
 * write whose commit check ({@code notExists}) could never pass, and retry without bound or pause: one CPU
 * pinned, the per-path write lock held, and every later save in the window queued behind it.
 */
enum SaveTarget {
    PRESENT,
    ABSENT,
    /** Neither present nor absent can be established; a write can never be committed safely. */
    INDETERMINATE;

    /**
     * How many times one save may stage and re-check before it is reported as failed. Each retry after the
     * first means the target changed between the conflict check and the commit; a target that keeps doing so
     * (another program appending to it) ends as a visible failed save instead of a loop.
     */
    static final int MAX_ATTEMPTS = 4;

    static SaveTarget of(boolean exists, boolean notExists) {
        if (exists) {
            return PRESENT;
        }
        return notExists ? ABSENT : INDETERMINATE;
    }
}
