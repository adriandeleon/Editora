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

        @Override
        public com.editora.config.Settings settings() {
            return settings;
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

    private void create() throws Exception {
        FxTestSupport.runOnFx(() -> {
            exports = new ExportCoordinator(host, null, null, null, path -> {});
            exports.printJobs = () -> {
                jobsCreated++;
                try {
                    return new PrintPreviewFxTest.FakeJob(PrintPreviewFxTest.letter());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            exports.printReporter = reported::add;
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
            exports.printProjectMap(new javafx.scene.image.WritableImage(10, 10));
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
            exports.printProjectMap(new javafx.scene.image.WritableImage(10, 10));
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
