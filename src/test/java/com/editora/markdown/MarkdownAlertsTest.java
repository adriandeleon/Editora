package com.editora.markdown;

import java.util.List;

import com.editora.markdown.MarkdownAlerts.Alert;
import com.editora.markdown.MarkdownAlerts.Kind;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownAlertsTest {

    private static final List<org.commonmark.Extension> EXT = List.of(MarkdownAlerts.extension());
    private static final Parser PARSER = Parser.builder()
            .extensions(EXT)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    private static Node first(String md) {
        return PARSER.parse(md).getFirstChild();
    }

    private static String text(Node inlineParent) {
        StringBuilder sb = new StringBuilder();
        for (Node n = inlineParent.getFirstChild(); n != null; n = n.getNext()) {
            sb.append(n instanceof Text t ? t.getLiteral() : "|");
        }
        return sb.toString();
    }

    @Test
    void aQuoteWhoseFirstLineIsAMarkerBecomesAnAlertOfThatKind() {
        for (Kind kind : Kind.values()) {
            Alert alert = assertInstanceOf(Alert.class, first("> [!" + kind.name() + "]\n> Body text.\n"));
            assertEquals(kind, alert.getKind());
            Paragraph body = assertInstanceOf(Paragraph.class, alert.getFirstChild());
            assertEquals("Body text.", text(body), "the marker line is gone, the rest stays");
            assertNull(body.getNext());
        }
        assertEquals(Kind.WARNING, ((Alert) first("> [!warning]\n> any case\n")).getKind());
        assertEquals(Kind.NOTE, ((Alert) first(">[!NOTE]  \n>two spaces after the marker\n")).getKind());
    }

    @Test
    void theRestOfTheQuoteIsItsContentWhateverBlocksItHas() {
        Alert alert = (Alert) first("> [!TIP]\n> First.\n>\n> - one\n> - two\n>\n> ```\n> code\n> ```\n");
        Node p = alert.getFirstChild();
        assertInstanceOf(Paragraph.class, p);
        assertInstanceOf(BulletList.class, p.getNext());
        assertInstanceOf(FencedCodeBlock.class, p.getNext().getNext());
        // The marker alone in its paragraph: that paragraph goes, the blocks after it are the content.
        Alert spaced = (Alert) first("> [!NOTE]\n>\n> After a blank quote line.\n");
        assertEquals("After a blank quote line.", text(spaced.getFirstChild()));
        assertNull(spaced.getFirstChild().getNext());
        // It keeps the quote's source lines, for whoever maps blocks to lines.
        assertEquals(0, alert.getSourceSpans().get(0).getLineIndex());
        assertEquals(
                8, alert.getSourceSpans().get(alert.getSourceSpans().size() - 1).getLineIndex());
    }

    @Test
    void anythingElseStaysABlockQuote() {
        assertInstanceOf(BlockQuote.class, first("> Just a quote.\n"));
        assertInstanceOf(BlockQuote.class, first("> [!NOTE] text on the marker line\n> more\n"), "not the whole line");
        assertInstanceOf(BlockQuote.class, first("> [!NOTE]\n"), "nothing to say");
        assertInstanceOf(BlockQuote.class, first("> [!QUESTION]\n> not one of the five\n"));
        assertInstanceOf(BlockQuote.class, first("> Text first.\n> [!NOTE]\n"));
        assertInstanceOf(BlockQuote.class, first("> **[!NOTE]**\n> emphasised\n"));
        assertInstanceOf(Paragraph.class, first("[!NOTE]\nnot in a quote\n"));
    }

    @Test
    void alertsNestAndSitInLists() {
        Node item = first("- item\n\n  > [!CAUTION]\n  > In a list.\n").getFirstChild();
        assertInstanceOf(Alert.class, item.getLastChild());
        Alert outer = (Alert) first("> [!NOTE]\n> Outer.\n>\n> > [!TIP]\n> > Inner.\n");
        Alert inner = assertInstanceOf(Alert.class, outer.getLastChild());
        assertEquals(Kind.TIP, inner.getKind());
    }

    @Test
    void theMarkerIsReadExactly() {
        assertEquals(Kind.NOTE, MarkdownAlerts.marker("[!NOTE]"));
        assertEquals(Kind.IMPORTANT, MarkdownAlerts.marker("  [!Important]  "));
        assertNull(MarkdownAlerts.marker("[!NOTE"));
        assertNull(MarkdownAlerts.marker("[NOTE]"));
        assertNull(MarkdownAlerts.marker("[!]"));
        assertNull(MarkdownAlerts.marker("[!NOTE] x"));
        assertNull(MarkdownAlerts.marker(null));
        assertEquals("Caution", Kind.CAUTION.title());
        assertEquals("caution", Kind.CAUTION.slug());
    }

    @Test
    void htmlAndPlainTextKnowTheNode() {
        Node doc = PARSER.parse("> [!WARNING]\n> Back up **first**.\n\nAfter.\n");
        String html = HtmlRenderer.builder().extensions(EXT).build().render(doc);
        assertTrue(html.contains("<div class=\"markdown-alert markdown-alert-warning\">"), html);
        assertTrue(html.contains("<p class=\"markdown-alert-title\">Warning</p>"), html);
        assertTrue(html.contains("<p>Back up <strong>first</strong>.</p>"), html);
        assertTrue(html.indexOf("</div>") < html.indexOf("<p>After.</p>"), html);
        String plain = TextContentRenderer.builder().extensions(EXT).build().render(doc);
        assertTrue(plain.contains("Warning:"), plain);
        assertTrue(plain.contains("Back up first."), plain);
    }
}
