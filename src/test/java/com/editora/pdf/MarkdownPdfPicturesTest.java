package com.editora.pdf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import javax.imageio.ImageIO;

import com.editora.editor.MarkdownRenderer;
import com.editora.mermaid.Mermaid;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pictures in the Markdown PDF: images among text, Mermaid diagrams, formulas, coloured code. */
class MarkdownPdfPicturesTest {

    private static final float BODY_X_HEIGHT = 11f * 0.546f; // Inter at the 11 pt body size

    private static void png(Path file, int w, int h) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ImageIO.write(bi, "png", file.toFile());
    }

    private static MarkdownPdfWriter.Outcome write(Path dir, String md, List<String> mmdc, boolean math)
            throws Exception {
        return MarkdownPdfWriter.write(
                MarkdownRenderer.parseToDocument(md),
                dir,
                PdfPageSpec.of("letter"),
                PdfDocMeta.NONE,
                mmdc,
                dir.resolve("out.pdf"),
                List.of(),
                math);
    }

    @Test
    void aBadgeRowIsEmbeddedInTheLineNotPrintedAsAltText(@TempDir Path dir) throws Exception {
        png(dir.resolve("ci.png"), 180, 40);
        png(dir.resolve("mit.png"), 160, 40);
        write(dir, "Status: ![CI](ci.png) ![License](mit.png) and text after.\n\nNext paragraph.\n", null, false);
        Path out = dir.resolve("out.pdf");
        String text = PdfProbe.text(out);
        assertFalse(text.contains("[CI]") || text.contains("[License]"), "no alt-text stand-ins: " + text);
        List<PdfProbe.Box> images = PdfProbe.images(out);
        assertEquals(2, images.size(), "both badges are drawn");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        PdfProbe.Glyph status = PdfProbe.glyphsOf(glyphs, "Status:").get(0);
        PdfProbe.Glyph after = PdfProbe.glyphsOf(glyphs, "and").get(0);
        PdfProbe.Glyph next = PdfProbe.glyphsOf(glyphs, "Next").get(0);
        for (PdfProbe.Box img : images) {
            assertTrue(img.height() <= 11f * 1.2f + 0.01f, "at line height, not natural size: " + img);
            assertTrue(img.y() < status.baseline() && img.y() + img.height() > status.baseline(), "on the text line");
            assertTrue(img.y() > next.baseline() + 8f, "clear of the next paragraph");
        }
        assertEquals(images.get(0).height() * 180f / 40f, images.get(0).width(), 0.1f, "aspect ratio kept");
        assertEquals(images.get(1).height() * 160f / 40f, images.get(1).width(), 0.1f);
        assertEquals(status.baseline(), after.baseline(), 0.01f, "the text continues on the same line");
        assertTrue(images.get(0).x() > status.right()
                && images.get(1).x() > images.get(0).x() + images.get(0).width());
        assertTrue(after.x() > images.get(1).x() + images.get(1).width(), "…after the badges");
    }

    @Test
    void aLinkedBadgeAmongTextIsClickable(@TempDir Path dir) throws Exception {
        png(dir.resolve("ci.png"), 180, 40);
        write(dir, "Build [![CI](ci.png)](https://example.com/ci) status.\n", null, false);
        Path out = dir.resolve("out.pdf");
        PdfProbe.Box img = PdfProbe.images(out).get(0);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getPage(0).getAnnotations().size());
            PDAnnotationLink link =
                    (PDAnnotationLink) doc.getPage(0).getAnnotations().get(0);
            assertEquals("https://example.com/ci", ((PDActionURI) link.getAction()).getURI());
            PDRectangle r = link.getRectangle();
            assertEquals(img.x(), r.getLowerLeftX(), 0.1f);
            assertEquals(img.x() + img.width(), r.getUpperRightX(), 0.1f);
            assertTrue(r.getLowerLeftY() <= img.y() + 0.1f && r.getUpperRightY() >= img.y() + img.height() - 0.1f);
        }
    }

    @Test
    void anImageThatCannotBeLoadedFallsBackToItsAltText(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("broken.png"), "not an image");
        write(
                dir,
                "See ![the chart](nope.png) and ![other](broken.png) here.\n\n- ![in a list](nope.png)\n",
                null,
                false);
        String text = PdfProbe.text(dir.resolve("out.pdf"));
        assertTrue(text.contains("See [the chart] and [other] here."), text);
        assertTrue(text.contains("[in a list]"), text);
        assertEquals(0, PdfProbe.images(dir.resolve("out.pdf")).size());
    }

    @Test
    void aPictureAloneInAParagraphLinkOrListItemKeepsItsSize(@TempDir Path dir) throws Exception {
        png(dir.resolve("shot.png"), 200, 100);
        write(
                dir,
                "[![shot](shot.png)](https://example.com/full)\n\n- ![in a list](shot.png)\n- text item\n\nTail.\n",
                null,
                false);
        Path out = dir.resolve("out.pdf");
        List<PdfProbe.Box> images = PdfProbe.images(out);
        assertEquals(2, images.size());
        for (PdfProbe.Box img : images) {
            assertEquals(200f, img.width(), 0.1f, "natural size, not a line-high thumbnail");
            assertEquals(100f, img.height(), 0.1f);
        }
        assertFalse(PdfProbe.text(out).contains("[in a list]"));
        assertTrue(images.get(1).x() > images.get(0).x() + 10f, "the list picture is indented beside its bullet");
        PdfProbe.Glyph item =
                PdfProbe.glyphsOf(PdfProbe.glyphs(out), "textitem").get(0);
        assertTrue(item.baseline() < images.get(1).y() - 5f, "the next item is below the picture");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(1, doc.getPage(0).getAnnotations().size(), "the linked picture is clickable");
            PDRectangle r = doc.getPage(0).getAnnotations().get(0).getRectangle();
            assertEquals(200f, r.getWidth(), 0.1f);
            assertEquals(100f, r.getHeight(), 0.1f);
        }
    }

    @Test
    void anSvgIsDrawnAtItsDeclaredSizeNotItsOversampledBitmap(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("box.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"60\">"
                        + "<rect width=\"120\" height=\"60\" fill=\"#36c\"/></svg>");
        write(dir, "![box](box.svg)\n", null, false);
        PdfProbe.Box img = PdfProbe.images(dir.resolve("out.pdf")).get(0);
        assertEquals(120f, img.width(), 0.5f);
        assertEquals(60f, img.height(), 0.5f);
    }

    /** A stand-in for mmdc: copies {@code png} to the {@code -o} path, or fails when {@code png} is null. */
    private static List<String> fakeMmdc(Path dir, Path png) throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX shell");
        Path script = dir.resolve("fake-mmdc.sh");
        Files.writeString(
                script,
                "#!/bin/sh\nwhile [ $# -gt 0 ]; do\n  if [ \"$1\" = \"-o\" ]; then out=\"$2\"; fi\n  shift\ndone\n"
                        + (png == null ? "echo 'Parse error' >&2\nexit 1\n" : "cp '" + png + "' \"$out\"\n"));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return List.of(script.toString());
    }

    @Test
    void aMermaidDiagramIsPlacedAtItsOwnSizeNotItsPixelSize(@TempDir Path dir) throws Exception {
        // What mmdc returns for a small flowchart: rendered at RENDER_SCALE device pixels per CSS pixel.
        png(dir.resolve("diagram.png"), 500, 910);
        MarkdownPdfWriter.Outcome outcome = write(
                dir,
                "Intro.\n\n```mermaid\nflowchart TD\n A-->B\n```\n\nAfter the diagram.\n",
                fakeMmdc(dir, dir.resolve("diagram.png")),
                false);
        Path out = dir.resolve("out.pdf");
        assertEquals(0, outcome.failedDiagrams());
        PdfProbe.Box img = PdfProbe.images(out).get(0);
        assertEquals(500f / Mermaid.RENDER_SCALE, img.width(), 0.1f);
        assertEquals(910f / Mermaid.RENDER_SCALE, img.height(), 0.1f);
        assertEquals(1, PdfProbe.pages(out), "a small diagram does not need a page of its own");
        assertEquals(1, PdfProbe.glyphsOf(PdfProbe.glyphs(out), "After").get(0).page());
        assertFalse(PdfProbe.text(out).contains("flowchart"));
    }

    @Test
    void aWideMermaidDiagramStillShrinksToThePage(@TempDir Path dir) throws Exception {
        png(dir.resolve("wide.png"), 3000, 600);
        write(dir, "```mermaid\ngantt\n```\n", fakeMmdc(dir, dir.resolve("wide.png")), false);
        PdfProbe.Box img = PdfProbe.images(dir.resolve("out.pdf")).get(0);
        assertEquals(612f - 2 * MarkdownPdfWriter.MARGIN, img.width(), 0.1f);
        assertEquals(img.width() / 5f, img.height(), 0.1f);
    }

    @Test
    void aMermaidBlockThatFailsIsPrintedAsSourceAndCounted(@TempDir Path dir) throws Exception {
        MarkdownPdfWriter.Outcome outcome = write(
                dir,
                "```mermaid\nnot a diagram ((\n```\n\n```mermaid\nalso broken\n```\n\n```java\nint x;\n```\n",
                fakeMmdc(dir, null),
                false);
        assertEquals(2, outcome.failedDiagrams(), "both failures are reported; a code block is not a diagram");
        String text = PdfProbe.text(dir.resolve("out.pdf"));
        assertTrue(text.contains("not a diagram ((") && text.contains("also broken"), "the source is kept: " + text);
        // Without mmdc nothing was attempted, so nothing failed: the blocks are code, as in the preview.
        assertEquals(0, write(dir, "```mermaid\ngraph TD\n```\n", null, false).failedDiagrams());
    }

    @Test
    void inlineMathIsSetAtTheBodyTextsXHeightOnItsBaseline(@TempDir Path dir) throws Exception {
        write(dir, "The letter $x$ beside x, and $y$ with a descender.\n", null, true);
        Path out = dir.resolve("out.pdf");
        List<PdfProbe.Box> images = PdfProbe.images(out);
        assertEquals(2, images.size(), "both formulas are pictures");
        float baseline =
                PdfProbe.glyphsOf(PdfProbe.glyphs(out), "beside").get(0).baseline();
        float inset = 1f / PdfMath.SCALE; // the bitmap's antialiasing border, in points
        PdfProbe.Box x = images.get(0);
        // "x" has no ascender or descender: the picture is exactly one x-height tall, standing on the baseline.
        assertEquals(BODY_X_HEIGHT, x.height() - 2 * inset, 0.35f, "the formula's x is as tall as the text's");
        assertEquals(baseline, x.y() + inset, 0.35f, "…and stands on the baseline");
        PdfProbe.Box y = images.get(1);
        assertTrue(y.y() < baseline - 1.5f, "a descender reaches below the baseline: " + y);
        assertEquals(baseline + BODY_X_HEIGHT, y.y() + y.height() - inset, 0.35f, "its x-height part lines up too");
        // Rendered at several pixels per point, so it stays sharp.
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            int widest = 0;
            for (org.apache.pdfbox.cos.COSName name :
                    doc.getPage(0).getResources().getXObjectNames()) {
                widest = Math.max(
                        widest,
                        ((org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject)
                                        doc.getPage(0).getResources().getXObject(name))
                                .getWidth());
            }
            assertTrue(widest >= x.width() * 3f, "at least 3 px per point: " + widest + " px for " + x.width());
        }
    }

    @Test
    void aTallFormulaPushesItsLineApartInsteadOfOverlapping(@TempDir Path dir) throws Exception {
        write(
                dir,
                "First line of text.  \nA fraction $\\frac{a+b}{c+d}$ here.  \nThird line.\n\n$$\\sum_{i=1}^{n} i$$\n\n"
                        + "Bad $\\nosuchcommand{x}$ stays text.\n",
                null,
                true);
        Path out = dir.resolve("out.pdf");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float first = PdfProbe.glyphsOf(glyphs, "First").get(0).baseline();
        float third = PdfProbe.glyphsOf(glyphs, "Third").get(0).baseline();
        PdfProbe.Box frac = PdfProbe.images(out).get(0);
        assertTrue(frac.height() > 15f, "taller than a line: " + frac);
        assertTrue(frac.y() + frac.height() <= first - 2f, "below the line above: " + frac + " vs " + first);
        assertTrue(frac.y() >= third + 8f, "above the line below: " + frac + " vs " + third);
        assertEquals(2, PdfProbe.images(out).size(), "the display formula is a picture too");
        assertTrue(PdfProbe.text(out).contains("$\\nosuchcommand{x}$"), "invalid LaTeX is shown as its source");
        assertNull(PdfMath.render("\\nosuchcommand{x}", false, 11f, Color.BLACK));
        assertNotNull(PdfMath.render("x", false, 11f, Color.BLACK));
    }

    @Test
    void theFormulaSizeFollowsTheBodyFontsXHeight() {
        assertEquals(11f * 0.546f / PdfMath.X_HEIGHT, PdfMath.emFor(11f, 0.546f), 0.001f);
        assertEquals(11f, PdfMath.emFor(11f, 0f), 0.001f, "no metrics: the nominal size");
        assertEquals(11f, PdfMath.emFor(11f, 0.40f), 0.001f, "never smaller than the text");
        assertEquals(11f * 1.4f, PdfMath.emFor(11f, 0.9f), 0.001f, "…nor absurdly larger");
        PdfMath.Formula x = PdfMath.render("x", false, 100f, Color.BLACK);
        assertEquals(100f * PdfMath.X_HEIGHT, x.height() - 2f / PdfMath.SCALE, 0.6f, "x is one x-height tall");
        assertEquals(1f / PdfMath.SCALE, x.depth(), 0.3f, "and has no depth beyond the bitmap's border");
        assertEquals(x.image().getWidth() / PdfMath.SCALE, x.width(), 0.001f);
    }

    @Test
    void aFencedBlockWithAKnownLanguageIsSyntaxColoured(@TempDir Path dir) throws Exception {
        write(dir, "```java\npublic class A { int x = 1; } // note\n```\n", null, false);
        Path out = dir.resolve("out.pdf");
        assertTrue(PdfProbe.text(out).contains("public class A { int x = 1; } // note"), PdfProbe.text(out));
        java.util.Set<String> coloured = PdfProbe.fillColors(out);
        assertTrue(coloured.contains(rgb(PdfTheme.hex("#cf222e"))), "keywords are red: " + coloured);
        assertTrue(coloured.contains(rgb(PdfTheme.hex("#6e7781"))), "comments are grey: " + coloured);

        write(dir, "```\npublic class A { int x = 1; }\n```\n\n```nosuchlanguage\nint x;\n```\n", null, false);
        java.util.Set<String> plain = PdfProbe.fillColors(out);
        assertFalse(plain.contains(rgb(PdfTheme.hex("#cf222e"))), "no language, no colour: " + plain);
    }

    private static String rgb(Color c) {
        return c.getRed() + "," + c.getGreen() + "," + c.getBlue();
    }
}
