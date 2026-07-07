package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for {@link AgentPanel}'s static helpers — the plan-checklist status glyph
 *  ({@link AgentPanel#glyphFor}) and the clickable-inline-code-path pre-filter
 *  ({@link AgentPanel#looksLikePath}). No FX toolkit needed: {@code AgentPanel} has no static state, so
 *  calling its static methods directly is safe. */
class AgentPanelGlyphTest {

    @Test
    void completedIsChecked() {
        assertEquals("☑", AgentPanel.glyphFor("completed"));
    }

    @Test
    void inProgressIsHalf() {
        assertEquals("◐", AgentPanel.glyphFor("in_progress"));
    }

    @Test
    void pendingAndUnrecognizedAreEmptyBox() {
        assertEquals("☐", AgentPanel.glyphFor("pending"));
        assertEquals("☐", AgentPanel.glyphFor("something_new"));
        assertEquals("☐", AgentPanel.glyphFor(""));
        assertEquals("☐", AgentPanel.glyphFor(null));
    }

    @Test
    void looksLikePathAcceptsAbsoluteAndRelativePaths() {
        assertTrue(AgentPanel.looksLikePath("/home/adl/find_my_files.py"));
        assertTrue(AgentPanel.looksLikePath("src/main/java/Foo.java"));
        assertTrue(AgentPanel.looksLikePath("~/notes.md"));
        assertTrue(AgentPanel.looksLikePath("~"));
    }

    @Test
    void looksLikePathRejectsBareIdentifiersAndPhrases() {
        assertFalse(AgentPanel.looksLikePath("script.py")); // no slash — too ambiguous to guess
        assertFalse(AgentPanel.looksLikePath("true"));
        assertFalse(AgentPanel.looksLikePath("getValue()"));
        assertFalse(AgentPanel.looksLikePath("a / b")); // whitespace: a sentence fragment, not a path
        assertFalse(AgentPanel.looksLikePath(""));
        assertFalse(AgentPanel.looksLikePath(null));
    }

    @Test
    void looksLikePathRejectsUrls() {
        assertFalse(AgentPanel.looksLikePath("https://example.com/path"));
        assertFalse(AgentPanel.looksLikePath("http://example.com/a/b"));
    }
}
