package com.editora.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link PdfExportService} tells the writers apart: an export being abandoned from a real failure. */
class PdfExportServiceTest {

    @Test
    void aCancelOrAnInterruptIsNotAFontFailure() {
        assertTrue(PdfExportService.abandoned(new java.util.concurrent.CancellationException("export cancelled")));
        assertTrue(PdfExportService.abandoned(new java.nio.channels.ClosedByInterruptException()));
        assertTrue(PdfExportService.abandoned(new java.io.InterruptedIOException()));
        assertTrue(
                PdfExportService.abandoned(
                        new java.io.IOException("font", new java.nio.channels.ClosedByInterruptException())),
                "also as the cause of what the font loader threw");
        assertFalse(PdfExportService.abandoned(new java.io.IOException("not a TrueType font")));
        assertFalse(PdfExportService.abandoned(new IllegalArgumentException("U+0378 is not available")));
        assertFalse(PdfExportService.abandoned(null));
    }

    @Test
    void anInterruptedThreadIsAbandoningWhateverItThrew() {
        Thread.currentThread().interrupt();
        try {
            assertTrue(PdfExportService.abandoned(new java.io.IOException("stream closed")));
        } finally {
            assertTrue(Thread.interrupted(), "the check must not swallow the interrupt");
        }
    }

    /** Outside an export of the service — a writer called directly, as the tests do — the page hook is silent. */
    @Test
    void thePageHookDoesNothingOutsideAnExport() {
        PdfExportService.pageStarted();
    }

    @Test
    void onlyTheCancelledResultIsCancelled() {
        assertFalse(PdfExportService.cancelled(new PdfExportService.Result(false, "cancelled")));
        assertFalse(PdfExportService.cancelled(new PdfExportService.Result(true, "")));
        assertFalse(PdfExportService.cancelled(null));
    }
}
