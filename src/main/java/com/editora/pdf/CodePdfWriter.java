package com.editora.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * Renders source text to a searchable, light-themed PDF with an embedded monospace font, optional
 * right-aligned line numbers, and (optional) syntax-highlight colors. Unless the {@link PdfPageSpec} turns it
 * off, every page has a footer — the document name and "Page n of N" ({@link PdfChrome}). Pure layout lives in {@link PdfText}; this class drives PDFBox.
 * Blocking — call off the FX thread.
 */
public final class CodePdfWriter {

    /** The margin used unless the {@link PdfPageSpec} sets one. */
    static final float MARGIN = 40f;

    private static final float LINE_HEIGHT_RATIO = 1.35f;
    private static final float GUTTER_GAP = 8f; // space between line numbers and code

    private CodePdfWriter() {}

    /** "a4" → A4, anything else → US Letter (see {@link PdfPageSpec#of}). */
    public static PDRectangle pageRectangle(String pageSizeKey) {
        return PdfPageSpec.of(pageSizeKey).rectangle();
    }

    /**
     * Writes {@code text} to {@code out} as a PDF. {@code spans} (highlight) may be null for plain text. Tabs
     * expand by {@code tabSize}. Characters JetBrains Mono lacks (CJK, Arabic, …) are drawn with a system
     * fallback font; returns how many could not be drawn at all and were replaced by {@code ?}.
     */
    public static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            String pageSizeKey,
            Path out)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, PdfPageSpec.of(pageSizeKey), PdfDocMeta.NONE, out);
    }

    /**
     * As {@link #write(String, StyleSpans, boolean, int, String, Path)} on the page {@code spec} describes,
     * with {@code meta}'s document name in the PDF's Title and in the footer beside the page label.
     */
    public static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            PdfPageSpec spec,
            PdfDocMeta meta,
            Path out)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, spec, meta, out, SystemFontFiles.get());
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
        return write(
                text, spans, lineNumbers, firstLineNumber, tabSize, PdfPageSpec.of(pageSizeKey), PdfDocMeta.NONE, out);
    }

    /** The excerpt form on the page {@code spec} describes, with {@code meta}'s document name. */
    public static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int firstLineNumber,
            int tabSize,
            PdfPageSpec spec,
            PdfDocMeta meta,
            Path out)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, spec, meta, out, SystemFontFiles.get(), firstLineNumber);
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
        return write(
                text, spans, lineNumbers, tabSize, PdfPageSpec.of(pageSizeKey), PdfDocMeta.NONE, out, fallbackFonts);
    }

    static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            PdfPageSpec spec,
            PdfDocMeta meta,
            Path out,
            List<Path> fallbackFonts)
            throws IOException {
        return write(text, spans, lineNumbers, tabSize, spec, meta, out, fallbackFonts, 1);
    }

    /** The fallback-font form with the number of the first line (see the {@code firstLineNumber} overload). */
    private static int write(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            PdfPageSpec spec,
            PdfDocMeta meta,
            Path out,
            List<Path> fallbackFonts,
            int firstLineNumber)
            throws IOException {
        try {
            return render(text, spans, lineNumbers, tabSize, spec, meta, out, fallbackFonts, firstLineNumber);
        } catch (IOException | RuntimeException e) {
            if (fallbackFonts.isEmpty() || PdfExportService.abandoned(e)) { // a cancel is not a bad font
                throw e;
            }
            // A system font PDFBox turns out unable to embed must not cost the export: retry with the bundled
            // fonts only (a failure unrelated to fonts just happens again and propagates).
            return render(text, spans, lineNumbers, tabSize, spec, meta, out, List.of(), firstLineNumber);
        }
    }

    private static int render(
            String text,
            StyleSpans<Collection<String>> spans,
            boolean lineNumbers,
            int tabSize,
            PdfPageSpec spec,
            PdfDocMeta meta,
            Path out,
            List<Path> fallbackFonts,
            int firstLineNumber)
            throws IOException {
        PDRectangle pageSize = spec.rectangle();
        float margin = spec.marginOr(MARGIN);
        float fontSize = spec.codeFontSize();
        float lineHeight = fontSize * LINE_HEIGHT_RATIO;
        List<List<PdfText.Run>> sourceLines = PdfText.splitIntoLineRuns(text, spans, Math.max(1, tabSize));

        try (PDDocument doc = new PDDocument();
                PdfGlyphs glyphs = new PdfGlyphs(doc, fallbackFonts)) {
            PDType0Font regular = font(doc, "JetBrainsMono-Regular");
            PDType0Font bold = font(doc, "JetBrainsMono-Bold");
            PDType0Font italic = font(doc, "JetBrainsMono-Italic");
            PDType0Font boldItalic = font(doc, "JetBrainsMono-BoldItalic");

            float charWidth = regular.getStringWidth("M") / 1000f * fontSize;
            int total = sourceLines.size();
            int digits =
                    Math.max(2, Integer.toString(firstLineNumber - 1 + total).length());
            float gutterWidth = lineNumbers ? digits * charWidth + GUTTER_GAP : 0f;
            float codeX = margin + gutterWidth;
            float contentWidth = pageSize.getWidth() - margin - codeX;
            int maxCols = Math.max(1, (int) Math.floor(contentWidth / charWidth));
            float topY = pageSize.getHeight() - margin - fontSize;
            // The footer sits just inside the bottom margin; the last code line keeps clear of it.
            float bottomY = spec.footer() ? margin + PdfChrome.FOOTER_SIZE + 6f : margin;

            Page page = new Page(doc, pageSize, topY);
            int lineNo = firstLineNumber - 1;
            for (List<PdfText.Run> source : sourceLines) {
                lineNo++;
                List<List<PdfText.Run>> visual = PdfText.wrap(source, maxCols);
                boolean first = true;
                for (List<PdfText.Run> vline : visual) {
                    if (page.y < bottomY) {
                        page.cs.close();
                        page = new Page(doc, pageSize, topY);
                    }
                    if (lineNumbers) {
                        // The number of a source line, or the mark that says "this is still the line above".
                        String label = first ? Integer.toString(lineNo) : PdfText.CONTINUATION_MARK;
                        drawGutter(page.cs, regular, fontSize, label, margin + digits * charWidth, page.y);
                    }
                    drawRuns(
                            page.cs,
                            glyphs,
                            vline,
                            codeX,
                            page.y,
                            charWidth,
                            fontSize,
                            regular,
                            bold,
                            italic,
                            boldItalic);
                    page.y -= lineHeight;
                    first = false;
                }
            }
            page.cs.close();
            PdfChrome.describe(doc, meta);
            if (spec.footer()) {
                PdfChrome.footers(doc, glyphs, regular, meta, margin, pageSize.getWidth() - margin, margin - 4f);
            }
            doc.save(out.toFile());
            return glyphs.missing();
        }
    }

    /**
     * Draws a gutter label — a line number or the continuation mark — right-aligned at {@code rightX}. It is
     * {@code /Artifact} marked content: page furniture, not part of the code, so a reader that honours
     * artifacts does not interleave the numbers with the text it extracts or copies.
     */
    private static void drawGutter(
            PDPageContentStream cs, PDType0Font font, float fontSize, String label, float rightX, float y)
            throws IOException {
        float w = font.getStringWidth(label) / 1000f * fontSize;
        cs.beginMarkedContent(COSName.ARTIFACT);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(PdfTheme.LINE_NUMBER);
        cs.newLineAtOffset(rightX - w, y);
        cs.showText(label);
        cs.endText();
        cs.endMarkedContent();
    }

    /** Draws one visual line's runs on the monospace grid (see {@link PdfGlyphs#showOnGrid}). */
    private static void drawRuns(
            PDPageContentStream cs,
            PdfGlyphs glyphs,
            List<PdfText.Run> runs,
            float x,
            float y,
            float charWidth,
            float fontSize,
            PDType0Font regular,
            PDType0Font bold,
            PDType0Font italic,
            PDType0Font boldItalic)
            throws IOException {
        int col = 0;
        for (PdfText.Run r : runs) {
            PDType0Font f = r.bold() ? (r.italic() ? boldItalic : bold) : (r.italic() ? italic : regular);
            cs.setNonStrokingColor(r.color());
            col += glyphs.showOnGrid(cs, f, fontSize, charWidth, r.text(), x + col * charWidth, y);
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
        final PDPageContentStream cs;
        float y;

        Page(PDDocument doc, PDRectangle size, float topY) throws IOException {
            PDPage page = new PDPage(size);
            doc.addPage(page);
            PdfExportService.pageStarted(); // progress, and where a cancelled export stops
            this.cs = new PDPageContentStream(doc, page);
            this.y = topY;
        }
    }
}
