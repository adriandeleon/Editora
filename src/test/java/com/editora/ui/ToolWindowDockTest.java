package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolWindowDockTest {

    private static final List<String> ORDER = List.of("a", "b", "c", "d");

    @Test
    void dropsBeforeTheTarget() {
        assertEquals(List.of("a", "d", "b", "c"), ToolWindowDock.dropOnto(ORDER, "d", "b", false));
    }

    @Test
    void dropsAfterTheTarget() {
        assertEquals(List.of("a", "b", "d", "c"), ToolWindowDock.dropOnto(ORDER, "d", "b", true));
    }

    /**
     * The source is removed before the index is taken. Read off the original list, the target's index is
     * one too high whenever the source sits ahead of it — so a "drop after b" would land past c.
     */
    @Test
    void movingForwardsLandsWhereItWasAimed() {
        assertEquals(List.of("b", "a", "c", "d"), ToolWindowDock.dropOnto(ORDER, "a", "b", true));
        assertEquals(List.of("b", "c", "a", "d"), ToolWindowDock.dropOnto(ORDER, "a", "c", true));
    }

    @Test
    void droppingOnItselfChangesNothing() {
        assertEquals(ORDER, ToolWindowDock.dropOnto(ORDER, "b", "b", true));
    }

    @Test
    void anUnorderedTargetLandsLast() {
        assertEquals(List.of("a", "c", "d", "b"), ToolWindowDock.dropOnto(ORDER, "b", "zzz", false));
    }

    @Test
    void dropAtEndMovesToTheBack() {
        assertEquals(List.of("a", "c", "d", "b"), ToolWindowDock.dropAtEnd(ORDER, "b"));
    }

    @Test
    void dropAtEndOfSomethingAlreadyLastChangesNothing() {
        assertEquals(ORDER, ToolWindowDock.dropAtEnd(ORDER, "d"));
    }

    @Test
    void theSourceListIsNeverMutated() {
        List<String> order = new java.util.ArrayList<>(ORDER);
        ToolWindowDock.dropOnto(order, "d", "a", false);
        ToolWindowDock.dropAtEnd(order, "a");
        assertEquals(ORDER, order, "callers commit the returned list themselves");
    }
}
