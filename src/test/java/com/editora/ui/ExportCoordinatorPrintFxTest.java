package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.print.PrintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The print guard in {@link ExportCoordinator}: one preparation and one Print Preview per window, and a
 * busy state that every way out clears. Printer jobs are {@link PrintPreviewFxTest.FakeJob}s — the
 * coordinator's job supplier is replaced, so no {@code PrinterJob} is ever created here.
 */
@Tag("fx")
class ExportCoordinatorPrintFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final com.editora.config.Settings settings = new com.editora.config.Settings();
        final List<String> statuses = new ArrayList<>();
        com.editora.editor.EditorBuffer active;

        @Override
        public com.editora.config.Settings settings() {
            return settings;
        }

        @Override
        public com.editora.editor.EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private final Host host = new Host();
    private final List<PrintService.Result> reported = new ArrayList<>();
    private int jobsCreated;
    private ExportCoordinator exports;
    /** Every fake job handed out, in order. */
    private final List<PrintPreviewFxTest.FakeJob> jobs = new ArrayList<>();
    /** The "no printer" dialogs the coordinator built — recorded, never shown. */
    private final List<javafx.scene.control.Alert> noPrinterDialogs = new ArrayList<>();
    /** Which button of a "no printer" dialog the test presses: 0 = Export to PDF…, 1 = Cancel. */
    private int noPrinterAnswer = 1;
    /** The file names the Save dialog of an export was opened with (it is then "cancelled"). */
    private final List<String> saveDialogs = new ArrayList<>();

    private void create() throws Exception {
        FxTestSupport.runOnFx(() -> {
            exports = new ExportCoordinator(host, null, null, null, path -> {}, chooser -> {
                saveDialogs.add(chooser.getInitialFileName());
                return null;
            });
            exports.printJobs = () -> {
                jobsCreated++;
                PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter));
                jobs.add(job);
                return job;
            };
            exports.printReporter = reported::add;
            exports.noPrinterPrompt = alert -> {
                noPrinterDialogs.add(alert);
                return java.util.Optional.of(alert.getButtonTypes().get(noPrinterAnswer));
            };
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            previews().forEach(Stage::close);
            if (exports != null) {
                exports.shutdown();
            }
        });
    }

    private static List<Stage> previews() {
        List<Stage> out = new ArrayList<>();
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w instanceof Stage s
                    && s.isShowing()
                    && tr("print.preview.title").equals(s.getTitle())) {
                out.add(s);
            }
        }
        return out;
    }

    private void awaitPreviews(int count) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (FxTestSupport.callOnFx(() -> previews().size()) == count && !preparing()) {
                break;
            }
            Thread.sleep(50);
        }
        Thread.sleep(150); // a second, wrongly-opened preview would arrive right behind the first
        FxTestSupport.drainFx();
        assertEquals(count, FxTestSupport.callOnFx(() -> previews().size()), "open Print Preview windows");
    }

    private boolean preparing() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(exports, "printPreparing"));
    }

    private PrintPreview openPreview() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(exports, "openPreview"));
    }

    private static final String CSV = "name,value\na,1\nb,2\n";

    @Test
    void aSecondPrintRequestWhileOneIsPreparingOrOpenIsDropped() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> {
            exports.csvPrint(CSV);
            exports.csvPrint(CSV); // before the first preparation has returned
            exports.printCode();
            exports.printPreview();
            exports.printProjectMap(mapOutput());
        });
        awaitPreviews(1);
        assertEquals(1, jobsCreated, "only the first request may create a printer job");
        assertEquals(List.of(tr("status.print.preparing")), host.statuses, "the dropped requests say nothing");

        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV)); // while the preview is open
        awaitPreviews(1);
        assertEquals(1, jobsCreated);
    }

    @Test
    void closingThePreviewLetsTheNextPrintThrough() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreview first = openPreview();
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<javafx.scene.control.Button>field(first, "close").fire());
        awaitPreviews(0);
        assertNull(openPreview());
        assertEquals(tr("status.print.cancelled"), host.statuses.get(host.statuses.size() - 1));

        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        assertEquals(2, jobsCreated);
    }

    @Test
    void aPrintResultClearsTheGuard() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreview first = openPreview();
        // Print… on the fake job: the pages go to its recorder.
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<javafx.scene.control.Button>field(first, "print").fire());
        awaitPreviews(0);
        assertEquals(1, reported.size());
        assertTrue(reported.get(0).ok());
        assertNull(openPreview());
        assertTrue(host.statuses.contains(tr("status.print.printing")));
    }

    /** A12: pagination runs inside the preview's constructor; an error there used to escape uncaught. */
    @Test
    void aPaginationFailureIsReportedAndClearsTheGuard() throws Exception {
        create();
        PrintService.Paginator exploding = layout -> {
            throw new OutOfMemoryError("Java heap space");
        };
        FxTestSupport.runOnFx(() -> exports.openPrintPreview(
                new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter)),
                new PrintService.Prepared(exploding, null)));
        awaitPreviews(0);
        assertEquals(1, reported.size(), "the failure must reach the user, not the uncaught handler");
        assertFalse(reported.get(0).ok());
        assertEquals("Java heap space", reported.get(0).message());
        assertNull(openPreview());

        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV)); // printing still works afterwards
        awaitPreviews(1);
    }

    @Test
    void aPreparationErrorIsReportedAndClearsTheGuard() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> {
            exports.csvPrint(CSV);
            assertTrue(FxTestSupport.<Boolean>field(exports, "printPreparing"));
        });
        awaitPreviews(1);
        PrintPreview first = openPreview();
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<javafx.scene.control.Button>field(first, "close").fire());
        FxTestSupport.runOnFx(() -> exports.openPrintPreview(
                new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter)),
                new PrintService.Prepared(null, "mmdc failed")));
        assertEquals("mmdc failed", reported.get(reported.size() - 1).message());
        assertFalse(preparing());
        assertNull(openPreview());
    }

    /** An empty CSV and "no printer" return before anything is in flight: they must not leave the guard set. */
    @Test
    void requestsThatNeverStartLeaveTheGuardClear() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> {
            exports.csvPrint("");
            exports.printCode(); // no active buffer
            exports.printJobs = () -> null; // no printer
            exports.csvPrint(CSV);
            exports.printProjectMap(mapOutput());
        });
        assertFalse(preparing());
        assertEquals(
                List.of(
                        tr("status.csv.empty"),
                        tr("status.noFileOpen"),
                        tr("status.print.noPrinter"),
                        tr("status.print.noPrinter")),
                host.statuses);
    }

    // --- no printer: a dialog that offers Export to PDF (A11) ---

    @Test
    void withNoPrinterADialogOffersExportToPdf() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> {
            exports.printJobs = () -> null;
            exports.csvPrint(CSV);
        });
        assertEquals(1, noPrinterDialogs.size(), "Ctrl+P must not look like it did nothing");
        javafx.scene.control.Alert dialog = noPrinterDialogs.get(0);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("dialog.print.noPrinter.header"), dialog.getHeaderText());
            assertEquals(tr("dialog.print.noPrinter.content"), dialog.getContentText());
            assertEquals(
                    List.of(tr("dialog.print.noPrinter.exportPdf"), javafx.scene.control.ButtonType.CANCEL.getText()),
                    dialog.getButtonTypes().stream()
                            .map(javafx.scene.control.ButtonType::getText)
                            .toList());
            assertTrue(
                    dialog.getDialogPane().getStylesheets().stream().anyMatch(u -> u.endsWith("app.css")),
                    "styled like the other dialogs");
            assertFalse(dialog.isShowing(), "the test never shows it");
        });
        assertEquals(List.of(tr("status.print.noPrinter")), host.statuses, "the status line still says so");
        assertEquals(List.of(), saveDialogs, "Cancel exports nothing");
        assertFalse(preparing());
    }

    @Test
    void theDialogsExportButtonRunsTheExportOfWhatWasBeingPrinted() throws Exception {
        create();
        noPrinterAnswer = 0; // Export to PDF…
        FxTestSupport.runOnFx(() -> {
            exports.printJobs = () -> null;
            com.editora.editor.EditorBuffer code = new com.editora.editor.EditorBuffer();
            code.setDisplayName("Main.java");
            code.setContent("class Main {}\n");
            host.active = code;
            exports.printCode(); // → editor.exportPdf
            assertEquals(List.of("Main.pdf"), saveDialogs);

            com.editora.editor.EditorBuffer markdown = new com.editora.editor.EditorBuffer();
            markdown.setDisplayName("notes.md");
            markdown.setContent("# Notes\n");
            host.active = markdown;
            exports.printPreview(); // → preview.exportPdf
            assertEquals(List.of("Main.pdf", "notes.pdf"), saveDialogs);

            com.editora.editor.EditorBuffer csv = new com.editora.editor.EditorBuffer();
            csv.setDisplayName("table.csv");
            csv.setContent(CSV);
            host.active = csv;
            exports.csvPrint(CSV); // → the CSV table export, named after the file
            assertEquals(List.of("Main.pdf", "notes.pdf", "table.pdf"), saveDialogs);

            host.active = null;
            exports.printProjectMap(mapOutput()); // → the Project Map export
            assertEquals(4, saveDialogs.size());
        });
        assertEquals(4, noPrinterDialogs.size());
        assertEquals(0, jobsCreated);
        assertFalse(preparing());
    }

    // --- a job that is not used is cancelled (A18) ---

    @Test
    void aJobWhosePreparationFailedIsCancelled() throws Exception {
        create();
        PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter));
        FxTestSupport.runOnFx(() -> exports.openPrintPreview(job, new PrintService.Prepared(null, "mmdc failed")));
        assertEquals(1, job.cancelled);
        assertEquals(1, reported.size());
    }

    @Test
    void aJobWhosePreviewCouldNotOpenIsCancelled() throws Exception {
        create();
        PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter));
        PrintService.Paginator exploding = layout -> {
            throw new IllegalStateException("layout failed");
        };
        FxTestSupport.runOnFx(() -> exports.openPrintPreview(job, new PrintService.Prepared(exploding, null)));
        assertEquals(1, job.cancelled);
        assertEquals("layout failed", reported.get(0).message());
    }

    @Test
    void theJobOfASecondPreviewThatIsNotOpenedIsCancelledAndTheFirstOneIsNot() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreviewFxTest.FakeJob late = new PrintPreviewFxTest.FakeJob(silently(PrintPreviewFxTest::letter));
        FxTestSupport.runOnFx(() -> exports.openPrintPreview(
                late, new PrintService.Prepared(new PrintPreviewFxTest.FakePaginator(1), null)));
        awaitPreviews(1);
        assertEquals(1, late.cancelled, "the request that lost the race gives its job back");
        assertEquals(0, jobs.get(0).cancelled, "the open preview keeps its job");

        PrintPreview first = openPreview();
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<javafx.scene.control.Button>field(first, "close").fire());
        awaitPreviews(0);
        assertEquals(1, jobs.get(0).cancelled, "Close cancels it");
    }

    @Test
    void aPrintedJobIsEndedAndNeverCancelled() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreview first = openPreview();
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<javafx.scene.control.Button>field(first, "print").fire());
        awaitPreviews(0);
        assertEquals(1, jobs.get(0).ended);
        assertEquals(0, jobs.get(0).cancelled);
    }

    // --- the preview's size and zoom are this window's for the session (A13) ---

    @Test
    void theNextPreviewOfTheWindowOpensAtTheSizeAndZoomOfTheLast() throws Exception {
        create();
        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreview first = openPreview();
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(first, "stage");
            stage.setWidth(650);
            stage.setHeight(510);
            first.setZoom(PrintZoom.Setting.percent(1.5));
            FxTestSupport.<javafx.scene.control.Button>field(first, "close").fire();
        });
        awaitPreviews(0);

        FxTestSupport.runOnFx(() -> exports.csvPrint(CSV));
        awaitPreviews(1);
        PrintPreview second = openPreview();
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(second, "stage");
            assertEquals(650, stage.getWidth(), 0.5);
            assertEquals(510, stage.getHeight(), 0.5);
            assertEquals(PrintZoom.Setting.percent(1.5), second.zoomSetting());
        });
    }

    /** A one-page Project Map job, as the map's Print… action hands over. */
    private static ProjectMapOutput mapOutput() {
        return new ProjectMapOutput() {
            @Override
            public boolean landscape() {
                return false;
            }

            @Override
            public Rendered render(double pageWidth, double pageHeight) {
                ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(
                        new ProjectMapOutputPlan.Box(0, 0, 10, 10), List.of(), pageWidth, pageHeight);
                return new Rendered(List.of(new javafx.scene.image.WritableImage(10, 10)), 1, plan);
            }
        };
    }

    private interface Throwing<T> {
        T get() throws Exception;
    }

    private static <T> T silently(Throwing<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
