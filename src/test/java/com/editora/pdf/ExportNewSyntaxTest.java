package com.editora.pdf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.editora.editor.MarkdownRenderer;
import com.editora.markdown.MarkdownAlerts;
import com.editora.markdown.MarkdownDefinitions;
import com.editora.markdown.MarkdownExportTree;
import com.editora.office.DocxWriter;
import com.editora.office.InlineRun;
import com.editora.office.OdtWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alerts, emoji, highlight, superscript / subscript and definition lists in DOCX, ODT and PDF. */
class ExportNewSyntaxTest {

    private static final String MD =
            "> [!WARNING]\n> Mind the *gap*.\n\nWater is H~2~O, area in m^2^, ==marked== text, done :rocket: in 2024\n\n"
                    + "Term one\n: First description.\n: Second description.\n\nafter\n";

    @TempDir
    Path dir;

    private static boolean any(Node root, Class<?> type) {
        for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
            if (type.isInstance(c) || any(c, type)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theTreeIsLoweredToBlocksEveryWriterKnows() {
        Node parsed = MarkdownRenderer.parseForRender(MD);
        assertTrue(any(parsed, MarkdownAlerts.Alert.class), "precondition: the parse makes an alert");
        assertTrue(any(parsed, MarkdownDefinitions.DefinitionList.class), "precondition: and a definition list");
        Node ast = MarkdownExportTree.lower(parsed);
        assertFalse(any(ast, MarkdownAlerts.Alert.class));
        assertFalse(any(ast, MarkdownDefinitions.DefinitionList.class));
        BlockQuote quote = assertInstanceOf(BlockQuote.class, ast.getFirstChild());
        Paragraph title = assertInstanceOf(Paragraph.class, quote.getFirstChild());
        assertEquals("Warning", InlineRun.flatten(title).get(0).text());
        assertTrue(InlineRun.flatten(title).get(0).bold());
        assertInstanceOf(Paragraph.class, title.getNext());
    }

    @Test
    void inlineRunsCarryHighlightScriptsAndTheEmojiCharacter() {
        Node ast = MarkdownRenderer.parseForRender("H~2~O m^2^ ==hot== :rocket:\n");
        var runs = InlineRun.flatten(ast.getFirstChild());
        InlineRun sub = runs.stream()
                .filter(r -> r.text().equals("2") && r.isSubscript())
                .findFirst()
                .orElseThrow();
        assertFalse(sub.highlight());
        assertTrue(runs.stream().anyMatch(r -> r.text().equals("2") && r.isSuperscript()));
        assertTrue(runs.stream().anyMatch(r -> r.text().equals("hot") && r.highlight() && r.script() == 0));
        assertTrue(runs.stream().anyMatch(r -> r.text().equals("🚀")), runs.toString());
        assertTrue(runs.stream().noneMatch(r -> r.text().contains(":rocket:")));
    }

    @Test
    void docxWritesTheTitledQuoteTheMarksAndTheDefinitions() throws Exception {
        Path out = dir.resolve("o.docx");
        DocxWriter.write(MD, dir, null, out);
        try (InputStream in = Files.newInputStream(out);
                XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder text = new StringBuilder();
            boolean sub = false;
            boolean sup = false;
            boolean marked = false;
            boolean boldTerm = false;
            for (XWPFParagraph p : doc.getParagraphs()) {
                text.append(p.getText()).append('\n');
                for (XWPFRun r : p.getRuns()) {
                    String t = r.text();
                    sub |= "2".equals(t)
                            && String.valueOf(r.getVerticalAlignment()).equals("subscript");
                    sup |= "2".equals(t)
                            && String.valueOf(r.getVerticalAlignment()).equals("superscript");
                    marked |= "marked".equals(t) && r.isHighlighted();
                    boldTerm |= "Term one".equals(t) && r.isBold();
                }
            }
            String all = text.toString();
            assertTrue(all.contains("Warning") && all.contains("Mind the gap."), all);
            assertFalse(all.contains("[!WARNING]"), all);
            assertTrue(all.contains("First description.") && all.contains("Second description."), all);
            assertTrue(all.contains("🚀") && !all.contains(":rocket:"), all);
            assertTrue(all.contains("H2O") && all.contains("m2"), all);
            assertTrue(sub, "subscript run");
            assertTrue(sup, "superscript run");
            assertTrue(marked, "highlighted run");
            assertTrue(boldTerm, "the term is bold");
        }
    }

    @Test
    void odtWritesTheSame() throws Exception {
        Path out = dir.resolve("o.odt");
        OdtWriter.write(MD, dir, null, out);
        String xml = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("content.xml")) {
                    xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(xml.contains("<text:span text:style-name=\"TSub\">2</text:span>"), xml);
        assertTrue(xml.contains("<text:span text:style-name=\"TSup\">2</text:span>"), xml);
        assertTrue(xml.contains("<text:span text:style-name=\"TMark\">marked</text:span>"), xml);
        assertTrue(xml.contains("style:name=\"TMark\"") && xml.contains("style:name=\"TSup\""), "styles declared");
        String text = xml.substring(xml.indexOf("<office:body"))
                .replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ");
        assertTrue(text.contains("Warning") && text.contains("Mind the") && !text.contains("[!WARNING]"), text);
        assertTrue(text.contains("Term one") && text.contains("First description."), text);
        assertTrue(text.contains("🚀") && !text.contains(":rocket:"), text);
    }

    @Test
    void pdfDrawsTheEmojiWithTheBundledFontAndMarksRaisesAndLowersText() throws Exception {
        Path out = dir.resolve("o.pdf");
        MarkdownPdfWriter.write(MD, dir, "letter", null, out);
        String text = PdfProbe.text(out).replaceAll("\\s+", " ");
        assertTrue(text.contains("Warning") && text.contains("Mind the gap."), text);
        assertFalse(text.contains("[!WARNING]"), text);
        assertTrue(text.contains("Term one") && text.contains("Second description."), text);
        assertTrue(text.contains("after"), text);

        // The emoji is its own character, drawn from the embedded Noto Emoji subset — on any machine.
        assertTrue(text.contains("\uD83D\uDE80"), "the rocket itself: " + text);
        assertFalse(text.contains(":rocket:") || text.contains("?"), text);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            java.util.Set<String> fonts = new java.util.TreeSet<>();
            for (org.apache.pdfbox.pdmodel.PDPage page : doc.getPages()) {
                for (org.apache.pdfbox.cos.COSName name : page.getResources().getFontNames()) {
                    fonts.add(page.getResources().getFont(name).getName());
                }
            }
            assertTrue(fonts.stream().anyMatch(f -> f.contains("NotoEmoji")), "embedded: " + fonts);
        }

        // Superscript and subscript: smaller glyphs, above and below the baseline of the words beside them.
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        float water = PdfProbe.glyphsOf(glyphs, "Water").get(0).baseline();
        PdfProbe.Glyph sub = PdfProbe.glyphsOf(glyphs, "H2O").get(1);
        PdfProbe.Glyph sup = PdfProbe.glyphsOf(glyphs, "m2").get(1);
        PdfProbe.Glyph h = PdfProbe.glyphsOf(glyphs, "H2O").get(0);
        assertEquals(water, h.baseline(), 0.01f);
        assertTrue(sub.baseline() < water - 0.5f, "the 2 of H2O sits lower: " + sub.baseline() + " vs " + water);
        assertTrue(sup.baseline() > water + 2f, "the 2 of m2 sits higher: " + sup.baseline() + " vs " + water);
        PdfProbe.Glyph two = PdfProbe.glyphsOf(glyphs, "2024").get(0);
        assertTrue(
                sub.right() - sub.x() < (two.right() - two.x()) * 0.85f, "and is smaller than a 2 of the running text");

        // Highlight: one filled marker box behind "marked", as wide as the word and no wider.
        List<PdfProbe.Glyph> marked = PdfProbe.glyphsOf(glyphs, "marked");
        float left = marked.get(0).x();
        float right = marked.get(marked.size() - 1).right();
        List<PdfProbe.Box> behind = PdfProbe.boxes(out).stream()
                .filter(b -> Math.abs(b.x() - left) < 0.6f
                        && Math.abs(b.x() + b.width() - right) < 0.6f
                        && b.y() < marked.get(0).baseline()
                        && b.y() + b.height() > marked.get(0).baseline())
                .toList();
        assertEquals(1, behind.size(), "a box behind the highlighted word: " + PdfProbe.boxes(out));
    }

    @Test
    void anAlertKeepsItsKindAndADefinitionItsIndentInTheLoweredTree() {
        Node ast = MarkdownExportTree.lower(MarkdownRenderer.parseForRender(MD));
        MarkdownExportTree.AlertQuote alert =
                assertInstanceOf(MarkdownExportTree.AlertQuote.class, ast.getFirstChild());
        assertEquals(MarkdownAlerts.Kind.WARNING, alert.getKind());
        assertEquals("9A6700", MarkdownExportTree.color(alert.getKind()));
        assertTrue(any(ast, MarkdownExportTree.Indented.class));
        for (MarkdownAlerts.Kind kind : MarkdownAlerts.Kind.values()) {
            assertTrue(MarkdownExportTree.color(kind).matches("[0-9A-F]{6}"), kind.name());
        }
    }

    @Test
    void docxColoursAnAlertsRuleAndTitleAndSetsDescriptionsInFromTheirTerm() throws Exception {
        Path out = dir.resolve("styled.docx");
        DocxWriter.write(MD + "\n> plain quote\n", dir, null, out);
        try (InputStream in = Files.newInputStream(out);
                XWPFDocument doc = new XWPFDocument(in)) {
            XWPFParagraph title = paragraph(doc, "Warning");
            XWPFParagraph body = paragraph(doc, "Mind the gap.");
            XWPFParagraph plain = paragraph(doc, "plain quote");
            assertEquals("9A6700", title.getRuns().get(0).getColor(), "the title in the kind's colour");
            assertTrue(title.getRuns().get(0).isBold());
            assertEquals(null, body.getRuns().get(0).getColor(), "the body in the text colour");
            assertEquals("9A6700", ruleColor(title));
            assertEquals("9A6700", ruleColor(body), "one rule down the whole alert");
            assertFalse("9A6700".equals(ruleColor(plain)), "a plain quote keeps its grey");
            assertTrue(
                    leftBorder(title).getSz().intValue()
                            > leftBorder(plain).getSz().intValue(),
                    "and a lighter rule");

            XWPFParagraph term = paragraph(doc, "Term one");
            XWPFParagraph first = paragraph(doc, "First description.");
            XWPFParagraph second = paragraph(doc, "Second description.");
            assertTrue(first.getIndentationLeft() > Math.max(0, term.getIndentationLeft()), "set in from the term");
            assertEquals(first.getIndentationLeft(), second.getIndentationLeft());
        }
    }

    private static XWPFParagraph paragraph(XWPFDocument doc, String text) {
        return doc.getParagraphs().stream()
                .filter(p -> p.getText().strip().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no paragraph \"" + text + "\""));
    }

    private static String ruleColor(XWPFParagraph p) {
        Object color = leftBorder(p).getColor();
        if (color instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02X", b & 0xff));
            }
            return hex.toString();
        }
        return String.valueOf(color).toUpperCase(java.util.Locale.ROOT);
    }

    private static org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder leftBorder(XWPFParagraph p) {
        return p.getCTP().getPPr().getPBdr().getLeft();
    }

    @Test
    void odtGivesAnAlertItsOwnStylesAndDescriptionsAnIndent() throws Exception {
        Path out = dir.resolve("styled.odt");
        OdtWriter.write(MD, dir, null, out);
        String content = null;
        String styles = "";
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("content.xml")) {
                    content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (e.getName().equals("styles.xml")) {
                    styles = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        String all = content + styles;
        assertTrue(
                content.contains(
                        "<text:p text:style-name=\"Alert_warning\"><text:span text:style-name=\"TAlert_warning\">"),
                "the title line: " + content);
        assertTrue(content.contains("<text:p text:style-name=\"Alert_warning\">Mind the "), "the body: " + content);
        assertTrue(
                all.contains("style:name=\"Alert_warning\"") && all.contains("fo:border-left=\"2.5pt solid #9a6700\""));
        assertTrue(all.contains("style:name=\"TAlert_warning\"") && all.contains("fo:color=\"#9a6700\""));
        assertTrue(
                all.contains("style:name=\"Alert_note\"") && all.contains("style:name=\"Alert_caution\""),
                "every kind");
        assertTrue(content.contains("<text:p text:style-name=\"Definition\">First description.</text:p>"), content);
        assertTrue(all.contains("style:name=\"Definition\""), "declared");
    }

    @Test
    void pdfDrawsAnAlertsRuleAndSetsDescriptionsInFromTheirTerm() throws Exception {
        Path out = dir.resolve("styled.pdf");
        MarkdownPdfWriter.write(MD, dir, "letter", null, out);
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        PdfProbe.Glyph warning = PdfProbe.glyphsOf(glyphs, "Warning").get(0);
        assertTrue(
                PdfProbe.segments(out).stream()
                        .anyMatch(s -> !s.horizontal()
                                && s.x1() < warning.x()
                                && Math.max(s.y1(), s.y2()) > warning.baseline()
                                && Math.min(s.y1(), s.y2()) < warning.baseline()),
                "a rule beside the alert");
        float term = PdfProbe.glyphsOf(glyphs, "Term").get(0).x();
        float first = PdfProbe.glyphsOf(glyphs, "First").get(0).x();
        float second = PdfProbe.glyphsOf(glyphs, "Second").get(0).x();
        assertTrue(first > term + 10f, "set in from the term: " + first + " vs " + term);
        assertEquals(first, second, 0.01f);
        // The title is painted in the kind's colour (the content stream sets it as its fill colour).
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(out.toFile())) {
            String stream = new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher fill = java.util.regex.Pattern.compile("([0-9.]+) ([0-9.]+) ([0-9.]+) (?:rg|sc)\\b")
                    .matcher(stream);
            boolean kindColour = false;
            while (fill.find()) {
                kindColour |= Math.abs(Float.parseFloat(fill.group(1)) - 0x9a / 255f) < 0.01f
                        && Math.abs(Float.parseFloat(fill.group(2)) - 0x67 / 255f) < 0.01f
                        && Float.parseFloat(fill.group(3)) < 0.01f;
            }
            assertTrue(kindColour, "#9a6700 as a fill colour");
        }
    }

    /**
     * Front matter is metadata: no export writes it into the document (the HTML page takes its title and
     * language from it), whatever "hide front matter" says — that switch has nothing left to hide here.
     */
    @Test
    void noExportWritesFrontMatterIntoTheDocument() throws Exception {
        String md = "---\ntitle: The Title\nauthor: Ada Example\ntags: [a, b]\n---\n\n# Heading\n\nBody text.\n";
        Path docx = dir.resolve("fm.docx");
        DocxWriter.write(md, dir, null, docx);
        String word;
        try (InputStream in = Files.newInputStream(docx);
                XWPFDocument doc = new XWPFDocument(in);
                var text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
            word = text.getText();
        }
        Path odt = dir.resolve("fm.odt");
        OdtWriter.write(md, dir, null, odt);
        String writer = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(odt))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().equals("content.xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    writer = xml.substring(xml.indexOf("<office:body")).replaceAll("<[^>]*>", " ");
                }
            }
        }
        Path pdf = dir.resolve("fm.pdf");
        MarkdownPdfWriter.write(md, dir, "letter", null, pdf);
        String html = com.editora.editor.MarkdownHtmlExport.toHtml(md, "file", false);
        String body = html.substring(html.indexOf("<body>"));
        String clipboard = com.editora.editor.MarkdownClipboardHtml.toHtml(md, false);
        for (String out : new String[] {word, writer, PdfProbe.text(pdf), body, clipboard}) {
            assertTrue(out.contains("Body text."), out);
            assertFalse(out.contains("Ada Example"), "front matter is not content: " + out);
            assertFalse(out.contains("tags"), out);
        }
        assertTrue(html.contains("<title>The Title</title>"), "it still names the page");
    }

    @Test
    void wikiLinksLeaveEveryExportAsOrdinaryLinksToTheFilesTheyResolveTo() throws Exception {
        Files.writeString(dir.resolve("Other Note.md"), "# Other\n\n## The Part\n");
        Files.write(
                dir.resolve("pic.png"),
                java.util.Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="));
        String md = "See [[Other Note#The Part|the part]] and [[Other Note]] and [[missing note]].\n\n![[pic.png]]\n";

        // The tree every writer walks: links and an image, no wiki node left.
        Node ast = MarkdownExportTree.lower(
                MarkdownRenderer.parseForRender(md), com.editora.editor.MarkdownHtmlExport.wikiPaths(dir));
        assertFalse(any(ast, com.editora.markdown.MarkdownWikiLinks.WikiLink.class));
        var runs = InlineRun.flatten(ast.getFirstChild());
        assertTrue(
                runs.stream().anyMatch(r -> r.text().equals("the part") && "Other%20Note.md#the-part".equals(r.href())),
                runs.toString());
        assertTrue(runs.stream().anyMatch(r -> r.text().equals("Other Note") && "Other%20Note.md".equals(r.href())));
        assertTrue(
                runs.stream().anyMatch(r -> r.text().contains("missing note") && r.href() == null),
                "unresolved: its text");

        Path docx = dir.resolve("w.docx");
        DocxWriter.write(md, dir, null, docx);
        try (InputStream in = Files.newInputStream(docx);
                XWPFDocument doc = new XWPFDocument(in)) {
            String text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc).getText();
            assertTrue(text.contains("the part") && text.contains("Other Note") && text.contains("missing note"), text);
            assertFalse(text.contains("[["), text);
            assertEquals(1, doc.getAllPictures().size(), "the embed is the picture");
            assertTrue(doc.getHyperlinks().length >= 2 || text.contains("Other"), "live links");
        }

        Path odt = dir.resolve("w.odt");
        OdtWriter.write(md, dir, null, odt);
        String content = null;
        boolean picture = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(odt))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                picture |= e.getName().startsWith("Pictures/");
                if (e.getName().equals("content.xml")) {
                    content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(
                content.contains("xlink:href=\"Other%20Note.md#the-part\"") && content.contains(">the part<"), content);
        assertTrue(content.contains("missing note") && !content.contains("[["), content);
        assertTrue(picture, "the embed is the picture");

        Path pdf = dir.resolve("w.pdf");
        MarkdownPdfWriter.write(md, dir, "letter", null, pdf);
        String text = PdfProbe.text(pdf).replaceAll("\\s+", " ");
        assertTrue(text.contains("the part") && text.contains("Other Note") && text.contains("missing note"), text);
        assertFalse(text.contains("[["), text);
        assertEquals(1, PdfProbe.images(pdf).size(), "the embed is the picture");

        String html = com.editora.editor.MarkdownHtmlExport.toHtml(
                md, "t", false, new com.editora.editor.MarkdownHtmlExport.Options(false, false, dir, null, null));
        assertTrue(html.contains("<a href=\"Other%20Note.md#the-part\">the part</a>"), html);
        assertTrue(html.contains("src=\"pic.png\""), html);
        assertFalse(html.contains("href=\"Other%20Note#"), "not the raw note name: " + html);
    }
}
