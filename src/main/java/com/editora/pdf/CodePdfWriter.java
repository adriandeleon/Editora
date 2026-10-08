package com.editora.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Renders source text to a searchable, light-themed PDF with an embedded monospace font, optional
 * right-aligned line numbers, and (optional) syntax-highlight colors. Pure layout lives in
 * {@link PdfText}; this class drives PDFBox. Blocking — call off the FX thread.
 */
public final class CodePdfWriter {

    private static final float MARGIN = 40f;
    private static final float FONT_SIZE = 9f;
    private static final float LINE_HEIGHT = FONT_SIZE * 1.35f;
    private static final float FOOTER_SIZE = 7.5f;
    private static final float GUTTER_GAP = 8f; // space between line numbers and code

    private CodePdfWriter() {}

    /** "a4" → A4, anything else → US Letter. */
    public static PDRectangle pageRectangle(String pageSizeKey) {
        return "a4".equalsIgnoreCase(pageSizeKey) ? PDRectangle.A4 : PDRectangle.LETTER;
    }

    /**
     * Writes {@code text} to {@code out} as a PDF. {@code spans} (highlight) may be null for plain text;
     * {@code title} is shown in the footer. Tabs expand by {@code tabSize}. Characters JetBrains Mono lacks
     * (CJK, Arabic, …) are drawn with a system fallback font; returns how many could not be drawn at all and
     * were replaced by {@code ?}.
     */
    public static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            String pageSizeKey,
            Path out)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, pageSizeKey, out, SystemFontFiles.get());
    }

    /**
     * As {@link #write(String, StyleSpans, boolean, int, String, Path)} for an excerpt of a file: the gutter
     * counts from {@code firstLineNumber} (1-based), the line the excerpt starts on in its file.
     */
    public static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int firstLineNumber,
            int tabSize,
            String pageSizeKey,
            Path out)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, pageSizeKey, out, SystemFontFiles.get(), firstLineNumber);
    }

    /** As {@link #write(String, StyleSpans, boolean, int, String, Path)} with explicit fallback font files. */
    static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            String pageSizeKey,
            Path out,
            List<Path> fallbackFonts)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, pageSizeKey, out, fallbackFonts, 1);
    }

    /** The fallback-font form with the number of the first line (see the {@code firstLineNumber} overload). */
    private static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            String pageSizeKey,
            Path out,
            List<Path> fallbackFonts,
            int firstLineNumber)
            throws IOException {
        try {
            return render(text, spans, lineNumbers, tabSize, pageSizeKey, out, fallbackFonts, firstLineNumber);
        } catch (IOException | RuntimeException e) {
            if (fallbackFonts.isEmpty()) {
                throw e;
            }
            // A system font PDFBox turns out unable to embed must not cost the export: retry with the bundled
            // fonts only (a failure unrelated to fonts just happens again and propagates).
            return render(text, spans, lineNumbers, tabSize, pageSizeKey, out, List.of(), firstLineNumber);
        }
    }

    private static int render(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            String pageSizeKey,
            Path out,
            List<Path> fallbackFonts,
            int firstLineNumber)
            throws IOException {
        PDRectangle pageSize = pageRectangle(pageSizeKey);
        List<List<PdfText.Run>> sourceLines = PdfText.splitIntoLineRuns(text, spans, Math.max(1, tabSize));

        try (PDDocument doc = new PDDocument();
                PdfGlyphs glyphs = new PdfGlyphs(doc, fallbackFonts)) {
            PDType0Font regular = font(doc, "JetBrainsMono-Regular");
            PDType0Font bold = font(doc, "JetBrainsMono-Bold");
            PDType0Font italic = font(doc, "JetBrainsMono-Italic");
            PDType0Font boldItalic = font(doc, "JetBrainsMono-BoldItalic");

            float charWidth = regular.getStringWidth("M") / 1000f * FONT_SIZE;
            int total = sourceLines.size();
            int digits =
                    Math.max(2, Integer.toString(firstLineNumber - 1 + total).length());
            float gutterWidth = lineNumbers ? digits * charWidth + GUTTER_GAP : 0f;
            float codeX = MARGIN + gutterWidth;
            float contentWidth = pageSize.getWidth() - MARGIN - codeX;
            int maxCols = Math.max(1, (int) Math.floor(contentWidth / charWidth));
            float topY = pageSize.getHeight() - MARGIN;
            float bottomY = MARGIN + FOOTER_SIZE + 6f;

            Page page = new Page(doc, pageSize, topY);
            int lineNo = firstLineNumber - 1;
            for (List<PdfText.Run> source : sourceLines) {
                lineNo++;
                List<List<PdfText.Run>> visual = PdfText.wrap(source, maxCols);
                boolean first = true;
                for (List<PdfText.Run> vline : visual) {
                    if (page.y < bottomY) {
                        page.finish(regular);
                        page = new Page(doc, pageSize, topY);
                    }
                    if (lineNumbers && first) {
                        drawLineNumber(page.cs, regular, lineNo, MARGIN + digits * charWidth, page.y);
                    }
                    drawRuns(page.cs, glyphs, vline, codeX, page.y, charWidth, regular, bold, italic, boldItalic);
                    page.y -= LINE_HEIGHT;
                    first = false;
                }
            }
            page.finish(regular);
            doc.save(out.toFile());
            return glyphs.missing();
        }
    }

    private static void drawLineNumber(PDPageContentStream cs, PDType0Font font, int n, float rightX, float y)
            throws IOException {
        String s = Integer.toString(n);
        float w = font.getStringWidth(s) / 1000f * FONT_SIZE;
        cs.beginText();
        cs.setFont(font, FONT_SIZE);
        cs.setNonStrokingColor(PdfTheme.LINE_NUMBER);
        cs.newLineAtOffset(rightX - w, y);
        cs.showText(s);
        cs.endText();
    }

    /** Draws one visual line's runs on the monospace grid (see {@link PdfGlyphs#showOnGrid}). */
    private static void drawRuns(
            PDPageContentStream cs,
            PdfGlyphs glyphs,
            List<PdfText.Run> runs,
            float x,
            float y,
            float charWidth,
            PDType0Font regular,
            PDType0Font bold,
            PDType0Font italic,
            PDType0Font boldItalic)
            throws IOException {
        int col = 0;
        for (PdfText.Run r : runs) {
            PDType0Font f = r.bold() ? (r.italic() ? boldItalic : bold) : (r.italic() ? italic : regular);
            cs.setNonStrokingColor(r.color());
            col += glyphs.showOnGrid(cs, f, FONT_SIZE, charWidth, r.text(), x + col * charWidth, y);
        }
    }

    private static PDType0Font font(PDDocument doc, String name) throws IOException {
        try (InputStream in =
                CodePdfWriter.class.getResourceAsStream("/com/editora/fonts/jetbrains-mono/" + name + ".ttf")) {
            if (in == null) {
                throw new IOException("Bundled font not found: " + name);
            }
            return PDType0Font.load(doc, in);
        }
    }

    /** One PDF page + its content stream and the current baseline {@code y}. */
    private static final class Page {
        final PDDocument doc;
        final PDPage page;
        final PDPageContentStream cs;
        final PDRectangle size;
        float y;

        Page(PDDocument doc, PDRectangle size, float topY) throws IOException {
            this.doc = doc;
            this.size = size;
            this.page = new PDPage(size);
            doc.addPage(page);
            this.cs = new PDPageContentStream(doc, page);
            this.y = topY - FONT_SIZE;
        }

        /** Draws a centered gray page number near the bottom margin and closes the stream. */
        void finish(PDType0Font font) throws IOException {
            String label = Integer.toString(doc.getNumberOfPages());
            float w = font.getStringWidth(label) / 1000f * FOOTER_SIZE;
            cs.beginText();
            cs.setFont(font, FOOTER_SIZE);
            cs.setNonStrokingColor(PdfTheme.LINE_NUMBER);
            cs.newLineAtOffset((size.getWidth() - w) / 2f, MARGIN - 4f);
            cs.showText(label);
            cs.endText();
            cs.close();
        }
    }
}
