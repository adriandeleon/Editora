package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure test for {@link AgentPanel#glyphFor} — the plan-checklist status glyph. No FX toolkit needed:
 *  {@code AgentPanel} has no static state, so calling its static method directly is safe. */
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
}
