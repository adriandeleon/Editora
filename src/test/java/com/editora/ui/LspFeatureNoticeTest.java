package com.editora.ui;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning on inlay hints, semantic highlighting or code lenses in a Python file showed nothing and said nothing:
 * Pyright gives none of them (Python review 2 C107 / E108).
 */
class LspFeatureNoticeTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void namesTheServerAUserKnows() {
        assertEquals("Pyright", LspFeatureNotice.serverName("pyright-langserver"));
        assertEquals("basedpyright", LspFeatureNotice.serverName("basedpyright-langserver"));
        assertEquals("jdtls", LspFeatureNotice.serverName("jdtls"));
    }

    @Test
    void saysSoOnlyWhenTheServerLacksTheFeature() {
        String notice = LspFeatureNotice.message(LspFeatureNotice.Feature.INLAY_HINTS, false, "pyright-langserver");
        assertTrue(notice != null && notice.contains("Pyright") && notice.contains("nlay hints"), notice);
        assertNull(LspFeatureNotice.message(LspFeatureNotice.Feature.INLAY_HINTS, true, "jdtls"));
        for (LspFeatureNotice.Feature f : LspFeatureNotice.Feature.values()) {
            String text = LspFeatureNotice.message(f, false, "pyright-langserver");
            assertTrue(text != null && text.contains("Pyright") && !text.contains("{0}"), f + ": " + text);
        }
    }
}
