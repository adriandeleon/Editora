package com.editora.lsp;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Mapping a server's code lens onto the neutral record the editor renders.
 *
 * <p>Mostly about what is <em>dropped</em>. A lens arrives unresolved — a range with no command — and a
 * server may decline to resolve it at all; rendering that as an empty band above a line is worse than
 * rendering nothing, because the reader is left wondering what the gap means.
 */
class CodeLensMappingTest {

    private static CodeLens lens(int line, String title) {
        CodeLens l = new CodeLens();
        l.setRange(new Range(new Position(line, 0), new Position(line, 10)));
        if (title != null) {
            l.setCommand(new Command(title, "someCommand"));
        }
        return l;
    }

    @Test
    void aResolvedLensKeepsItsLineAndTitle() {
        LspManager.Lens mapped = LspManager.toLens(lens(41, "3 references"));
        assertEquals(41, mapped.line());
        assertEquals("3 references", mapped.title());
    }

    @Test
    void anUnresolvedLensIsDropped() {
        // The common case for a server that never got asked to resolve, or declined.
        assertNull(LspManager.toLens(lens(3, null)));
    }

    @Test
    void aBlankTitleIsDropped() {
        assertNull(LspManager.toLens(lens(3, "   ")));
        assertNull(LspManager.toLens(lens(3, "")));
    }

    @Test
    void titlesAreTrimmedSoTheRowDoesNotStartWithSpace() {
        assertEquals(
                "3 references", LspManager.toLens(lens(1, "  3 references  ")).title());
    }

    @Test
    void aLensWithNoRangeIsDropped() {
        CodeLens noRange = new CodeLens();
        noRange.setCommand(new Command("3 references", "c"));
        assertNull(LspManager.toLens(noRange));
        assertNull(LspManager.toLens(null));
    }
}
