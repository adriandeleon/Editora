package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure tests for {@link AgentCoordinator#slice} — the ACP fs-read line/limit windowing. */
class AgentSliceTest {

    private static final String TEXT = "a\nb\nc\nd";

    @Test
    void noWindowReturnsWholeText() {
        assertEquals(TEXT, AgentCoordinator.slice(TEXT, null, null));
        assertEquals(TEXT, AgentCoordinator.slice(TEXT, 1, null));
    }

    @Test
    void lineIsOneBasedStart() {
        assertEquals("c\nd", AgentCoordinator.slice(TEXT, 3, null));
    }

    @Test
    void limitCapsLineCount() {
        assertEquals("a\nb", AgentCoordinator.slice(TEXT, null, 2));
        assertEquals("b\nc", AgentCoordinator.slice(TEXT, 2, 2));
    }

    @Test
    void outOfRangeClampsAndNullIsEmpty() {
        assertEquals("", AgentCoordinator.slice(TEXT, 99, 5));
        assertEquals("a\nb\nc\nd", AgentCoordinator.slice(TEXT, -3, null));
        assertEquals("", AgentCoordinator.slice(null, 1, 1));
    }
}
