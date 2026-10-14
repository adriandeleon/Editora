package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import com.editora.markdown.MarkdownWikiLinks.WikiLink;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownWikiLinksTest {

    private static final List<org.commonmark.Extension> EXT = List.of(
            MarkdownWikiLinks.extension(),
            TablesExtension.create(),
            TaskListItemsExtension.create(),
            MarkdownEmoji.extension(),
            MarkdownMarks.extension());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();
    private static final Parser PLAIN = Parser.builder().build();

    /** Every wiki link of {@code md} as {@code target#heading|label}, an embed with a leading {@code !}. */
    private static List<String> links(String md) {
        List<String> out = new ArrayList<>();
        PARSER.parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                if (node instanceof WikiLink w) {
                    out.add((w.isEmbed() ? "!" : "")
                            + w.getTarget()
                            + (w.getHeading() == null ? "" : "#" + w.getHeading())
                            + (w.getLabel() == null ? "" : "|" + w.getLabel()));
                }
                visitChildren(node);
            }
        });
        return out;
    }

    private static String html(String md) {
        return HtmlRenderer.builder().extensions(EXT).build().render(PARSER.parse(md));
    }

    private static String text(String md) {
        return TextContentRenderer.builder()
                .extensions(EXT)
                .build()
                .render(PARSER.parse(md))
                .strip();
    }

    /** What the document is without the extension: where no wiki link is made, nothing may differ. */
    private static void assertUntouched(String md) {
        assertEquals(List.of(), links(md), md);
        assertEquals(
                HtmlRenderer.builder().build().render(PLAIN.parse(md)),
                HtmlRenderer.builder()
                        .build()
                        .render(Parser.builder()
                                .extensions(List.of(MarkdownWikiLinks.extension()))
                                .build()
                                .parse(md)),
                md);
    }

    @Test
    void theFourFormsAndTheHeadingOnlyForm() {
        assertEquals(List.of("note"), links("See [[note]]."));
        assertEquals(List.of("note|a label"), links("See [[note|a label]]."));
        assertEquals(List.of("note#Some heading"), links("See [[note#Some heading]]."));
        assertEquals(List.of("note#Some heading|label"), links("See [[note#Some heading|label]]."));
        assertEquals(List.of("#Only heading"), links("Above: [[#Only heading]]"));
        assertEquals(List.of("folder/My note 2.md"), links("[[folder/My note 2.md]]"));
        assertEquals(List.of("a", "b|B", "c#d"), links("[[a]] [[b|B]], then [[c#d]]"));
        assertEquals(List.of("a", "b"), links("[[a]][[b]]"), "back to back");
        assertEquals(List.of("a"), links("([[a]])"));
        assertEquals(List.of("a"), links("«[[a]]»."));
        assertEquals(List.of("note|label"), links("[[ note | label ]]"), "parts are trimmed");
        assertEquals(List.of("note"), links("[[note|]]"), "an empty label is no label");
        assertEquals(List.of("note"), links("[[note#]]"));
    }

    @Test
    void whatTheNodeSays() {
        WikiLink plain = MarkdownWikiLinks.parse("note", false);
        assertEquals("note", plain.getTarget());
        assertNull(plain.getHeading());
        assertNull(plain.getLabel());
        assertFalse(plain.isEmbed());
        assertEquals("note", plain.getText());
        assertEquals("note", plain.getReference());

        WikiLink full = MarkdownWikiLinks.parse("note#Heading|Shown", false);
        assertEquals("note", full.getTarget());
        assertEquals("Heading", full.getHeading());
        assertEquals("Shown", full.getLabel());
        assertEquals("Shown", full.getText());
        assertEquals("note#Heading", full.getReference());

        assertEquals(
                "note#Heading", MarkdownWikiLinks.parse("note#Heading", false).getText());
        WikiLink here = MarkdownWikiLinks.parse("#Heading", false);
        assertEquals("", here.getTarget());
        assertEquals("Heading", here.getText());
        assertEquals("#Heading", here.getReference());
        assertTrue(MarkdownWikiLinks.parse("pic.png", true).isEmbed());

        assertNull(MarkdownWikiLinks.parse("", false));
        assertNull(MarkdownWikiLinks.parse("   ", false));
        assertNull(MarkdownWikiLinks.parse("|label", false), "a label needs something to label");
        assertNull(MarkdownWikiLinks.parse("#", false));
        assertNull(MarkdownWikiLinks.parse("a[b", false));
        assertNull(MarkdownWikiLinks.parse("a]b", false));
        assertNull(MarkdownWikiLinks.parse("a\nb", false));
        assertNull(MarkdownWikiLinks.parse("x".repeat(MarkdownWikiLinks.MAX_LENGTH + 1), false));
        assertNull(MarkdownWikiLinks.parse(null, false));
    }

    @Test
    void anEmbedIsAWikiLinkWithABang() {
        assertEquals(List.of("!image.png"), links("![[image.png]]"));
        assertEquals(List.of("!assets/pic 1.png|A caption"), links("Look: ![[assets/pic 1.png|A caption]] here"));
        assertEquals(List.of("a", "!b.png"), links("[[a]]![[b.png]]"));
        assertEquals(List.of("!a"), links("wow![[a]]"), "a bang before the brackets is the embed marker");
        assertEquals(List.of("a"), links("\\![[a]]"), "an escaped bang is text, the link stays");
    }

    @Test
    void neverInsideCode() {
        assertUntouched("Use `[[note]]` to link.");
        assertUntouched("```\n[[note]]\n```\n");
        assertUntouched("    [[note]]\n");
        assertUntouched("``` md\n![[image.png]]\n```\n");
        assertUntouched("<div>[[note]]</div>\n");
        assertEquals(List.of("real"), links("`[[code]]` and [[real]]"));
    }

    @Test
    void whatWasNotAWikiLinkStaysWhatItWas() {
        assertUntouched("\\[[note]]");
        assertUntouched("[\\[note]]");
        assertUntouched("[[note\\]]");
        assertUntouched("[[note]");
        assertUntouched("[note]]");
        assertUntouched("[[]]");
        assertUntouched("[[ ]]");
        assertUntouched("[[|label]]");
        assertUntouched("[[a\nb]]");
        assertUntouched("[[a `code` b]]");
        assertUntouched("[text](url) and [ref] and ![alt](pic.png)");
        assertUntouched("A [[note]](https://example.com) link whose text is in brackets.");
        assertUntouched("[[note]][ref]\n\n[ref]: https://example.com\n");
        assertUntouched("- [ ] task\n- [x] done\n");
        assertUntouched("An array a[[1]] in R is… still a[[1]]? No:");
    }

    @Test
    void anExtraPairOfBracketsStaysAroundTheLink() {
        assertEquals(List.of("a"), links("[[[a]]]"));
        assertEquals("[a]", text("[[[a]]]"));
        assertEquals("[a", text("\\[[[a]]"), "an escaped bracket before one is kept");
        assertEquals("!a", text("\\![[a]]"));
        assertEquals("x [[a]] y", text("x \\[[a]] y"), "and an escaped pair is no link at all");
    }

    @Test
    void aDefinedReferenceInBracketsStaysThatLink() {
        String md = "See [[note]].\n\n[note]: https://example.com\n";
        assertEquals(List.of(), links(md));
        boolean[] link = new boolean[1];
        PARSER.parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(Link l) {
                link[0] = "https://example.com".equals(l.getDestination());
            }
        });
        assertTrue(link[0]);
    }

    @Test
    void insideOtherConstructs() {
        assertEquals(List.of("note"), links("# Heading with [[note]]\n"));
        assertEquals(List.of("note|n"), links("- item [[note|n]]\n"));
        assertEquals(List.of("note"), links("> quoted [[note]]\n"));
        assertEquals(List.of("note"), links("**bold [[note]] bold**"));
        assertEquals(List.of("my_note_name"), links("[[my_note_name]]"), "underscores are not emphasis here");
        assertEquals(List.of("a*b*c"), links("[[a*b*c]]"), "nor are stars: the inside is plain text");
        assertEquals(List.of("a==b==c"), links("[[a==b==c]]"));
        assertEquals(List.of("note"), links("- [ ] [[note]]\n"), "after a task box");
        assertEquals(List.of("note"), links("| a | b |\n|---|---|\n| [[note]] | x |\n"));
        assertEquals(
                List.of("note|label"),
                links("| a | b |\n|---|---|\n| [[note\\|label]] | x |\n"),
                "in a table the pipe is escaped, as everywhere in a table");
        assertEquals(List.of("note"), links("[see [[note]] too](https://example.com)"));
    }

    @Test
    void rendersAsALinkOrAnImageAndAsItsText() {
        assertEquals("<p>See <a class=\"wikilink\" href=\"note\">note</a>.</p>\n", html("See [[note]]."));
        assertEquals(
                "<p><a class=\"wikilink\" href=\"My%20note#Top\">shown &amp; told</a></p>\n",
                html("[[My note#Top|shown & told]]"));
        assertEquals("<p><img class=\"wikilink\" src=\"pic.png\" alt=\"pic.png\" /></p>\n", html("![[pic.png]]"));
        assertEquals(
                "See note and Label and note#Top and Top.",
                text("See [[note]] and [[x|Label]] and [[note#Top]] and [[#Top]]."));
        assertEquals("pic.png", text("![[pic.png]]"));
    }
}
