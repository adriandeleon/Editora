package com.editora.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentReadWindowTest {
    @Test
    void paginationCanReconstructAFileWithoutMissingBoundaryLines() {
        String source = String.join(
                "\n",
                java.util.stream.IntStream.rangeClosed(1, 205)
                        .mapToObj(i -> "line" + i)
                        .toList());
        var first = AgentReadWindow.read(source, 1, 100);
        assertEquals(205, first.totalLines());
        assertEquals(101, first.nextLine());
        var second = AgentReadWindow.read(source, first.nextLine(), 100);
        var last = AgentReadWindow.read(source, second.nextLine(), 100);
        assertEquals(source, first.text() + "\n" + second.text() + "\n" + last.text());
        assertNull(last.nextLine());
        assertFalse(last.truncated());
    }

    @Test
    void sizeBudgetKeepsWholeLinesAndExplicitlyMarksOversizedSingleLines() {
        var preview = AgentReadWindow.read("a".repeat(3500) + "\n" + "b".repeat(3500), 1, 100);
        assertEquals(1, preview.endLine());
        assertEquals(2, preview.nextLine());
        assertTrue(preview.truncated());
        assertFalse(preview.longLineTruncated());
        var huge = AgentReadWindow.read("x".repeat(8000), 1, 100);
        assertTrue(huge.longLineTruncated());
        assertTrue(huge.truncated());
        assertTrue(huge.text().length() <= 6100);
        assertNull(AgentReadWindow.read("", 1, 100).nextLine());
        assertEquals("", AgentReadWindow.read("one", 100, 100).text());
    }
}
