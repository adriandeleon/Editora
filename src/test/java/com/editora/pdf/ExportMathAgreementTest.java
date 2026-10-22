package com.editora.pdf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.editora.editor.markdown.MarkdownClipboardHtml;
import com.editora.editor.markdown.MarkdownHtmlExport;
import com.editora.editor.markdown.MarkdownRenderer;
import com.editora.editor.markdown.MathImages;
import com.editora.markdown.MarkdownMath;
import com.editora.office.DocxWriter;
import com.editora.office.OdtWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One parse decides what is math — the preview's ({@code MarkdownRenderer.parseForRender}) — and every export
 * draws exactly those formulas: the HTML page, the clipboard fragment, DOCX, ODT and PDF. They used to scan
 * for {@code $} on their own, two different ways, and disagreed with the preview and with each other: the
 * HTML export turned {@code $HOME:$PATH} inside code into an image and missed a {@code $$} block that spans
 * lines; DOCX/ODT/PDF looked in text CommonMark had already rewritten, so {@code $a*b*c$} was no formula and
 * {@code \\{} had lost its backslash.
 */
class ExportMathAgreementTest {

    @TempDir
    Path dir;

    private boolean wasEnabled;

    @BeforeEach
    void mathOn() {
        wasEnabled = MathImages.isEnabled();
        MathImages.configure(true, false);
        Assumptions.assumeTrue(MathImages.renderPng("x^2", false, 18f, false) != null, "JLaTeXMath renders here");
    }

    @AfterEach
    void restore() {
        MathImages.configure(wasEnabled, false);
    }

    /** A document, the number of formulas the preview's parse finds in it, and text every output must keep. */
    private record Case(String name, String markdown, int formulas, List<String> kept) {}

    private static final List<Case> CASES = List.of(
            new Case(
                    "shell variables in inline code",
                    "Shell: `export PATH=$HOME/bin:$PATH` and `$A:$B` here.\n",
                    0,
                    List.of("export PATH=$HOME/bin:$PATH", "$A:$B")),
            new Case("indented code", "para\n\n    $indented$ code\n", 0, List.of("$indented$ code")),
            new Case("fenced code", "```sh\necho $a$ $$b$$\n```\n", 0, List.of("echo $a$ $$b$$")),
            new Case("prices", "Costs $5 and $10 today, between $5-$10.\n", 0, List.of("$5 and $10", "$5-$10")),
            new Case(
                    "a block over several lines (samples/markdown/math.md)",
                    "Block math:\n\n$$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n$$\n",
                    1,
                    List.of("Block math:")),
            new Case("a block on one line", "$$E = mc^2$$\n", 1, List.of()),
            new Case(
                    "row breaks and braces",
                    "$$\n\\begin{matrix} a & b \\\\ c & d \\end{matrix}\n$$\n\nSet $\\{a,b\\}$ end.\n",
                    2,
                    List.of("Set", "end.")),
            new Case("asterisks and underscores", "star $a*b*c$ and $x_i + y_i$ end\n", 2, List.of("star", "end")),
            new Case("display inside a sentence", "so $$x^2$$ follows and $y$ too\n", 2, List.of("follows and")),
            new Case(
                    "in a table and a heading",
                    "# Energy $E=mc^2$\n\n| a | b |\n|---|---|\n| $x^2$ | $\\alpha$ |\n",
                    3,
                    List.of("Energy")),
            new Case("an unclosed block is not a formula yet", "$$\na = b\n", 0, List.of("a = b")),
            new Case("an escaped dollar", "Escaped \\$a\\$ stays text\n", 0, List.of("$a$ stays text")));

    private static int formulasInTheParse(String md) {
        int[] n = {0};
        MarkdownRenderer.parseForRender(md, true).accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                n[0] += node instanceof MarkdownMath.Inline ? 1 : 0;
                visitChildren(node);
            }

            @Override
            public void visit(CustomBlock node) {
                n[0] += node instanceof MarkdownMath.Block b && b.isClosed() ? 1 : 0;
                visitChildren(node);
            }
        });
        return n[0];
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String unescape(String html) {
        return html.replaceAll("<[^>]*>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    @Test
    void theCasesAreWhatThePreviewsParseSaysTheyAre() {
        for (Case c : CASES) {
            assertEquals(c.formulas(), formulasInTheParse(c.markdown()), c.name());
        }
    }

    @Test
    void theHtmlPageAndTheClipboardDrawExactlyThoseFormulas() {
        for (Case c : CASES) {
            String page = MarkdownHtmlExport.toHtml(c.markdown(), "t", true);
            String body = page.substring(page.indexOf("<article"));
            String clip = MarkdownClipboardHtml.toHtml(c.markdown(), true);
            String markup = MarkdownClipboardHtml.toMarkup(c.markdown(), true);
            for (String html : List.of(body, clip, markup)) {
                assertEquals(c.formulas(), count(html, "<img class=\"md-math "), c.name() + ": " + html);
                assertFalse(html.contains("&lt;img class"), c.name() + ": an image tag written as text: " + html);
                for (String kept : c.kept()) {
                    assertTrue(unescape(html).contains(kept), c.name() + ": '" + kept + "' in " + html);
                }
            }
        }
        // The LaTeX is the source's, character for character (it is the picture's alt text).
        String html = MarkdownHtmlExport.toHtml("$$\n\\int_0^1 x^2 \\, dx\n$$\n\n$\\{a,b\\}$ $a*b*c$\n", "t", true);
        assertTrue(html.contains("alt=\"\\int_0^1 x^2 \\, dx\""), html);
        assertTrue(html.contains("alt=\"\\{a,b\\}\""), html);
        assertTrue(html.contains("alt=\"a*b*c\""), html);
        assertTrue(html.contains("class=\"md-math md-math-block\" alt=\"\\int"), "a block formula is a block image");
        // With math off nothing is a formula anywhere.
        String off = MarkdownHtmlExport.toHtml("$x^2$ and\n\n$$\ny\n$$\n", "t", false);
        assertEquals(0, count(off, "<img class=\"md-math "));
        assertTrue(off.contains("$x^2$ and"), off);
    }

    private static int pictures(XWPFDocument doc) {
        int n = 0;
        for (XWPFParagraph p : doc.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                n += r.getEmbeddedPictures().size();
            }
        }
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow row : t.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        for (XWPFRun r : p.getRuns()) {
                            n += r.getEmbeddedPictures().size();
                        }
                    }
                }
            }
        }
        return n;
    }

    @Test
    void docxOdtAndPdfDrawExactlyThoseFormulas() throws Exception {
        int i = 0;
        for (Case c : CASES) {
            Path docx = dir.resolve("c" + i + ".docx");
            DocxWriter.write(c.markdown(), dir, null, docx);
            try (InputStream in = Files.newInputStream(docx);
                    XWPFDocument doc = new XWPFDocument(in);
                    var text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
                assertEquals(c.formulas(), pictures(doc), "docx, " + c.name());
                for (String kept : c.kept()) {
                    assertTrue(text.getText().contains(kept), "docx, " + c.name() + ": " + text.getText());
                }
            }

            Path odt = dir.resolve("c" + i + ".odt");
            OdtWriter.write(c.markdown(), dir, null, odt);
            String content = null;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(odt))) {
                for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    if (e.getName().equals("content.xml")) {
                        content = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
            assertEquals(c.formulas(), count(content, "<draw:frame "), "odt, " + c.name() + ": " + content);
            String odtText = unescape(content.replaceAll("<text:s text:c=\"(\\d+)\"/>", " "));
            for (String kept : c.kept()) {
                assertTrue(
                        odtText.replaceAll("\\s+", " ").contains(kept.replaceAll("\\s+", " ")),
                        "odt, " + c.name() + ": " + odtText);
            }

            Path pdf = dir.resolve("c" + i + ".pdf");
            MarkdownPdfWriter.write(c.markdown(), dir, "letter", null, pdf);
            assertEquals(c.formulas(), PdfProbe.images(pdf).size(), "pdf, " + c.name());
            String pdfText = PdfProbe.text(pdf).replaceAll("\\s+", " ");
            for (String kept : c.kept()) {
                assertTrue(pdfText.contains(kept.replaceAll("\\s+", " ")), "pdf, " + c.name() + ": " + pdfText);
            }
            i++;
        }
    }
}
