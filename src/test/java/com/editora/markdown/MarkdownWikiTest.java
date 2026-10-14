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
}
