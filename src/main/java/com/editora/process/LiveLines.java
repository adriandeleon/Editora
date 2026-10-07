package com.editora.process;

import java.io.ByteArrayOutputStream;

/**
 * Splits one of a child's output streams into lines <em>as they arrive</em>, for {@link ProcessRunner}'s
 * live variant: a {@code git clone} of a large repository runs for minutes, and a transcript that only
 * appears once it has exited leaves the user looking at nothing.
 *
 * <p>A bare carriage return ends a <b>transient</b> line — progress the child means to overwrite
 * ({@code Receiving objects:  12%\r}). It is delivered at once, since the next update may be a second away,
 * and the line after it takes its place. A CRLF is one ordinary line: the text already delivered as transient
 * is delivered again as final.
 *
 * <p>Not thread-safe: one instance per stream, fed by that stream's reader thread.
 */
final class LiveLines {

    /** No line longer than this is materialized; the excess is dropped. */
    static final int MAX_LINE_BYTES = 64 * 1024;

    private final ProcessRunner.LiveOutput sink;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** The transient line just delivered at a CR, until the byte after it says whether that was a CRLF. */
    private String afterCr;

    LiveLines(ProcessRunner.LiveOutput sink) {
        this.sink = sink;
    }

    void feed(byte[] buf, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            byte b = buf[i];
            if (b == '\n') {
                if (afterCr != null && line.size() == 0) {
                    emit(afterCr, false); // CRLF: the line shown as progress was a whole line after all
                } else {
                    emit(take(), false);
                }
                afterCr = null;
            } else if (b == '\r') {
                if (line.size() > 0) {
                    afterCr = take();
                    emit(afterCr, true);
                }
            } else {
                afterCr = null;
                if (line.size() < MAX_LINE_BYTES) {
                    line.write(b);
                }
            }
        }
    }

    /** End of stream: whatever stopped short of a newline is a line too. */
    void end() {
        if (line.size() > 0) {
            emit(take(), false);
        }
    }

    private String take() {
        byte[] bytes = line.toByteArray();
        line.reset();
        return ChildText.decode(bytes, 0, bytes.length - ChildText.incompleteUtf8Tail(bytes, bytes.length));
    }

    private void emit(String text, boolean transientLine) {
        try {
            sink.line(text, transientLine);
        } catch (RuntimeException ignored) {
            // a listener's failure must not stop the pipe being drained (the child would block on it)
        }
    }

    /**
     * {@code text} as a terminal would leave it: of each line, only what follows its last carriage return.
     * A command run live with progress captures every overwritten update in its stderr, which is not what an
     * error message should quote.
     */
    static String collapseCarriageReturns(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (String l : text.split("\n", -1)) {
            String body = l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
            int cr = body.lastIndexOf('\r');
            sb.append(cr < 0 ? body : body.substring(cr + 1)).append('\n');
        }
        sb.setLength(sb.length() - 1); // split(-1) yields one element more than there are newlines
        return sb.toString();
    }
}
