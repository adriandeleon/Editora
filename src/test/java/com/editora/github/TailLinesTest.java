package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailLinesTest {

    @Test
    void keepsEverythingUnderTheLimit() {
        TailLines tail = new TailLines(3);
        tail.add("a");
        tail.add("b");
        tail.add("c");

        assertEquals(List.of("a", "b", "c"), tail.lines());
        assertFalse(tail.truncated());
    }

    /** G3: what survives is the end of the stream — where a failed log's failure is. */
    @Test
    void keepsTheLastLinesOfALongStream() {
        TailLines tail = new TailLines(3);
        for (int i = 1; i <= 100_000; i++) {
            tail.add("line " + i);
        }

        assertEquals(List.of("line 99998", "line 99999", "line 100000"), tail.lines());
        assertTrue(tail.truncated());
    }
}
