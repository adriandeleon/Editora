package com.editora.pdf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editor.MarkdownHtmlExport;
import com.editora.editor.MarkdownRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What makes a Markdown PDF navigable: link annotations, heading destinations, the outline, its metadata. */
class PdfNavigationTest {

    private static final PdfDocMeta META = new PdfDocMeta("notes.md", "es", (p, n) -> "Página " + p + " de " + n);

    /** A link annotation as a test reads it: 1-based page, rectangle, URI or the 1-based page it jumps to. */
    private record Link(int page, PDRectangle rect, String uri, int targetPage, float targetTop, boolean bordered) {}

    private static Path write(Path dir, String md, PdfPageSpec spec, PdfDocMeta meta) throws Exception {
        Path out = dir.resolve("nav.pdf");
        MarkdownPdfWriter.write(MarkdownRenderer.parseToDocument(md), dir, spec, meta, null, out, List.of(), false);
        return out;
    }

    private static Path write(Path dir, String md) throws Exception {
        return write(dir, md, PdfPageSpec.of("letter"), META);
    }

    private static List<Link> links(Path pdf) throws Exception {
        List<Link> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (int pi = 0; pi < doc.getNumberOfPages(); pi++) {
                for (PDAnnotation a : doc.getPage(pi).getAnnotations()) {
                    if (!(a instanceof PDAnnotationLink link)) {
                        continue;
                    }
                    String uri = null;
                    int targetPage = 0;
                    float top = 0f;
                    if (link.getAction() instanceof PDActionURI u) {
                        uri = u.getURI();
                    } else if (link.getAction() instanceof PDActionGoTo go
                            && go.getDestination() instanceof PDPageDestination dest) {
                        targetPage = dest.retrievePageNumber() + 1;
                        top =
                                ((org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination
                                                        .PDPageXYZDestination)
                                                dest)
                                        .getTop();
                    }
                    boolean bordered = link.getBorderStyle() == null
                            || link.getBorderStyle().getWidth() > 0f;
                    out.add(new Link(pi + 1, link.getRectangle(), uri, targetPage, top, bordered));
                }
            }
        }
        return out;
    }

    private static void assertCovers(PDRectangle rect, List<PdfProbe.Glyph> glyphs) {
        for (PdfProbe.Glyph g : glyphs) {
            assertTrue(
                    rect.getLowerLeftX() <= g.x() + 0.5f && g.right() <= rect.getUpperRightX() + 0.5f,
                    "the rectangle spans the glyph horizontally: " + rect + " vs " + g);
            assertTrue(
                    rect.getLowerLeftY() <= g.baseline() && g.baseline() + 5f <= rect.getUpperRightY(),
                    "the rectangle holds the glyph's line: " + rect + " vs " + g);
        }
    }

    @Test
    void aWebLinkIsAnAnnotationOverItsText(@TempDir Path dir) throws Exception {
        Path out = write(dir, "Read the [project guide](https://example.com/guide?a=1) today.\n");
        List<Link> links = links(out);
        assertEquals(1, links.size(), "one link, one rectangle: " + links);
        Link link = links.get(0);
        assertEquals("https://example.com/guide?a=1", link.uri());
        assertFalse(link.bordered(), "no visible border");
        List<PdfProbe.Glyph> glyphs = PdfProbe.glyphs(out);
        assertCovers(link.rect(), PdfProbe.glyphsOf(glyphs, "project"));
        assertCovers(link.rect(), PdfProbe.glyphsOf(glyphs, "guide"));
        PdfProbe.Glyph before = PdfProbe.glyphsOf(glyphs, "the").get(2);
        PdfProbe.Glyph after = PdfProbe.glyphsOf(glyphs, "today").get(0);
        assertTrue(before.right() <= link.rect().getLowerLeftX() + 0.5f, "the text before is outside");
        assertTrue(after.x() >= link.rect().getUpperRightX() - 0.5f, "the text after is outside");
        assertTrue(link.rect().getHeight() < 15f, "one line tall: " + link.rect());
    }

    @Test
    void aLinkThatWrapsGetsOneRectanglePerLine(@TempDir Path dir) throws Exception {
        String filler = "word ".repeat(17);
        Path out =
                write(dir, filler + "[a link whose text is long enough to wrap around](https://example.com/w) end.\n");
        List<Link> links = links(out);
        assertEquals(2, links.size(), "one rectangle per line fragment: " + links);
        assertTrue(links.stream().allMatch(l -> "https://example.com/w".equals(l.uri())));
        assertTrue(
                links.get(0).rect().getLowerLeftY() > links.get(1).rect().getUpperRightY() - 3f,
                "the fragments are on successive lines: " + links);
        assertTrue(links.get(1).rect().getLowerLeftX() < 51f, "the second fragment starts at the left margin");
    }

    @Test
    void onlyWebAndMailLinksBecomeActions(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "[mail](mailto:a@example.com) [auto](<https://example.org/ü x>) [file](file:///etc/passwd) "
                        + "[script](javascript:alert(1)) [relative](docs/other.md) [ftp](ftp://example.com/x)\n");
        List<String> uris = links(out).stream().map(Link::uri).toList();
        assertEquals(List.of("mailto:a@example.com", "https://example.org/%C3%BC%20x"), uris);
        String text = PdfProbe.text(out);
        assertTrue(text.contains("file") && text.contains("relative"), "the other links keep their text: " + text);
    }

    @Test
    void aFragmentLinkJumpsToItsHeadingEvenWhenTheHeadingComesLater(@TempDir Path dir) throws Exception {
        StringBuilder md =
                new StringBuilder("# Top\n\nSee [the details](#deep-dive--part-2) and [nowhere](#missing).\n\n");
        for (int i = 0; i < 70; i++) {
            md.append("Filler paragraph ").append(i).append(".\n\n");
        }
        md.append("## Deep Dive & Part 2\n\nBody.\n");
        Path out = write(dir, md.toString());
        List<Link> links = links(out);
        assertEquals(1, links.size(), "the link to a heading that does not exist gets no action: " + links);
        Link link = links.get(0);
        assertNull(link.uri());
        PdfProbe.Glyph heading = PdfProbe.glyphsOf(PdfProbe.glyphs(out), "Deep").get(0);
        assertTrue(heading.page() > 1, "the heading is on a later page");
        assertEquals(heading.page(), link.targetPage(), "the link lands on the heading's page");
        assertTrue(
                link.targetTop() > heading.baseline() && link.targetTop() < heading.baseline() + 40f,
                "…just above the heading: " + link.targetTop() + " vs baseline " + heading.baseline());
    }

    @Test
    void headingAnchorsAreTheHtmlExportsIds() {
        String md = "# Hello, World!\n\n## Hello, World!\n\n### `code` & Ünïcode_x-y\n\n#### 日本語 title\n";
        Node ast = MarkdownRenderer.parseToDocument(md);
        Map<Heading, String> ids = MarkdownPdfWriter.headingIds(ast);
        List<String> pdfIds = new ArrayList<>();
        for (Node n = ast.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Heading h) {
                pdfIds.add(ids.get(h));
            }
        }
        List<String> htmlIds = new ArrayList<>();
        Matcher m = Pattern.compile("<h[1-6] id=\"([^\"]*)\"").matcher(MarkdownHtmlExport.toHtml(md, "t", false));
        while (m.find()) {
            htmlIds.add(m.group(1));
        }
        assertEquals(4, htmlIds.size(), "the HTML export gives every heading an id");
        assertEquals(htmlIds, pdfIds, "a #fragment link resolves the same way in both exports");
        // …and the links the "insert table of contents" command writes point at them.
        assertEquals(pdfIds.get(0), com.editora.markdown.MarkdownToc.slug("Hello, World!"));
    }

    @Test
    void headingsFormANestedOutline(@TempDir Path dir) throws Exception {
        Path out = write(
                dir,
                "# One\n\ntext\n\n## One A\n\ntext\n\n### One A i\n\ntext\n\n## One B\n\ntext\n\n# Two\n\n"
                        + "#### Two deep\n\ntext\n");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            assertNotNull(outline, "the PDF has bookmarks");
            assertEquals("One[One A[One A i], One B], Two[Two deep]", describe(outline.children()));
            PDOutlineItem first = outline.getFirstChild();
            assertEquals(0, ((PDPageDestination) first.getDestination()).retrievePageNumber());
        }
    }

    private static String describe(Iterable<PDOutlineItem> items) {
        List<String> parts = new ArrayList<>();
        for (PDOutlineItem item : items) {
            String kids = describe(item.children());
            parts.add(item.getTitle() + (kids.isEmpty() ? "" : "[" + kids + "]"));
        }
        return String.join(", ", parts);
    }

    @Test
    void aDocumentWithoutHeadingsHasNoOutline(@TempDir Path dir) throws Exception {
        Path out = write(dir, "Just a paragraph.\n");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertNull(doc.getDocumentCatalog().getDocumentOutline());
        }
    }

    @Test
    void theDocumentInformationNamesTheDocumentAndTheApplication(@TempDir Path dir) throws Exception {
        long before = System.currentTimeMillis();
        Path out = write(dir, "# T\n");
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals("notes.md", doc.getDocumentInformation().getTitle());
            assertEquals("Editora", doc.getDocumentInformation().getCreator());
            assertNull(doc.getDocumentInformation().getAuthor(), "the application does not know the author");
            assertNotNull(doc.getDocumentInformation().getCreationDate());
            long created = doc.getDocumentInformation().getCreationDate().getTimeInMillis();
            assertTrue(Math.abs(created - before) < 120_000, "created now");
            assertEquals("es", doc.getDocumentCatalog().getLanguage());
        }
        // Without metadata from the caller a PDF still says what made it, and claims no title or language.
        Path bare = write(dir, "# T\n", PdfPageSpec.of("letter"), PdfDocMeta.NONE);
        try (PDDocument doc = Loader.loadPDF(bare.toFile())) {
            assertNull(doc.getDocumentInformation().getTitle());
            assertEquals("Editora", doc.getDocumentInformation().getCreator());
            assertNull(doc.getDocumentCatalog().getLanguage());
        }
    }

    @Test
    void everyPageHasTheFooterWithTheRightCount(@TempDir Path dir) throws Exception {
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            md.append("Paragraph ").append(i).append(" of the body.\n\n");
        }
        Path out = write(dir, md.toString());
        int pages = PdfProbe.pages(out);
        assertTrue(pages >= 3, "a multi-page document: " + pages);
        for (int p = 1; p <= pages; p++) {
            assertEquals(
                    "notes.md Página " + p + " de " + pages,
                    PdfProbe.artifactText(out, p).strip().replaceAll("\\s+", " "),
                    "the footer of page " + p);
        }
        // The footer is page furniture: a reader that honours /Artifact extracts the body without it, while
        // one that does not (PDFBox's stock stripper, pdftotext) still sees it.
        assertFalse(PdfProbe.text(out).contains("Página"), "not part of the content");
        assertTrue(PdfProbe.rawText(out).contains("Página 2 de " + pages));
        // …and it sits in the bottom margin, clear of the body, which keeps the margin it always had.
        for (PdfProbe.Glyph g : PdfProbe.glyphs(out)) {
            assertTrue(g.baseline() >= MarkdownPdfWriter.MARGIN, "body text below the bottom margin: " + g);
        }
    }

    @Test
    void theFooterCanBeSwitchedOffAndNothingElseChanges(@TempDir Path dir) throws Exception {
        String md = "# One\n\n[x](https://example.com)\n\n" + "Paragraph of the body.\n\n".repeat(120);
        Path on = write(dir.resolve("a").toFile().mkdirs() ? dir.resolve("a") : dir, md);
        Path off = write(dir, md, PdfPageSpec.of("letter").withFooter(false), META);
        assertEquals("", PdfProbe.artifactText(off).strip(), "no footer at all");
        assertEquals(PdfProbe.text(on), PdfProbe.text(off), "the body is laid out the same");
        assertEquals(PdfProbe.pages(on), PdfProbe.pages(off));
        assertEquals(1, links(off).size(), "links are unaffected");
        try (PDDocument doc = Loader.loadPDF(off.toFile())) {
            assertNotNull(doc.getDocumentCatalog().getDocumentOutline(), "the outline is unaffected");
            assertEquals("notes.md", doc.getDocumentInformation().getTitle(), "so is the metadata");
        }
    }

    @Test
    void aLongDocumentNameIsShortenedNotDrawnOverThePageLabel(@TempDir Path dir) throws Exception {
        String name = "a-very-long-file-name-".repeat(12) + ".md";
        Path out = write(dir, "text\n", PdfPageSpec.of("letter"), new PdfDocMeta(name, null, null));
        String footer = PdfProbe.artifactText(out).strip();
        assertTrue(footer.endsWith("1 / 1"), "the neutral page label when the caller gives no formatter: " + footer);
        assertTrue(footer.contains("…"), "the name is cut with an ellipsis: " + footer);
        assertTrue(footer.length() < name.length());
    }

    @Test
    void aSmallMarginStillKeepsTheBodyClearOfTheFooter(@TempDir Path dir) throws Exception {
        PdfPageSpec tight = new PdfPageSpec("a4", true, 18f, 9f, true);
        Path out = write(dir, "Paragraph of the body.\n\n".repeat(80), tight, META);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(
                    PDRectangle.A4.getHeight(), doc.getPage(0).getMediaBox().getWidth(), 0.01f, "landscape");
        }
        float lowestBody = Float.MAX_VALUE;
        for (PdfProbe.Glyph g : PdfProbe.glyphs(out)) {
            lowestBody = Math.min(lowestBody, g.baseline());
        }
        assertTrue(lowestBody >= 12f + PdfChrome.FOOTER_SIZE + 3f, "the body stops above the footer: " + lowestBody);
        assertTrue(PdfProbe.rightmostInk(out) <= PDRectangle.A4.getHeight() - 18f + 0.5f);
    }

    /**
     * The "narrow" preset in both writers: with the footer the body stops above it and loses no more than
     * the footer needs; without it the body runs down to the margin. Nothing is drawn outside the margins.
     */
    @Test
    void theNarrowMarginPresetKeepsTheFooterClearAndTheBodyInsideTheMargins(@TempDir Path dir) throws Exception {
        float m = PdfPageSpec.NARROW_MARGIN;
        float[] mdLowestByFooter = new float[2];
        for (boolean footer : new boolean[] {true, false}) {
            PdfPageSpec narrow = new PdfPageSpec("letter", false, PdfPageSpec.marginOf("narrow"), 9f, footer);

            Path md = write(dir, "Paragraph of the body.\n\n".repeat(120), narrow, META);
            float mdLowest = lowestBaseline(md);
            assertTrue(mdLowest >= m, "Markdown body inside the bottom margin (footer " + footer + "): " + mdLowest);
            mdLowestByFooter[footer ? 1 : 0] = mdLowest;
            assertEquals(footer, PdfProbe.artifactText(md).contains("notes.md"), "Markdown footer " + footer);
            if (footer) {
                assertTrue(mdLowest >= footerBaseline(md) + PdfChrome.FOOTER_SIZE + 3f, "clear of the footer");
            }
            assertEquals(m, PdfProbe.glyphs(md).get(0).x(), 0.5f, "the body starts at the narrow margin");

            Path code = dir.resolve("code-" + footer + ".pdf");
            CodePdfWriter.write("int x = 1;\n".repeat(200), null, false, 4, narrow, META, code);
            float codeLowest = lowestBaseline(code);
            float floor = footer ? m + PdfChrome.FOOTER_SIZE + 6f : m;
            assertTrue(codeLowest >= floor, "code body above " + floor + " (footer " + footer + "): " + codeLowest);
            assertTrue(codeLowest < floor + 9f * 1.6f, "and uses the page down to there: " + codeLowest);
            assertEquals(footer, PdfProbe.artifactText(code).contains("notes.md"), "code footer " + footer);
            if (footer) {
                assertTrue(codeLowest >= footerBaseline(code) + PdfChrome.FOOTER_SIZE + 2f, "clear of the footer");
            }
            assertEquals(m, PdfProbe.glyphs(code).get(0).x(), 0.5f, "the code starts at the narrow margin");
        }
        assertEquals(
                mdLowestByFooter[0],
                mdLowestByFooter[1],
                0.01f,
                "at this margin the footer fits below the Markdown body and costs it no line");
    }

    /** The lowest body baseline of the first page (a full one in these tests). */
    private static float lowestBaseline(Path pdf) throws Exception {
        assertTrue(PdfProbe.pages(pdf) > 1, "the first page is full");
        float lowest = Float.MAX_VALUE;
        for (PdfProbe.Glyph g : PdfProbe.glyphs(pdf)) {
            if (g.page() == 1) {
                lowest = Math.min(lowest, g.baseline());
            }
        }
        return lowest;
    }

    /** The baseline of the footer (the only text below the body) on the first page. */
    private static float footerBaseline(Path pdf) throws Exception {
        float[] lowest = {Float.MAX_VALUE};
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    for (org.apache.pdfbox.text.TextPosition p : positions) {
                        lowest[0] = Math.min(lowest[0], p.getPageHeight() - p.getYDirAdj());
                    }
                }
            };
            stripper.setEndPage(1);
            stripper.getText(doc);
        }
        return lowest[0];
    }
}
