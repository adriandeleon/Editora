package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regex literals for the light scanners: where one starts, where it ends, and one still being typed. */
class RegexLiteralsTest {

    @Test
    void aSlashWhereAnOperandIsExpectedStartsARegex() {
        String s = "x = /a[/]b/g;";
        assertEquals(s.indexOf("/g"), RegexLiterals.end(s, s.indexOf('/')));
        String t = "return /`/.test(v)";
        assertEquals(t.indexOf("/.test"), RegexLiterals.end(t, t.indexOf('/')));
        String div = "const half = total / 2 / n;";
        assertEquals(div.indexOf('/'), RegexLiterals.end(div, div.indexOf('/')), "after an operand it divides");
        String open = "x = /abc";
        assertEquals(open.indexOf('/'), RegexLiterals.end(open, open.indexOf('/')), "unclosed on its line: a division");
        assertTrue(RegexLiterals.in("typescriptreact"));
        assertFalse(RegexLiterals.in("java"));
    }

    @Test
    void aRegexStillBeingTypedIsOpenAtTheEndOfTheLine() {
        assertTrue(RegexLiterals.openAtEnd("const r = /['"));
        assertTrue(RegexLiterals.openAtEnd("if (/^\\d+"));
        assertFalse(RegexLiterals.openAtEnd("const r = /a/; const s = '"), "that one is closed");
        assertFalse(RegexLiterals.openAtEnd("const half = a / "), "a division");
        assertFalse(RegexLiterals.openAtEnd("x(); // see /path"), "a comment");
    }
}
