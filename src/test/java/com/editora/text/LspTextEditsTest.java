package com.editora.text;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A whole-file format edit that changes nothing is told apart from one that changes something (Bash review B14). */
class LspTextEditsTest {

    private static final String TEXT = "a\n  b\nc\n";

    @Test
    void aWholeDocumentEditOfTheSameTextChangesNothing() {
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 3, 0, TEXT))));
        assertTrue(
                LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 2, 999, "a\n  b\nc"))),
                "an end past the line is its end");
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 2, 1, 3, "b"))));
        assertTrue(LspTextEdits.changesNothing(TEXT, List.of()));
    }

    @Test
    void anyDifferenceIsAChange() {
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(0, 0, 3, 0, "a\n    b\nc\n"))));
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 0, 1, 0, " "))));
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(9, 3, 9, 4, ""))), "outside the text");
        assertFalse(LspTextEdits.changesNothing(TEXT, List.of(new LspTextEdit(1, 3, 1, 1, ""))), "a reversed range");
    }

    /**
     * HTML review 2 B104: the HTML server is told never to add the final newline (its option also put a blank line
     * before every {@code </style>}), and Editora keeps it on the edits instead.
     */
    @Test
    void theFinalNewlineIsKeptOnTheEditReachingTheEnd() {
        String text = "<p>\n x</p>\n";
        List<LspTextEdit> whole = List.of(new LspTextEdit(0, 0, 2, 0, "<p>\n  x\n</p>"));
        assertEquals(
                List.of(new LspTextEdit(0, 0, 2, 0, "<p>\n  x\n</p>\n")),
                LspTextEdits.withFinalNewline(text, whole, true, true));
        assertEquals(whole, LspTextEdits.withFinalNewline(text, whole, false, true), "no newline wanted: as given");
        assertEquals(
                List.of(new LspTextEdit(0, 0, 2, 0, "<p>")),
                LspTextEdits.withFinalNewline(text, List.of(new LspTextEdit(0, 0, 2, 0, "<p>\n\n")), false, true),
                "a trailing break the file must not have is taken off");
        List<LspTextEdit> already = List.of(new LspTextEdit(0, 0, 2, 0, "<p>x</p>\n"));
        assertEquals(already, LspTextEdits.withFinalNewline(text, already, true, true));
        // An edit that deletes up to the end leaves what precedes it, which here ends with a break.
        List<LspTextEdit> deletion = List.of(new LspTextEdit(1, 0, 2, 0, ""));
        assertEquals(deletion, LspTextEdits.withFinalNewline(text, deletion, true, true));
        assertEquals(
                List.of(new LspTextEdit(0, 3, 0, 8, "\n")),
                LspTextEdits.withFinalNewline("<p>x</p>", List.of(new LspTextEdit(0, 3, 0, 8, "")), true, true),
                "an emptying edit after no break gets one");
    }

    /** No edit reaches the end: Format Document adds or takes off the break there; a selection leaves it. */
    @Test
    void withNoEditAtTheEndOneIsAddedForAWholeDocument() {
        List<LspTextEdit> inner = List.of(new LspTextEdit(0, 0, 0, 1, "  "));
        assertEquals(
                List.of(inner.get(0), new LspTextEdit(1, 4, 1, 4, "\n")),
                LspTextEdits.withFinalNewline("x\n<p/>", inner, true, true));
        assertEquals(inner, LspTextEdits.withFinalNewline("x\n<p/>", inner, true, false), "a selection: untouched");
        assertEquals(inner, LspTextEdits.withFinalNewline("x\n<p/>", inner, false, true), "already without");
        assertEquals(
                List.of(inner.get(0), new LspTextEdit(1, 4, 3, 0, "")),
                LspTextEdits.withFinalNewline("x\n<p/>\n\n", inner, false, true));
        assertEquals(inner, LspTextEdits.withFinalNewline("x\n<p/>\n", inner, true, true), "already with");
    }

    /** Nothing to adjust: no edits (a failed or empty answer), or an edit outside the text. */
    @Test
    void noEditsOrAnEditOutsideTheTextAreLeftAsTheyAre() {
        assertEquals(List.of(), LspTextEdits.withFinalNewline("x", List.of(), true, true));
        List<LspTextEdit> outside = List.of(new LspTextEdit(9, 0, 9, 1, "y"));
        assertEquals(outside, LspTextEdits.withFinalNewline("x", outside, true, true));
    }

    /**
     * HTML review 3 A205: under {@code insert_final_newline = true} the HTML server answered an already formatted page
     * with its text less the final line break, then that line break inserted — no change together.
     */
    @Test
    void editsThatChangeNothingTogetherChangeNothing() {
        String page = "<div>\n    <p>ec on</p>\n</div>\n";
        List<LspTextEdit> answer = List.of(
                new LspTextEdit(0, 0, 3, 0, "<div>\n    <p>ec on</p>\n</div>"), new LspTextEdit(3, 0, 3, 0, "\n"));
        assertTrue(LspTextEdits.changesNothing(page, answer));
        assertTrue(LspTextEdits.changesNothing(page, List.of(answer.get(1), answer.get(0))), "in either order");
        assertFalse(LspTextEdits.changesNothing(page, List.of(answer.get(0))), "the line break taken off is a change");
        assertFalse(LspTextEdits.changesNothing(
                page,
                List.of(
                        new LspTextEdit(0, 0, 3, 0, "<div>\n  <p>ec on</p>\n</div>"),
                        new LspTextEdit(3, 0, 3, 0, "\n"))));
        // what the edits leave counts, wherever each one is
        assertTrue(LspTextEdits.changesNothing(
                "ab",
                List.of(
                        new LspTextEdit(0, 0, 0, 2, ""),
                        new LspTextEdit(0, 0, 0, 0, "a"),
                        new LspTextEdit(0, 2, 0, 2, "b"))));
        assertFalse(LspTextEdits.changesNothing(
                "ab",
                List.of(
                        new LspTextEdit(0, 0, 0, 2, ""),
                        new LspTextEdit(0, 2, 0, 2, "a"),
                        new LspTextEdit(0, 0, 0, 0, "b"))));
        assertFalse(
                LspTextEdits.changesNothing(
                        "abc",
                        List.of(
                                new LspTextEdit(0, 0, 0, 2, "ab"),
                                new LspTextEdit(0, 1, 0, 3, "bc"),
                                new LspTextEdit(0, 0, 0, 0, "x"))),
                "overlapping edits are not judged a no-op");
        assertTrue(LspTextEdits.changesNothing(
                "a\r\nb", List.of(new LspTextEdit(0, 0, 0, 1, ""), new LspTextEdit(0, 0, 0, 0, "a"))));
    }
}
