package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of moving around inside a Map card: Go to Line's input, a name's uses, the nearest declaration. */
class MapCardMovesTest {

    @Test
    void goToLineTakesALineOrLineColumnClampedToTheCardsLines() {
        assertArrayEquals(new int[] {41, 0}, MapCardMoves.parseLine("42", 0, 99));
        assertArrayEquals(new int[] {41, 6}, MapCardMoves.parseLine(" 42 : 7 ", 0, 99));
        assertArrayEquals(new int[] {41, 0}, MapCardMoves.parseLine("42:", 0, 99), "an empty column is the start");
        assertArrayEquals(new int[] {41, 0}, MapCardMoves.parseLine("42:-3", 0, 99), "a negative column is 0");
        assertArrayEquals(new int[] {99, 0}, MapCardMoves.parseLine("500", 0, 99), "past the last line: the last");
        assertArrayEquals(new int[] {10, 0}, MapCardMoves.parseLine("3", 10, 20), "before a declaration: its first");
        assertArrayEquals(new int[] {10, 0}, MapCardMoves.parseLine("0", 10, 20));
        assertNull(MapCardMoves.parseLine("abc", 0, 99));
        assertNull(MapCardMoves.parseLine("4:x", 0, 99));
        assertNull(MapCardMoves.parseLine("", 0, 99));
        assertNull(MapCardMoves.parseLine("4", 5, 4), "no lines");
    }

    @Test
    void theUsesOfTheNameAtTheCaretAreItsWholeWordUses() {
        String text = "int total = 0;\ntotal += totals;\nreturn total;\n";
        List<int[]> uses = MapCardMoves.usesOf(text, 6, 6, 6, "java", at -> true, 100);
        assertEquals(3, uses.size(), "totals is another name");
        assertArrayEquals(new int[] {4, 9, 0}, uses.get(0));
        assertArrayEquals(new int[] {15, 20, 0}, uses.get(1));
        assertEquals(
                2, MapCardMoves.usesOf(text, 6, 6, 6, "java", at -> true, 2).size(), "at most max");
        assertEquals(
                3, MapCardMoves.usesOf(text, 4, 4, 9, "java", at -> true, 100).size(), "the name selected");
        assertTrue(MapCardMoves.usesOf(text, 4, 0, 9, "java", at -> true, 100).isEmpty(), "more than the name");
        assertTrue(MapCardMoves.usesOf(text, 6, 6, 6, "java", at -> false, 100).isEmpty(), "a keyword");
        assertTrue(MapCardMoves.usesOf(text, 3, 3, 3, "java", at -> true, 100).isEmpty(), "int is used once");
        assertTrue(
                MapCardMoves.usesOf(text, 12, 12, 12, "java", at -> true, 100).isEmpty(), "a number");
        assertTrue(
                MapCardMoves.usesOf(text, 11, 11, 11, "java", at -> true, 100).isEmpty(), "not on a word");
        assertEquals(
                2,
                MapCardMoves.usesOf("$el.x; $el.y", 1, 1, 1, "javascript", at -> true, 100)
                        .size(),
                "JavaScript's $ is part of the name");
    }

    @Test
    void onlyKeywordsConstantsAndNumbersAreNotNames() {
        assertTrue(MapCardMoves.isName(null));
        assertTrue(MapCardMoves.isName(List.of()));
        assertTrue(MapCardMoves.isName(Set.of("type")));
        assertTrue(MapCardMoves.isName(List.of("comment")));
        assertFalse(MapCardMoves.isName(List.of("keyword")));
        assertFalse(MapCardMoves.isName(List.of("search", "constant")));
        assertFalse(MapCardMoves.isName(List.of("number")));
    }

    @Test
    void definitionsMoveByTheColumnZeroRuleOrPythonsDefs() {
        String c = "int a() {\n  return 1;\n}\nint b() {\n  return 2;\n}\n";
        int inB = c.indexOf("return 2");
        assertEquals(c.indexOf("int b"), MapCardMoves.defun(c, inB, false, false));
        assertEquals(c.length(), MapCardMoves.defun(c, inB, false, true));
        String python = "def a():\n    return 1\n\ndef b():\n    return 2\n";
        int inA = python.indexOf("return 1");
        assertEquals(0, MapCardMoves.defun(python, inA, true, false));
        assertTrue(MapCardMoves.defun(python, inA, true, true) > inA);
    }

    @Test
    void theNearestDeclarationIsByTheLineItsNameIsOn() {
        Path file = Path.of("Big.java");
        ProjectMapStructure.Node type = node(file, "Big", 8);
        ProjectMapStructure.Node first = node(file, "m0(int)", 10);
        ProjectMapStructure.Node second = node(file, "m1(int)", 20);
        List<ProjectMapStructure.Node> all = List.of(type, second, first); // depth-first, not by line
        assertSame(first, MapCards.declarationFrom(all, 12, -1));
        assertSame(second, MapCards.declarationFrom(all, 12, 1));
        assertSame(type, MapCards.declarationFrom(all, 10, -1), "from a declaration's own line: the one before");
        assertSame(second, MapCards.declarationFrom(all, 10, 1));
        assertNull(MapCards.declarationFrom(all, 8, -1));
        assertNull(MapCards.declarationFrom(all, 20, 1));
        assertSame(type, MapCards.declarationFrom(all, 0, 1));
        assertNull(MapCards.declarationFrom(List.of(), 5, 1));
    }

    private static ProjectMapStructure.Node node(Path file, String label, int line) {
        return new ProjectMapStructure.Node(
                file.resolve(label),
                new ProjectMapModel.Symbol(file, label, "method", line, line, line + 5, null, false),
                List.of());
    }
}
