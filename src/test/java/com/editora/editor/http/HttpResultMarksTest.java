package com.editora.editor.http;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import org.fxmisc.richtext.Inlay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The editor keeps a request's result on its line, and drops it once the line is edited (U11). */
class HttpResultMarksTest {

    private static IntFunction<String> lines(String... text) {
        List<String> all = Arrays.asList(text);
        return i -> i >= 0 && i < all.size() ? all.get(i) : null;
    }

    private static HttpLineResult result(int line, String anchor, String text) {
        return new HttpLineResult(
                line,
                anchor,
                List.of(
                        new HttpLineResult.Segment(text, "http-result-ok"),
                        new HttpLineResult.Segment(" · 5 ms", "http-result")),
                "http-result-ok",
                "Ran at 10:00:00",
                null);
    }

    @Test
    void aResultIsKeptOnlyWhereItsLineStillReadsAsItDid() {
        HttpResultMarks marks = new HttpResultMarks();
        IntFunction<String> text = lines("### a", "GET /a", "", "### b", "GET /b");
        Set<Integer> changed = marks.set(List.of(result(1, "GET /a", "200 OK"), result(4, "GET /old", "404")), text);
        assertEquals(Set.of(1), changed);
        assertEquals(1, marks.all().size());
        assertEquals("http-result-ok", marks.glyphClass(1));
        assertNull(marks.glyphClass(4), "its line reads something else now");

        assertNull(marks.set(List.of(result(1, "GET /a", "200 OK")), text), "the same look: nothing to repaint");
        assertEquals(Set.of(1), marks.set(List.of(), text), "cleared");
        assertTrue(marks.isEmpty());
    }

    @Test
    void theScanDropsAResultWhoseLineWasEdited() {
        HttpResultMarks marks = new HttpResultMarks();
        marks.set(
                List.of(result(1, "GET /a", "200 OK"), result(4, "GET /b", "404")),
                lines("### a", "GET /a", "", "### b", "GET /b"));
        assertNull(marks.reanchor(List.of(1, 4), lines("### a", "GET /a", "", "### b", "GET /b")));

        Set<Integer> changed = marks.reanchor(List.of(1, 4), lines("### a", "GET /a", "", "### b", "GET /bx"));
        assertTrue(changed.contains(4));
        assertEquals(List.of(1), marks.all().stream().map(HttpLineResult::line).toList());
        // …and when the line is no longer a request line at all.
        marks.reanchor(List.of(), lines("### a", "GET /a"));
        assertTrue(marks.isEmpty());
    }

    @Test
    void theInlaysFollowTheLinesOtherInlaysAtTheEndOfTheLine() {
        HttpResultMarks marks = new HttpResultMarks();
        marks.set(List.of(result(1, "GET /a", "200 OK")), lines("### a", "GET /a"));
        List<Inlay> base = new ArrayList<>(List.of(new Inlay(0, "hint", "inlay-hint")));
        List<Inlay> out = marks.inlays(1, base, 6);
        assertEquals(
                List.of("hint", "200 OK", " · 5 ms"),
                out.stream().map(Inlay::getText).toList());
        assertEquals(List.of(0, 6, 6), out.stream().map(Inlay::getColumn).toList());
        assertEquals(
                List.of("inlay-hint", "http-result-ok", "http-result"),
                out.stream().map(Inlay::getStyleClass).toList());
        assertEquals(base, marks.inlays(0, base, 5), "a line without a result keeps its own");
        assertNull(marks.inlays(0, null, 5));
    }

    @Test
    void aClickRunsTheResultsAction() {
        HttpResultMarks marks = new HttpResultMarks();
        List<String> clicked = new ArrayList<>();
        marks.set(
                List.of(new HttpLineResult(1, "GET /a", List.of(), null, null, () -> clicked.add("a"))),
                lines("### a", "GET /a"));
        assertTrue(marks.activate(1));
        assertFalse(marks.activate(0));
        assertEquals(List.of("a"), clicked);
    }
}
