package com.editora.terminal;

import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A rectangular selection: the same columns on every row, a newline after each. */
class BlockSelectionTest {

    private static TerminalTextBuffer bufferWith(String... rows) {
        TerminalTextBuffer buffer = new TerminalTextBuffer(40, 6, new StyleState());
        for (int i = 0; i < rows.length; i++) buffer.writeString(0, i + 1, new CharBuffer(rows[i]));
        return buffer;
    }

    private static String text(TerminalTextBuffer buffer, int anchorColumn, int anchorRow, int row, int boundary) {
        TerminalSelection block = SelectionGeometry.blockDrag(anchorColumn, anchorRow, row, boundary);
        buffer.lock();
        try {
            return BlockSelection.text(block, buffer);
        } finally {
            buffer.unlock();
        }
    }

    @Test
    void aColumnOfAListingComesOutAsALineAPiece() {
        TerminalTextBuffer buffer =
                bufferWith("-rw-r--r-- alpha   12", "-rw-r--r-- bravo  340", "drwxr-xr-x charlie  4");
        // Press on the "a" of alpha, release after the "e" of charlie two rows down.
        assertEquals("alpha\nbravo\ncharlie", text(buffer, 11, 0, 2, 18));
    }

    @Test
    void aBackwardsDragIsTheSameBlock() {
        TerminalTextBuffer buffer =
                bufferWith("-rw-r--r-- alpha   12", "-rw-r--r-- bravo  340", "drwxr-xr-x charlie  4");
        assertEquals(text(buffer, 11, 0, 2, 18), text(buffer, 17, 2, 0, 11));
    }

    @Test
    void theAnchorCellIsInWhicheverWayTheDragWent() {
        TerminalSelection left = SelectionGeometry.blockDrag(5, 0, 1, 2);
        assertArrayEquals(new int[] {2, 6}, SelectionGeometry.blockOnRow(left, 0));
        TerminalSelection right = SelectionGeometry.blockDrag(5, 0, 1, 8);
        assertArrayEquals(new int[] {5, 8}, SelectionGeometry.blockOnRow(right, 1));
    }

    @Test
    void everyRowOfTheBlockHasTheSameSpanAndNoOtherRowHasOne() {
        TerminalSelection block = SelectionGeometry.blockDrag(3, 1, 3, 7);
        for (int row = 1; row <= 3; row++)
            assertArrayEquals(new int[] {3, 7}, SelectionGeometry.blockOnRow(block, row));
        assertNull(SelectionGeometry.blockOnRow(block, 0));
        assertNull(SelectionGeometry.blockOnRow(block, 4));
    }

    @Test
    void aShortRowContributesAnEmptyLineNotTheNextRowsText() {
        TerminalTextBuffer buffer = bufferWith("abcdefgh", "ab", "abcdefgh");
        assertEquals("cde\n\ncde", text(buffer, 2, 0, 2, 5));
    }

    @Test
    void trailingBlanksAreDropped() {
        assertEquals("ab", BlockSelection.cut("ab      x", 0, 6));
    }

    @Test
    void aWideGlyphIsCopiedOnceAndItsPlaceholderNot() {
        // 日 takes two cells: the glyph and JediTerm's placeholder after it.
        String line = "a日" + CharUtils.DWC + "b";
        assertEquals("a日b", BlockSelection.cut(line, 0, 4));
        assertFalse(BlockSelection.cut(line, 0, 4).indexOf(CharUtils.DWC) >= 0);
    }

    @Test
    void columnsAreColumnsWhereAnAstralIconSitsToTheLeft() {
        // The icon is one column and two slots; column 2 is the "n", not the "a".
        String line = "󰀀 name";
        assertEquals("name", BlockSelection.cut(line, 2, 6));
    }
}
