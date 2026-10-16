package com.editora.process;

import java.io.IOException;

/**
 * Starts a local process, waiting out {@code ETXTBSY} ("Text file busy") instead of failing on it.
 *
 * <p>Linux refuses to exec a file that any process still has open for writing. A program written a moment
 * ago (a script just saved, a server just unpacked, a test's stand-in tool) is usually closed by the time it
 * is run, but not always: when <em>another</em> thread of this JVM starts a process while the file is still
 * open, the child inherits the write descriptor and keeps it until it closes its descriptors and execs, which
 * under load can be milliseconds later. Exec in that window fails with {@code error: 26 (Text file busy)},
 * and callers read a failed start as "not installed". Closing the file first does not help, since the copy
 * that blocks the exec is the child's. The condition clears on its own, so a short retry is the fix.
 */
public final class BusyExecutable {

    /** Waits between attempts, doubling; about 0.6 s in all before the last failure is reported. */
    private static final long FIRST_WAIT_MS = 5;

    private static final int ATTEMPTS = 8;

    private BusyExecutable() {}

    /** {@code pb.start()}, retried while the executable is busy; any other failure is thrown at once. */
    public static Process start(ProcessBuilder pb) throws IOException {
        return start(pb, ATTEMPTS, FIRST_WAIT_MS);
    }

    static Process start(ProcessBuilder pb, int attempts, long firstWaitMs) throws IOException {
        long wait = firstWaitMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return pb.start();
            } catch (IOException e) {
                if (attempt >= attempts || !isBusy(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                wait *= 2;
            }
        }
    }

    /**
     * Whether {@code e} is the JDK's report of {@code ETXTBSY}: {@code Exec failed, error: 26 (Text file
     * busy)} today, {@code error=26, Text file busy} on older JDKs. 26 is ETXTBSY on Linux and macOS alike.
     */
    static boolean isBusy(IOException e) {
        String message = e.getMessage();
        return message != null
                && (message.contains("error: 26 ")
                        || message.contains("error=26,")
                        || message.contains("Text file busy"));
    }
}
