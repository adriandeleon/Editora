package com.editora.terminal;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Word selection over JediTerm's separator helpers.
 *
 * <p>These exist because {@code getNextSeparator} returns the last character <em>of</em> the word
 * while a selection's end is exclusive — so the obvious wiring silently drops the final character,
 * which reads as "selection works" right up until someone double-clicks a word and pastes it.
 */
class WordSelectionTest {

    private TerminalTextBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new TerminalTextBuffer(40, 4, new StyleState());
        buffer.writeString(0, 1, new CharBuffer("hotel india juliett"));
    }

    private String wordAt(int column) {
        Point at = new Point(column, 0);
        TerminalSelection selection = TerminalView.wordSelection(at, buffer);
        return SelectionUtil.getSelectionText(selection, buffer);
    }

    @ParameterizedTest(name = "column {0} selects \"{1}\"")
    @CsvSource({
        "0, hotel", // first character of the first word
        "4, hotel", // last character of that word — the off-by-one case
        "6, india",
        "7, india",
        "10, india", // last character of a middle word
        "12, juliett",
        "18, juliett", // last character of the last word
    })
    void doubleClickSelectsTheWholeWord(int column, String expected) {
        assertEquals(expected, wordAt(column));
    }

    @Test
    void selectionIsWholeWordNotJustToTheCaret() {
        // The failure this guards against returned "indi" for a click inside "india".
        assertEquals("india", wordAt(8));
    }
}
