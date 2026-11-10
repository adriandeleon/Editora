package com.editora.ui;

import java.util.List;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pure rules behind the evaluate fields: caret words, history keys and sharing, a path below an expression. */
class DebugEvaluateTextTest {

    @Test
    void wordMotionIsEmacsForwardAndBackwardWord() {
        String text = "list.get(index) + 1";
        assertEquals(4, CodeFieldKeymap.wordForward(text, 0), "to the end of `list`");
        assertEquals(8, CodeFieldKeymap.wordForward(text, 4), "over the dot, to the end of `get`");
        assertEquals(text.length(), CodeFieldKeymap.wordForward(text, 16));
        assertEquals(9, CodeFieldKeymap.wordBackward(text, 15), "back to the start of `index`");
        assertEquals(0, CodeFieldKeymap.wordBackward(text, 3));
        assertEquals(0, CodeFieldKeymap.wordBackward("", 5));
    }

    @Test
    void killLineTakesTheRestOfTheLineThenItsBreak() {
        String text = "int a = 1;\nreturn a;";
        assertEquals(10, CodeFieldKeymap.killLineEnd(text, 4));
        assertEquals(11, CodeFieldKeymap.killLineEnd(text, 10), "at the end of a line: the line break");
        assertEquals(text.length(), CodeFieldKeymap.killLineEnd(text, text.length()));
    }

    @Test
    void theHistoryKeysOfAOneLineField() {
        assertEquals(Boolean.TRUE, DebugPanelText.historyKey(KeyCode.UP, false, false, false, false, null));
        assertEquals(Boolean.FALSE, DebugPanelText.historyKey(KeyCode.DOWN, false, false, false, false, null));
        assertEquals(Boolean.TRUE, DebugPanelText.historyKey(KeyCode.P, false, true, false, false, null), "M-p");
        assertEquals(Boolean.FALSE, DebugPanelText.historyKey(KeyCode.N, false, true, false, false, null), "M-n");
        assertEquals(
                Boolean.TRUE,
                DebugPanelText.historyKey(KeyCode.P, true, false, false, false, "nav.lineUp"),
                "Emacs C-p: the keymap's line up");
        assertEquals(Boolean.FALSE, DebugPanelText.historyKey(KeyCode.N, true, false, false, false, "nav.lineDown"));
        assertNull(DebugPanelText.historyKey(KeyCode.P, false, false, false, false, null), "typing a p");
        assertNull(DebugPanelText.historyKey(KeyCode.UP, false, false, false, true, null), "Shift+Up selects");
    }

    @Test
    void historiesOverOneListKeepTheirOwnPlace() {
        DebugPanelText.History shared = new DebugPanelText.History();
        shared.add("a");
        DebugPanelText.History field = new DebugPanelText.History(shared);
        DebugPanelText.History window = new DebugPanelText.History(shared);
        window.add("b");
        assertEquals(List.of("a", "b"), field.entries(), "one list");
        assertEquals("b", field.up("typing"));
        assertEquals("a", field.up("b"));
        assertEquals("b", window.up(""), "the other field starts from the newest");
        assertEquals("b", field.down());
        assertEquals("typing", field.down());
        field.add("a");
        assertEquals(List.of("b", "a"), shared.entries(), "evaluated again: the newest, listed once");
    }

    @Test
    void aPathBelowAnExpressionKeepsTheExpressionWhole() {
        assertEquals("obj.items", DebugPanelText.pathRoot("obj.items"));
        assertEquals("arr[3].x", DebugPanelText.pathRoot("arr[3].x"));
        assertEquals("(a + b)", DebugPanelText.pathRoot("a + b"));
        assertEquals("(list.get(0))", DebugPanelText.pathRoot("list.get(0)"));
        assertEquals("", DebugPanelText.pathRoot(" "));
    }
}
