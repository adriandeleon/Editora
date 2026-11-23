package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.editora.run.AnsiText;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Run output chunk collapses to what a terminal would still show, and its styles are found in one pass. */
class RunPanelCarriageTest {

    private static String piece(String text) {
        RunPanel.Carriage c = RunPanel.Carriage.of(text);
        return text.substring(c.from(), c.to());
    }

    @Test
    void onlyTheLastNonEmptySegmentSurvives() {
        assertEquals("last", piece("a\rb\rlast"));
        RunPanel.Carriage c = RunPanel.Carriage.of("a\rb\rlast");
        assertTrue(c.returnBefore());
        assertFalse(c.returnAfter());
        assertFalse(c.clearScreen());
    }

    @Test
    void aTrailingReturnKeepsThePieceAndMarksTheLine() {
        RunPanel.Carriage c = RunPanel.Carriage.of("50%\r\r");
        assertEquals("50%", "50%\r\r".substring(c.from(), c.to()));
        assertFalse(c.returnBefore(), "nothing before it in this chunk overwrites the line");
        assertTrue(c.returnAfter());
    }

    @Test
    void plainTextAndBareReturns() {
        RunPanel.Carriage plain = RunPanel.Carriage.of("hello");
        assertEquals("hello", piece("hello"));
        assertFalse(plain.returnBefore() || plain.returnAfter() || plain.clearScreen());
        RunPanel.Carriage bare = RunPanel.Carriage.of("\r");
        assertEquals(bare.from(), bare.to());
        assertTrue(bare.returnAfter());
        RunPanel.Carriage empty = RunPanel.Carriage.of("");
        assertEquals(0, empty.to());
    }

    @Test
    void anEraseScreenDropsEverythingBeforeIt() {
        String text = "old\rolder" + AnsiText.CLEAR_SCREEN + "x\rfresh";
        RunPanel.Carriage c = RunPanel.Carriage.of(text);
        assertTrue(c.clearScreen());
        assertEquals("fresh", text.substring(c.from(), c.to()));
        assertTrue(c.returnBefore());
        String justClear = "abc\r" + AnsiText.CLEAR_SCREEN;
        RunPanel.Carriage cleared = RunPanel.Carriage.of(justClear);
        assertTrue(cleared.clearScreen());
        assertEquals(cleared.from(), cleared.to());
        assertFalse(cleared.returnBefore() || cleared.returnAfter(), "a return before the erase is gone with it");
    }

    @Test
    void aProgressLineOfThousandsOfSegmentsIsOnePiece() {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 9_000; i++) {
            line.append('\r').append(10_000 + i);
        }
        assertEquals("18999", piece(line.toString()));
    }

    /** The spans as the old per-cut lookup computed them: the reference the one-pass walk must match. */
    private static List<StyleSpan<Collection<String>>> reference(String piece, List<AnsiText.Run> runs, String base) {
        List<StyleSpan<Collection<String>>> out = new ArrayList<>();
        java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>(List.of(0, piece.length()));
        int pos = 0;
        for (AnsiText.Run r : runs) {
            pos += r.length();
            cuts.add(Math.min(pos, piece.length()));
        }
        var urls = com.editora.run.ConsoleUrls.find(piece);
        for (var u : urls) {
            cuts.add(u.start());
            cuts.add(u.end());
        }
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && cut > prev) {
                List<String> classes = new ArrayList<>(List.of(base));
                int p = 0;
                for (AnsiText.Run r : runs) {
                    if (prev < p + r.length()) {
                        classes.addAll(r.classes());
                        break;
                    }
                    p += r.length();
                }
                for (var u : urls) {
                    if (prev >= u.start() && prev < u.end()) {
                        classes.add("console-url");
                        break;
                    }
                }
                out.add(new StyleSpan<>(classes, cut - prev));
            }
            prev = cut;
        }
        return out;
    }

    private static List<StyleSpan<Collection<String>>> list(StyleSpans<Collection<String>> spans) {
        List<StyleSpan<Collection<String>>> out = new ArrayList<>();
        spans.forEach(out::add);
        return out;
    }

    @Test
    void spansMatchThePerCutLookup() {
        String piece = "see https://example.com/a and http://x.org now, done";
        List<AnsiText.Run> runs = List.of(
                new AnsiText.Run(0, Set.of("ansi-blue")),
                new AnsiText.Run(6, Set.of("ansi-red")),
                new AnsiText.Run(10, Set.of()),
                new AnsiText.Run(3, Set.of("ansi-green")),
                new AnsiText.Run(200, Set.of("ansi-bold")));
        assertEquals(reference(piece, runs, "run-stderr"), list(RunPanel.spans(piece, runs, "run-stderr")));
        assertEquals(reference(piece, List.of(), "run-stderr"), list(RunPanel.spans(piece, List.of(), "run-stderr")));
    }

    @Test
    void aHeavilyColouredLongLineIsLinear() {
        StringBuilder text = new StringBuilder();
        List<AnsiText.Run> runs = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            text.append("ab");
            runs.add(new AnsiText.Run(2, Set.of(i % 2 == 0 ? "ansi-red" : "ansi-blue")));
        }
        long start = System.nanoTime();
        StyleSpans<Collection<String>> spans = RunPanel.spans(text.toString(), runs, "run-stdout");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(60_000, spans.length());
        assertEquals(30_000, spans.getSpanCount());
        assertTrue(millis < 2_000, "30k colour runs took " + millis + " ms");
    }
}
