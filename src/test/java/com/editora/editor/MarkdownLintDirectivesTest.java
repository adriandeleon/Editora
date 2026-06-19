package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the inline markdownlint directive parser. */
class MarkdownLintDirectivesTest {

    private static MarkdownLintDirectives of(String text) {
        return MarkdownLintDirectives.compute(text.split("\n", -1));
    }

    @Test
    void disableRegionForListedRule() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable MD009 -->\nline\nline");
        assertTrue(d.disabled(1, "MD009"));
        assertTrue(d.disabled(2, "MD009"));
        assertFalse(d.disabled(1, "MD040"));
    }

    @Test
    void enableReopensRegion() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable MD009 -->\na\n<!-- markdownlint-enable MD009 -->\nb");
        assertTrue(d.disabled(1, "MD009"));
        assertFalse(d.disabled(3, "MD009"));
    }

    @Test
    void disableAllWildcard() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable -->\nx");
        assertTrue(d.disabled(1, "MD009"));
        assertTrue(d.disabled(1, "MD040"));
    }

    @Test
    void disableLineIsScopedToTheSameLine() {
        MarkdownLintDirectives d = of("a <!-- markdownlint-disable-line MD009 -->\nb");
        assertTrue(d.disabled(0, "MD009"));
        assertFalse(d.disabled(1, "MD009"));
    }

    @Test
    void disableNextLineIsScopedToTheFollowingLine() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable-next-line MD009 -->\nb\nc");
        assertFalse(d.disabled(0, "MD009"));
        assertTrue(d.disabled(1, "MD009"));
        assertFalse(d.disabled(2, "MD009"));
    }

    @Test
    void outOfRangeLineIsNeverDisabled() {
        MarkdownLintDirectives d = of("plain");
        assertFalse(d.disabled(99, "MD009"));
        assertFalse(d.disabled(-1, "MD009"));
    }
}
