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
