package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RipgrepArgsTest {

    private static final long TWO_MB = 2L * 1024 * 1024;

    @Test
    void literalCaseInsensitiveDefault() {
        List<String> a = RipgrepArgs.build(new SearchQuery("foo", false, false, false), true, TWO_MB);
        assertTrue(a.contains("--json"));
        assertTrue(a.contains("--no-messages"));
        assertTrue(a.contains("--max-filesize=" + TWO_MB));
        assertTrue(a.contains("-i"), "case-insensitive");
        assertFalse(a.contains("-s"));
        assertTrue(a.contains("-F"), "literal → fixed-strings");
        assertFalse(a.contains("-w"));
        assertFalse(a.contains("--no-ignore"), "respects .gitignore by default");
        // pattern passed via -e, last, so a leading-dash query is safe.
        assertEquals("-e", a.get(a.size() - 2));
        assertEquals("foo", a.get(a.size() - 1));
    }

    @Test
    void regexCaseSensitiveWholeWord() {
        List<String> a = RipgrepArgs.build(new SearchQuery("fo+", true, true, true), true, TWO_MB);
        assertTrue(a.contains("-s"), "case-sensitive");
        assertFalse(a.contains("-i"));
        assertFalse(a.contains("-F"), "regex → no fixed-strings");
        assertTrue(a.contains("-w"), "whole-word");
        assertEquals("fo+", a.get(a.size() - 1));
    }

    @Test
    void noIgnoreWhenNotRespectingGitignore() {
        List<String> a = RipgrepArgs.build(new SearchQuery("x", false, false, false), false, TWO_MB);
        assertTrue(a.contains("--no-ignore"));
    }

    @Test
    void leadingDashQueryIsAfterDashE() {
        List<String> a = RipgrepArgs.build(new SearchQuery("-foo", false, false, false), true, TWO_MB);
        assertEquals("-e", a.get(a.size() - 2));
        assertEquals("-foo", a.get(a.size() - 1));
    }
}
