package com.editora.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;

class CodePdfWriterTest {

    @Test
    void pageRectangleMaps() {
        assertEquals(PDRectangle.A4, CodePdfWriter.pageRectangle("a4"));
        assertEquals(PDRectangle.LETTER, CodePdfWriter.pageRectangle("letter"));
        assertEquals(PDRectangle.LETTER, CodePdfWriter.pageRectangle("nonsense"));
    }

    @Test
    void writesHighlightedCodePdf(@TempDir Path dir) throws Exception {
        String src = "public class Hi {\n    // a comment\n    int x = 42;\n}\n";
        var grammar = GrammarRegistry.shared().forFileName("Hi.java");
        var spans = grammar == null ? null : TextMateHighlighter.compute(src, grammar);
        Path out = dir.resolve("code.pdf");

        CodePdfWriter.write(src, spans, true, 4, "letter", out);

        assertTrue(Files.exists(out), "PDF created");
        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes.length > 500, "non-trivial PDF size: " + bytes.length);
        assertEquals("%PDF", new String(bytes, 0, 4), "valid PDF header");
    }

    @Test
    void unsupportedGlyphsDoNotAbortCodeExport(@TempDir Path dir) throws Exception {
        // U+2011 (non-breaking hyphen) and U+2192 have no glyph in the embedded mono font; they must
        // degrade to '?' rather than throwing "could not find the glyphId" at draw time.
        Path out = dir.resolve("glyphs.pdf");
        CodePdfWriter.write("var x = \"a‑b → c\";\n", null, false, 4, "letter", out);
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("%PDF", new String(bytes, 0, 4));
        assertTrue(bytes.length > 500);
    }

    @Test
    void writesPlainPdfWithoutLineNumbersOrHighlight(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("plain.pdf");
        // No grammar (spans=null), line numbers off — and a very long line to exercise wrapping.
        String src = "x".repeat(2000) + "\nsecond line\n";
        CodePdfWriter.write(src, null, false, 4, "a4", out);
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("%PDF", new String(bytes, 0, 4));
        assertTrue(bytes.length > 500);
    }
}
