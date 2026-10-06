package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RipgrepArgsTest {

    private static final long TWO_MB = 2L * 1024 * 1024;
    private static final List<String> NONE = List.of();

    @Test
    void literalCaseInsensitiveDefault() {
        List<String> a = RipgrepArgs.build(new SearchQuery("foo", false, false, false), NONE, NONE, true, TWO_MB);
        assertTrue(a.contains("--json"));
        assertTrue(a.contains("--no-messages"));
        assertTrue(a.contains("--max-filesize=" + TWO_MB));
        assertTrue(a.contains("-i"), "case-insensitive");
        assertFalse(a.contains("-s"));
        assertTrue(a.contains("-F"), "literal → fixed-strings");
        assertFalse(a.contains("-w"));
        assertFalse(a.contains("--no-ignore"), "respects .gitignore by default");
        assertFalse(a.contains("-g"), "no globs → no -g");
        // pattern passed via -e, last, so a leading-dash query is safe.
        assertEquals("-e", a.get(a.size() - 2));
        assertEquals("foo", a.get(a.size() - 1));
    }

    @Test
    void regexCaseSensitiveWholeWord() {
        List<String> a = RipgrepArgs.build(new SearchQuery("fo+", true, true, true), NONE, NONE, true, TWO_MB);
        assertTrue(a.contains("-s"), "case-sensitive");
        assertFalse(a.contains("-i"));
        assertFalse(a.contains("-F"), "regex → no fixed-strings");
        assertTrue(a.contains("-w"), "whole-word");
        assertEquals("fo+", a.get(a.size() - 1));
    }

    @Test
    void noIgnoreWhenNotRespectingGitignore() {
        List<String> a = RipgrepArgs.build(new SearchQuery("x", false, false, false), NONE, NONE, false, TWO_MB);
        assertTrue(a.contains("--no-ignore"));
    }

    @Test
    void leadingDashQueryIsAfterDashE() {
        List<String> a = RipgrepArgs.build(new SearchQuery("-foo", false, false, false), NONE, NONE, true, TWO_MB);
        assertEquals("-e", a.get(a.size() - 2));
        assertEquals("-foo", a.get(a.size() - 1));
    }

    @Test
    void includeAndExcludeBecomeGlobFlags() {
        List<String> a = RipgrepArgs.build(
                new SearchQuery("foo", false, false, false),
                List.of("*.java", "src/**"),
                List.of("**/test/**"),
                true,
                TWO_MB);
        // include → -g <glob>
        assertEquals("-g", a.get(a.indexOf("*.java") - 1));
        assertTrue(a.contains("src/**"));
        // exclude → -g !<glob>
        assertTrue(a.contains("!**/test/**"), "exclude is negated");
        assertEquals("-g", a.get(a.indexOf("!**/test/**") - 1));
    }

    // --- A12-9 / A12-21: the flags that keep ripgrep and the Java matcher on the same lines and files ------

    @Test
    void crlfIsALineTerminatorAndGitignoreAppliesOutsideARepository() {
        List<String> a = RipgrepArgs.build(new SearchQuery(";$", true, true, false), NONE, NONE, true, TWO_MB);
        assertTrue(a.contains("--crlf"), "$ must match before \\r\\n, as it does for the Java matcher");
        assertTrue(a.contains("--no-require-git"), "the walker applies .gitignore without a .git too");
        List<String> off = RipgrepArgs.build(new SearchQuery("x", true, false, false), NONE, NONE, false, TWO_MB);
        assertTrue(off.contains("--no-ignore"));
        assertFalse(off.contains("--no-require-git"));
    }
}
