package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownWikiTest {

    private static String apply(String text, MarkdownEdit e) {
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void findsEveryFormUnderTheCaret() {
        String t = "see [[Note]] and [[Other Note#Some Heading|the label]] and ![[pic.png]] and [[#Here]].";
        MarkdownWiki.Link plain = MarkdownWiki.at(t, 6);
        assertEquals("Note", plain.target());
        assertNull(plain.heading());
        assertNull(plain.label());
        assertEquals("Note", plain.shown());
        MarkdownWiki.Link full = MarkdownWiki.at(t, t.indexOf("Other"));
        assertEquals("Other Note", full.target());
        assertEquals("Some Heading", full.heading());
        assertEquals("the label", full.label());
        assertEquals("Other Note#Some Heading", full.address());
        MarkdownWiki.Link embed = MarkdownWiki.at(t, t.indexOf("pic"));
        assertTrue(embed.embed());
        assertEquals(t.indexOf("![[pic"), embed.start());
        assertEquals("Here", MarkdownWiki.at(t, t.indexOf("#Here")).heading());
        assertEquals("", MarkdownWiki.at(t, t.indexOf("#Here")).target());
        assertNull(MarkdownWiki.at(t, 1));
        assertNull(MarkdownWiki.at("`[[code]]`", 4), "not in a code span");
        assertNull(MarkdownWiki.at("[[]]", 2));
    }

    @Test
    void aWikiLinkBecomesAMarkdownLink() {
        String t = "see [[Other Note#Some Heading|the label]] now";
        assertEquals(
                "see [the label](<Other Note.md#some-heading>) now",
                apply(t, MarkdownWiki.toMarkdown(MarkdownWiki.at(t, 6), null)));
        assertEquals(
                "[Note](Note.md)", apply("[[Note]]", MarkdownWiki.toMarkdown(MarkdownWiki.at("[[Note]]", 3), null)));
        assertEquals(
                "[Note](../docs/note.md)",
                apply("[[Note]]", MarkdownWiki.toMarkdown(MarkdownWiki.at("[[Note]]", 3), name -> "../docs/note.md")),
                "the window's resolver knows where the note is");
        assertEquals(
                "![pic.png](pic.png)",
                apply("![[pic.png]]", MarkdownWiki.toMarkdown(MarkdownWiki.at("![[pic.png]]", 4), null)));
        assertEquals(
                "[Here](#here)",
                apply("[[#Here]]", MarkdownWiki.toMarkdown(MarkdownWiki.at("[[#Here]]", 4), n -> "x")));
    }

    @Test
    void aMarkdownLinkBecomesAWikiLink() {
        String t = "see [the label](docs/Other%20Note.md#some-heading) now";
        assertEquals(
                "see [[docs/Other Note#some-heading|the label]] now",
                apply(t, MarkdownWiki.toWiki(MarkdownLinks.at(t, 6))));
        assertEquals(
                "[[Note]]", apply("[Note](./Note.md)", MarkdownWiki.toWiki(MarkdownLinks.at("[Note](./Note.md)", 3))));
        assertEquals(
                "![[pic.png|a cat]]",
                apply("![a cat](pic.png)", MarkdownWiki.toWiki(MarkdownLinks.at("![a cat](pic.png)", 3))));
        assertNull(
                MarkdownWiki.toWiki(MarkdownLinks.at("[x](https://example.com)", 2)),
                "a wiki link cannot leave the project");
        assertNull(MarkdownWiki.toWiki(MarkdownLinks.at("[x](mailto:a@b.c)", 2)));
    }

    @Test
    void writingLeavesOffALabelThatSaysNothingNew() {
        assertEquals("[[Note]]", MarkdownWiki.write("Note", "Note", false));
        assertEquals("[[Note]]", MarkdownWiki.write(" Note ", "", false));
        assertEquals("[[Note#H|see]]", MarkdownWiki.write("Note#H", "see", false));
        assertEquals("![[a.png]]", MarkdownWiki.write("a.png", null, true));
        assertFalse(MarkdownWiki.write("N", "a|b]]", false).contains("|b]]]"));
    }

    // ---- the same forms the preview's parser takes, and no others ----

    /** Whether the renderer's parser makes a wiki link of {@code markdown} — the reference for every case below. */
    private static boolean rendersAsWikiLink(String markdown) {
        org.commonmark.node.Node doc = org.commonmark.parser.Parser.builder()
                .extensions(java.util.List.of(
                        org.commonmark.ext.gfm.tables.TablesExtension.create(), MarkdownWikiLinks.extension()))
                .build()
                .parse(markdown);
        boolean[] found = {false};
        doc.accept(new org.commonmark.node.AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.CustomNode node) {
                found[0] |= node instanceof MarkdownWikiLinks.WikiLink;
                visitChildren(node);
            }
        });
        return found[0];
    }

    /** The editing side and the parser must agree on {@code markdown}, with the caret inside the brackets. */
    private static MarkdownWiki.Link agreed(String markdown, boolean expected) {
        MarkdownWiki.Link link = MarkdownWiki.at(markdown, markdown.indexOf("[[") + 3);
        assertEquals(expected, rendersAsWikiLink(markdown), "the parser on: " + markdown);
        assertEquals(expected, link != null, "the editing side on: " + markdown);
        return link;
    }

    @Test
    void notDirectlyAfterALetterOrDigit() {
        agreed("x[[1]] is indexing", false);
        agreed("see [[1]] here", true);
        agreed("a,[[b]]", true);
    }

    @Test
    void aDefinedReferenceStaysAReferenceLinkInBrackets() {
        agreed("see [[note]] here\n\n[note]: http://x\n", false);
        agreed("see [[Note]] here\n\n[note]: http://x\n", false);
        agreed("see [[note]] here\n\n[other]: http://x\n", true);
    }

    @Test
    void bracketsFollowedByADestinationOrALabelAreAnOrdinaryLink() {
        agreed("see [[note]](http://x) here", false);
        agreed("see [[note]][ref] here\n\n[ref]: http://x\n", false);
        agreed("see [[note]] (not a destination)", true);
        agreed("[[a]][[b]]", true);
    }

    @Test
    void threeBracketsAreABracketTheLinkAndABracket() {
        String t = "see [[[a]]] here";
        MarkdownWiki.Link link = agreed(t, true);
        assertEquals("a", link.target());
        assertEquals(t.indexOf("[[a"), link.start());
        assertEquals(t.indexOf("]]]") + 2, link.end(), "the outer brackets are text around it");
    }

    @Test
    void inATableTheBarIsWrittenEscaped() {
        String table = "| a | b |\n|---|---|\n| [[note\\|the label]] | x |\n";
        MarkdownWiki.Link link = agreed(table, true);
        assertEquals("note", link.target());
        assertEquals("the label", link.label());
        assertTrue(link.inTable());
        agreed("| a | b |\n|---|---|\n| [[note|label]] | x |\n", false);
        assertEquals("[[note\\|new]]", MarkdownWiki.write("note", "new", false, true));
        assertEquals("[[note|new]]", MarkdownWiki.write("note", "new", false, false));
        String md = "| a |\n|---|\n| [the label](note.md) |\n";
        assertEquals(
                "[[note\\|the label]]",
                MarkdownWiki.toWiki(MarkdownLinks.at(md, md.indexOf("[the") + 2), true)
                        .replacement());
    }

    @Test
    void neverInCodeAndNotWhenEscaped() {
        agreed("a `[[code]]` span", false);
        agreed("```\n[[fenced]]\n```\n", false);
        agreed("    [[indented code]]\n", false);
        agreed("an escaped \\[[note]] bracket", false);
        agreed("a double backslash \\\\[[note]] is a link", true);
    }

    @Test
    void theInsideIsReadByTheParsersOwnRule() {
        agreed("[[ ]]", false);
        agreed("[[|label only]]", false);
        agreed("[[#Heading]]", true);
        assertNull(agreed("[[note#|]]", true).heading(), "an empty heading or label is as if not written");
        assertNull(MarkdownWiki.at("[[note#|]]", 3).label());
        MarkdownWiki.Link embed = agreed("![[pic.png|alt]]", true);
        assertTrue(embed.embed());
        assertTrue(agreed("x![[pic.png]]", true).embed(), "an embed's bang separates it from the word");
        agreed("[[a|b|c]]", true);
        assertEquals("b|c", MarkdownWiki.at("[[a|b|c]]", 3).label());
    }
}
