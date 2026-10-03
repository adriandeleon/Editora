package com.editora.pdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.MarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownPdfWriterTest {

    @Test
    void writesMarkdownPdfWithCommonElements(@TempDir Path dir) throws Exception {
        String md = """
                # Title

                A paragraph with **bold**, *italic*, `code`, and a [link](https://example.com) that is
                long enough to wrap across more than a single line in the output document for sure.

                - bullet one
                - bullet two

                1. first
                2. second

                > a block quote

                | A | B |
                |---|---|
                | 1 | 2 |

                ```java
                int x = 1;
                ```

                ```mermaid
                flowchart TD
                  A --> B
                ```

                ---
                """;
        Path out = dir.resolve("md.pdf");
        // mmdcCommand=null => the mermaid block degrades to a code block (no external tool needed).
        MarkdownPdfWriter.write(md, dir, "letter", null, out);

        assertTrue(Files.exists(out));
        byte[] bytes = Files.readAllBytes(out);
        assertEquals("%PDF", new String(bytes, 0, 4));
        assertTrue(bytes.length > 800, "non-trivial PDF: " + bytes.length);
        String text = PdfProbe.text(out);
        assertTrue(text.contains("A paragraph with bold, italic, code, and a link that is"), text);
        assertTrue(text.contains("• bullet one") && text.contains("2. second"), text);
        assertTrue(text.contains("a block quote"), text);
        assertTrue(text.contains("int x = 1;"), text);
        assertTrue(text.contains("  A --> B"), "the mermaid block degrades to its source: " + text);
    }

    @Test
    void emptyMarkdownStillProducesValidPdf(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("empty.pdf");
        MarkdownPdfWriter.write("", null, "a4", null, out);
        assertEquals("%PDF", new String(Files.readAllBytes(out), 0, 4));
    }

    @Test
    void unicodeSymbolsDoNotAbortExport(@TempDir Path dir) throws Exception {
        // Characters outside WinAnsi (arrows, checks, math) previously threw in width measurement.
        // Note: `phase‑N` uses a NON-BREAKING HYPHEN (U+2011) inside inline code — the embedded
        // mono font has no glyph for it and threw at draw time even though width measurement passed.
        String md = """
                # Flow A → B ⇒ C

                Steps: up ↑, down ↓, ok ✓, fail ✗, ≠ ≤ ≥, 3 × 4, an em… ellipsis.

                Inline code with a non‑breaking hyphen: `phase‑N‑tasks.md`.

                | Stage | Result |
                |-------|--------|
                | parse → render | ✓ |
                """;
        Path out = dir.resolve("uni.pdf");
        MarkdownPdfWriter.write(md, dir, "letter", null, out);
        assertEquals("%PDF", new String(Files.readAllBytes(out), 0, 4));
    }

    // --- content: what a reader of the PDF actually gets ---------------------------------------------

    private static final float LETTER_RIGHT = 612f - 50f; // page width minus the writer's margin

    private static Path bundledFont(String rel) throws Exception {
        return Path.of(MarkdownPdfWriterTest.class
                .getResource("/com/editora/fonts/" + rel)
                .toURI());
    }

    private static int write(String md, Path out, List<Path> fallbackFonts) throws Exception {
        return MarkdownPdfWriter.write(MarkdownRenderer.parseToDocument(md), null, "letter", null, out, fallbackFonts);
    }

    @Test
    void inlineStyleBoundariesDoNotInsertSpaces(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("inline.pdf");
        write("Hello **world**! (see [docs](u)) and un*real*ly `Ctrl`+`C`.\n", out, List.of());
        assertEquals(
                "Hello world! (see docs) and unreally Ctrl+C.",
                PdfProbe.text(out).strip());
    }

    @Test
    void realWhitespaceAroundInlineStylesIsKept(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("spaces.pdf");
        write("""
                one *two* three **four**five `six` seven
                eight after a soft break, a [two word](u) link and `a b` code.

                # Head *ing* x
                """, out, List.of());
        String text = PdfProbe.text(out);
        assertTrue(text.contains("one two three fourfive six seven eight after a soft break,"), text);
        assertTrue(text.contains("a two word link and a b code."), text);
        assertTrue(text.contains("Head ing x"), text);
    }

    @Test
    void codeBlocksWrapLongLinesAndExpandTabs(@TempDir Path dir) throws Exception {
        String longLine = "abcdefghij".repeat(20); // 200 columns — far wider than the page
        Path out = dir.resolve("code.pdf");
        int missing = write("```\n" + longLine + "\n\tindented\ta\n```\n", out, List.of());

        assertEquals(0, missing, "a tab is not an unrenderable character");
        String text = PdfProbe.text(out);
        assertFalse(text.contains("?"), "the tab used to be drawn as '?': " + text);
        assertTrue(PdfProbe.squeezed(out).startsWith(longLine), "every character of the long line survives");
        assertTrue(text.lines().anyMatch(l -> l.equals("    indented    a")), "tab stops are 4 columns: " + text);
        assertTrue(text.lines().count() >= 4, "the long line was wrapped onto continuation lines: " + text);
        assertTrue(
                PdfProbe.rightmostInk(out) <= LETTER_RIGHT + 0.5f,
                "nothing is drawn past the right margin (was clipped)");
    }

    @Test
    void aWordWiderThanTheLineIsBrokenNotClipped(@TempDir Path dir) throws Exception {
        String url = "https://example.com/" + "segment/".repeat(30) + "end";
        String id = "VeryLongIdentifierWithoutAnyBreakOpportunity".repeat(4);
        Path out = dir.resolve("long.pdf");
        write(
                "See " + url + " now.\n\n`" + id + "`\n\n| key | value |\n|---|---|\n| k | " + id + " |\n",
                out,
                List.of());
        String squeezed = PdfProbe.squeezed(out);
        assertTrue(squeezed.contains(url), "the whole URL is in the PDF");
        assertEquals(2, squeezed.split(id, -1).length - 1, "inline code and the table cell both keep the identifier");
        assertTrue(PdfProbe.rightmostInk(out) <= LETTER_RIGHT + 0.5f, "long words are hard-broken inside the margin");
    }

    @Test
    void htmlBlocksInlineHtmlAndFootnotesAreNotDropped(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("misc.pdf");
        write("""
                Text with a note[^n] and <kbd>Enter</kbd>.

                <details>
                <summary>More</summary>
                </details>

                <!-- hidden comment -->

                [^n]: The footnote body.
                """, out, List.of());
        String text = PdfProbe.text(out);
        assertTrue(text.contains("note[n]"), text);
        assertTrue(text.contains("<kbd>Enter</kbd>"), text);
        assertTrue(text.contains("<summary>More</summary>"), text);
        assertTrue(text.contains("[n] The footnote body."), text);
        assertFalse(text.contains("hidden comment"), text);
    }

    @Test
    void charactersNoFontCoversAreCountedNotSilentlyReplaced(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("cjk.pdf");
        // No fallback fonts at all: the bundled Inter / JetBrains Mono have no CJK or Hebrew glyphs.
        int missing = write("# 中文\n\nx中y and שלום\n\n```\n日本\n```\n\nA → B\n", out, List.of());
        assertEquals(2 + 1 + 4 + 2, missing, "every '?' written is reported");
        String text = PdfProbe.text(out);
        assertTrue(text.contains("x?y and ????"), text);
        assertTrue(text.contains("A → B") || text.contains("A -> B"), "an arrow is never a counted loss: " + text);

        assertEquals(0, write("plain ASCII, déjà vu, Ελληνικά, Кириллица\n", dir.resolve("ok.pdf"), List.of()));
    }

    @Test
    void aFallbackFontSuppliesGlyphsThePrimaryFontLacks(@TempDir Path dir) throws Exception {
        // Box-drawing characters are in the bundled mono font but not in Inter (the prose font) — standing in
        // here for "a system font that has what the bundled one lacks", so the test needs no installed font.
        String md = "tree ├── leaf\n";
        Path without = dir.resolve("without.pdf");
        assertEquals(3, write(md, without, List.of()));
        assertTrue(PdfProbe.text(without).contains("tree ??? leaf"), PdfProbe.text(without));

        Path with = dir.resolve("with.pdf");
        List<Path> fallback = List.of(
                dir.resolve("not-a-font.ttf"), // an unreadable candidate is skipped, not fatal
                bundledFont("jetbrains-mono/JetBrainsMono-Regular.ttf"));
        assertEquals(0, write(md, with, fallback), "the fallback font covers them: nothing is reported missing");
        assertTrue(PdfProbe.text(with).contains("tree ├── leaf"), PdfProbe.text(with));
    }

    @Test
    void anImageWhosePathNeedsAngleBracketsIsEmbedded(@TempDir Path dir) throws Exception {
        Path assets = Files.createDirectories(dir.resolve("assets"));
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(
                bi, "png", assets.resolve("image (1) shot.png").toFile());
        String md = com.editora.markdown.MarkdownImagePaste.snippet("assets/image (1) shot.png", "the shot") + "\n";
        Path out = dir.resolve("img.pdf");
        MarkdownPdfWriter.write(MarkdownRenderer.parseToDocument(md), dir, "letter", null, out, List.of());
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            int images = 0;
            for (org.apache.pdfbox.cos.COSName name :
                    doc.getPage(0).getResources().getXObjectNames()) {
                if (doc.getPage(0).getResources().isImageXObject(name)) {
                    images++;
                }
            }
            assertEquals(1, images, "the picture is in the PDF");
        }
        assertFalse(PdfProbe.text(out).contains("the shot"), "…not its alt-text fallback");
    }

    @Test
    void glyphRunsSubstituteCountAndFallBack() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
                PdfGlyphs none = new PdfGlyphs(doc, List.of())) {
            var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            assertEquals("A -> B", none.rendered(font, "A → B"));
            assertEquals("plain ascii", none.rendered(font, "plain ascii"));
            assertEquals("a b", none.rendered(font, "a\tb"), "a tab is a space, never '?'");
            // An unmapped, unencodable char (CJK) degrades to '?' — and is counted once it is drawn.
            assertEquals("x?y", none.rendered(font, "x中y"));
            assertEquals(0, none.missing(), "measuring does not count");
            assertEquals(1, none.runs(font, "x中y", true).size());
            assertEquals(1, none.missing());
        }
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
                PdfGlyphs glyphs =
                        new PdfGlyphs(doc, List.of(bundledFont("jetbrains-mono/JetBrainsMono-Regular.ttf")))) {
            var font = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            List<PdfGlyphs.Run> runs = glyphs.runs(font, "a├b", true);
            assertEquals(3, runs.size(), "primary, fallback, primary");
            assertEquals("├", runs.get(1).text());
            assertTrue(runs.get(1).font() != font);
            assertEquals(0, glyphs.missing());
        }
    }

    @Test
    void systemFontDiscoveryKeepsOnlyKnownFacesInPriorityOrder(@TempDir Path dir) throws Exception {
        Path noto = Files.createDirectories(dir.resolve("truetype/noto"));
        Files.createFile(noto.resolve("NotoSans-Regular.ttf"));
        Files.createFile(noto.resolve("NotoSansThai-Regular.ttf"));
        Files.createFile(dir.resolve("truetype/SomeDisplayFace.ttf"));
        Path cjk = Files.createDirectories(dir.resolve("opentype/noto"));
        Files.createFile(cjk.resolve("NotoSansCJK-Regular.ttc"));

        List<Path> found = SystemFontFiles.find(List.of(dir.resolve("missing"), dir));
        assertEquals(
                List.of(
                        cjk.resolve("NotoSansCJK-Regular.ttc"),
                        noto.resolve("NotoSans-Regular.ttf"),
                        noto.resolve("NotoSansThai-Regular.ttf")),
                found,
                "CJK first, then broad coverage, then per-script; unknown faces are never probed");

        assertEquals(
                List.of(Path.of("C:\\Windows", "Fonts")),
                SystemFontFiles.roots("Windows 11", "C:\\Users\\me", java.util.Map.of("WINDIR", "C:\\Windows")));
        assertTrue(SystemFontFiles.roots("Mac OS X", "/Users/me", java.util.Map.of())
                .contains(Path.of("/System/Library/Fonts")));
        assertTrue(
                SystemFontFiles.roots("Linux", "/home/me", java.util.Map.of()).contains(Path.of("/usr/share/fonts")));
        SystemFontFiles.get(); // discovery on the real machine never throws
    }
}
