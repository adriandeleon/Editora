package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import com.editora.markdown.MarkdownDefinitions.DefinitionList;
import com.editora.markdown.MarkdownDefinitions.Description;
import com.editora.markdown.MarkdownDefinitions.Term;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownDefinitionsTest {

    private static final List<org.commonmark.Extension> EXT = List.of(MarkdownDefinitions.extension());
    private static final Parser PARSER = Parser.builder()
            .extensions(EXT)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    private static Node first(String md) {
        return PARSER.parse(md).getFirstChild();
    }

    /** A list as {@code T:term} / {@code D:definition} entries; inline markup shown by node type. */
    private static List<String> shape(Node list) {
        List<String> out = new ArrayList<>();
        for (Node n = list.getFirstChild(); n != null; n = n.getNext()) {
            StringBuilder sb = new StringBuilder(n instanceof Term ? "T:" : n instanceof Description ? "D:" : "?:");
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                sb.append(
                        c instanceof Text t
                                ? t.getLiteral()
                                : c instanceof SoftLineBreak
                                        ? "/"
                                        : c instanceof Code code
                                                ? "`" + code.getLiteral() + "`"
                                                : "<" + c.getClass().getSimpleName() + ">");
            }
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    void aTermAndTheColonLinesUnderIt() {
        Node list = first("Apple\n: A fruit.\n: A computer company.\n");
        assertInstanceOf(DefinitionList.class, list);
        assertEquals(List.of("T:Apple", "D:A fruit.", "D:A computer company."), shape(list));
        assertNull(list.getNext());
        assertEquals(
                List.of("T:Term", "D:Padded like Pandoc writes it."),
                shape(first("Term\n:   Padded like Pandoc writes it.\n")));
        assertEquals(
                List.of("T:Colour", "T:Color", "D:What the eye sees."),
                shape(first("Colour\nColor\n: What the eye sees.\n")),
                "two terms share the definition");
    }

    @Test
    void inlineMarkupWorksInBoth() {
        assertEquals(
                List.of("T:<Emphasis> and `code`", "D:See <Link>."),
                shape(first("*Term* and `code`\n: See [the docs](x.md).\n")));
    }

    @Test
    void aLineWithoutAColonContinuesTheDefinition() {
        assertEquals(
                List.of("T:Term", "D:First line/second line.", "D:Another."),
                shape(first("Term\n: First line\nsecond line.\n: Another.\n")));
    }

    @Test
    void theLooseFormAndAFurtherDefinitionAfterABlankLine() {
        Node doc = PARSER.parse("Orange\n\n: The fruit of an evergreen tree.\n\n: Also a colour.\n\nAfter.\n");
        Node list = doc.getFirstChild();
        assertEquals(List.of("T:Orange", "D:The fruit of an evergreen tree.", "D:Also a colour."), shape(list));
        assertInstanceOf(Paragraph.class, list.getNext());
        assertNull(list.getNext().getNext());
        assertEquals(0, list.getSourceSpans().get(0).getLineIndex(), "it starts at its term");
        assertEquals(
                4, list.getSourceSpans().get(list.getSourceSpans().size() - 1).getLineIndex());
    }

    @Test
    void separateTermsAreSeparateLists() {
        Node doc = PARSER.parse("A\n: one\n\nB\n: two\n");
        assertEquals(List.of("T:A", "D:one"), shape(doc.getFirstChild()));
        assertEquals(List.of("T:B", "D:two"), shape(doc.getLastChild()));
        assertFalse(doc.getFirstChild() == doc.getLastChild());
    }

    @Test
    void whatIsNotADefinitionList() {
        assertInstanceOf(Paragraph.class, first("Just a paragraph\nwith two lines.\n"));
        assertInstanceOf(Paragraph.class, first("Time is 10:30\n:no space after the colon\n"));
        assertInstanceOf(Paragraph.class, first(": a colon line with nothing above it\n"));
        assertInstanceOf(Paragraph.class, first("Ratio 1 : 2 and key: value\n"));
        assertInstanceOf(FencedCodeBlock.class, first("```\nTerm\n: not here\n```\n"));
        Node afterList =
                PARSER.parse("- item\n\n: after a list, not a paragraph\n").getLastChild();
        assertInstanceOf(Paragraph.class, afterList);
        // A long paragraph is prose, not a term, even when a colon paragraph follows it.
        Node prose = PARSER.parse("One\ntwo\nthree\nfour\n\n: stray\n").getFirstChild();
        assertInstanceOf(Paragraph.class, prose);
        // Inside a quote it works like anywhere else.
        Node quote = first("> Term\n> : In a quote.\n");
        assertInstanceOf(BlockQuote.class, quote);
        assertInstanceOf(DefinitionList.class, quote.getFirstChild());
        assertTrue(MarkdownDefinitions.opensDefinition(": x"));
        assertTrue(MarkdownDefinitions.opensDefinition(":\tx"));
        assertFalse(MarkdownDefinitions.opensDefinition(":x"));
        assertFalse(MarkdownDefinitions.opensDefinition(":"));
        assertFalse(MarkdownDefinitions.opensDefinition(null));
    }

    @Test
    void htmlAndPlainText() {
        Node doc = PARSER.parse("Apple\n: A **fruit**.\n: A company.\n");
        String html = HtmlRenderer.builder().extensions(EXT).build().render(doc);
        assertEquals("<dl>\n<dt>Apple</dt>\n<dd>A <strong>fruit</strong>.</dd>\n<dd>A company.</dd>\n</dl>\n", html);
        String text = TextContentRenderer.builder().extensions(EXT).build().render(doc);
        assertTrue(text.contains("Apple") && text.contains("A fruit.") && text.contains("A company."), text);
    }
}
