package com.editora.office;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import com.editora.editor.MarkdownRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.commonmark.node.Paragraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxWriterTest {

    @Test
    void writesAReadableDocxWithTextAndTable() throws Exception {
        String md = "# Heading\n\nHello **bold** world.\n\n- item one\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";
        Path out = Files.createTempFile("editora-docx-test", ".docx");
        try {
            DocxWriter.write(md, null, null, out);
            assertTrue(Files.size(out) > 0, "non-empty .docx");
            try (InputStream in = Files.newInputStream(out);
                    XWPFDocument doc = new XWPFDocument(in)) {
                String text =
                        doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
                assertTrue(text.contains("Heading"), "heading text present");
                assertTrue(text.contains("Hello bold world."), "paragraph text present");
                assertTrue(text.contains("item one"), "list item present");
                assertEquals(1, doc.getTables().size(), "one table");
                assertTrue(doc.getTables().get(0).getRow(0).getCell(0).getText().contains("A"), "table header cell");
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /** Markdown whose every visible piece of text must survive an office export. */
    static final String EVERYTHING = """
            # Setup

            1. Install the tools:

               ```sh
               npm ci --prefer-offline
               ```

               > quoted inside the item

            2. Then run `make`.

            - [x] done task
            - [ ] open task

            A claim[^src] and more.

            <details>
            <summary>Raw html block</summary>
            </details>

            <!-- an invisible comment -->

            [^src]: The footnote body, with **bold**.

                Second footnote paragraph.

            After the footnote.
            """;

    private static String docxText(String md) throws Exception {
        Path out = Files.createTempFile("editora-docx-content", ".docx");
        try {
            DocxWriter.write(md, null, null, out);
            try (InputStream in = Files.newInputStream(out);
                    XWPFDocument doc = new XWPFDocument(in);
                    org.apache.poi.xwpf.extractor.XWPFWordExtractor extractor =
                            new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
                return extractor.getText();
            }
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    void nothingWithTextIsDroppedFromTheDocument() throws Exception {
        String text = docxText(EVERYTHING);
        assertTrue(text.contains("1. Install the tools:"), text);
        assertTrue(text.contains("npm ci --prefer-offline"), "the fenced block inside the list item was lost: " + text);
        assertTrue(text.contains("quoted inside the item"), text);
        assertTrue(text.contains("2. Then run make."), text);
        assertTrue(text.contains("☑ done task") && text.contains("☐ open task"), "task state survives: " + text);
        assertTrue(text.contains("A claim[src] and more."), "the footnote reference is visible: " + text);
        assertTrue(text.contains("<summary>Raw html block</summary>"), "raw HTML is shown as source: " + text);
        assertTrue(!text.contains("invisible comment"), text);
        assertTrue(text.contains("[src] The footnote body, with bold."), text);
        assertTrue(text.contains("Second footnote paragraph."), text);
        assertTrue(
                text.indexOf("After the footnote.") < text.indexOf("[src] The footnote body"),
                "footnotes are written at the end: " + text);
    }

    @Test
    void aListItemThatStartsWithACodeBlockStillGetsItsMarker() throws Exception {
        String text = docxText("1. ```\n   first\n   ```\n2. plain\n");
        assertTrue(text.contains("1. "), text);
        assertTrue(text.contains("first"), text);
        assertTrue(
                text.indexOf("1. ") < text.indexOf("first") && text.indexOf("first") < text.indexOf("2. plain"), text);
    }

    @Test
    void soleDisplayMathSpansMultipleLines() {
        // $$ on its own line, body, then $$ — commonmark inserts SoftLineBreaks; the detector must span them
        // (regression: it bailed on the first break, so only single-line $$…$$ exported as a block image).
        Paragraph multi = (Paragraph)
                MarkdownRenderer.parseToDocument("$$\n\\int_0^1 x dx\n$$").getFirstChild();
        String latex = InlineRun.soleDisplayMath(multi);
        assertTrue(latex != null && latex.contains("\\int_0^1 x dx"), "multi-line $$ block, got: " + latex);

        Paragraph single =
                (Paragraph) MarkdownRenderer.parseToDocument("$$ a^2 + b^2 $$").getFirstChild();
        assertTrue(InlineRun.soleDisplayMath(single) != null, "single-line $$ block");

        Paragraph prose =
                (Paragraph) MarkdownRenderer.parseToDocument("just some prose").getFirstChild();
        assertNull(InlineRun.soleDisplayMath(prose), "prose is not display math");
    }
}
