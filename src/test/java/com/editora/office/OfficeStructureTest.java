package com.editora.office;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structure an exported office document has to carry so that it is a document and not formatted text:
 * heading styles a word processor recognises, lists that are lists, table column alignment, working links
 * (to the web, and to the document's own headings), and a title.
 */
class OfficeStructureTest {

    @TempDir
    Path dir;

    private XWPFDocument docx(String md) throws IOException {
        return docx(md, "export.docx");
    }

    private XWPFDocument docx(String md, String name) throws IOException {
        Path out = dir.resolve(name);
        DocxWriter.write(md, dir, null, out);
        try (InputStream in = Files.newInputStream(out)) {
            return new XWPFDocument(in);
        }
    }

    private String odtXml(String md) {
        return OdtWriter.contentXml(md, dir, null, new ArrayList<>());
    }

    private static XWPFRun run(XWPFDocument doc, String text) {
        for (XWPFParagraph p : doc.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                if (text.equals(r.text())) {
                    return r;
                }
            }
        }
        throw new AssertionError("no run '" + text + "'");
    }

    // --- headings ---------------------------------------------------------------------------------------

    @Test
    void docxHeadingsUseDefinedHeadingStylesWithOutlineLevels() throws Exception {
        try (XWPFDocument doc = docx("# One\n\n## Two\n\n###### Six\n\ntext\n")) {
            assertNotNull(doc.getStyles(), "the styles part exists");
            for (int level = 1; level <= 6; level++) {
                XWPFStyle style = doc.getStyles().getStyle("Heading" + level);
                assertNotNull(style, "Heading" + level + " is defined, not just referenced");
                assertEquals("heading " + level, style.getName(), "the name Word knows its built-in heading by");
                assertEquals(
                        level - 1,
                        style.getCTStyle().getPPr().getOutlineLvl().getVal().intValue(),
                        "what the navigation pane and a generated table of contents read");
                assertEquals("Normal", style.getBasisStyleID());
            }
            assertEquals("Heading1", doc.getParagraphs().get(0).getStyle());
            assertEquals("Heading2", doc.getParagraphs().get(1).getStyle());
            assertEquals("Heading6", doc.getParagraphs().get(2).getStyle());
            assertNull(doc.getParagraphs().get(3).getStyle());
        }
    }

    // --- lists ------------------------------------------------------------------------------------------

    @Test
    void anOrderedListIsAWordListThatStartsWhereTheSourceDoes() throws Exception {
        String md = "5. five\n6. six\n\ntext\n\n1. one\n   - [x] done\n   - [ ] open\n2. two\n";
        try (XWPFDocument doc = docx(md)) {
            assertEquals(
                    List.of("5. five", "6. six", "text", "1. one", "• ☑ done", "• ☐ open", "2. two"),
                    DocxLists.shown(doc));
            assertNotNull(doc.getNumbering(), "the numbering part exists");
            XWPFParagraph five = doc.getParagraphs().get(0);
            assertEquals("five", five.getText(), "the number is not typed into the text");
            assertEquals(5, five.getNumStartOverride().intValue());
            assertEquals("%1.", five.getNumLevelText());
        }
    }

    @Test
    void aSecondParagraphAndACodeBlockOfAnItemLineUpWithItsText() throws Exception {
        String md = "1. first\n\n   second paragraph\n\n   ```\n   code\n   ```\n2. next\n";
        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("1. first", "second paragraph", "code", "2. next"), DocxLists.shown(doc));
            int textLeft = doc.getParagraphs().get(0).getIndentationLeft();
            assertEquals(720, textLeft);
            assertEquals(textLeft, doc.getParagraphs().get(1).getIndentationLeft());
            assertEquals(textLeft, doc.getParagraphs().get(2).getIndentationLeft());
        }
    }

    // --- block quotes -----------------------------------------------------------------------------------

    @Test
    void whatAQuoteContainsKeepsItsOwnDepthInDocx() throws Exception {
        String md = "> para\n>\n> - item\n>   - nested\n>\n> > inner quote\n";
        try (XWPFDocument doc = docx(md)) {
            assertEquals(List.of("para", "• item", "• nested", "inner quote"), DocxLists.shown(doc));
            List<XWPFParagraph> ps = doc.getParagraphs();
            int para = ps.get(0).getIndentationLeft();
            int item = ps.get(1).getIndentationLeft();
            int nested = ps.get(2).getIndentationLeft();
            int inner = ps.get(3).getIndentationLeft();
            assertEquals(480, para);
            assertTrue(item > para, "a list in a quote is indented within it: " + item);
            assertTrue(nested > item, "and its nested list further: " + nested);
            assertEquals(960, inner, "a quote in a quote is two levels in");
            for (XWPFParagraph p : ps) {
                assertTrue(p.getCTP().getPPr().isSetPBdr(), "every quoted paragraph carries the quote's rule");
            }
        }
    }

    // --- tables -----------------------------------------------------------------------------------------

    private static final String ALIGNED = "| L | C | R | N |\n| :-- | :-: | --: | --- |\n| a | b | c | d |\n";

    @Test
    void docxTableColumnsKeepTheirAlignment() throws Exception {
        try (XWPFDocument doc = docx(ALIGNED)) {
            XWPFTable table = doc.getTables().getFirst();
            for (int row = 0; row < 2; row++) {
                List<ParagraphAlignment> got = new ArrayList<>();
                for (int col = 0; col < 4; col++) {
                    got.add(table.getRow(row)
                            .getCell(col)
                            .getParagraphs()
                            .getFirst()
                            .getAlignment());
                }
                assertEquals(
                        List.of(
                                ParagraphAlignment.LEFT,
                                ParagraphAlignment.CENTER,
                                ParagraphAlignment.RIGHT,
                                ParagraphAlignment.LEFT),
                        got,
                        "row " + row);
            }
        }
    }

    @Test
    void odtTableColumnsKeepTheirAlignment() {
        String xml = odtXml(ALIGNED);
        assertTrue(xml.contains("<text:p text:style-name=\"Standard\">a</text:p>"), xml);
        assertTrue(xml.contains("<text:p text:style-name=\"PCenter\">b</text:p>"), xml);
        assertTrue(xml.contains("<text:p text:style-name=\"PRight\">c</text:p>"), xml);
        assertTrue(xml.contains("<text:p text:style-name=\"Standard\">d</text:p>"), xml);
        assertTrue(xml.contains("style:name=\"PCenter\"") && xml.contains("fo:text-align=\"center\""), xml);
        assertTrue(xml.contains("style:name=\"PRight\"") && xml.contains("fo:text-align=\"end\""), xml);
    }

    // --- links ------------------------------------------------------------------------------------------

    private static final String LINKS = "# Set up\n\n## Set up\n\n"
            + "[web](https://example.com/a) [mail](mailto:a@b.c) [rel](<docs/my guide.md#x>) "
            + "[first](#set-up) [second](#Set-Up-1) [nowhere](#nowhere) "
            + "[js](javascript:alert(1)) [file](file:///etc/passwd) [vb](VBScript:x) [custom](myapp://run)\n";

    @Test
    void onlyWebMailAndRelativeLinksAreLiveInDocx() throws Exception {
        try (XWPFDocument doc = docx(LINKS)) {
            assertEquals(
                    "https://example.com/a",
                    ((XWPFHyperlinkRun) run(doc, "web")).getHyperlink(doc).getURL());
            assertEquals(
                    "mailto:a@b.c",
                    ((XWPFHyperlinkRun) run(doc, "mail")).getHyperlink(doc).getURL());
            assertEquals(
                    "docs/my%20guide.md#x",
                    ((XWPFHyperlinkRun) run(doc, "rel")).getHyperlink(doc).getURL());
            for (String plain : List.of("js", "file", "vb", "custom", "nowhere")) {
                assertFalse(run(doc, plain) instanceof XWPFHyperlinkRun, plain + " keeps its text and loses its link");
            }
            String rels = new String(
                    doc.getPackagePart()
                            .getPackage()
                            .getPart(org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName(
                                    "/word/_rels/document.xml.rels"))
                            .getInputStream()
                            .readAllBytes(),
                    StandardCharsets.UTF_8);
            assertFalse(rels.toLowerCase(java.util.Locale.ROOT).contains("javascript"), rels);
            assertFalse(rels.contains("passwd"), rels);
        }
    }

    @Test
    void aLinkToAHeadingIsAnInternalLinkToItsBookmarkInDocx() throws Exception {
        try (XWPFDocument doc = docx(LINKS)) {
            String firstBookmark =
                    doc.getParagraphs().get(0).getCTP().getBookmarkStartArray(0).getName();
            String secondBookmark =
                    doc.getParagraphs().get(1).getCTP().getBookmarkStartArray(0).getName();
            assertTrue(firstBookmark.matches("[A-Za-z][A-Za-z0-9_]{0,39}"), "a name Word accepts: " + firstBookmark);
            assertFalse(firstBookmark.equals(secondBookmark));
            XWPFHyperlinkRun first = (XWPFHyperlinkRun) run(doc, "first");
            XWPFHyperlinkRun second = (XWPFHyperlinkRun) run(doc, "second");
            assertEquals(firstBookmark, first.getAnchor());
            assertEquals(secondBookmark, second.getAnchor(), "the second heading of the same name, by its -1 anchor");
            assertNull(first.getHyperlinkId(), "internal: no external relationship");
        }
    }

    @Test
    void odtLinksFollowTheSameRules() {
        String xml = odtXml(LINKS);
        assertTrue(xml.contains("<text:bookmark text:name=\"set-up\"/>Set up</text:h>"), xml);
        assertTrue(xml.contains("<text:bookmark text:name=\"set-up-1\"/>Set up</text:h>"), xml);
        assertTrue(xml.contains("xlink:href=\"#set-up\">first</text:a>"), xml);
        assertTrue(xml.contains("xlink:href=\"#set-up-1\">second</text:a>"), xml);
        assertTrue(xml.contains("xlink:href=\"https://example.com/a\">web</text:a>"), xml);
        assertTrue(xml.contains("xlink:href=\"docs/my guide.md#x\">rel</text:a>"), xml);
        assertFalse(xml.contains("javascript"), xml);
        assertFalse(xml.contains("passwd"), xml);
        assertFalse(xml.toLowerCase(java.util.Locale.ROOT).contains("vbscript"), xml);
        assertFalse(xml.contains("#nowhere"), xml);
        for (String plain : List.of("js", "file", "vb", "custom", "nowhere")) {
            assertTrue(xml.contains(" " + plain + " ") || xml.contains(" " + plain + "<"), plain + " keeps its text");
        }
    }

    @Test
    void theHrefPolicy() {
        for (String ok : List.of(
                "https://x.y",
                "HTTP://x.y",
                "mailto:a@b.c",
                "guide.md",
                "./a/b.md#c",
                "../a.png",
                "/abs/x",
                "a b.md",
                "#frag",
                "docs/a:b.md",
                "?q=a:b")) {
            assertTrue(InlineRun.isSafeHref(ok), ok);
        }
        for (String bad : List.of(
                "javascript:alert(1)",
                " JavaScript:alert(1)",
                "vbscript:x",
                "file:///etc/passwd",
                "data:text/html,x",
                "smb://h/s",
                "myapp://x",
                "",
                "  ")) {
            assertFalse(InlineRun.isSafeHref(bad), bad);
        }
        assertFalse(InlineRun.isSafeHref(null));
    }

    // --- metadata ---------------------------------------------------------------------------------------

    @Test
    void theDocumentCarriesItsTitleAndItsGenerator() throws Exception {
        try (XWPFDocument doc = docx("---\ntitle: \"From front matter\"\n---\n\n# A heading\n")) {
            assertEquals(
                    "From front matter", doc.getProperties().getCoreProperties().getTitle());
            assertEquals("Editora", doc.getProperties().getCoreProperties().getCreator());
        }
        try (XWPFDocument doc = docx("intro\n\n# The *first* heading\n\n# Another\n")) {
            assertEquals(
                    "The first heading", doc.getProperties().getCoreProperties().getTitle());
        }
        try (XWPFDocument doc = docx("no heading at all\n", "meeting-notes.docx")) {
            assertEquals(
                    "meeting-notes", doc.getProperties().getCoreProperties().getTitle());
        }
    }

    @Test
    void theOdtPackageHasAMetaPartWithTheTitle() throws Exception {
        Path out = dir.resolve("notes.odt");
        OdtWriter.write("# Release notes & 1 < 2\n", dir, null, out);
        String meta = null;
        String manifest = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("meta.xml")) {
                    meta = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (e.getName().equals("META-INF/manifest.xml")) {
                    manifest = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertNotNull(meta, "meta.xml is in the package");
        assertTrue(meta.contains("<dc:title>Release notes &amp; 1 &lt; 2</dc:title>"), meta);
        assertTrue(meta.contains("<meta:generator>Editora</meta:generator>"), meta);
        assertTrue(manifest.contains("manifest:full-path=\"meta.xml\""), manifest);
        javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(meta.getBytes(StandardCharsets.UTF_8)));
        assertFalse(OdtWriter.metaXml(null).contains("dc:title"));
    }

    // --- inline pictures --------------------------------------------------------------------------------

    @Test
    void aRowOfBadgesIsARowOfPictures() throws Exception {
        BufferedImage img = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        Files.write(dir.resolve("a.png"), png.toByteArray());
        Files.write(dir.resolve("b.png"), png.toByteArray());
        String md = "[![build](a.png)](https://ci.example) ![docs][d]\n\n[d]: b.png\n";
        try (XWPFDocument doc = docx(md)) {
            XWPFParagraph p = doc.getParagraphs().getFirst();
            assertEquals(
                    2,
                    p.getRuns().stream()
                            .mapToInt(r -> r.getEmbeddedPictures().size())
                            .sum());
            assertEquals(" ", p.getText(), "no alt text beside a picture that was embedded");
        }
        List<OdtWriter.Embedded> images = new ArrayList<>();
        OdtWriter.contentXml(md, dir, null, images);
        assertEquals(2, images.size());
    }
}
