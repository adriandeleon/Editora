package com.editora.recovery;

/**
 * The decisions behind crash recovery, as pure functions: which buffers get a recovery copy, and when a
 * buffer that changed is copied again.
 *
 * <p>Taking a copy means building the whole document as one string on the UI thread, which costs time in
 * proportion to its length. So nothing is copied per keystroke: a window looks at its unsaved buffers about
 * once a second and copies one when the user has paused, or — while they keep typing — when the last copy
 * has become old. Both waits grow with the document, so a very large buffer is copied rarely.
 */
public final class RecoveryPolicy {

    /**
     * The largest document, in UTF-16 code units, that is copied. Above it the buffer has no recovery copy
     * newer than the last one taken while it was still under the limit; the user is told once.
     */
    public static final int MAX_CHARS = 16 * 1024 * 1024;

    /** Up to this length a pause is all it takes; beyond it copies are spaced out by {@link #minIntervalMillis}. */
    static final int SMALL_CHARS = 1024 * 1024;

    /** The longest the newest edits of a small buffer stay uncopied while the user types without pause. */
    static final long BASE_MAX_WAIT_MILLIS = 10_000;

    private static final long SPACING_PER_MEGA_CHAR_MILLIS = 2_000;

    private RecoveryPolicy() {}

    /** Why a buffer has no recovery copy taken right now. */
    public enum Skip {
        /** It is copied. */
        NONE,
        /** Nothing to lose: it matches what is on disk. */
        CLEAN,
        /** Its text is not a document the user could save: a loading shell, a slice of a huge file, a log tail. */
        NOT_A_DOCUMENT,
        /** Longer than {@link #MAX_CHARS}. */
        TOO_LARGE
    }

    /**
     * Whether a buffer in this state gets a recovery copy.
     *
     * @param dirty has unsaved changes
     * @param loading still an empty shell waiting for its file
     * @param partial holds only part of its file (a truncated huge-file load, or a log trimmed while following)
     * @param following is following a growing log file
     * @param length document length in UTF-16 code units
     */
    public static Skip skip(boolean dirty, boolean loading, boolean partial, boolean following, int length) {
        if (loading || partial || following) {
            return Skip.NOT_A_DOCUMENT;
        }
        if (!dirty) {
            return Skip.CLEAN;
        }
        return length > MAX_CHARS ? Skip.TOO_LARGE : Skip.NONE;
    }

    /** The least time between two copies of a document of {@code length}: none while it is small. */
    public static long minIntervalMillis(int length) {
        if (length <= SMALL_CHARS) {
            return 0;
        }
        return (long) Math.ceil(length / (double) SMALL_CHARS) * SPACING_PER_MEGA_CHAR_MILLIS;
    }

    /** The longest a changed document of {@code length} goes without a copy while it keeps changing. */
    public static long maxWaitMillis(int length) {
        return Math.max(BASE_MAX_WAIT_MILLIS, 2 * minIntervalMillis(length));
    }

    /**
     * Whether a changed buffer is copied on this look.
     *
     * @param changedSinceLastLook the text changed again since the previous look (the user is still typing)
     * @param millisSinceLastCopy time since the last copy was taken (a very large value when there is none)
     * @param length document length in UTF-16 code units
     */
    public static boolean due(boolean changedSinceLastLook, long millisSinceLastCopy, int length) {
        if (millisSinceLastCopy >= maxWaitMillis(length)) {
            return true;
        }
        return !changedSinceLastLook && millisSinceLastCopy >= minIntervalMillis(length);
    }
}
