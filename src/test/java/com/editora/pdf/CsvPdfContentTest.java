package com.editora.pdf;

import java.nio.file.Path;
import java.util.List;

import com.editora.markdown.CsvTableDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CSV → PDF: cells are data. They reach the page verbatim and in the columns the grid shows. */
class CsvPdfContentTest {

    @Test
    void cellsAreNotReparsedAsMarkdown(@TempDir Path dir) throws Exception {
        String csv = """
                name,expr,type,path
                __init__,2*3*4,List<String>,C:\\\\srv
                *a*,`b`,[c](d),# e
                """;
        Path out = dir.resolve("table.pdf");
        MarkdownPdfWriter.write(CsvTableDocument.fromCsv(csv), null, "letter", null, out, List.of());
        String text = PdfProbe.text(out);
        assertTrue(text.contains("name expr type path"), text);
        assertTrue(text.contains("__init__ 2*3*4 List<String> C:\\\\srv"), text);
        assertTrue(text.contains("*a* `b` [c](d) # e"), text);
    }

    @Test
    void aPipeDelimitedFileKeepsItsColumns(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("pipes.pdf");
        MarkdownPdfWriter.write(
                CsvTableDocument.fromCsv("\nid|city|note\n1|Monterrey|a,b\n2|León|c;d\n"),
                null,
                "letter",
                null,
                out,
                List.of());
        String text = PdfProbe.text(out);
        assertTrue(text.contains("id city note"), text);
        assertTrue(text.contains("1 Monterrey a,b"), text);
        assertTrue(text.contains("2 León c;d"), text);
        assertFalse(text.contains("|"), "the delimiter is not cell content: " + text);
    }

    @Test
    void aCellTallerThanAPageIsSplitNotCutOff(@TempDir Path dir) throws Exception {
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            cell.append("note ")
                    .append(i)
                    .append(" is one of sixty long remarks kept in a single cell end")
                    .append(i)
                    .append(". ");
        }
        Path out = dir.resolve("long.pdf");
        MarkdownPdfWriter.write(
                CsvTableDocument.fromCsv("id,notes\n1,\"" + cell + "\"\n2,short\n"),
                null,
                "letter",
                null,
                out,
                List.of());
        String text = PdfProbe.squeezed(out);
        for (int i = 1; i <= 60; i++) {
            assertTrue(text.contains("cellend" + i + "."), "remark " + i + " is lost");
        }
        assertTrue(text.contains("2short"), "the row after it survives");
        assertTrue(PdfProbe.pages(out) >= 2, "the cell spans pages");
        for (PdfProbe.Glyph g : PdfProbe.glyphs(out)) {
            assertTrue(g.baseline() >= 50f, "drawn below the bottom margin: " + g);
        }
    }
}
