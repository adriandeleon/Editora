package com.editora.editor;

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
}
