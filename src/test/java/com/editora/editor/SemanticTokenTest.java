package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which lines a semantic-tokens response can restyle. */
class SemanticTokenTest {

    private static final List<SemanticToken> VIEWPORT =
            List.of(new SemanticToken(40, 0, 3, "sem-type"), new SemanticToken(41, 4, 5, "sem-function"));

    @Test
    void anIdenticalResponseRestylesNothing() {
        List<SemanticToken> again =
                List.of(new SemanticToken(40, 0, 3, "sem-type"), new SemanticToken(41, 4, 5, "sem-function"));
        assertEquals(Integer.MAX_VALUE, SemanticToken.firstChangedLine(VIEWPORT, again, false));
        assertEquals(Integer.MAX_VALUE, SemanticToken.firstChangedLine(List.of(), List.of(), false));
    }

    @Test
    void anIdenticalResponseIsStillPaintedWhenTheOverlayWasSuppressed() {
        // The text was edited since: the pass for that edit dropped the overlay, so it has to come back.
        assertEquals(40, SemanticToken.firstChangedLine(VIEWPORT, List.copyOf(VIEWPORT), true));
    }

    @Test
    void aDifferentResponseRestylesFromTheEarlierOfTheTwoLists() {
        List<SemanticToken> scrolledDown = List.of(new SemanticToken(55, 0, 3, "sem-type"));
        assertEquals(40, SemanticToken.firstChangedLine(VIEWPORT, scrolledDown, false), "old tokens must clear");
        List<SemanticToken> scrolledUp = List.of(new SemanticToken(12, 0, 3, "sem-type"));
        assertEquals(12, SemanticToken.firstChangedLine(VIEWPORT, scrolledUp, false));
        assertEquals(40, SemanticToken.firstChangedLine(VIEWPORT, List.of(), false), "tokens went away");
        assertEquals(40, SemanticToken.firstChangedLine(List.of(), VIEWPORT, false), "tokens arrived");
    }

    @Test
    void theFirstLineIsTheMinimumEvenIfTheListIsNotSorted() {
        List<SemanticToken> unsorted = List.of(
                new SemanticToken(9, 0, 1, "a"), new SemanticToken(2, 0, 1, "a"), new SemanticToken(5, 0, 1, "a"));
        assertEquals(2, SemanticToken.firstChangedLine(List.of(), unsorted, false));
    }
}
