package com.editora.lsp;

import org.eclipse.lsp4j.FormattingOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FormattingIndentTest {

    @Test
    void aSpaceIndentedDocumentSendsItsOwnWidth() {
        assertEquals(new FormattingIndent(2, true), FormattingIndent.of("  ", 4));
        assertEquals(new FormattingIndent(4, true), FormattingIndent.of("    ", 8));
    }

    @Test
    void aTabIndentedDocumentSendsTabsAtItsShownWidth() {
        assertEquals(new FormattingIndent(8, false), FormattingIndent.of("\t", 8));
    }

    @Test
    void nothingKnownFallsBackToTheTabSizeWithSpaces() {
        assertEquals(new FormattingIndent(3, true), FormattingIndent.of("", 3));
        assertEquals(new FormattingIndent(4, true), FormattingIndent.of(null, 0));
    }

    @Test
    void saveRulesTheFileSetsGoIntoTheOptionsAndUnsetOnesStayOut() {
        FormattingOptions set =
                new FormattingIndent(2, true).withSaveRules(true, false).options();
        assertEquals(2, set.getTabSize());
        assertEquals(Boolean.TRUE, set.isTrimTrailingWhitespace());
        assertEquals(Boolean.FALSE, set.isInsertFinalNewline());
        assertFalse(set.containsKey("trimFinalNewlines"), "saving never collapses extra final newlines");

        FormattingOptions unset = new FormattingIndent(4, false).options();
        assertFalse(unset.containsKey("trimTrailingWhitespace"));
        assertFalse(unset.containsKey("insertFinalNewline"));
    }
}
