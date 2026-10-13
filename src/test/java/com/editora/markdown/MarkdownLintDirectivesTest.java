package com.editora.markdown;

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

    // --- C3: names -------------------------------------------------------------------------------------

    @Test
    void anAliasNamesOnlyItsRule() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable no-trailing-spaces -->\nx");
        assertTrue(d.disabled(1, "MD009"));
        assertFalse(d.disabled(1, "MD010"));
    }

    @Test
    void aTagNamesItsGroup() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable whitespace -->\nx");
        assertTrue(d.disabled(1, "MD009"));
        assertTrue(d.disabled(1, "MD010"));
        assertTrue(d.disabled(1, "MD012"));
        assertFalse(d.disabled(1, "MD034"));
    }

    @Test
    void anUnknownNameChangesNothing() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable MD034 -->\n"
                + "<!-- markdownlint-disable no-inline-html -->\nx\n"
                + "<!-- markdownlint-enable no-inline-html -->\ny\n"
                + "<!-- markdownlint-disable-next-line line-length -->\nz");
        assertFalse(d.disabled(2, "MD009"), "disabling a rule we do not have must not disable ours");
        assertTrue(d.disabled(2, "MD034"));
        assertTrue(d.disabled(4, "MD034"), "enabling it must not re-enable ours");
        assertFalse(d.disabled(6, "MD009"));
    }

    // --- C12: the other forms --------------------------------------------------------------------------

    @Test
    void aRuleCanBeReEnabledAfterDisablingEverything() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable -->\na\n<!-- markdownlint-enable MD018 -->\nb");
        assertTrue(d.disabled(1, "MD018"));
        assertFalse(d.disabled(3, "MD018"));
        assertTrue(d.disabled(3, "MD009"));
    }

    @Test
    void captureAndRestore() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable MD018 -->\n"
                + "<!-- markdownlint-capture -->\n"
                + "<!-- markdownlint-disable MD009 -->\na\n"
                + "<!-- markdownlint-restore -->\nb");
        assertTrue(d.disabled(3, "MD009"));
        assertFalse(d.disabled(5, "MD009"));
        assertTrue(d.disabled(5, "MD018"));
    }

    @Test
    void restoreWithoutCaptureGoesBackToTheStart() {
        MarkdownLintDirectives d = of("<!-- markdownlint-disable MD009 -->\na\n<!-- markdownlint-restore -->\nb");
        assertTrue(d.disabled(1, "MD009"));
        assertFalse(d.disabled(3, "MD009"));
    }

    @Test
    void fileScopeDirectivesApplyFromTheFirstLine() {
        MarkdownLintDirectives d = of("a\nb\n<!-- markdownlint-disable-file MD009 no-hard-tabs -->");
        assertTrue(d.disabled(0, "MD009"));
        assertTrue(d.disabled(0, "MD010"));
        assertFalse(d.disabled(0, "MD012"));
        MarkdownLintDirectives all =
                of("a\n<!-- markdownlint-disable-file -->\n<!-- markdownlint-enable-file MD012 -->");
        assertTrue(all.disabled(0, "MD009"));
        assertFalse(all.disabled(0, "MD012"));
    }

    @Test
    void configureFileTakesAConfigObject() {
        MarkdownLintDirectives d =
                of("<!-- markdownlint-configure-file { \"MD009\": false, \"no-hard-tabs\": false } -->\na");
        assertTrue(d.disabled(1, "MD009"));
        assertTrue(d.disabled(1, "MD010"));
        assertFalse(d.disabled(1, "MD012"));
    }

    @Test
    void aDirectiveShownInACodeBlockIsNotADirective() {
        MarkdownLintDirectives d = of("```html\n<!-- markdownlint-disable -->\n```\n\na");
        assertFalse(d.disabled(4, "MD009"));
    }

    @Test
    void directiveLinesAreRecognised() {
        assertTrue(MarkdownLintDirectives.isDirectiveLine("  <!-- markdownlint-disable-next-line MD040 -->"));
        assertFalse(MarkdownLintDirectives.isDirectiveLine("text <!-- markdownlint-disable-line -->"));
        assertFalse(MarkdownLintDirectives.isDirectiveLine("<!-- a comment -->"));
    }
}
