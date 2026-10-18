package com.editora.terminal;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongConsumer;

import com.editora.process.PosixShell;

/**
 * A terminal's output with the pid line its start script printed ({@link PosixShell#TERMINAL_PID_MARK}) taken out
 * and reported. The line comes first, ahead of anything the shell prints; whatever came before it (a launcher's
 * own warning) is kept. If no mark arrives within the first few kilobytes, everything is passed on as it is.
 */
final class PidMarkStream extends FilterInputStream {

    /** How much output may arrive before the mark stops being looked for. */
    static final int LOOK_AHEAD = 8 * 1024;

    private static final byte[] MARK = PosixShell.TERMINAL_PID_MARK.getBytes(StandardCharsets.UTF_8);

    private final LongConsumer onPid;
    private byte[] pending;
    private int pendingAt;
    private boolean scanned;

    PidMarkStream(InputStream in, LongConsumer onPid) {
        super(in);
        this.onPid = onPid;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!scanned) {
            scan();
        }
        if (pending != null) {
            int n = Math.min(len, pending.length - pendingAt);
            System.arraycopy(pending, pendingAt, b, off, n);
            pendingAt += n;
            if (pendingAt == pending.length) {
                pending = null;
            }
            return n;
        }
        return super.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return pending != null ? pending.length - pendingAt : super.available();
    }

    /** Reads until the mark's line is complete (or the look-ahead runs out), then queues what remains. */
    private void scan() throws IOException {
        scanned = true;
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (seen.size() < LOOK_AHEAD) {
            byte[] sofar = seen.toByteArray();
            int at = indexOf(sofar, MARK);
            if (at >= 0) {
                int end = indexOf(sofar, (byte) '\n', at + MARK.length);
                if (end >= 0) {
                    String digits = new String(
                                    sofar, at + MARK.length, end - at - MARK.length, StandardCharsets.US_ASCII)
                            .strip();
                    try {
                        onPid.accept(Long.parseLong(digits));
                    } catch (NumberFormatException notAPid) {
                        // Not ours after all: pass the line through.
                        queue(sofar);
                        return;
                    }
                    ByteArrayOutputStream rest = new ByteArrayOutputStream();
                    rest.write(sofar, 0, at);
                    rest.write(sofar, end + 1, sofar.length - end - 1);
                    queue(rest.toByteArray());
                    return;
                }
            }
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            seen.write(buf, 0, n);
        }
        queue(seen.toByteArray());
    }

    private void queue(byte[] bytes) {
        pending = bytes.length == 0 ? null : bytes;
        pendingAt = 0;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static int indexOf(byte[] haystack, byte b, int from) {
        for (int i = from; i < haystack.length; i++) {
            if (haystack[i] == b) {
                return i;
            }
        }
        return -1;
    }
}
