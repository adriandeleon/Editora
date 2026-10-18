package com.editora.terminal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shell-integration sequences taken out of a terminal's output, in stream order, and nothing else touched. */
class OscTapTest {

    private final List<String> seen = new ArrayList<>();
    private final OscTap.Listener listener = new OscTap.Listener() {
        @Override
        public void cwd(String url) {
            seen.add("cwd " + url);
        }

        @Override
        public void promptMark(char kind, String params) {
            seen.add("mark " + kind + (params.isEmpty() ? "" : " " + params));
        }
    };

    /** Feeds {@code pieces} one read at a time and returns what came out: text as "text …", events as they are. */
    private List<String> run(String... pieces) {
        OscTap tap = new OscTap();
        StringBuilder text = new StringBuilder();
        for (String piece : pieces) {
            tap.feed(piece.toCharArray(), 0, piece.length());
            char[] buf = new char[64];
            while (tap.hasNext()) {
                int n = tap.next(buf, 0, buf.length, listener);
                if (n == 0) {
                    if (!text.isEmpty()) {
                        seen.add(seen.size() - 1, "text " + text); // text read before the event
                        text.setLength(0);
                    }
                } else {
                    text.append(buf, 0, n);
                }
            }
        }
        if (!text.isEmpty()) {
            seen.add("text " + text);
        }
        return seen;
    }

    @Test
    void aCwdReportIsTakenOutAndDeliveredInOrder() {
        assertEquals(
                List.of("text before", "cwd file://box/home/ana/my%20app", "text after"),
                run("before\u001b]7;file://box/home/ana/my%20app\u0007after"));
    }

    @Test
    void promptMarksWithEitherTerminator() {
        assertEquals(
                List.of("mark A", "text $ ", "mark B", "text ls", "mark C", "text out\r\n", "mark D 1"),
                run("\u001b]133;A\u0007$ \u001b]133;B\u001b\\ls\u001b]133;C\u0007out\r\n\u001b]133;D;1\u0007"));
    }

    @Test
    void aSequenceSplitAcrossReadsIsStillOne() {
        assertEquals(List.of("text x", "cwd file:///tmp", "text y"), run("x\u001b", "]7;fi", "le:///tmp\u001b", "\\y"));
    }

    @Test
    void otherSequencesPassThroughExactly() {
        String title = "\u001b]0;my title\u0007";
        String colour = "\u001b[31mred\u001b[0m";
        String hyperlink = "\u001b]8;;https://x\u001b\\link\u001b]8;;\u001b\\";
        assertEquals(List.of("text " + title + colour + hyperlink), run(title + colour + hyperlink));
    }

    @Test
    void aDoubleEscapeKeepsTheFirstAndReadsTheSecond() {
        assertEquals(List.of("text \u001b", "cwd file:///a"), run("\u001b\u001b]7;file:///a\u0007"));
    }

    @Test
    void anOverlongOscIsPassedOnRatherThanHeld() {
        String body = "x".repeat(OscTap.MAX_SEQUENCE + 5);
        List<String> out = run("\u001b]9;" + body + "\u0007");
        assertEquals(1, out.size());
        assertEquals("text \u001b]9;" + body + "\u0007", out.get(0));
    }

    @Test
    void aMarkWithoutAKindIsNotOurs() {
        assertEquals(List.of("text \u001b]133;\u0007"), run("\u001b]133;\u0007"));
    }

    @Test
    @org.junit.jupiter.api.Timeout(10)
    void aTrailingEscapeIsHandedOutWhenNothingElseIsComing() throws Exception {
        // vim leaving insert mode, a lone Escape echoed: the read ends with ESC and the shell says nothing more.
        FakeConnector inner = new FakeConnector();
        TappedConnector tapped = new TappedConnector(inner, listener);
        inner.emit("vim says \u001b");
        char[] buf = new char[64];
        StringBuilder got = new StringBuilder();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (got.indexOf("\u001b") < 0 && System.nanoTime() < deadline) {
            int n = tapped.read(buf, 0, buf.length);
            if (n < 0) break;
            got.append(buf, 0, n);
        }
        assertEquals("vim says \u001b", got.toString());
        inner.close();
    }
}
