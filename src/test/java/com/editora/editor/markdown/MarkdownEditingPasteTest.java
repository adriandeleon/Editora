package com.editora.editor.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure halves of Markdown editing commands: what Paste as Markdown inserts, what a link command starts from. */
class MarkdownEditingPasteTest {

    @Test
    void nothingToConvert() {
        assertNull(MarkdownEditing.converted(true, null, null));
        assertNull(MarkdownEditing.converted(true, null, "plain words"), "no HTML and no tab: nothing to convert");
        assertNull(MarkdownEditing.converted(false, null, "a\tb"), "a plain paste never becomes a table");
        assertNull(MarkdownEditing.converted(true, "<span></span>", "x"), "HTML that converts to nothing");
    }

    @Test
    void anExplicitPasteConvertsAnyHtml() {
        assertEquals(
                "**bold**",
                MarkdownEditing.converted(true, "<b>bold</b>", "bold").strip());
        assertNull(MarkdownEditing.converted(false, "<b>bold</b>", "bold"), "a plain paste only converts structure");
    }

    @Test
    void aPlainPasteConvertsStructureUnlessTheTextIsAlreadyMarkdown() {
        String list = MarkdownEditing.converted(false, "<ul><li>one</li><li>two</li></ul>", "one\r\ntwo");
        assertTrue(list.contains("one") && list.contains("two") && list.strip().startsWith("-"), list);
        assertNull(
                MarkdownEditing.converted(false, "<ul><li>one</li></ul>", "- one"),
                "the text already is the Markdown: it is pasted as it is");
        assertNull(MarkdownEditing.converted(false, "<h1>same</h1>", "# same"));
        assertNull(MarkdownEditing.converted(false, "<a href=\"u\">x</a>", "[x](u)"));
    }

    @Test
    void tabSeparatedTextBecomesATableWhenTheHtmlSaysNothing() {
        String table = MarkdownEditing.converted(true, null, "a\tb\r\n1\t2");
        assertTrue(table.startsWith("| a"), table);
        assertTrue(table.contains("| 1"), table);
        assertTrue(table.contains("---"), table);
        String fromBlankHtml = MarkdownEditing.converted(true, "<span> </span>", "x\ty");
        assertTrue(fromBlankHtml.startsWith("| x"), fromBlankHtml);
    }

    @Test
    void whatALinkCommandStartsFrom() {
        String text = "see [a](u) here\n\n| x | y |\n|---|---|\n| 1 | 2 |\n";
        MarkdownEditing.LinkHere onLink = MarkdownEditing.linkHere(text, 5, 5);
        assertEquals("u", onLink.link().destination());
        assertFalse(onLink.inTable());
        assertEquals("", onLink.selection());
        MarkdownEditing.LinkHere selected = MarkdownEditing.linkHere(text, 14, 11);
        assertEquals("her", selected.selection(), "a selection made backwards");
        MarkdownEditing.LinkHere twoLines = MarkdownEditing.linkHere(text, 0, text.indexOf("| x"));
        assertEquals("", twoLines.selection(), "text over several lines is no link text");
        assertTrue(twoLines.inTable());
        assertNull(twoLines.wiki());
    }
}
