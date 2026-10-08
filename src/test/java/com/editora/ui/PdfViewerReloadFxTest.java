package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C18: the PDF viewer holds the document it read in memory, so an export written over a PDF that is open in
 * a tab left the tab showing the old document. The window now reloads that tab when the export is in place.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PdfViewerReloadFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path tmp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static void writePdf(Path pdf, int pages) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(pdf.toFile());
        }
    }

    private static void awaitPages(PdfViewerPane pane, int pages) throws Exception {
        for (int i = 0; i < 200 && FxTestSupport.callOnFx(pane::pageCount) != pages; i++) {
            Thread.sleep(50);
        }
        assertEquals(pages, FxTestSupport.callOnFx(pane::pageCount));
    }

    @Test
    void anExportOverAnOpenPdfReloadsItsTab() throws Exception {
        Path pdf = tmp.resolve("report.pdf");
        writePdf(pdf, 1);
        FileWorkflowCoordinator files = FxTestSupport.field(fx.controller, "fileWorkflows");
        Tab tab = FxTestSupport.callOnFx(() -> files.openPdfTab(pdf, true));
        assertTrue(tab.getUserData() instanceof PdfViewerPane);
        PdfViewerPane pane = (PdfViewerPane) tab.getUserData();
        awaitPages(pane, 1);

        writePdf(pdf, 3); // the export replaces the file…
        ExportCoordinator exports = FxTestSupport.field(fx.controller, "exports");
        FxTestSupport.runOnFx(() -> exports.exported.accept(pdf)); // …and says so, as staged() does on commit
        awaitPages(pane, 3);

        // A path no tab shows, and a tab that is not a PDF viewer, are simply left alone.
        FxTestSupport.runOnFx(() -> {
            exports.exported.accept(tmp.resolve("elsewhere.pdf"));
            files.reloadViewerTab(tmp.resolve("notes.txt"));
        });
    }

    /** A reload keeps the page being read when the new document still has it, and recovers from an error. */
    @Test
    void aReloadStaysOnThePageAndBringsAFailedViewerBack() throws Exception {
        Path pdf = tmp.resolve("pages.pdf");
        writePdf(pdf, 4);
        PdfViewerPane pane = FxTestSupport.callOnFx(() -> new PdfViewerPane(pdf));
        try {
            awaitPages(pane, 4);
            FxTestSupport.runOnFx(() -> FxTestSupport.call(pane, "showPage", new Class<?>[] {int.class}, 2));
            writePdf(pdf, 6);
            FxTestSupport.runOnFx(pane::reload);
            awaitPages(pane, 6);
            assertEquals(2, (int) FxTestSupport.callOnFx(() -> FxTestSupport.<Integer>field(pane, "currentPage")));

            writePdf(pdf, 2); // shorter than the page being read: the last page it has
            FxTestSupport.runOnFx(pane::reload);
            awaitPages(pane, 2);
            assertEquals(1, (int) FxTestSupport.callOnFx(() -> FxTestSupport.<Integer>field(pane, "currentPage")));
        } finally {
            FxTestSupport.runOnFx(pane::dispose);
        }
    }
}
