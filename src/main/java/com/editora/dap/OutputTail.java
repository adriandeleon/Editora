package com.editora.dap;

/**
 * How long the end of a session waits for the program's output still on its way (review E104).
 *
 * <p>When java-debug starts the program itself (the program console off), the program's output reaches Editora
 * as the adapter's {@code output} events, which trail far behind a program that prints fast: java-debug sends
 * its {@code exited} and {@code terminated} events while thousands of lines are still coming. Ending the session
 * on {@code terminated} closed the connection and the last of the output — usually the lines that matter — was
 * lost. So the end waits until the output has been quiet for {@link #QUIET_MILLIS}, never longer than
 * {@link #MAX_WAIT_MILLIS} after the adapter said it was done; output that has already stopped ends it at once.
 * Pure, so the timing is unit-tested.
 */
final class OutputTail {

    /** Output silent this long counts as finished. */
    static final long QUIET_MILLIS = 300;

    /** The longest the end of a session waits for trailing output. */
    static final long MAX_WAIT_MILLIS = 30_000;

    private OutputTail() {}

    /**
     * Milliseconds to wait before ending, or 0 to end now.
     *
     * @param nowNanos the current {@link System#nanoTime()}
     * @param lastOutputNanos when the session's last output arrived ({@code 0}: none yet)
     * @param doneNanos when the adapter said the program was done
     */
    static long waitMillis(long nowNanos, long lastOutputNanos, long doneNanos) {
        if (lastOutputNanos == 0) {
            return 0;
        }
        long sinceDone = (nowNanos - doneNanos) / 1_000_000;
        if (sinceDone >= MAX_WAIT_MILLIS) {
            return 0;
        }
        long quietFor = (nowNanos - lastOutputNanos) / 1_000_000;
        if (quietFor >= QUIET_MILLIS) {
            return 0;
        }
        return Math.min(QUIET_MILLIS - quietFor, MAX_WAIT_MILLIS - sinceDone);
    }
}
