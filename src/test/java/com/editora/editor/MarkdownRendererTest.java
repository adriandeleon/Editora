package com.editora.editor;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no-toolkit) tests for the Markdown parse step — they validate that commonmark core + the GFM
 * extensions (tables, task lists, strikethrough, autolink) resolve on the module path and produce the
 * expected AST node types. Node→JavaFX rendering needs the FX thread, so it isn't unit-tested here.
 */
class MarkdownRendererTest {

    @Test
    void htmlCommentsAreRecognizedAsInvisible() {
        assertTrue(MarkdownRenderer.isHtmlComment("<!-- hidden -->"));
        assertTrue(MarkdownRenderer.isHtmlComment("   <!--\nmulti\nline\n-->"));
        assertFalse(MarkdownRenderer.isHtmlComment("<div>shown</div>"));
        assertFalse(MarkdownRenderer.isHtmlComment(""));
        assertFalse(MarkdownRenderer.isHtmlComment(null));
    }

    private static final String SAMPLE = """
            # Heading

            A paragraph with ~~struck~~ text and a bare URL https://example.com here.

            - [x] done
            - [ ] todo

            | A | B |
            |---|---|
            | 1 | 2 |
            """;

    private static class Counter extends AbstractVisitor {
        boolean heading;
        boolean table;
        boolean checkedTask;
        boolean strikethrough;
        boolean link;

        @Override
        public void visit(Heading h) {
            heading = true;
            super.visit(h);
        }

        @Override
        public void visit(Link l) {
            link = true; // autolink turns the bare URL into a Link
            super.visit(l);
        }

        @Override
        protected void visitChildren(Node parent) {
            if (parent instanceof TableBlock) {
                table = true;
            } else if (parent instanceof Strikethrough) {
                strikethrough = true;
            } else if (parent instanceof TaskListItemMarker m && m.isChecked()) {
                checkedTask = true;
            }
            super.visitChildren(parent);
        }
    }

    @Test
    void parsesCommonMarkPlusGfmExtensions() {
        Node doc = MarkdownRenderer.parseToDocument(SAMPLE);
        Counter c = new Counter();
        doc.accept(c);
        assertTrue(c.heading, "expected a Heading");
        assertTrue(c.table, "expected a GFM TableBlock");
        assertTrue(c.checkedTask, "expected a checked TaskListItemMarker");
        assertTrue(c.strikethrough, "expected a Strikethrough");
        assertTrue(c.link, "expected an autolinked Link");
    }

    @Test
    void plainTextStripsMarkupKeepsVisibleText() {
        String t = MarkdownRenderer.plainText(SAMPLE);
        // visible text is kept
        assertTrue(t.contains("Heading"), "heading text");
        assertTrue(t.contains("struck"), "strikethrough content");
        assertTrue(t.contains("done") && t.contains("todo"), "task item text");
        assertTrue(t.contains("https://example.com"), "link text");
        // GFM table cell text survives (the tables extension renders to plain text too)
        assertTrue(t.contains("1") && t.contains("2"), "table cell text");
        // markup is removed
        assertFalse(t.contains("#"), "no heading hashes");
        assertFalse(t.contains("~~"), "no strikethrough markers");
        assertFalse(t.contains("---"), "no table delimiter row");
    }

    @Test
    void multiLineDisplayMathIsRecognized() {
        // $$ on its own line, content, then $$ — commonmark inserts SoftLineBreaks; paragraphText must
        // span them so the paragraph is recognized as a sole $$…$$ display block (regression: it bailed
        // on the first SoftLineBreak, so only single-line $$…$$ rendered).
        org.commonmark.node.Node doc = MarkdownRenderer.parseToDocument("$$\n\\int_0^1 x^2 dx\n$$");
        org.commonmark.node.Paragraph p = (org.commonmark.node.Paragraph) doc.getFirstChild();
        String latex = MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p));
        assertTrue(latex != null && latex.contains("\\int_0^1 x^2 dx"), "multi-line $$ block, got: " + latex);

        // single-line form still works
        org.commonmark.node.Node doc2 = MarkdownRenderer.parseToDocument("$$ a^2 + b^2 = c^2 $$");
        org.commonmark.node.Paragraph p2 = (org.commonmark.node.Paragraph) doc2.getFirstChild();
        assertTrue(MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p2)) != null, "single-line $$");

        // a normal paragraph is NOT display math
        org.commonmark.node.Node doc3 = MarkdownRenderer.parseToDocument("just some prose here");
        org.commonmark.node.Paragraph p3 = (org.commonmark.node.Paragraph) doc3.getFirstChild();
        assertFalse(MarkdownRenderer.soleDisplayMath(MarkdownRenderer.paragraphText(p3)) != null, "prose");
    }
}
