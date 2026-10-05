package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordCharsTest {

    @Test
    void combiningMarksBelongToTheWord() {
        assertTrue(WordChars.isMark('́'), "combining acute accent (Mn)");
        assertTrue(WordChars.isMark('ा'), "Devanagari vowel sign aa (Mc)");
        assertTrue(WordChars.isMark('⃝'), "combining enclosing circle (Me)");
        assertTrue(WordChars.isLetterDigitOrMark('́'));
        assertTrue(WordChars.isLetterDigitOrMark('e'));
        assertTrue(WordChars.isLetterDigitOrMark('7'));
    }

    @Test
    void punctuationAndSpaceDoNot() {
        assertFalse(WordChars.isMark('e'));
        assertFalse(WordChars.isMark('´'), "the spacing acute accent is a symbol, not a mark");
        assertFalse(WordChars.isLetterDigitOrMark(' '));
        assertFalse(WordChars.isLetterDigitOrMark('-'));
        assertFalse(WordChars.isLetterDigitOrMark('_'), "each caller decides about the underscore itself");
    }

    @Test
    void theWordCommandsKeepADecomposedAccentWithItsLetter() {
        String text = "café x"; // NFD "café x"
        // M-u from the start upcases the whole word and leaves the caret after the accent, not before it.
        EmacsEdits.Edit up = EmacsEdits.upcaseWord(text, 0);
        assertEquals(5, up.to(), "the word ends after the combining mark");
        assertEquals(5, up.caret());
        // M-DEL from behind the accent takes the letter with it.
        EmacsEdits.Edit back = EmacsEdits.backwardKillWord(text, 5);
        assertEquals(0, back.from());
    }
}
