package com.editora.ui;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a document is back to its baseline, read from the span its edits touched only (PERF-08, Pauta 6.0.1). */
class EditedSpanTest {

    /** A document under edit, counting what the comparison reads of it. */
    private static final class Doc {
        final StringBuilder text;
        final EditedSpan span = new EditedSpan();
        long read;

        Doc(String text) {
            this.text = new StringBuilder(text);
        }

        void replace(int start, int end, String with) {
            text.replace(start, end, with);
            span.edited(start, with.length(), text.length());
        }

        boolean equals(String baseline) {
            return span.equalsBaseline(baseline, text.length(), (s, e) -> {
                read += e - s;
                return text.substring(s, e);
            });
        }
    }

    @Test
    void typingAndUndoingInALargeDocumentReadsTheEditedSpanOnly() {
        String base = "line\n".repeat(200_000);
        Doc d = new Doc(base);
        assertTrue(d.equals(base), "nothing edited");
        d.replace(500_000, 500_000, "x");
        assertFalse(d.equals(base), "a different length answers without reading");
        assertEquals(0, d.read);
        d.replace(500_000, 500_001, "");
        assertTrue(d.equals(base), "typed and deleted again");
        d.replace(500_000, 500_001, "L"); // same length, other text
        assertFalse(d.equals(base));
        assertTrue(d.read < 10, "read " + d.read + " characters, not the document");
    }

    @Test
    void resetMakesTheDocumentItsBaselineAgain() {
        Doc d = new Doc("abc");
        d.replace(1, 2, "X");
        assertFalse(d.equals("abc"));
        d.span.reset();
        assertTrue(d.span.untouched());
        assertTrue(d.equals("aXc"));
        assertFalse(d.equals(null));
    }

    @Test
    void agreesWithAWholeComparisonOverRandomEdits() {
        Random random = new Random(8);
        for (int round = 0; round < 300; round++) {
            String base = randomText(random, random.nextInt(40));
            Doc d = new Doc(base);
            for (int edit = 0; edit < 6; edit++) {
                int start = random.nextInt(d.text.length() + 1);
                int end = start + random.nextInt(d.text.length() - start + 1);
                String with = random.nextInt(3) == 0
                        ? base.substring(Math.min(start, base.length()), Math.min(end, base.length()))
                        : randomText(random, random.nextInt(4));
                d.replace(start, end, with);
                assertEquals(d.text.toString().equals(base), d.equals(base), "round " + round + " edit " + edit);
            }
        }
    }

    private static String randomText(Random random, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + random.nextInt(2)));
        }
        return sb.toString();
    }
}
