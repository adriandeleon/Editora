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
}
