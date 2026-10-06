package com.editora.pdf;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void longLinesWrapAndTabsExpandInTheExtractedText(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("content.pdf");
        String longLine = "0123456789".repeat(30);
        int missing = CodePdfWriter.write(
                "if (x) {\n\treturn y;\n}\n" + longLine + "\n", null, true, 4, "letter", out, java.util.List.of());
        assertEquals(0, missing);
        String text = PdfProbe.text(out);
        assertTrue(text.contains("return y;"), text);
        assertTrue(text.lines().anyMatch(l -> l.endsWith("    return y;")), "the tab became 4 columns: " + text);
        assertTrue(PdfProbe.squeezed(out).contains(longLine), "a wrapped line loses no characters");
        assertTrue(PdfProbe.rightmostInk(out) <= 612f - 40f + 0.5f, "nothing is drawn past the right margin");
    }

    @Test
    void unrenderableCharactersAreCountedAndWideOnesKeepTheGrid(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("cjk.pdf");
        // No fallback fonts: JetBrains Mono has no CJK glyphs, and neither U+2011 nor an arrow counts as lost
        // when it has a glyph or a readable stand-in.
        int missing = CodePdfWriter.write("// 中文 ok\nint x = 1;\n", null, false, 4, "letter", out, java.util.List.of());
        assertEquals(2, missing, "both ideographs are reported, not silently turned into '?'");
        String text = PdfProbe.text(out);
        assertTrue(text.contains("int x = 1;"), text);
        assertTrue(text.contains("ok"), text);

        // 60 wide characters are 120 columns: they must wrap by display width, not by character count.
        Path wide = dir.resolve("wide.pdf");
        CodePdfWriter.write("中".repeat(60) + "\n", null, false, 4, "letter", wide, java.util.List.of());
        assertTrue(PdfProbe.rightmostInk(wide) <= 612f - 40f + 0.5f, "wide characters stay inside the margin");
        assertEquals(
                60, PdfProbe.squeezed(wide).replace("1", "").length(), "all sixty cells were drawn"); // page no. "1"
    }
}
