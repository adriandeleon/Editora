package com.editora.editor.search;

import java.util.Optional;

import com.editora.editor.search.QueryReplace.Match;
import com.editora.editor.search.QueryReplace.Source;
import com.editora.editor.search.QueryReplace.Spec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The query-replace session reads the document only as far as its next match (PERF-08, Pauta 6.2.0). */
class QueryReplaceWindowTest {

    /** A document that counts the characters it hands out. */
    private static final class Counted implements Source {
        final String text;
        long read;

        Counted(String text) {
            this.text = text;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public String text(int start, int end) {
            read += end - start;
            return text.substring(start, end);
        }
    }

    private static Spec spec(String q, String r, boolean regex, boolean wholeWord, boolean caseSensitive) {
        return new Spec(q, r, caseSensitive, regex, wholeWord, false);
    }

    @Test
    void aMatchNearTheOffsetReadsAWindowNotTheDocument() {
        String doc = "x".repeat(2_000_000) + " foo " + "y".repeat(3_000_000);
        int from = 2_000_000;
        for (Spec s : new Spec[] {spec("foo", "bar", false, false, false), spec("f(o+)", "<$1>", true, false, false)}) {
            Counted c = new Counted(doc);
            Match m = QueryReplace.nextIn(c, from, s).orElseThrow();
            assertEquals(from + 1, m.start());
            assertEquals(from + 4, m.end());
            assertTrue(c.read < 100_000, "read " + c.read + " characters for one answer");
        }
    }

    @Test
    void answersWhatTheWholeTextAnswers() {
        String doc = "alpha beta\nfoo Foo food foo_bar\n" + "z".repeat(40_000) + " foo\nend foo";
        Spec[] specs = {
            spec("foo", "X", false, false, false),
            spec("foo", "X", false, true, false),
            spec("Foo", "X", false, false, true),
            spec("f(o)o", "[$1]", true, false, false),
            spec("^end", "E", true, false, false),
            spec("foo$", "F", true, false, false),
            spec("z+", "Z", true, false, false),
            spec("(?<=\\s)foo", "Q", true, false, false),
            spec("nothing", "N", false, false, false),
        };
        for (Spec s : specs) {
            for (int from : new int[] {0, 1, 11, 15, 20, 30, 40_031, 40_034, doc.length() - 3, doc.length()}) {
                Optional<Match> whole = QueryReplace.next(doc, from, s);
                Optional<Match> windowed = QueryReplace.nextIn(new Counted(doc), from, s);
                assertEquals(whole, windowed, s + " from " + from);
            }
        }
    }

    @Test
    void aMatchRunningPastTheFirstWindowIsReadWhole() {
        String doc = "a".repeat(QueryReplace.FIRST_WINDOW * 3) + "b";
        Match m = QueryReplace.nextIn(new Counted(doc), 0, spec("a+b", "-", true, false, false))
                .orElseThrow();
        assertEquals(0, m.start());
        assertEquals(doc.length(), m.end());
    }

    @Test
    void pastTheEndOrAnEmptyQueryFindsNothing() {
        Counted c = new Counted("foo");
        assertTrue(
                QueryReplace.nextIn(c, 4, spec("foo", "x", false, false, false)).isEmpty());
        assertTrue(QueryReplace.nextIn(c, 0, spec("", "x", false, false, false)).isEmpty());
        assertEquals(0, c.read);
    }

    @Test
    void aReplacementNamingAMissingGroupStillFails() {
        assertThrows(
                RuntimeException.class,
                () -> QueryReplace.nextIn(new Counted("foo"), 0, spec("foo", "$3", true, false, false)));
    }

    @Test
    void aPieceTooShortAsksForMoreAndALongerOneGivesTheWholeDocumentsAnswer() {
        String doc = "a".repeat(40_000) + "neeedle" + "b".repeat(40_000);
        Counted whole = new Counted(doc);
        Spec s = spec("ne+dle", "X", true, false, false);
        Match expected = QueryReplace.nextIn(whole, 0, s).orElseThrow();

        QueryReplace.Piece small = QueryReplace.piece(whole, 0, 100);
        assertThrows(QueryReplace.NeedMore.class, () -> QueryReplace.nextIn(small, 0, s), "the match is past it");

        // What the session does: double the piece until the search no longer reads past it.
        int size = 100;
        Optional<Match> found = Optional.empty();
        for (boolean done = false; !done; ) {
            try {
                found = QueryReplace.nextIn(QueryReplace.piece(whole, 0, size), 0, s);
                done = true;
            } catch (QueryReplace.NeedMore more) {
                size *= 2;
            }
        }
        Match m = found.orElseThrow();
        assertEquals(expected, m, "the answer the whole document gives");
        assertEquals(40_000, m.start());
        assertEquals("X", m.replacement());
        assertEquals("neeedle", QueryReplace.piece(whole, 0, size).text(m.start(), m.end()));
    }

    @Test
    void aLookbehindAcrossAPageBoundaryReadsThePreviousPage() {
        int boundary = QueryReplace.PAGE;
        String doc = "x".repeat(boundary - 1) + "ab" + "x".repeat(boundary) + "ab";
        Spec s = spec("(?<=a)b", "B", true, false, false);
        Match m = QueryReplace.nextIn(new Counted(doc), boundary - 4, s).orElseThrow();
        assertEquals(boundary, m.start(), "the b just past the boundary, after the a just before it");
        Match next = QueryReplace.nextIn(new Counted(doc), boundary + 1, s).orElseThrow();
        assertEquals(doc.length() - 1, next.start(), "and the next one two pages on");
    }
}
