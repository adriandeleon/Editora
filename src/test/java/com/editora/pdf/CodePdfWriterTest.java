package com.editora.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(60, PdfProbe.squeezed(wide).length(), "all sixty cells were drawn");
    }

    private static final PdfDocMeta META = new PdfDocMeta("Demo.java", "en", (p, n) -> "Page " + p + " of " + n);

    @Test
    void everyPageHasTheNameAndPageOfPagesFooter(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("footer.pdf");
        CodePdfWriter.write("line\n".repeat(150), null, true, 4, PdfPageSpec.of("letter"), META, out, List.of());
        int pages = PdfProbe.pages(out);
        assertTrue(pages >= 3, "several pages: " + pages);
        for (int p = 1; p <= pages; p++) {
            String furniture = PdfProbe.artifactText(out, p);
            assertTrue(
                    furniture.strip().replaceAll("\\s+", " ").endsWith("Demo.java Page " + p + " of " + pages),
                    "the footer of page " + p + ": " + furniture);
        }
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals("Demo.java", doc.getDocumentInformation().getTitle());
            assertEquals("Editora", doc.getDocumentInformation().getCreator());
            assertNotNull(doc.getDocumentInformation().getCreationDate());
            assertEquals("en", doc.getDocumentCatalog().getLanguage());
        }
    }

    @Test
    void withTheFooterOffThereIsNoPageFurnitureAtAll(@TempDir Path dir) throws Exception {
        Path on = dir.resolve("on.pdf");
        Path off = dir.resolve("off.pdf");
        String code = "line\n".repeat(150);
        CodePdfWriter.write(code, null, false, 4, PdfPageSpec.of("letter"), META, on, List.of());
        CodePdfWriter.write(code, null, false, 4, PdfPageSpec.of("letter").withFooter(false), META, off, List.of());
        assertTrue(PdfProbe.artifactText(on).contains("Page 1 of"));
        assertEquals("", PdfProbe.artifactText(off).strip(), "no footer, not even a bare page number");
        assertEquals(PdfProbe.text(on), PdfProbe.text(off), "the code is the same");
        assertEquals(PdfProbe.rawText(off), PdfProbe.text(off), "nothing but the code is in the PDF");
        try (PDDocument doc = Loader.loadPDF(off.toFile())) {
            assertEquals("Demo.java", doc.getDocumentInformation().getTitle(), "the metadata is unaffected");
        }
    }

    @Test
    void lineNumbersArePageFurnitureNotPartOfTheCode(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("gutter.pdf");
        String code = "alpha();\n    beta();\ngamma();\n";
        CodePdfWriter.write(code, null, true, 4, PdfPageSpec.of("letter").withFooter(false), META, out, List.of());
        // A reader that honours /Artifact gets the code and nothing else…
        assertEquals(
                List.of("alpha();", "    beta();", "gamma();"),
                PdfProbe.text(out).lines().toList());
        assertEquals(
                List.of("1", "2", "3", "4"),
                PdfProbe.artifactText(out).lines().map(String::strip).toList());
        // …one that does not still sees the numbers.
        assertTrue(PdfProbe.rawText(out).contains("1"));
    }

    @Test
    void aWrappedLineBreaksAtASpaceAndIsMarkedAsAContinuation(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("wrap.pdf");
        String comment = "// " + "several words that run on ".repeat(8).strip();
        CodePdfWriter.write(
                "first();\n" + comment + "\nlast();\n", null, true, 4, PdfPageSpec.of("letter"), META, out, List.of());
        List<String> lines = PdfProbe.text(out).lines().toList();
        assertEquals("first();", lines.get(0));
        assertEquals("last();", lines.get(lines.size() - 1));
        List<String> wrapped = lines.subList(1, lines.size() - 1);
        assertTrue(wrapped.size() >= 2, "the comment wraps: " + lines);
        assertEquals(
                comment,
                String.join(" ", wrapped).replaceAll("\\s+", " "),
                "at spaces: every visual line holds whole words");
        // The gutter shows the number once and the mark on each continuation line, at the same baseline.
        String gutter = PdfProbe.artifactText(out, 1);
        assertEquals(wrapped.size() - 1, gutter.chars().filter(c -> c == 0x21AA).count(), gutter);
        assertFalse(PdfProbe.text(out).contains(PdfText.CONTINUATION_MARK), "the mark is not part of the code");
        assertTrue(gutter.lines().map(String::strip).toList().containsAll(List.of("1", "2", "3", "4")), gutter);
    }

    @Test
    void theSpecSetsMarginOrientationAndFontSize(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("spec.pdf");
        PdfPageSpec spec = new PdfPageSpec("a4", true, 72f, 12f, true);
        CodePdfWriter.write("x".repeat(400) + "\n", null, false, 4, spec, META, out, List.of());
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(
                    PDRectangle.A4.getHeight(), doc.getPage(0).getMediaBox().getWidth(), 0.01f);
            assertEquals(PDRectangle.A4.getWidth(), doc.getPage(0).getMediaBox().getHeight(), 0.01f);
        }
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        assertEquals(72f, glyphs.get(0).x(), 0.01f, "the left margin");
        assertEquals(12f * 0.6f, glyphs.get(1).x() - glyphs.get(0).x(), 0.01f, "12 pt JetBrains Mono cells");
        assertTrue(PdfProbe.rightmostInk(out) <= PDRectangle.A4.getHeight() - 72f + 0.5f, "the right margin");
        assertEquals(PDRectangle.A4.getWidth() - 72f - 12f, glyphs.get(0).baseline(), 0.01f, "the top margin");
    }
}
