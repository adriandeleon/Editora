package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CSV → table document builder used by CSV → PDF / Print (cells are literal text, never Markdown). */
class CsvTableDocumentTest {

    /** The table as rows of cell strings; asserts every cell holds at most one literal Text node. */
    private static List<List<String>> cells(Node document) {
        TableBlock table = assertInstanceOf(TableBlock.class, document.getFirstChild());
        List<List<String>> out = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                List<String> r = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    TableCell tc = assertInstanceOf(TableCell.class, cell);
                    assertEquals(section instanceof TableHead, tc.isHeader());
                    Node child = tc.getFirstChild();
                    if (child == null) {
                        r.add("");
                    } else {
                        assertNull(child.getNext(), "one literal node per cell");
                        r.add(assertInstanceOf(Text.class, child).getLiteral());
                    }
                }
                out.add(r);
            }
        }
        return out;
    }

    @Test
    void markdownPunctuationStaysLiteral() {
        Node doc = CsvTableDocument.fromCsv("a,b,c,d\n__init__,2*3*4,List<String>,C:\\\\srv\n");
        assertEquals(
                List.of(List.of("a", "b", "c", "d"), List.of("__init__", "2*3*4", "List<String>", "C:\\\\srv")),
                cells(doc));
        TableBlock table = (TableBlock) doc.getFirstChild();
        assertInstanceOf(TableHead.class, table.getFirstChild());
        assertInstanceOf(TableBody.class, table.getLastChild());
    }

    @Test
    void usesTheGridsDelimiterDetection() {
        // Pipe-delimited, with a leading blank line: MarkdownTable's own sniffer knew neither.
        assertEquals(
                List.of(List.of("", "", ""), List.of("id", "city", "note"), List.of("1", "x", "a,b")),
                cells(CsvTableDocument.fromCsv("\nid|city|note\n1|x|a,b\n")));
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), cells(CsvTableDocument.fromCsv("a;b\n1;2")));
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), cells(CsvTableDocument.fromCsv("a\tb\n1\t2")));
    }

    @Test
    void padsRaggedRowsFlattensNewlinesAndRejectsEmptyInput() {
        assertEquals(
                List.of(List.of("a", "b", "c"), List.of("1", "", ""), List.of("x y", "q\"t", "")),
                cells(CsvTableDocument.fromCsv("a,b,c\n1\n\"x\ny\",\"q\"\"t\"\n\n\n")));
        assertNull(CsvTableDocument.fromCsv("   "));
        assertNull(CsvTableDocument.fromCsv(null));
        assertTrue(cells(CsvTableDocument.fromCsv("only")).equals(List.of(List.of("only"))));
    }
}
