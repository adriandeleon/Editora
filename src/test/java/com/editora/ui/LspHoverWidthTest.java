package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover is as wide as its code needs (Python review B12): Pyright's signature block scrolled sideways in a
 * popup sized to the docstring's prose.
 */
class LspHoverWidthTest {

    @Test
    void theLongestCodeLineSetsTheWidthUpToTheMaximum() {
        String hover =
                "```python\n(function) def helper(\n    count: int,\n    label: str = \"x\"\n) -> str\n```\n---\n"
                        + "Return the label repeated with some long prose that wraps.";
        double width = HoverCard.codeWidth(hover);
        assertEquals(22 * HoverCard.CODE_CHAR_WIDTH + HoverCard.CODE_CHROME, width, 0.001);
        assertTrue(width > 240, "wider than the prose-sized popup the signature was cut in");
        assertEquals(0, HoverCard.codeWidth("just prose, no code"), 0.0);
        assertEquals(HoverCard.MAX_WIDTH, HoverCard.codeWidth("```\n" + "x".repeat(400) + "\n```"));
    }

    /** A man page (bash-language-server's command hover) gets its 80 columns uncut (Bash review C7). */
    @Test
    void aManPageWidensTheCardToEightyColumns() {
        String man = "``` man\n" + "x".repeat(80) + "\n```";
        assertEquals(HoverCard.MAN_MAX_WIDTH, HoverCard.maxWidth(man));
        assertEquals(HoverCard.MAN_MAX_WIDTH, HoverCard.codeWidth(man), 0.001);
        assertEquals(HoverCard.MAX_WIDTH, HoverCard.maxWidth("```python\nx\n```"));
        assertTrue(HoverCard.MAN_MAX_WIDTH > HoverCard.MAX_WIDTH);
    }

    /** The card pages from the keyboard; every other key is the editor's (C7). */
    @Test
    void pageKeysScrollTheCardAndEscapeClosesIt() {
        assertEquals(
                HoverCard.Key.CLOSE, HoverCard.keyFor(javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
        assertEquals(
                HoverCard.Key.PAGE_DOWN,
                HoverCard.keyFor(javafx.scene.input.KeyCode.PAGE_DOWN, false, false, false, false));
        assertEquals(
                HoverCard.Key.PAGE_DOWN, HoverCard.keyFor(javafx.scene.input.KeyCode.V, true, false, false, false));
        assertEquals(HoverCard.Key.PAGE_UP, HoverCard.keyFor(javafx.scene.input.KeyCode.V, false, true, false, false));
        assertEquals(
                HoverCard.Key.PAGE_UP,
                HoverCard.keyFor(javafx.scene.input.KeyCode.PAGE_UP, false, false, false, false));
        assertEquals(HoverCard.Key.NONE, HoverCard.keyFor(javafx.scene.input.KeyCode.V, false, false, false, false));
        assertEquals(
                HoverCard.Key.NONE, HoverCard.keyFor(javafx.scene.input.KeyCode.PAGE_DOWN, false, false, false, true));
        assertEquals("nav.pageDown", HoverCard.SCROLL_CHORDS.get("C-v"), "handed over only while it means paging");
    }

    /** "1 symbols" (C16). */
    @Test
    void oneSymbolIsSingular() {
        com.editora.i18n.Messages.init("en");
        assertEquals("1 symbol", com.editora.i18n.Messages.tr("lsp.workspaceSymbols.count", 1));
        assertEquals("3 symbols", com.editora.i18n.Messages.tr("lsp.workspaceSymbols.count", 3));
    }
}
