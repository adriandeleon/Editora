package com.editora.diff;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffTextTest {

    @Test
    void preservesCrLfAndFinalNewlineWhenRecomposing() {
        DiffText text = DiffText.parse("one\r\ntwo\r\n");
        assertEquals(List.of("one", "two"), text.lines());
        assertEquals("\r\n", text.lineSeparator());
        assertTrue(text.finalNewline());
        assertEquals("one\r\nTWO\r\n", text.compose(List.of("one", "TWO")));
    }

    @Test
    void distinguishesTerminatedAndUnterminatedLastLine() {
        assertTrue(DiffText.parse("x\n").finalNewline());
        assertFalse(DiffText.parse("x").finalNewline());
        assertEquals(List.of("x"), DiffText.parse("x\n").lines());
        assertEquals(List.of("x"), DiffText.parse("x").lines());
    }

    @Test
    void keepsInteriorAndTerminalBlankLines() {
        DiffText text = DiffText.parse("a\n\n");
        assertEquals(List.of("a", ""), text.lines());
        assertEquals("a\n\n", text.compose(text.lines()));
    }

    @Test
    void supportsClassicMacSeparatorsAndEmptyDocuments() {
        DiffText text = DiffText.parse("one\rtwo\r");

        assertEquals(List.of("one", "two"), text.lines());
        assertEquals("\r", text.lineSeparator());
        assertTrue(text.finalNewline());
        assertEquals("", DiffText.parse(null).compose(List.of()));
        assertEquals("\n", new DiffText(null, null, false).lineSeparator());
    }
}
