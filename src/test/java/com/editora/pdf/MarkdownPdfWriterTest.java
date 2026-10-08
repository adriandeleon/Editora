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

    // --- layout: tables, lists, spacing, pagination (the page geometry is Letter, 50 pt margins) ---------

    private static final float PAGE_MARGIN = 50f;

    private static Path layout(Path dir, String md) throws Exception {
        Path out = dir.resolve("layout.pdf");
        MarkdownPdfWriter.write(MarkdownRenderer.parseToDocument(md), dir, "letter", null, out, List.of());
        return out;
    }

    @Test
    void aTableRowTallerThanAPageSplitsAcrossPagesWithoutLosingText(@TempDir Path dir) throws Exception {
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            cell.append("Sentence number ")
                    .append(i)
                    .append(" of forty has enough words to wrap in its column, ")
                    .append("so that the whole cell is far taller than one page end")
                    .append(i)
                    .append(". ");
        }
        Path out = layout(
                dir,
                "Intro.\n\n| Key | Value |\n|---|---|\n| small | x |\n| big | " + cell
                        + "|\n| after | y |\n\nTail paragraph.\n");

        String text = PdfProbe.squeezed(out);
        for (int i = 1; i <= 40; i++) {
            assertTrue(text.contains("Sentencenumber" + i + "offorty"), "sentence " + i + " start is lost");
            assertTrue(text.contains("pageend" + i + "."), "sentence " + i + " end is lost");
        }
        assertTrue(text.contains("aftery") && text.contains("Tailparagraph."), "what follows the row survives");
        assertTrue(PdfProbe.pages(out) >= 3, "the row spans pages: " + PdfProbe.pages(out));
        for (PdfProbe.Glyph g : PdfProbe.glyphs(out)) {
            assertTrue(g.baseline() >= PAGE_MARGIN, "drawn below the bottom margin: " + g);
        }
        for (PdfProbe.Box b : PdfProbe.boxes(out)) {
            assertTrue(b.y() >= PAGE_MARGIN - 0.01f, "a row border runs below the bottom margin: " + b);
        }
    }

    @Test
    void aTableRowThatFitsAPageIsNotSplit(@TempDir Path dir) throws Exception {
        // Enough filler that the three-line row cannot finish on page 1: it moves to page 2 whole.
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < 29; i++) {
            md.append("filler ").append(i).append("\n\n");
        }
        md.append("| A |\n|---|\n| rowstart ").append("word ".repeat(40)).append("rowend |\n");
        Path out = layout(dir, md.toString());
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        int startPage = PdfProbe.glyphsOf(glyphs, "rowstart").get(0).page();
        assertEquals(startPage, PdfProbe.glyphsOf(glyphs, "rowend").get(0).page(), "the row stays on one page");
    }

    @Test
    void taskListItemsKeepTheirCheckboxAndState(@TempDir Path dir) throws Exception {
        Path out = layout(dir, "- [ ] task open\n- [x] task done\n- plain bullet\n");
        String text = PdfProbe.text(out);
        // The state is extractable in its Markdown form (drawn invisibly over the vector box) …
        assertTrue(text.contains("[ ] task open"), text);
        assertTrue(text.contains("[x] task done"), text);
        assertTrue(text.contains("• plain bullet"), text);
        assertFalse(text.contains("• task"), "a task item has a box, not a bullet: " + text);
        // … and visible as a square per task item, with a tick (two strokes) in the done one only.
        List<PdfProbe.Box> squares = PdfProbe.boxes(out).stream()
                .filter(b -> Math.abs(b.width() - b.height()) < 0.01f && b.width() < 12f)
                .toList();
        assertEquals(2, squares.size(), "one checkbox per task item: " + squares);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float openLine = PdfProbe.glyphsOf(glyphs, "open").get(0).baseline();
        float doneLine = PdfProbe.glyphsOf(glyphs, "done").get(0).baseline();
        List<PdfProbe.Segment> strokes = PdfProbe.segments(out);
        assertEquals(2, strokes.stream().filter(s -> onLine(s, doneLine)).count(), "the done box is ticked");
        assertEquals(0, strokes.stream().filter(s -> onLine(s, openLine)).count(), "the open box is empty");
    }

    private static boolean onLine(PdfProbe.Segment s, float baseline) {
        return s.y1() > baseline - 3f && s.y1() < baseline + 12f;
    }

    @Test
    void orderedListMarkersAreRightAlignedAndClearOfTheText(@TempDir Path dir) throws Exception {
        Path out = layout(dir, "98. ninetyeight\n99. ninetynine\n100. onehundred\n101. onehundredone\n");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float dotRight = -1f;
        float textLeft = -1f;
        for (String[] item : new String[][] {
            {"98.", "ninetyeight"}, {"99.", "ninetynine"}, {"100.", "onehundred"}, {"101.", "onehundredone"}
        }) {
            List<PdfProbe.Glyph> marker = PdfProbe.glyphsOf(glyphs, item[0]);
            PdfProbe.Glyph first = PdfProbe.glyphsOf(glyphs, item[1]).get(0);
            float right = marker.get(marker.size() - 1).right();
            assertTrue(first.x() >= right + 3f, item[0] + " overprints its text: " + right + " vs " + first.x());
            if (dotRight < 0) {
                dotRight = right;
                textLeft = first.x();
            }
            assertEquals(dotRight, right, 0.05f, "markers are right-aligned (" + item[0] + ")");
            assertEquals(textLeft, first.x(), 0.05f, "item text shares one indent (" + item[0] + ")");
        }
    }

    @Test
    void aShortOrderedListKeepsTheUsualIndent(@TempDir Path dir) throws Exception {
        List<PdfProbe.Glyph> ordered = PdfProbe.glyphs(layout(dir, "1. first\n2. second\n"));
        List<PdfProbe.Glyph> bullets = PdfProbe.glyphs(layout(dir, "- first\n- second\n"));
        assertEquals(
                PdfProbe.glyphsOf(bullets, "first").get(0).x(),
                PdfProbe.glyphsOf(ordered, "first").get(0).x(),
                0.05f,
                "single-digit numbers do not widen the indent");
    }

    @Test
    void blocksAfterATableKeepClearOfIt(@TempDir Path dir) throws Exception {
        Path out = layout(dir, "| A |\n|---|\n| lastrow |\n\nfollowing paragraph\n");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float tableBottom = PdfProbe.boxes(out).stream()
                .map(PdfProbe.Box::y)
                .min(Float::compare)
                .orElseThrow();
        float next = PdfProbe.glyphsOf(glyphs, "following").get(0).baseline();
        // An 11 pt line needs ~12 pt above its baseline; anything less and it touches the border.
        assertTrue(tableBottom - next >= 18f, "paragraph touches the table: " + tableBottom + " vs " + next);

        // A code block's grey strip starts under the table, not over its last row.
        out = layout(dir, "| A |\n|---|\n| lastrow |\n\n```\ncode\n```\n");
        List<PdfProbe.Box> boxes = PdfProbe.boxes(out);
        PdfProbe.Box strip = boxes.get(boxes.size() - 1); // drawn last
        float rowBottom = boxes.subList(0, boxes.size() - 1).stream()
                .map(PdfProbe.Box::y)
                .min(Float::compare)
                .orElseThrow();
        assertTrue(strip.y() + strip.height() <= rowBottom - 4f, "the code strip overlaps the table: " + strip);
    }

    @Test
    void aRuleSitsBetweenItsNeighboursNotThroughTheNextLine(@TempDir Path dir) throws Exception {
        Path out = layout(dir, "before\n\n---\n\n[^1]: footnote text\n\nSee[^1].\n");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        List<PdfProbe.Segment> rules = PdfProbe.segments(out).stream()
                .filter(PdfProbe.Segment::horizontal)
                .toList();
        assertEquals(1, rules.size(), rules.toString());
        float ruleY = rules.get(0).y1();
        float above = PdfProbe.glyphsOf(glyphs, "before").get(0).baseline();
        float below = PdfProbe.glyphsOf(glyphs, "footnote").get(0).baseline();
        assertTrue(ruleY - below >= 14f, "the rule strikes through the next line: " + ruleY + " vs " + below);
        assertTrue(above - ruleY >= 8f, "the rule touches the line above: " + above + " vs " + ruleY);
    }

    @Test
    void textAfterAnImageKeepsClearOfIt(@TempDir Path dir) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(40, 30, 1);
        javax.imageio.ImageIO.write(img, "png", dir.resolve("pic.png").toFile());
        Path out = layout(dir, "top line\n\n![pic](pic.png)\n\nafter image\n");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float top = PdfProbe.glyphsOf(glyphs, "top").get(0).baseline();
        float after = PdfProbe.glyphsOf(glyphs, "after").get(0).baseline();
        List<PdfProbe.Box> images = PdfProbe.images(out);
        assertEquals(1, images.size(), images.toString());
        PdfProbe.Box image = images.get(0);
        assertEquals(30f, image.height(), 0.01f);
        // The same 8 pt gap on both sides: under the line above (whose box ends 3 pt below its baseline)
        // and over the line below (whose box starts 12 pt above its baseline).
        assertEquals(3f + 8f, top - (image.y() + image.height()), 0.1f, "gap above the image");
        assertEquals(8f + 12f, image.y() - after, 0.1f, "gap below the image");
    }

    @Test
    void aHeadingIsNeverLeftAloneAtTheFootOfAPage(@TempDir Path dir) throws Exception {
        // Slide the heading down the page one line at a time: wherever it lands, its section's first two
        // lines are on the same page.
        boolean brokeBefore = false;
        for (int filler = 36; filler <= 50; filler++) {
            String md = "x  \n".repeat(filler) + "\n## Zzheading\n\n" + "Zzbody " + "word ".repeat(40) + "\n";
            List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(layout(dir, md));
            PdfProbe.Glyph heading = PdfProbe.glyphsOf(glyphs, "Zzheading").get(0);
            PdfProbe.Glyph body = PdfProbe.glyphsOf(glyphs, "Zzbody").get(0);
            assertEquals(heading.page(), body.page(), "orphaned heading with " + filler + " filler lines");
            long bodyLinesWithHeading = glyphs.stream()
                    .filter(g -> g.page() == heading.page() && g.baseline() < heading.baseline())
                    .map(PdfProbe.Glyph::baseline)
                    .distinct()
                    .count();
            assertTrue(bodyLinesWithHeading >= 2, "one body line under the heading with " + filler + " lines");
            brokeBefore |= heading.page() == 2 && heading.baseline() > 700f;
        }
        assertTrue(brokeBefore, "the probe must cover a heading pushed to the top of page 2");
    }

    @Test
    void aBlockQuoteHasItsBarOnEveryPageItSpans(@TempDir Path dir) throws Exception {
        StringBuilder md = new StringBuilder("intro\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("> quoted paragraph ").append(i).append("\n>\n");
        }
        Path out = layout(dir, md.toString());
        int pages = PdfProbe.pages(out);
        assertTrue(pages >= 2, "the quote spans pages: " + pages);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        for (int page = 1; page <= pages; page++) {
            int p = page;
            List<PdfProbe.Segment> bars = PdfProbe.segments(out).stream()
                    .filter(s -> s.page() == p && s.vertical())
                    .toList();
            assertEquals(1, bars.size(), "one bar segment on page " + page + ": " + bars);
            List<Float> lines = glyphs.stream()
                    .filter(g -> g.page() == p && g.text().equals("q"))
                    .map(PdfProbe.Glyph::baseline)
                    .toList();
            float firstLine = lines.stream().max(Float::compare).orElseThrow();
            float lastLine = lines.stream().min(Float::compare).orElseThrow();
            PdfProbe.Segment bar = bars.get(0);
            assertTrue(Math.max(bar.y1(), bar.y2()) >= firstLine + 8f, "bar starts at the first line's top");
            assertTrue(Math.min(bar.y1(), bar.y2()) <= lastLine, "bar reaches the last line on page " + page);
            assertTrue(Math.min(bar.y1(), bar.y2()) >= PAGE_MARGIN - 12f, "bar stays on the page: " + bar);
        }
    }

    @Test
    void strikethroughIsDrawnStruck(@TempDir Path dir) throws Exception {
        Path out = layout(dir, "keep ~~gone away~~ stay\n");
        assertTrue(PdfProbe.text(out).contains("keep gone away stay"), PdfProbe.text(out));
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        List<PdfProbe.Glyph> gone = PdfProbe.glyphsOf(glyphs, "gone");
        List<PdfProbe.Glyph> away = PdfProbe.glyphsOf(glyphs, "away");
        float from = gone.get(0).x();
        float to = away.get(away.size() - 1).right();
        float baseline = gone.get(0).baseline();
        List<PdfProbe.Segment> lines = PdfProbe.segments(out).stream()
                .filter(PdfProbe.Segment::horizontal)
                .toList();
        assertFalse(lines.isEmpty(), "no strike line drawn");
        float min = Float.MAX_VALUE;
        float max = 0f;
        for (PdfProbe.Segment s : lines) {
            assertTrue(s.y1() > baseline + 1.5f && s.y1() < baseline + 6f, "through the x-height, not under: " + s);
            min = Math.min(min, Math.min(s.x1(), s.x2()));
            max = Math.max(max, Math.max(s.x1(), s.x2()));
        }
        assertEquals(from, min, 0.1f, "the line starts at the struck text");
        assertEquals(to, max, 0.1f, "and ends with it — neighbours are not struck");
    }
}
