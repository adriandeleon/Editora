package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmacsNavigationTest {

    @Test
    void movesAcrossCharactersAndLineBoundaries() {
        String text = "abc\ndef";

        assertEquals(1, EmacsNavigation.backwardChar(text, 2));
        assertEquals(3, EmacsNavigation.forwardChar(text, 2));
        assertEquals(0, EmacsNavigation.lineStart(text, 2));
        assertEquals(3, EmacsNavigation.lineEnd(text, 2));
        assertEquals(4, EmacsNavigation.lineStart(text, 5));
        assertEquals(7, EmacsNavigation.lineEnd(text, 5));
    }

    @Test
    void preservesGoalColumnAcrossVerticalMotion() {
        String text = "12345\nx\nabcdef";

        EmacsNavigation.CaretMove firstMove = EmacsNavigation.nextLine(text, 4, null);
        assertEquals(7, firstMove.caretPosition());
        assertEquals(4, firstMove.goalColumn());

        EmacsNavigation.CaretMove secondMove = EmacsNavigation.nextLine(text, firstMove.caretPosition(), firstMove.goalColumn());
        assertEquals(12, secondMove.caretPosition());
        assertEquals(4, secondMove.goalColumn());

        EmacsNavigation.CaretMove thirdMove = EmacsNavigation.previousLine(text, secondMove.caretPosition(), secondMove.goalColumn());
        assertEquals(7, thirdMove.caretPosition());
        assertEquals(4, thirdMove.goalColumn());
    }

    @Test
    void movesByWordAcrossTypicalCodePunctuation() {
        String text = "foo_bar + baz";

        assertEquals(7, EmacsNavigation.forwardWord(text, 0));
        assertEquals(13, EmacsNavigation.forwardWord(text, 7));
        assertEquals(10, EmacsNavigation.backwardWord(text, 13));
        assertEquals(0, EmacsNavigation.backwardWord(text, 7));
    }
}

