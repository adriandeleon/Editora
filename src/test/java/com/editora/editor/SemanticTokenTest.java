package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which lines a semantic-tokens response can restyle. */
class SemanticTokenTest {

    private static final List<SemanticToken> VIEWPORT =
            List.of(new SemanticToken(40, 0, 3, "sem-type"), new SemanticToken(41, 4, 5, "sem-function"));

    /** {@code changedLines} with the painted overlay intact (no edit since the tokens were anchored). */
    private static int[] intact(List<SemanticToken> current, List<SemanticToken> next) {
        return SemanticToken.changedLines(current, next, -1, -1, 0);
    }

    /** A token on every line of a {@code lines}-line file — what a full-document response looks like. */
    private static List<SemanticToken> everyLine(int lines) {
        List<SemanticToken> tokens = new ArrayList<>();
        for (int line = 0; line < lines; line++) {
            tokens.add(new SemanticToken(line, 0, 3, line % 2 == 0 ? "sem-type" : "sem-variable"));
        }
        return tokens;
    }

    @Test
    void anIdenticalResponseRestylesNothing() {
        List<SemanticToken> again =
                List.of(new SemanticToken(40, 0, 3, "sem-type"), new SemanticToken(41, 4, 5, "sem-function"));
        assertNull(intact(VIEWPORT, again));
        assertNull(intact(List.of(), List.of()));
    }

    @Test
    void anIdenticalResponseIsStillPaintedWhereTheOverlayWasErased() {
        // The text was edited since: the pass for that edit dropped the overlay there, so it has to come
        // back — on the lines it was dropped from, not on every line the tokens cover.
        assertArrayEquals(new int[] {41, 41}, SemanticToken.changedLines(VIEWPORT, List.copyOf(VIEWPORT), 41, 41, 0));
        assertArrayEquals(new int[] {40, 41}, SemanticToken.changedLines(VIEWPORT, List.copyOf(VIEWPORT), 0, 99, 0));
        assertNull(
                SemanticToken.changedLines(VIEWPORT, List.copyOf(VIEWPORT), 10, 12, 0),
                "an edit on lines without tokens leaves nothing to repaint");
    }

    @Test
    void aDifferentResponseRestylesTheLinesOfBothLists() {
        List<SemanticToken> scrolledDown = List.of(new SemanticToken(55, 0, 3, "sem-type"));
        assertArrayEquals(new int[] {40, 55}, intact(VIEWPORT, scrolledDown), "old tokens must clear");
        List<SemanticToken> scrolledUp = List.of(new SemanticToken(12, 0, 3, "sem-type"));
        assertArrayEquals(new int[] {12, 41}, intact(VIEWPORT, scrolledUp));
        assertArrayEquals(new int[] {40, 41}, intact(VIEWPORT, List.of()), "tokens went away");
        assertArrayEquals(new int[] {40, 41}, intact(List.of(), VIEWPORT), "tokens arrived");
    }

    @Test
    void sharedTokensAtBothEndsBoundTheRange() {
        List<SemanticToken> before = everyLine(1000);
        List<SemanticToken> after = new ArrayList<>(before);
        after.set(500, new SemanticToken(500, 0, 3, "sem-parameter"));
        assertArrayEquals(new int[] {500, 500}, intact(before, after), "one changed token, one line");
        after.add(501, new SemanticToken(500, 6, 2, "sem-type"));
        assertArrayEquals(new int[] {500, 500}, intact(before, after), "a token added on the same line");
        after.remove(700);
        assertArrayEquals(new int[] {500, 699}, intact(before, after), "two changes span the lines between");
    }

    @Test
    void typingOnOneLineOfAFullyTokenizedFileRestylesThatLine() {
        // The reply to a keystroke on line 500 carries the same tokens everywhere else; the old ones are
        // still painted, so only the edited line — where the lexical pass dropped the overlay — is redone.
        List<SemanticToken> before = everyLine(1000);
        List<SemanticToken> after = new ArrayList<>(before);
        after.set(500, new SemanticToken(500, 1, 3, "sem-type"));
        assertArrayEquals(new int[] {500, 500}, SemanticToken.changedLines(before, after, 500, 500, 0));
    }

    @Test
    void tokensBelowAnInsertedLineAreRecognisedThroughTheShift() {
        // A line inserted at 500: every token from there on is reported one line lower, and is painted one
        // line lower already, because the styles moved with the text.
        List<SemanticToken> before = everyLine(1000);
        List<SemanticToken> after = new ArrayList<>();
        for (SemanticToken t : before) {
            after.add(t.line() < 500 ? t : new SemanticToken(t.line() + 1, t.startChar(), t.length(), t.cssClasses()));
        }
        assertNull(SemanticToken.changedLines(before, after, 500, 500, 1), "the new line has no token");
        after.add(500, new SemanticToken(500, 2, 4, "sem-function"));
        assertArrayEquals(new int[] {500, 500}, SemanticToken.changedLines(before, after, 500, 500, 1));

        // Two lines deleted at 300 (old lines 300 and 301 are gone).
        List<SemanticToken> shrunk = new ArrayList<>();
        for (SemanticToken t : before) {
            if (t.line() < 300) {
                shrunk.add(t);
            } else if (t.line() > 301) {
                shrunk.add(new SemanticToken(t.line() - 2, t.startChar(), t.length(), t.cssClasses()));
            }
        }
        // The edit point is line 300 of the new text; its token (old line 302's) sits on the erased line.
        assertArrayEquals(new int[] {300, 300}, SemanticToken.changedLines(before, shrunk, 300, 300, -2));
    }

    @Test
    void aChangeBelowTheEditIsStillFound() {
        List<SemanticToken> before = everyLine(1000);
        List<SemanticToken> after = new ArrayList<>();
        for (SemanticToken t : before) {
            after.add(t.line() < 500 ? t : new SemanticToken(t.line() + 1, t.startChar(), t.length(), t.cssClasses()));
        }
        after.set(800, new SemanticToken(801, 0, 3, "sem-deprecated")); // old line 800, now 801, reclassified
        // One range is returned, so it runs from just below the edit to the change — not to the end.
        assertArrayEquals(new int[] {501, 801}, SemanticToken.changedLines(before, after, 500, 500, 1));
    }

    @Test
    void theRangeIsTheHullEvenIfTheListIsNotSorted() {
        List<SemanticToken> unsorted = List.of(
                new SemanticToken(9, 0, 1, "a"), new SemanticToken(2, 0, 1, "a"), new SemanticToken(5, 0, 1, "a"));
        assertArrayEquals(new int[] {2, 9}, intact(List.of(), unsorted));
    }
}
