package com.editora.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputCapTest {

    @Test
    void textThatFitsIsReturnedAsIs() {
        assertNull(OutputCap.cap(null));
        String fits = "x".repeat(OutputCap.MAX_CHARS);
        assertSame(fits, OutputCap.cap(fits));
    }

    @Test
    void longerTextKeepsItsHeadAndTailAroundANoteOfTheCut() {
        String head = "H".repeat(OutputCap.HEAD_CHARS);
        String middle = "M".repeat(12_345);
        String tail = "T".repeat(OutputCap.TAIL_CHARS);
        String capped = OutputCap.cap(head + middle + tail);
        assertEquals(head + OutputCap.note(12_345) + tail, capped);
        assertTrue(capped.length() < OutputCap.MAX_CHARS + 200);
    }

    @Test
    void theAccumulatorKeepsWhatFitsAndOtherwiseTheHeadAndTheLastTail() {
        OutputCap.Accumulator small = new OutputCap.Accumulator();
        assertTrue(small.isEmpty());
        small.append("one\n");
        small.append("two\n");
        assertEquals("one\ntwo\n", small.toString());

        OutputCap.Accumulator big = new OutputCap.Accumulator();
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            String line = "line " + i + " of a chatty test\n"; // about 1 MB in all
            big.append(line);
            all.append(line);
        }
        String kept = big.toString();
        String expected = OutputCap.cap(all.toString());
        assertEquals(expected, kept, "streamed in lines or capped whole, the same head, note and tail");
        assertTrue(kept.endsWith("line 39999 of a chatty test\n"));
        assertTrue(kept.startsWith("line 0 of a chatty test\n"));
    }

    @Test
    void anAppendThatStraddlesTheHeadIsSplitBetweenHeadAndTail() {
        OutputCap.Accumulator acc = new OutputCap.Accumulator();
        acc.append("a".repeat(OutputCap.HEAD_CHARS - 1));
        acc.append("bc"); // one char completes the head, one starts the tail: nothing cut
        assertEquals("a".repeat(OutputCap.HEAD_CHARS - 1) + "bc", acc.toString());
    }
}
