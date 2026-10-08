package com.editora.office;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import com.editora.editor.MarkdownRenderer;
import com.editora.editor.MathImages;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the two Markdown office writers put in the document for the constructs the smoke tests leave out:
 * pictures (local, {@code data:}, SVG, undecodable), math, Mermaid blocks, nested lists, line breaks,
 * underline, indented code and characters XML cannot carry.
 */
class OfficeDocumentContentTest {

    @TempDir
    Path dir;

    // --- fixtures ---------------------------------------------------------------------------------------

    private static byte[] image(String format, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out), format + " is writable");
        return out.toByteArray();
    }

    private record Odt(String xml, List<OdtWriter.Embedded> images) {
        /** The text of every paragraph and heading, one per line; parsing proves the XML is well-formed. */
        String text() throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            org.w3c.dom.Document doc =
                    f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            StringBuilder out = new StringBuilder();
            org.w3c.dom.NodeList all =
                    doc.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:text:1.0", "*");
            for (int i = 0; i < all.getLength(); i++) {
                String name = all.item(i).getLocalName();
                if (name.equals("p") || name.equals("h")) {
                    out.append(all.item(i).getTextContent()).append('\n');
                }
            }
            return out.toString();
        }
    }

    private Odt odt(String md, List<String> mmdc) {
        List<OdtWriter.Embedded> images = new ArrayList<>();
        return new Odt(OdtWriter.contentXml(md, dir, mmdc, images), images);
    }

    private Odt odt(String md) {
        return odt(md, null);
    }

    private XWPFDocument docx(String md, List<String> mmdc) throws IOException {
        Path out = Files.createTempFile(dir, "doc", ".docx");
        DocxWriter.write(md, dir, mmdc, out);
        try (InputStream in = Files.newInputStream(out)) {
            return new XWPFDocument(in);
        }
    }

    private XWPFDocument docx(String md) throws IOException {
        return docx(md, null);
    }

    private static XWPFRun runWith(XWPFDocument doc, String text) {
        for (XWPFParagraph p : doc.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                if (text.equals(r.text())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no run with text '" + text + "' in "
                + doc.getParagraphs().stream().map(XWPFParagraph::getText).toList());
    }

    private static List<String> paragraphs(XWPFDocument doc) {
        return doc.getParagraphs().stream().map(XWPFParagraph::getText).toList();
    }

    /** Runs {@code body} with math rendering switched on, and puts the process-wide switch back after. */
    private static void withMath(ThrowingRunnable body) throws Exception {
        boolean was = MathImages.isEnabled();
        MathImages.configure(true, false);
        try {
            body.run();
        } finally {
            MathImages.configure(was, false);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A stand-in {@code mmdc}: copies {@code png} to the path after {@code -o} (its fourth argument). */
    private List<String> fakeMmdc(byte[] png) throws IOException {
        Path picture = Files.write(dir.resolve("diagram-source.png"), png);
        Path script = dir.resolve("fake-mmdc.sh");
        Files.writeString(script, "#!/bin/sh\ncp '" + picture + "' \"$4\"\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return List.of(script.toString());
    }

    // --- the .odt package -------------------------------------------------------------------------------

    @Test
    void theOdtPackageStartsWithAStoredMimetypeAndListsEveryPictureInItsManifest() throws Exception {
        byte[] png = image("png", 40, 20);
        Files.write(dir.resolve("pic.png"), png);
        Path out = dir.resolve("out.odt");
        OdtWriter.write("# T\n\n![a picture](pic.png)\n", dir, null, out);

        List<String> names = new ArrayList<>();
        byte[] picture = null;
        String manifest = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
                byte[] data = zip.readAllBytes();
                if (e.getName().equals("mimetype")) {
                    assertEquals(ZipEntry.STORED, e.getMethod(), "ODF requires the mimetype entry uncompressed");
                    assertEquals(
                            "application/vnd.oasis.opendocument.text", new String(data, StandardCharsets.US_ASCII));
                } else if (e.getName().equals("Pictures/img0.png")) {
                    picture = data;
                } else if (e.getName().equals("META-INF/manifest.xml")) {
                    manifest = new String(data, StandardCharsets.UTF_8);
                }
            }
        }
        assertEquals(
                List.of("mimetype", "content.xml", "styles.xml", "META-INF/manifest.xml", "Pictures/img0.png"), names);
        assertArrayEquals(png, picture, "the picture is stored byte for byte");
        assertNotNull(manifest);
        assertTrue(
                manifest.contains("manifest:full-path=\"Pictures/img0.png\" manifest:media-type=\"image/png\""),
                manifest);
        assertTrue(manifest.contains("manifest:full-path=\"content.xml\""), manifest);
    }

    // --- pictures ---------------------------------------------------------------------------------------

    @Test
    void aPictureOnItsOwnLineIsEmbeddedScaledToThePageWidth() throws Exception {
        Files.write(dir.resolve("wide.png"), image("png", 960, 96));
        Odt odt = odt("![wide](wide.png)\n");
        assertEquals(1, odt.images().size());
        assertEquals("Pictures/img0.png", odt.images().getFirst().name());
        // 960 px is capped at 480 px = 5 inches = 12.7 cm; the height follows.
        assertTrue(odt.xml().contains("svg:width=\"12.700cm\" svg:height=\"1.270cm\""), odt.xml());
        assertFalse(odt.text().contains("wide"), "an embedded picture does not also print its alt text");

        try (XWPFDocument doc = docx("![wide](wide.png)\n")) {
            assertEquals(1, doc.getAllPictures().size());
            assertEquals(
                    Document.PICTURE_TYPE_PNG, doc.getAllPictures().getFirst().getPictureType());
        }
    }

    @Test
    void eachRasterFormatKeepsItsOwnTypeAndExtension() throws Exception {
        byte[] jpeg = image("jpg", 8, 8);
        byte[] gif = image("gif", 8, 8);
        byte[] bmp = image("bmp", 8, 8);
        Files.write(dir.resolve("a.jpg"), jpeg);
        Files.write(dir.resolve("b.gif"), gif);
        Files.write(dir.resolve("c.bmp"), bmp);
        Odt odt = odt("![a](a.jpg)\n\n![b](b.gif)\n\n![c](c.bmp)\n");
        assertEquals(
                List.of("Pictures/img0.jpg image/jpeg", "Pictures/img1.gif image/gif", "Pictures/img2.bmp image/bmp"),
                odt.images().stream().map(e -> e.name() + " " + e.mediaType()).toList());

        assertEquals(Document.PICTURE_TYPE_JPEG, OfficeImages.poiPictureType(jpeg));
        assertEquals(Document.PICTURE_TYPE_GIF, OfficeImages.poiPictureType(gif));
        assertEquals(Document.PICTURE_TYPE_BMP, OfficeImages.poiPictureType(bmp));
        assertEquals(Document.PICTURE_TYPE_PNG, OfficeImages.poiPictureType(image("png", 8, 8)));
        // Too short to carry a signature, or none at all: treated as PNG rather than failing.
        assertEquals("png", OfficeImages.extension(new byte[] {1, 2}));
        assertEquals("image/png", OfficeImages.mediaType(null));
    }

    @Test
    void aDataUriPictureIsEmbeddedWithoutABaseDirectory() throws Exception {
        String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(image("png", 10, 10));
        List<OdtWriter.Embedded> images = new ArrayList<>();
        String xml = OdtWriter.contentXml("![inline](" + uri + ")\n", null, null, images);
        assertEquals(1, images.size());
        assertTrue(xml.contains("xlink:href=\"Pictures/img0.png\""), xml);
    }

    @Test
    void anSvgPictureIsRasterisedBecauseOfficeFormatsCannotCarryIt() throws Exception {
        Files.writeString(
                dir.resolve("badge.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"10\">"
                        + "<rect width=\"20\" height=\"10\" fill=\"#0a0\"/></svg>");
        byte[] png = OfficeImages.load("badge.svg", dir);
        assertNotNull(png);
        assertEquals("png", OfficeImages.extension(png));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(2.0, decoded.getWidth() / (double) decoded.getHeight(), 0.01, "the aspect ratio is kept");

        Files.writeString(dir.resolve("broken.svg"), "<svg this is not xml");
        assertNull(OfficeImages.load("broken.svg", dir), "an SVG that cannot be drawn is not passed on as bytes");
    }

    @Test
    void aPictureThatCannotBeLoadedOrDecodedLeavesItsAltTextOrItsAddress() throws Exception {
        Files.writeString(dir.resolve("notes.png"), "this is not a picture");
        String md = "![the chart](missing.png)\n\n![](missing-too.png)\n\n![garbled](notes.png)\n";

        Odt odt = odt(md);
        assertEquals(List.of(), odt.images());
        assertEquals("the chart\nmissing-too.png\ngarbled\n", odt.text());

        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("the chart", "missing-too.png", "garbled"), paragraphs(doc));
            assertEquals(0, doc.getAllPictures().size());
            assertTrue(runWith(doc, "the chart").isItalic(), "the stand-in text is marked as such");
            assertTrue(runWith(doc, "garbled").isItalic());
        }
        assertNull(OfficeImages.load(null, dir));
        assertNull(OfficeImages.load("relative.png", null), "a relative path is never resolved against the cwd");
    }

    @Test
    void aPictureInsideASentenceIsReplacedByItsAltText() throws Exception {
        Files.write(dir.resolve("pic.png"), image("png", 4, 4));
        String md = "See ![the *small* `icon`](pic.png) here and ![](pic.png) there.\n";
        Odt odt = odt(md);
        assertEquals(List.of(), odt.images(), "only a picture on its own line is embedded");
        assertEquals("See the small icon here and  there.\n", odt.text());
        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("See the small icon here and  there."), paragraphs(doc));
        }
    }

    // --- math -------------------------------------------------------------------------------------------

    @Test
    void mathIsLeftAsSourceWhileMathRenderingIsOff() throws Exception {
        boolean was = MathImages.isEnabled();
        MathImages.configure(false, false);
        try {
            assertNull(OfficeImages.renderMath("x^2", true));
            Odt odt = odt("$$\nx^2\n$$\n\nInline $a+b$ here.\n");
            assertEquals(List.of(), odt.images());
            assertTrue(odt.text().contains("Inline $a+b$ here."), odt.text());
            try (XWPFDocument doc = docx("Inline $a+b$ here.\n")) {
                assertEquals(List.of("Inline $a+b$ here."), paragraphs(doc));
                assertEquals(0, doc.getAllPictures().size());
            }
        } finally {
            MathImages.configure(was, false);
        }
    }

    @Test
    void displayAndInlineMathBecomePicturesInTheOdt() throws Exception {
        withMath(() -> {
            Odt odt = odt("$$\nx^2 + y^2\n$$\n\nInline $a+b$ here, `$not math$` there.\n");
            assertEquals(
                    List.of("Pictures/img0.png", "Pictures/math1.png"),
                    odt.images().stream().map(OdtWriter.Embedded::name).toList());
            String text = odt.text();
            assertTrue(text.contains("Inline  here, $not math$ there."), "code keeps its dollars: " + text);
            assertTrue(odt.xml().contains("svg:height=\"0.420cm\""), "inline math is sized to the line: " + odt.xml());
        });
    }

    @Test
    void displayMathInsideASentenceIsTallerThanInlineMath() throws Exception {
        withMath(() -> {
            Odt odt = odt("Sum $$\\sum_i x_i$$ done.\n");
            assertEquals(1, odt.images().size());
            assertTrue(odt.xml().contains("svg:height=\"0.850cm\""), odt.xml());
        });
    }

    @Test
    void mathThatDoesNotParseStaysReadableAsItsSource() throws Exception {
        withMath(() -> {
            String md = "Bad $\\nosuchmacro{1}$ inline.\n";
            Odt odt = odt(md);
            assertEquals(List.of(), odt.images());
            assertEquals("Bad $\\nosuchmacro{1}$ inline.\n", odt.text());
            try (XWPFDocument doc = docx(md)) {
                assertEquals(List.of("Bad $\\nosuchmacro{1}$ inline."), paragraphs(doc));
                assertEquals(0, doc.getAllPictures().size());
            }
            // A display block that cannot be drawn falls back to an ordinary paragraph.
            Odt block = odt("$$\n\\nosuchmacro{1}\n$$\n");
            assertEquals(List.of(), block.images());
            assertTrue(block.text().contains("\\nosuchmacro{1}"), block.text());
            try (XWPFDocument doc = docx("$$\n\\nosuchmacro{1}\n$$\n")) {
                assertEquals(1, doc.getParagraphs().size(), "no empty paragraph is left where the picture would be");
                assertTrue(paragraphs(doc).getFirst().contains("\\nosuchmacro{1}"));
            }
        });
    }

    @Test
    void displayAndInlineMathBecomePicturesInTheDocx() throws Exception {
        withMath(() -> {
            try (XWPFDocument doc = docx("$$\nx^2 + y^2\n$$\n\nInline $a+b$ here.\n\n- $$z$$\n")) {
                assertEquals(3, doc.getAllPictures().size(), "the block, the inline span and the list item's block");
                assertTrue(
                        paragraphs(doc).contains("Inline  here."),
                        paragraphs(doc).toString());
                XWPFParagraph inline = doc.getParagraphs().stream()
                        .filter(p -> p.getText().startsWith("Inline"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        1,
                        inline.getRuns().stream()
                                .mapToInt(r -> r.getEmbeddedPictures().size())
                                .sum(),
                        "the formula sits inside its sentence");
            }
        });
    }

    // --- mermaid ----------------------------------------------------------------------------------------

    @Test
    void aMermaidBlockWithoutARendererIsKeptAsCode() throws Exception {
        String md = "```mermaid\ngraph TD; A-->B;\n```\n";
        assertNull(OfficeImages.renderMermaid(null, "graph TD;"));
        assertNull(OfficeImages.renderMermaid(List.of(), "graph TD;"));
        Odt odt = odt(md);
        assertEquals(List.of(), odt.images());
        assertTrue(odt.xml().contains("Preformatted_20_Text"), odt.xml());
        assertEquals("graph TD; A-->B;\n", odt.text());
        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("graph TD; A-->B;"), paragraphs(doc));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aMermaidBlockIsReplacedByTheRenderersPicture() throws Exception {
        byte[] png = image("png", 30, 30);
        List<String> mmdc = fakeMmdc(png);
        String md = "```Mermaid theme\ngraph TD; A-->B;\n```\n\n```sh\necho kept\n```\n";

        Odt odt = odt(md, mmdc);
        assertEquals(1, odt.images().size());
        assertArrayEquals(png, odt.images().getFirst().bytes());
        assertEquals("\necho kept\n", odt.text(), "the diagram source is not also printed; other code is");

        try (XWPFDocument doc = docx(md, mmdc)) {
            assertEquals(1, doc.getAllPictures().size());
            assertArrayEquals(png, doc.getAllPictures().getFirst().getData());
            assertFalse(paragraphs(doc).contains("graph TD; A-->B;"));
            assertTrue(paragraphs(doc).contains("echo kept"));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRendererThatFailsLeavesTheMermaidSourceInTheDocument() throws Exception {
        Path script = dir.resolve("failing-mmdc.sh");
        Files.writeString(script, "#!/bin/sh\necho 'Parse error on line 1' >&2\nexit 1\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        String md = "```mermaid\ngraph oops\n```\n";

        assertNull(OfficeImages.renderMermaid(List.of(script.toString()), "graph oops"));
        assertEquals("graph oops\n", odt(md, List.of(script.toString())).text());
        try (XWPFDocument doc = docx(md, List.of(script.toString()))) {
            assertEquals(List.of("graph oops"), paragraphs(doc));
            assertEquals(0, doc.getAllPictures().size());
        }
    }

    // --- inline styling ---------------------------------------------------------------------------------

    @Test
    void odtInlineStylesNestAndALinkWrapsItsText() throws Exception {
        Odt odt = odt("++under++ and ***both*** and [**site**](https://example.com/?a=1&b=\"2\")\n");
        String xml = odt.xml();
        assertTrue(xml.contains("<text:span text:style-name=\"TUnderline\">under</text:span>"), xml);
        assertTrue(
                xml.contains("<text:span text:style-name=\"TBold\"><text:span text:style-name=\"TItalic\">both"
                        + "</text:span></text:span>"),
                xml);
        assertTrue(
                xml.contains(
                        "<text:a xlink:type=\"simple\" xlink:href=\"https://example.com/?a=1&amp;b=&quot;2&quot;\">"
                                + "<text:span text:style-name=\"TBold\">site</text:span></text:a>"),
                xml);
        assertEquals("under and both and site\n", odt.text());
    }

    @Test
    void docxRunsCarryItalicStrikeUnderlineAndLinks() throws Exception {
        try (XWPFDocument doc = docx("*it* ~~gone~~ ++under++ [site](https://example.com) [++ul++](https://e.org)\n")) {
            assertTrue(runWith(doc, "it").isItalic());
            assertTrue(runWith(doc, "gone").isStrikeThrough());
            assertEquals(UnderlinePatterns.SINGLE, runWith(doc, "under").getUnderline());
            XWPFRun link = runWith(doc, "site");
            assertTrue(link instanceof XWPFHyperlinkRun);
            assertEquals(
                    "https://example.com",
                    ((XWPFHyperlinkRun) link).getHyperlink(doc).getURL());
            assertEquals(UnderlinePatterns.SINGLE, link.getUnderline());
            assertTrue(runWith(doc, "ul") instanceof XWPFHyperlinkRun);
        }
    }

    @Test
    void aLineBreakInsideAParagraphIsKeptAsABreak() throws Exception {
        String md = "first  \nsecond\nthird\n";
        Odt odt = odt(md);
        assertTrue(odt.xml().contains("first<text:line-break/>second<text:line-break/>third"), odt.xml());
        try (XWPFDocument doc = docx(md)) {
            XWPFParagraph p = doc.getParagraphs().getFirst();
            assertEquals(
                    List.of("first\n", "second\n", "third"),
                    p.getRuns().stream().map(XWPFRun::text).toList());
        }
    }

    @Test
    void aSoftBreakIsItsOwnRun() {
        Node paragraph = MarkdownRenderer.parseToDocument("a\nb\n").getFirstChild();
        List<InlineRun> runs = InlineRun.flatten(paragraph);
        assertEquals(List.of("a", "\n", "b"), runs.stream().map(InlineRun::text).toList());
        assertTrue(runs.get(1).isBreak());
        assertFalse(runs.get(0).isBreak());
    }

    // --- headings, rules, code, lists -------------------------------------------------------------------

    @Test
    void docxHeadingsShrinkWithTheirLevel() throws Exception {
        try (XWPFDocument doc = docx("# one\n\n## two\n\n### three\n\n#### four\n\n##### five\n\n###### six\n")) {
            assertEquals(22, runWith(doc, "one").getFontSizeAsDouble().intValue());
            assertEquals(18, runWith(doc, "two").getFontSizeAsDouble().intValue());
            assertEquals(15, runWith(doc, "three").getFontSizeAsDouble().intValue());
            assertEquals(13, runWith(doc, "four").getFontSizeAsDouble().intValue());
            assertEquals(12, runWith(doc, "five").getFontSizeAsDouble().intValue());
            assertEquals(12, runWith(doc, "six").getFontSizeAsDouble().intValue());
            assertTrue(runWith(doc, "six").isBold());
            assertEquals("Heading6", doc.getParagraphs().get(5).getStyle());
        }
    }

    @Test
    void aThematicBreakIsARuledEmptyParagraphInTheDocx() throws Exception {
        try (XWPFDocument doc = docx("above\n\n---\n\nbelow\n")) {
            assertEquals(List.of("above", "", "below"), paragraphs(doc));
            assertEquals(
                    org.apache.poi.xwpf.usermodel.Borders.SINGLE,
                    doc.getParagraphs().get(1).getBorderBottom());
        }
    }

    @Test
    void indentedCodeKeepsItsLinesAndWhitespace() throws Exception {
        String md = "text\n\n    plain\n    a  b\tc d\n";
        Odt odt = odt(md);
        assertTrue(
                odt.xml()
                        .contains(
                                "<text:p text:style-name=\"Preformatted_20_Text\">a<text:s text:c=\"2\"/>b<text:tab/>c d"
                                        + "</text:p>"),
                odt.xml());
        try (XWPFDocument doc = docx(md)) {
            assertEquals("text", paragraphs(doc).getFirst());
            assertEquals("plain", paragraphs(doc).get(1));
            assertEquals(
                    runWith(doc, "a  b\tc d").getFontFamily(),
                    runWith(doc, "plain").getFontFamily());
            assertNotNull(runWith(doc, "plain").getFontFamily(), "code is set in the monospace face");
            assertNull(runWith(doc, "text").getFontFamily());
            assertEquals(3, doc.getParagraphs().size());
        }
    }

    @Test
    void nestedListsKeepTheirNestingKindAndNumbering() throws Exception {
        String md = "- outer\n  - inner bullet\n  1. inner one\n  2. inner two\n\n3. three\n4. four\n";
        Odt odt = odt(md);
        assertTrue(
                odt.xml()
                        .contains("<text:list text:style-name=\"L1\"><text:list-item>"
                                + "<text:p text:style-name=\"Standard\">outer</text:p>"
                                + "<text:list text:style-name=\"L1\">"),
                odt.xml());
        assertTrue(odt.xml().contains("</text:list><text:list text:style-name=\"L2\"><text:list-item>"), odt.xml());
        assertEquals("outer\ninner bullet\ninner one\ninner two\nthree\nfour\n", odt.text());

        try (XWPFDocument doc = docx(md)) {
            assertEquals(
                    List.of("• outer", "• inner bullet", "1. inner one", "2. inner two", "3. three", "4. four"),
                    paragraphs(doc));
            assertEquals(360, doc.getParagraphs().get(0).getIndentationLeft());
            assertEquals(720, doc.getParagraphs().get(1).getIndentationLeft(), "a nested item is indented further");
        }
    }

    @Test
    void aListItemThatIsOnlyAPictureOrARuleStillShowsItsMarker() throws Exception {
        Files.write(dir.resolve("pic.png"), image("png", 4, 4));
        try (XWPFDocument doc = docx("1. ![p](pic.png)\n2. ---\n")) {
            assertEquals("1. ", paragraphs(doc).getFirst());
            assertEquals(1, doc.getAllPictures().size());
            assertTrue(paragraphs(doc).contains("2. "), paragraphs(doc).toString());
        }
    }

    @Test
    void aQuoteKeepsBlocksOtherThanParagraphs() throws Exception {
        String md = "> quoted\n>\n> - listed\n>\n>     coded\n";
        Odt odt = odt(md);
        assertTrue(odt.xml().contains("<text:p text:style-name=\"Quotations\">quoted</text:p>"), odt.xml());
        assertEquals("quoted\nlisted\ncoded\n", odt.text());
        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("quoted", "• listed", "coded"), paragraphs(doc));
            assertEquals(480, doc.getParagraphs().getFirst().getIndentationLeft());
        }
    }

    @Test
    void aFootnoteThatStartsWithABlockOrIsEmptyStillShowsItsLabel() throws Exception {
        String md = "Claim[^a] and[^b].\n\n[^a]:\n    - a list first\n\n[^b]:\n\nTail.\n";
        Odt odt = odt(md);
        String text = odt.text();
        assertTrue(text.startsWith("Claim[a] and[b].\n"), text);
        assertTrue(text.contains("[a] \na list first\n"), text);
        assertTrue(text.contains("[b] \n"), text);
        try (XWPFDocument doc = docx(md)) {
            List<String> all = paragraphs(doc);
            assertTrue(all.contains("[a] "), all.toString());
            assertTrue(all.contains("• a list first"), all.toString());
            assertTrue(all.contains("[b] "), all.toString());
        }
    }

    @Test
    void aTableWithShortRowsIsPaddedToTheHeaderWidth() throws Exception {
        String md = "| A | B | C |\n|---|---|---|\n| 1 |\n";
        Odt odt = odt(md);
        assertTrue(odt.xml().contains("<table:table"), odt.xml());
        try (XWPFDocument doc = docx(md)) {
            assertEquals(1, doc.getTables().size());
            assertEquals(3, doc.getTables().getFirst().getRow(1).getTableCells().size());
            assertEquals("1", doc.getTables().getFirst().getRow(1).getCell(0).getText());
            assertEquals("", doc.getTables().getFirst().getRow(1).getCell(2).getText());
        }
    }

    // --- text XML cannot carry --------------------------------------------------------------------------

    @Test
    void charactersXmlForbidsAreReplacedSoTheDocumentStillOpens() throws Exception {
        assertEquals("", OdtWriter.stripInvalidXml(null));
        assertEquals("", OdtWriter.stripInvalidXml(""));
        String clean = "tab\tnl\ncr\r ok 😀 �";
        assertTrue(clean == OdtWriter.stripInvalidXml(clean), "a clean string is returned as it is, not copied");
        assertEquals("a�b", OdtWriter.stripInvalidXml("a\u000Cb"));
        assertEquals("�x", OdtWriter.stripInvalidXml("\uDE00x"), "a low surrogate with nothing before it");
        assertEquals("x�", OdtWriter.stripInvalidXml("x\uD83D"), "a high surrogate at the end");
        assertEquals("x�y", OdtWriter.stripInvalidXml("x\uD83Dy"), "a high surrogate followed by a letter");
        assertEquals("x�y", OdtWriter.stripInvalidXml("x\uDE00y"), "a low surrogate after a letter");
        assertEquals("��", OdtWriter.stripInvalidXml("￾￿"));

        Odt odt = odt("page\u000Cbreak and bell\u0007 1 < 2 & more\n");
        assertEquals("page�break and bell� 1 < 2 & more\n", odt.text()); // parsing proves well-formedness
    }

    @Test
    void theInfoStringDecidesWhatCountsAsMermaid() {
        assertTrue(InlineRun.isMermaidInfo("mermaid"));
        assertTrue(InlineRun.isMermaidInfo("  MERMAID {theme: dark}"));
        assertFalse(InlineRun.isMermaidInfo("mermaidx"));
        assertFalse(InlineRun.isMermaidInfo(""));
        assertFalse(InlineRun.isMermaidInfo(null));
        assertEquals("[]", InlineRun.footnoteMarker(null));
    }

    @Test
    void onlyAParagraphThatIsOneDisplayFormulaCountsAsABlockOfMath() {
        assertEquals("x", soleMath("$$x$$\n"));
        assertEquals("a b", soleMath("$$\na\nb\n$$\n").replaceAll("\\s+", " ").strip());
        assertNull(soleMath("$x$\n"), "inline math is not a block");
        assertNull(soleMath("before $$x$$\n"));
        assertNull(soleMath("$$x$$ after\n"));
        assertNull(soleMath("$$x$$ and $$y$$\n"));
        assertNull(soleMath("**$$x$$**\n"), "styled text is an ordinary paragraph");
        assertNull(soleMath("plain\n"));
    }

    private static String soleMath(String md) {
        return InlineRun.soleDisplayMath(MarkdownRenderer.parseToDocument(md).getFirstChild());
    }
}
