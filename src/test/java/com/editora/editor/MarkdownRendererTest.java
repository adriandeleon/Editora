package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;

/**
 * Pure (no-toolkit) tests for the Markdown parse step — they validate that commonmark core + the GFM
 * extensions (tables, task lists, strikethrough, autolink) resolve on the module path and produce the
 * expected AST node types. Node→JavaFX rendering needs the FX thread, so it isn't unit-tested here.
 */
class MarkdownRendererTest {

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
}
