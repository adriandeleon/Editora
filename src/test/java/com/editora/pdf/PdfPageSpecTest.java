package com.editora.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPageSpecTest {

    @Test
    void theDefaultSpecIsThePageEveryExportUsed() {
        PdfPageSpec letter = PdfPageSpec.of("letter");
        assertEquals(PDRectangle.LETTER, letter.rectangle());
        assertFalse(letter.landscape());
        assertTrue(letter.footer(), "the footer is on unless a caller turns it off");
        assertEquals(9f, letter.codeFontSize());
        assertEquals(40f, letter.marginOr(CodePdfWriter.MARGIN), "the code PDF's own margin");
        assertEquals(50f, letter.marginOr(MarkdownPdfWriter.MARGIN), "the Markdown PDF's own margin");
        assertEquals(PDRectangle.A4, PdfPageSpec.of("A4").rectangle());
        assertEquals(PDRectangle.A4, PdfPageSpec.of(" a4 ").rectangle());
    }

    @Test
    void aSpecCanTurnThePageAndSetTheMargin() {
        PdfPageSpec spec = new PdfPageSpec("a4", true, 24f, 11f, false);
        assertEquals(PDRectangle.A4.getHeight(), spec.rectangle().getWidth());
        assertEquals(PDRectangle.A4.getWidth(), spec.rectangle().getHeight());
        assertEquals(24f, spec.marginOr(50f));
        assertEquals(11f, spec.codeFontSize());
        assertFalse(spec.footer());
        assertTrue(spec.withFooter(true).footer());
        assertEquals(spec, spec.withFooter(true).withFooter(false));
        // Nonsense falls back to the defaults rather than producing an unusable page.
        PdfPageSpec odd = new PdfPageSpec(null, false, Float.NaN, -3f, true);
        assertEquals(PdfPageSpec.of("letter"), odd);
    }

    @Test
    void anUnknownPageSizeFallsBackToLetterAndIsLoggedOnce() {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger log = Logger.getLogger(PdfPageSpec.class.getName());
        log.addHandler(handler);
        try {
            String key = "legal-" + System.nanoTime(); // unique, so an earlier test cannot have reported it
            assertEquals(PDRectangle.LETTER, PdfPageSpec.of(key).rectangle());
            assertEquals(PDRectangle.LETTER, PdfPageSpec.of(key).rectangle());
            assertEquals(PDRectangle.LETTER, CodePdfWriter.pageRectangle(key.toUpperCase()));
            assertEquals(1, records.size(), "reported once, not on every export");
            assertTrue(records.get(0).getMessage().contains(key), records.get(0).getMessage());
            PdfPageSpec.of(null);
            PdfPageSpec.of("");
            PdfPageSpec.of("letter");
            assertEquals(1, records.size(), "an unset size is simply the default");
        } finally {
            log.removeHandler(handler);
        }
    }

    @Test
    void theFurnitureGreyIsReadableOnWhite() {
        assertTrue(
                PdfTheme.contrast(PdfTheme.LINE_NUMBER, PdfTheme.BACKGROUND) >= 4.5,
                "gutter numbers and footers are small text: "
                        + PdfTheme.contrast(PdfTheme.LINE_NUMBER, PdfTheme.BACKGROUND));
        assertEquals(21.0, PdfTheme.contrast(java.awt.Color.BLACK, java.awt.Color.WHITE), 0.01);
    }
}
