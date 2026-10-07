package com.editora.process;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveLinesTest {

    /** Feeds {@code chunks} and returns each delivery as {@code text} (final) or {@code ~text} (transient). */
    private static List<String> lines(String... chunks) {
        List<String> seen = new ArrayList<>();
        LiveLines lines = new LiveLines((text, transientLine) -> seen.add((transientLine ? "~" : "") + text));
        for (String chunk : chunks) {
            byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
            lines.feed(bytes, 0, bytes.length);
        }
        lines.end();
        return seen;
    }

    @Test
    void newlineTerminatedLinesAreFinal() {
        assertEquals(List.of("a", "", "b"), lines("a\n\nb\n"));
    }

    @Test
    void aBareCarriageReturnDeliversProgressAtOnceAsTransient() {
        assertEquals(
                List.of("Cloning into 'x'...", "~Receiving  1%", "~Receiving 50%", "Receiving 100%, done."),
                lines("Cloning into 'x'...\nReceiving  1%\rReceiving 50%\rReceiving 100%, done.\n"));
    }

    @Test
    void crlfIsOneOrdinaryLineEvenSplitAcrossReads() {
        // The CR has already gone out as progress when the LF arrives: the same text is re-delivered as final.
        assertEquals(List.of("~a", "a", "~b", "b"), lines("a\r\nb\r", "\n"));
    }

    @Test
    void aLineSplitAcrossReadsIsDeliveredOnce() {
        assertEquals(List.of("hello world"), lines("hello ", "world\n"));
    }

    @Test
    void anUnterminatedTailIsDeliveredAtEndOfStream() {
        assertEquals(List.of("a", "fatal: early EOF"), lines("a\nfatal: early EOF"));
    }

    @Test
    void aThrowingListenerDoesNotStopTheStream() {
        int[] calls = {0};
        LiveLines lines = new LiveLines((text, transientLine) -> {
            calls[0]++;
            throw new IllegalStateException("boom");
        });
        byte[] bytes = "a\nb\n".getBytes(StandardCharsets.UTF_8);
        lines.feed(bytes, 0, bytes.length);
        assertEquals(2, calls[0]);
    }

    @Test
    void collapsingKeepsOnlyWhatATerminalWouldStillShow() {
        assertEquals(
                "Cloning into 'x'...\nReceiving 100%, done.\nfatal: index-pack failed",
                LiveLines.collapseCarriageReturns(
                        "Cloning into 'x'...\nReceiving  1%\rReceiving 100%, done.\nfatal: index-pack failed"));
        assertEquals("a\nb\n", LiveLines.collapseCarriageReturns("a\r\nb\r\n"));
        assertEquals("plain\n", LiveLines.collapseCarriageReturns("plain\n"));
        assertNull(LiveLines.collapseCarriageReturns(null));
    }
}
