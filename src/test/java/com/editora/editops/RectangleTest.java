package com.editora.editops;

import java.util.List;

import com.editora.editops.Rectangle.Bounds;
import com.editora.editops.Rectangle.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure Emacs rectangle engine (no toolkit). */
class RectangleTest {

    /** Three equal-length lines, so column arithmetic is easy to read. */
    private static final String GRID = "abcdef\nghijkl\nmnopqr";

    /** Applies an edit and returns the resulting whole text. */
    private static String apply(String text, Edit e) {
        return e == null ? text : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    /** The offset of {@code line}:{@code col} in {@code text}. */
    private static int at(String text, int line, int col) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = text.indexOf('\n', off) + 1;
        }
        return off + col;
    }

    private static Bounds box(String text, int topLine, int bottomLine, int leftCol, int rightCol) {
        return Rectangle.bounds(text, at(text, topLine, leftCol), at(text, bottomLine, rightCol));
    }

    // --- geometry --------------------------------------------------------------------------------

    @Test
    void boundsReadsLinesAndColumnsFromTwoOffsets() {
        Bounds b = box(GRID, 0, 2, 2, 4);
        assertEquals(new Bounds(0, 2, 2, 4), b);
        assertEquals(2, b.width());
        assertEquals(3, b.lineCount());
    }

    @Test
    void boundsNormalisesAReversedSelection() {
        // Mark below-right of point, or above-left: the rectangle is the same either way.
        assertEquals(box(GRID, 0, 2, 2, 4), Rectangle.bounds(GRID, at(GRID, 2, 4), at(GRID, 0, 2)));
    }

    @Test
    void boundsTakesTheSmallerAndLargerColumnWhenTheSelectionSlantsBackwards() {
        // Point at line 0 col 4, mark at line 2 col 1 → columns [1,4), not [4,1).
        Bounds b = Rectangle.bounds(GRID, at(GRID, 0, 4), at(GRID, 2, 1));
        assertEquals(new Bounds(0, 2, 1, 4), b);
    }

    @Test
    void boundsClampsOffsetsOutsideTheText() {
        assertEquals(new Bounds(0, 2, 0, 6), Rectangle.bounds(GRID, -50, 9999));
    }

    // --- extract ---------------------------------------------------------------------------------

    @Test
    void extractTakesTheColumnsOfEachLine() {
        assertEquals(List.of("cd", "ij", "op"), Rectangle.extract(GRID, box(GRID, 0, 2, 2, 4)));
    }

    @Test
    void extractPadsLinesTooShortToReachTheRectangle() {
        String text = "abcdef\ngh\nmnopqr";
        assertEquals(
                List.of("cd", "  ", "op"),
                Rectangle.extract(text, box(text, 0, 2, 2, 4)),
                "the shape is preserved so a later yank still lines up");
    }

    // --- delete / kill ---------------------------------------------------------------------------

    @Test
    void deleteRemovesTheColumnsAndClosesTheGap() {
        assertEquals("abef\nghkl\nmnqr", apply(GRID, Rectangle.delete(GRID, box(GRID, 0, 2, 2, 4))));
    }

    @Test
    void deleteLeavesLinesThatDoNotReachTheRectangleAlone() {
        String text = "abcdef\ngh\nmnopqr";
        assertEquals("abef\ngh\nmnqr", apply(text, Rectangle.delete(text, box(text, 0, 2, 2, 4))));
    }

    @Test
    void deleteOfAZeroWidthRectangleIsANoOp() {
        assertNull(Rectangle.delete(GRID, box(GRID, 0, 2, 3, 3)));
    }

    @Test
    void deleteLeavesTheCaretAtTheTopLeftCorner() {
        Edit e = Rectangle.delete(GRID, box(GRID, 0, 2, 2, 4));
        assertEquals(at(GRID, 0, 2), e.caret());
    }

    // --- clear -----------------------------------------------------------------------------------

    @Test
    void clearBlanksTheColumnsKeepingTheWidth() {
        assertEquals("ab  ef\ngh  kl\nmn  qr", apply(GRID, Rectangle.clear(GRID, box(GRID, 0, 2, 2, 4))));
    }

    @Test
    void clearPadsAShortLineOutToTheRectangle() {
        String text = "abcdef\ngh\nmnopqr";
        assertEquals(
                "ab  ef\ngh  \nmn  qr",
                apply(text, Rectangle.clear(text, box(text, 0, 2, 2, 4))),
                "Emacs forces move-to-column, so a short line is extended with spaces");
    }

    // --- open ------------------------------------------------------------------------------------

    @Test
    void openInsertsBlanksAndShiftsTheTextRight() {
        assertEquals("ab  cdef\ngh  ijkl\nmn  opqr", apply(GRID, Rectangle.open(GRID, box(GRID, 0, 2, 2, 4))));
    }

    // --- string-rectangle ------------------------------------------------------------------------

    @Test
    void replaceSwapsEachLinesSegmentForTheString() {
        assertEquals("abXXef\nghXXkl\nmnXXqr", apply(GRID, Rectangle.replace(GRID, box(GRID, 0, 2, 2, 4), "XX")));
    }

    @Test
    void replaceNeedNotMatchTheRectangleWidth() {
        assertEquals("ab-ef\ngh-kl\nmn-qr", apply(GRID, Rectangle.replace(GRID, box(GRID, 0, 2, 2, 4), "-")));
    }

    @Test
    void replaceOnAZeroWidthRectangleInsertsAtThatColumnOnEveryLine() {
        // The prefix-a-block-of-lines idiom: C-x r t with point and mark in the same column.
        assertEquals("ab//cdef\ngh//ijkl\nmn//opqr", apply(GRID, Rectangle.replace(GRID, box(GRID, 0, 2, 2, 2), "//")));
    }

    @Test
    void replaceWithTheTextAlreadyThereIsANoOp() {
        assertNull(
                Rectangle.replace(GRID, box(GRID, 0, 0, 2, 4), "cd"), "line 0 already reads cd across those columns");
    }

    @Test
    void replaceIsNotANoOpWhenOnlySomeLinesAlreadyMatch() {
        assertEquals(
                "abcdef\nghcdkl\nmncdqr",
                apply(GRID, Rectangle.replace(GRID, box(GRID, 0, 2, 2, 4), "cd")),
                "line 0 is unchanged but lines 1 and 2 are not");
    }

    // --- number-lines ----------------------------------------------------------------------------

    @Test
    void numberLinesInsertsConsecutiveNumbersDownTheLeftEdge() {
        assertEquals(
                "1 abcdef\n2 ghijkl\n3 mnopqr", apply(GRID, Rectangle.numberLines(GRID, box(GRID, 0, 2, 0, 0), 1)));
    }

    @Test
    void numberLinesRightAlignsToTheWidestNumber() {
        String text = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk";
        String out = apply(text, Rectangle.numberLines(text, box(text, 0, 10, 0, 0), 1));
        assertEquals(" 1 a", out.split("\n")[0], "single digits are padded to the width of 11");
        assertEquals("11 k", out.split("\n")[10]);
    }

    @Test
    void numberLinesCanStartFromAnyNumber() {
        assertEquals(
                "5 abcdef\n6 ghijkl\n7 mnopqr", apply(GRID, Rectangle.numberLines(GRID, box(GRID, 0, 2, 0, 0), 5)));
    }

    // --- yank ------------------------------------------------------------------------------------

    @Test
    void yankInsertsTheRectangleAtTheCaretPushingTextRight() {
        List<String> rect = List.of("12", "34", "56");
        assertEquals("ab12cdef\ngh34ijkl\nmn56opqr", apply(GRID, Rectangle.yank(GRID, at(GRID, 0, 2), rect)));
    }

    @Test
    void yankExtendsTheDocumentWhenTheRectangleRunsPastTheLastLine() {
        String text = "abcdef";
        assertEquals(
                "ab12cdef\n  34\n  56",
                apply(text, Rectangle.yank(text, at(text, 0, 2), List.of("12", "34", "56"))),
                "new lines are created and padded out to the caret column");
    }

    @Test
    void yankPadsAShortTargetLineOutToTheCaretColumn() {
        String text = "abcdef\ngh\nmnopqr";
        assertEquals(
                "ab12cdef\ngh34\nmn56opqr",
                apply(text, Rectangle.yank(text, at(text, 0, 2), List.of("12", "34", "56"))));
    }

    @Test
    void yankOfNothingIsANoOp() {
        assertNull(Rectangle.yank(GRID, 0, List.of()));
        assertNull(Rectangle.yank(GRID, 0, null));
    }

    // --- round trip ------------------------------------------------------------------------------

    @Test
    void killThenYankElsewhereMovesTheRectangle() {
        Bounds b = box(GRID, 0, 2, 2, 4);
        List<String> killed = Rectangle.extract(GRID, b);
        String afterKill = apply(GRID, Rectangle.delete(GRID, b));
        assertEquals("abef\nghkl\nmnqr", afterKill);
        assertEquals(
                "abefcd\nghklij\nmnqrop",
                apply(afterKill, Rectangle.yank(afterKill, at(afterKill, 0, 4), killed)),
                "the rectangle keeps its shape across the move");
    }
}
