package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
