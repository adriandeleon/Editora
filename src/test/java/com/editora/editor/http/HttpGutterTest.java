package com.editora.editor.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The .http Run gutter on its own: what a ▶ and the running ■ say and do, and how the ■ follows its request. */
class HttpGutterTest {

    private static final Map<Integer, String> TEXT = Map.of(0, "GET http://a.test/", 4, "POST http://b.test/");

    @Test
    void aPlayGlyphRunsItsRequestAndTheStopGlyphCancels() {
        HttpGutter g = new HttpGutter();
        List<String> calls = new ArrayList<>();
        g.setHooks(new HttpHooks(
                line -> calls.add("run " + line), null, () -> calls.add("cancel"), () -> "Run (C-M-r)", null, null));
        g.setRequestLines(List.of(0, 4), TEXT::get);
        assertEquals("Run (C-M-r)", g.tooltip(4));
        assertNull(g.tooltip(2), "no glyph there");
        assertArrayEquals(new int[] {-1, 4}, g.setRunning(1, TEXT::get));
        assertEquals(tr("editmenu.cancelHttpRequest.tip"), g.tooltip(4));
        g.click(4);
        g.click(0);
        assertEquals(List.of("cancel", "run 0"), calls);
    }

    @Test
    void theStopGlyphFollowsItsRequestWhenLinesMove() {
        HttpGutter g = new HttpGutter();
        g.setRequestLines(List.of(0, 4), TEXT::get);
        g.setRunning(1, TEXT::get);
        Map<Integer, String> moved = Map.of(1, "GET http://a.test/", 5, "POST http://b.test/");
        g.setRequestLines(List.of(1, 5), moved::get);
        assertEquals(5, g.runningLine(), "a line inserted above: the ■ moved with its request");
        Map<Integer, String> edited = Map.of(1, "GET http://a.test/", 5, "PUT http://b.test/");
        g.setRequestLines(List.of(1, 6), edited::get);
        assertEquals(-1, g.runningLine(), "its line was edited: no request is marked rather than the wrong one");
    }

    @Test
    void noHooksMeansNothingHappens() {
        HttpGutter g = new HttpGutter();
        g.setHooks(null);
        g.setRequestLines(List.of(0), TEXT::get);
        g.click(0);
        assertNull(g.tooltip(0));
    }

    @Test
    void theStopGlyphFollowsItsOwnRequestNotAnotherToTheSameUrl() {
        // X5: two requests to the same URL with different headers; the second runs, a request is inserted on top.
        HttpGutter g = new HttpGutter();
        Map<Integer, String> before = Map.of(
                0, "GET http://a.test/slow", 1, "X-Which: first", 3, "GET http://a.test/slow", 4, "X-Which: second");
        g.setRequestLines(List.of(0, 3), before::get);
        g.setRunning(1, before::get);
        Map<Integer, String> after = Map.of(
                0,
                "GET http://a.test/top",
                3,
                "GET http://a.test/slow",
                4,
                "X-Which: first",
                6,
                "GET http://a.test/slow",
                7,
                "X-Which: second");
        g.setRequestLines(List.of(0, 3, 6), after::get);
        assertEquals(6, g.runningLine(), "the ■ stays on the second request, not the nearest same-URL one");
    }

    @Test
    void aFoldedRequestDrawsItsGlyphOnTheLineThatShows() {
        // X14: Fold All hid every ▶; a folded request's glyph moves up to its ### header.
        HttpGutter g = new HttpGutter();
        g.setRequestLines(List.of(1, 5), Map.of(1, "GET http://a.test/", 5, "POST http://b.test/")::get);
        java.util.Set<Integer> hidden = java.util.Set.of(1, 2, 3, 5, 6);
        java.util.function.IntPredicate folded = hidden::contains;
        assertEquals(0, HttpGutter.glyphLine(1, folded));
        assertEquals(4, HttpGutter.glyphLine(5, folded));
        assertEquals(true, g.isGlyphLine(0, folded));
        assertEquals(true, g.isGlyphLine(4, folded));
        assertEquals(false, g.isGlyphLine(1, folded), "a hidden line draws nothing");
        assertEquals(5, g.requestLineAt(4, folded));
        List<String> calls = new ArrayList<>();
        g.setHooks(new HttpHooks(
                line -> calls.add("run " + line), null, () -> calls.add("cancel"), () -> "Run", null, null));
        g.click(4, folded);
        assertEquals(List.of("run 5"), calls, "a click on the header's ▶ runs the folded request");
        g.setRunning(1, Map.of(1, "GET http://a.test/", 5, "POST http://b.test/")::get);
        assertEquals(4, g.runningGlyphLine(folded), "the ■ of a folded request shows on its header too");
        assertEquals(tr("editmenu.cancelHttpRequest.tip"), g.tooltip(4, folded));
        assertEquals(false, g.isGlyphLine(4, l -> false), "unfolded: the header line has no glyph of its own");
    }
}
