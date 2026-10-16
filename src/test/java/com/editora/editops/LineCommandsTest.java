package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LineCommandsTest {

    /** Applies a single edit and marks its selection: [ ] around a range, | for a bare caret. */
    private static String apply(String text, Indenter.TabEdit e) {
        String after = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        if (e.selStart() == e.selEnd()) {
            return after.substring(0, e.selStart()) + "|" + after.substring(e.selStart());
        }
        return after.substring(0, e.selStart())
                + "["
                + after.substring(e.selStart(), e.selEnd())
                + "]"
                + after.substring(e.selEnd());
    }

    /** Enter as a code buffer would: the line's indentation, one unit more after an opening brace. */
    private static Indenter.EnterEdit enter(String text, int at) {
        int start = LineCommands.lineStart(text, at);
        int i = start;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        String indent = text.substring(start, i) + (at > 0 && text.charAt(at - 1) == '{' ? "  " : "");
        return new Indenter.EnterEdit("\n" + indent, 1 + indent.length());
    }

    // --- insert below / above ---

    @Test
    void insertBelowOpensAnIndentedLineWhereverTheCaretIs() {
        String text = "  foo();\nbar\n";
        assertEquals("  foo();\n  |\nbar\n", apply(text, LineCommands.insertBelow(text, 3, 3, at -> enter(text, at))));
        String block = "if (x) {\n}";
        assertEquals("if (x) {\n  |\n}", apply(block, LineCommands.insertBelow(block, 0, 0, at -> enter(block, at))));
    }

    @Test
    void insertBelowGoesUnderTheLastLineOfTheSelection() {
        String text = "a\nb\nc\n";
        assertEquals("a\nb\n|\nc\n", apply(text, LineCommands.insertBelow(text, 0, 3, at -> enter(text, at))));
        // A selection that stops at the start of line c does not take line c in.
        assertEquals("a\nb\n|\nc\n", apply(text, LineCommands.insertBelow(text, 0, 4, at -> enter(text, at))));
        String last = "a\nlast";
        assertEquals("a\nlast\n|", apply(last, LineCommands.insertBelow(last, 3, 3, at -> enter(last, at))));
    }

    @Test
    void insertAboveTakesTheLinesIndentation() {
        String text = "x\n    two\n";
        assertEquals("x\n    |\n    two\n", apply(text, LineCommands.insertAbove(text, 8, 8)));
        assertEquals("|\nx\n    two\n", apply(text, LineCommands.insertAbove(text, 1, 1)));
    }

    // --- delete ---

    @Test
    void deleteRemovesTheLineAndKeepsTheColumnOnTheNextOne() {
        String text = "alpha\nbeta\ngamma\n";
        assertEquals("alpha\ngam|ma\n", apply(text, LineCommands.deleteLines(text, 9, 9)));
        assertEquals("alpha\nga|mma\n", apply(text, LineCommands.deleteLines(text, 8, 8)), "col 2");
        assertEquals("beta|\ngamma\n", apply(text, LineCommands.deleteLines(text, 5, 5)), "col 5 on a 4-long line");
    }

    @Test
    void deleteTakesEveryLineTheSelectionTouches() {
        String text = "a\nb\nc\nd\n";
        assertEquals("a\n|d\n", apply(text, LineCommands.deleteLines(text, 2, 5)));
        assertEquals("a\n|c\nd\n", apply(text, LineCommands.deleteLines(text, 2, 4)), "ends at c's start");
    }

    @Test
    void deletingTheLastLineTakesTheBreakBeforeIt() {
        String text = "one\ntwo";
        assertEquals("on|e", apply(text, LineCommands.deleteLines(text, 6, 6)));
        assertEquals("|", apply("only", LineCommands.deleteLines("only", 2, 2)));
        assertEquals("|", apply("", LineCommands.deleteLines("", 0, 0)));
        assertEquals("a\n|b", apply("a\nb\n", LineCommands.deleteLines("a\nb\n", 4, 4)), "the empty last line");
    }

    // --- select ---

    @Test
    void selectTakesTheWholeLineThenGrowsByLines() {
        String text = "one\ntwo\nthree";
        Indenter.TabEdit first = LineCommands.selectLine(text, 5, 5);
        assertEquals("one\n[two\n]three", apply(text, first));
        Indenter.TabEdit second = LineCommands.selectLine(text, first.selStart(), first.selEnd());
        assertEquals("one\n[two\nthree]", apply(text, second));
        Indenter.TabEdit third = LineCommands.selectLine(text, second.selStart(), second.selEnd());
        assertEquals("one\n[two\nthree]", apply(text, third), "nothing more to take");
    }

    @Test
    void selectWidensAPartialSelectionToWholeLines() {
        String text = "one\ntwo\nthree";
        assertEquals("[one\ntwo\n]three", apply(text, LineCommands.selectLine(text, 1, 5)));
        assertEquals("[one\n]two\nthree", apply(text, LineCommands.selectLine(text, 1, 4)), "ends at a line start");
    }

    // --- indent / outdent ---

    /** A Tab that adds or removes two leading spaces on each line it is given. */
    private static Indenter.TabEdit twoSpaces(String text, int from, int to, boolean outdent) {
        int start = LineCommands.lineStart(text, from);
        int end = LineCommands.lineEnd(text, to);
        StringBuilder out = new StringBuilder();
        for (String line : text.substring(start, end).split("\n", -1)) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(outdent ? (line.startsWith("  ") ? line.substring(2) : line) : "  " + line);
        }
        String repl = out.toString();
        return new Indenter.TabEdit(start, end, repl, start, start + repl.length());
    }

    @Test
    void indentMovesABareCaretWithItsText() {
        String text = "ab\ncd";
        LineCommands.TabFunction tab = (from, to, back) -> twoSpaces(text, from, to, back);
        assertEquals("ab\n  c|d", apply(text, LineCommands.indentLines(text, 4, 4, false, tab)));
        String indented = "  ab";
        LineCommands.TabFunction tab2 = (from, to, back) -> twoSpaces(indented, from, to, back);
        assertEquals("a|b", apply(indented, LineCommands.indentLines(indented, 3, 3, true, tab2)));
        assertEquals("|ab", apply(indented, LineCommands.indentLines(indented, 1, 1, true, tab2)), "inside the indent");
    }

    @Test
    void indentOfASelectionIsTheBuffersOwnTab() {
        String text = "ab\ncd";
        LineCommands.TabFunction tab = (from, to, back) -> twoSpaces(text, from, to, back);
        assertEquals("[  ab\n  cd]", apply(text, LineCommands.indentLines(text, 1, 4, false, tab)));
        assertNull(LineCommands.indentLines(text, 1, 1, false, (from, to, back) -> null), "the buffer declined");
    }
}
