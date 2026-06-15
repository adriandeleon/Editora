package com.editora.editor;

import java.util.BitSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellCheckOverlayTest {

    private static BitSet fences(String text) {
        return SpellCheckOverlay.fencedCodeLines(text);
    }

    @Test
    void marksLinesInsideABacktickFence() {
        // line: 0 prose, 1 ```, 2 code, 3 code, 4 ```, 5 prose
        BitSet f = fences("prose\n```bash\n./mvnw javafx:run\nmvn test\n```\nmore prose\n");
        assertFalse(f.get(0));
        assertTrue(f.get(1)); // opening fence
        assertTrue(f.get(2));
        assertTrue(f.get(3));
        assertTrue(f.get(4)); // closing fence
        assertFalse(f.get(5));
    }

    @Test
    void handlesTildeFencesAndIndentation() {
        BitSet f = fences("text\n  ~~~\n  code here\n  ~~~\ntext\n");
        assertFalse(f.get(0));
        assertTrue(f.get(1));
        assertTrue(f.get(2));
        assertTrue(f.get(3));
        assertFalse(f.get(4));
    }

    @Test
    void unterminatedFenceRunsToEnd() {
        BitSet f = fences("intro\n```\ncode\nmore code\n");
        assertFalse(f.get(0));
        assertTrue(f.get(1));
        assertTrue(f.get(2));
        assertTrue(f.get(3));
    }

    @Test
    void multipleFencesAndProseBetween() {
        BitSet f = fences("a\n```\nx\n```\nb\n```\ny\n```\nc\n");
        assertFalse(f.get(0)); // a
        assertTrue(f.get(2)); // x
        assertFalse(f.get(4)); // b
        assertTrue(f.get(6)); // y
        assertFalse(f.get(8)); // c
    }

    @Test
    void noFencesOrEmpty() {
        assertTrue(fences("just\nplain\nprose\n").isEmpty());
        assertTrue(fences("").isEmpty());
        assertTrue(fences(null).isEmpty());
    }
}
