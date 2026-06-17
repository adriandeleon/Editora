package com.editora.todo;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoScannerTest {

    private static List<TodoPatterns.Compiled> defaults() {
        return TodoPatterns.compile(TodoPatterns.defaults());
    }

    @Test
    void findsTodoWithOffsetsLineAndColumn() {
        String text = "first line\n// TODO: fix this\nlast";
        List<TodoMatch> m = TodoScanner.scan(text, defaults());
        assertEquals(1, m.size());
        TodoMatch t = m.get(0);
        assertEquals("TODO", t.patternName());
        assertEquals(2, t.line());
        assertEquals(4, t.col()); // "// " then TODO
        assertEquals(text.indexOf("TODO"), t.start());
        assertEquals(text.indexOf("TODO") + 4, t.end());
        assertEquals("// TODO: fix this", t.lineText());
        assertEquals("#E5C07B", t.color());
    }

    @Test
    void caseInsensitiveByDefault() {
        List<TodoMatch> m = TodoScanner.scan("a todo here", defaults());
        assertEquals(1, m.size());
        assertEquals("TODO", m.get(0).patternName());
    }

    @Test
    void caseSensitivePatternDoesNotMatchLowercase() {
        var compiled = TodoPatterns.compile(List.of(new TodoPattern("TODO", "\\bTODO\\b", "#fff", true, true)));
        assertTrue(TodoScanner.scan("a todo here", compiled).isEmpty());
        assertEquals(1, TodoScanner.scan("a TODO here", compiled).size());
    }

    @Test
    void multiplePatternsAndDocumentOrder() {
        String text = "FIXME then TODO on one line";
        List<TodoMatch> m = TodoScanner.scan(text, defaults());
        assertEquals(2, m.size());
        assertEquals("FIXME", m.get(0).patternName()); // sorted by start offset
        assertEquals("TODO", m.get(1).patternName());
        assertTrue(m.get(0).start() < m.get(1).start());
    }

    @Test
    void handlesCrlfOffsets() {
        String text = "x\r\n// TODO y\r\n";
        List<TodoMatch> m = TodoScanner.scan(text, defaults());
        assertEquals(1, m.size());
        assertEquals(2, m.get(0).line());
        assertEquals(text.indexOf("TODO"), m.get(0).start());
    }

    @Test
    void zeroLengthPatternDoesNotLoopForever() {
        var compiled = TodoPatterns.compile(List.of(new TodoPattern("empty", "x*", "#fff", false, true)));
        // Must terminate; "x*" can match empty between chars — scanner steps past zero-length matches.
        List<TodoMatch> m = TodoScanner.scan("axbx", compiled);
        assertTrue(m.size() >= 2); // the two 'x' runs are real (non-empty) matches
    }

    @Test
    void emptyAndNullAreSafe() {
        assertTrue(TodoScanner.scan(null, defaults()).isEmpty());
        assertTrue(TodoScanner.scan("", defaults()).isEmpty());
        assertTrue(TodoScanner.scan("TODO", List.of()).isEmpty());
        assertTrue(TodoScanner.scan("nothing here", defaults()).isEmpty());
    }
}
