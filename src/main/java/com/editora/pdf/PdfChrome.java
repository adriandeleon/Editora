package com.editora.pdf;

import java.io.IOException;
import java.util.Calendar;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * What every text PDF carries around its content: the document information and the page footer. Shared by
 * {@link CodePdfWriter} and {@link MarkdownPdfWriter} so the two cannot drift apart.
 */
final class PdfChrome {

    /** The footer's type size. */
    static final float FOOTER_SIZE = 7.5f;
    /** The least space kept between the document name and the page label. */
    private static final float FOOTER_GAP = 12f;

    private PdfChrome() {}

    /**
     * Sets the document information — Title (the document name), Creator and CreationDate — and the
     * catalog language. There is deliberately no Author: the application does not know who wrote the file.
     */
    static void describe(PDDocument doc, PdfDocMeta meta) {
        PDDocumentInformation info = doc.getDocumentInformation();
        if (meta.title() != null && !meta.title().isBlank()) {
            info.setTitle(meta.title());
        }
        info.setCreator("Editora");
        info.setCreationDate(Calendar.getInstance());
        if (meta.language() != null && !meta.language().isBlank()) {
            doc.getDocumentCatalog().setLanguage(meta.language());
        }
    }

    /**
     * Draws the footer on every page of {@code doc}: the document name on the left and the page label
     * ("Page 2 of 7") on the right, between {@code left} and {@code right} on {@code baseline}. A final pass
     * over the finished document, because the label needs the page count. Each footer is {@code /Artifact}
     * marked content (pagination), so a reader that honours artifacts leaves it out of the extracted text.
     */
    static void footers(
            PDDocument doc, PdfGlyphs glyphs, PDFont font, PdfDocMeta meta, float left, float right, float baseline)
            throws IOException {
        int pages = doc.getNumberOfPages();
        String title =
                meta.title() == null ? "" : glyphs.rendered(font, meta.title().strip());
        COSDictionary props = new COSDictionary();
        props.setName(COSName.TYPE, "Pagination");
        props.setName(COSName.SUBTYPE, "Footer");
        PDPropertyList footer = PDPropertyList.create(props);
        for (int i = 0; i < pages; i++) {
            PDPage page = doc.getPage(i);
            String label = glyphs.rendered(font, meta.label(i + 1, pages));
            float labelW = glyphs.width(font, label, FOOTER_SIZE);
            String name = fit(glyphs, font, title, right - left - labelW - FOOTER_GAP);
            try (PDPageContentStream cs =
                    new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.beginMarkedContent(COSName.ARTIFACT, footer);
                cs.setNonStrokingColor(PdfTheme.LINE_NUMBER);
                if (!name.isEmpty()) {
                    glyphs.show(cs, font, FOOTER_SIZE, name, left, baseline);
                }
                glyphs.show(cs, font, FOOTER_SIZE, label, right - labelW, baseline);
                cs.endMarkedContent();
            }
        }
    }

    /** {@code text}, or its longest prefix that fits {@code avail} points with an ellipsis appended. */
    private static String fit(PdfGlyphs glyphs, PDFont font, String text, float avail) throws IOException {
        if (text.isEmpty() || glyphs.width(font, text, FOOTER_SIZE) <= avail) {
            return text;
        }
        String ellipsis = glyphs.rendered(font, "…");
        int end = text.length();
        while (end > 0) {
            end = text.offsetByCodePoints(end, -1);
            String cut = text.substring(0, end).stripTrailing() + ellipsis;
            if (glyphs.width(font, cut, FOOTER_SIZE) <= avail) {
                return end == 0 ? "" : cut;
            }
        }
        return "";
    }
}
