package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.editora.editor.EditorBuffer;
import com.editora.pdf.PdfExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The flow around a PDF export in {@link ExportCoordinator}: what the user is told while exports run and
 * queue, cancelling them, what a closing window leaves behind, and opening the result afterwards.
 */
@Tag("fx")
class ExportCoordinatorFlowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path temp;

    private static final class Host extends CoordinatorHostStub {
        final com.editora.config.Settings settings = new com.editora.config.Settings();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        /** Every label the status bar's background-work indicator was given, and how many are still up. */
        final List<String> taskLabels = new CopyOnWriteArrayList<>();

        int openTasks;
        Runnable onTask = () -> {};
        EditorBuffer active;

        @Override
        public com.editora.config.Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public AutoCloseable startBackgroundTask(String label) {
            taskLabels.add(label);
            openTasks++;
            onTask.run();
            boolean[] closed = {false};
            return () -> {
                if (!closed[0]) {
                    closed[0] = true;
                    openTasks--;
                }
            };
        }
    }

    private final Host host = new Host();
    private final List<java.io.File> destinations = new ArrayList<>();
    private final List<Path> opened = new ArrayList<>();
    private final List<Path> replaced = new ArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private ExportCoordinator exports;

    /** A coordinator whose Save dialog answers with {@code files}, one per export, in order. */
    private void create(String... files) throws Exception {
        for (String name : files) {
            destinations.add(temp.resolve(name).toFile());
        }
        FxTestSupport.runOnFx(() -> {
            exports = new ExportCoordinator(host, null, null, null, opened::add, chooser -> destinations.remove(0));
            exports.exported = replaced::add;
            host.settings.setPdfSyntaxHighlighting(false); // straight to the pages: no tokenizing first
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (exports != null) {
                exports.shutdown();
            }
            // An open buffer's highlight pass runs to the end: three of these long files held the Java
            // grammar's queue for longer than the next test class waits for its first pass.
            buffers.forEach(EditorBuffer::dispose);
            buffers.clear();
        });
    }

    /** A source file long enough (hundreds of pages) that its export is still running when the test acts. */
    private EditorBuffer longFile() {
        EditorBuffer b = new EditorBuffer();
        b.setDisplayName("Long.java");
        b.setContent("int value = 1; // a line of code\n".repeat(60_000));
        buffers.add(b);
        return b;
    }

    private EditorBuffer shortFile() {
        EditorBuffer b = new EditorBuffer();
        b.setDisplayName("Short.java");
        b.setContent("class Short {}\n");
        buffers.add(b);
        return b;
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("timed out waiting for " + what);
    }

    private List<String> stagingFolders() throws Exception {
        try (Stream<Path> files = Files.list(temp)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".editora-export-"))
                    .toList();
        }
    }

    private boolean pageProgressShown() {
        String prefix = tr("status.pdf.exportingPage", 1);
        prefix = prefix.substring(0, prefix.length() - 1); // without the page number
        for (String label : host.taskLabels) {
            if (label.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static long exportThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("pdf-export") && t.isAlive())
                .count();
    }

    @Test
    void aSecondExportSaysItIsQueuedAndTheRunningOneShowsItsPages() throws Exception {
        create("first.pdf", "second.pdf");
        FxTestSupport.runOnFx(() -> {
            host.active = longFile();
            exports.exportCodePdf();
            host.active = shortFile();
            exports.exportCodePdf();
        });
        assertEquals(
                List.of(tr("status.pdf.exporting"), tr("status.pdf.exporting"), tr("status.pdf.queued", 1)),
                List.copyOf(host.statuses),
                "the second export says it is queued behind one");
        assertEquals(List.of(tr("status.pdf.exporting")), List.copyOf(host.taskLabels));
        await("page progress in the status bar", this::pageProgressShown);
        await("both exports", () -> host.statuses.size() >= 5);
        assertEquals(
                List.of(
                        tr("status.pdf.exported", temp.resolve("first.pdf").toString()),
                        tr("status.pdf.exported", temp.resolve("second.pdf").toString())),
                host.statuses.subList(3, 5));
        assertEquals(0, FxTestSupport.callOnFx(() -> host.openTasks), "the indicator is taken down at the end");
        assertEquals(List.of(temp.resolve("first.pdf"), temp.resolve("second.pdf")), replaced);
        assertEquals(List.of(), stagingFolders());
    }

    @Test
    void cancellingStopsTheRunningAndTheQueuedExportAndLeavesNothingBehind() throws Exception {
        create("first.pdf", "second.pdf", "third.pdf");
        Path old = Files.writeString(temp.resolve("first.pdf"), "the previous export");
        FxTestSupport.runOnFx(() -> {
            host.active = longFile();
            exports.exportCodePdf();
            exports.exportCodePdf();
        });
        await("the first export to be writing pages", this::pageProgressShown);
        FxTestSupport.runOnFx(() -> exports.cancelPdfExports());
        await(
                "both exports to report",
                () -> host.statuses.stream()
                                .filter(tr("status.pdf.cancelled")::equals)
                                .count()
                        == 2);
        assertEquals("the previous export", Files.readString(old), "a cancelled export replaces nothing");
        assertFalse(Files.exists(temp.resolve("second.pdf")));
        assertEquals(List.of(), stagingFolders(), "the staging files are removed");
        assertEquals(List.of(), replaced);
        assertEquals(0, FxTestSupport.callOnFx(() -> host.openTasks));
        assertTrue(
                host.statuses.stream().noneMatch(s -> s.startsWith(tr("status.pdf.exportFailed", ""))),
                "a cancel is not a failure: " + host.statuses);

        // Nothing is running any more — and the next export works.
        FxTestSupport.runOnFx(() -> {
            exports.cancelPdfExports();
            assertEquals(tr("status.pdf.nothingToCancel"), host.statuses.get(host.statuses.size() - 1));
            host.active = shortFile();
            exports.exportCodePdf();
        });
        String done = tr("status.pdf.exported", temp.resolve("third.pdf").toString());
        await("the third export", () -> host.statuses.contains(done));
        assertTrue(Files.size(temp.resolve("third.pdf")) > 0);
    }

    /** C14: the queued export's {@code .editora-export-<n>/} folder used to stay beside the destination. */
    @Test
    void closingTheWindowDropsRunningAndQueuedExportsAndTheirStagingFolders() throws Exception {
        create("first.pdf", "second.pdf");
        long threadsBefore = exportThreads();
        FxTestSupport.runOnFx(() -> {
            host.active = longFile();
            exports.exportCodePdf();
            exports.exportCodePdf();
        });
        assertEquals(2, stagingFolders().size(), "precondition: each export has its staging folder");
        await("the first export to be writing pages", this::pageProgressShown);
        int said = host.statuses.size();
        FxTestSupport.runOnFx(() -> exports.shutdown());
        assertEquals(List.of(), stagingFolders(), "shutdown removes the staging folder of both");

        // The interrupted writer winds down on its thread; whatever it still reports must change nothing.
        for (int i = 0; i < 400 && exportThreads() > threadsBefore; i++) {
            Thread.sleep(50);
        }
        FxTestSupport.drainFx();
        Thread.sleep(200);
        FxTestSupport.drainFx();
        assertEquals(List.of(), stagingFolders());
        assertFalse(Files.exists(temp.resolve("first.pdf")), "an interrupted export is not committed");
        assertFalse(Files.exists(temp.resolve("second.pdf")));
        assertEquals(
                List.of(),
                host.statuses.subList(said, host.statuses.size()),
                "and reports nothing — least of all \"n characters could not be rendered\"");
        assertEquals(0, FxTestSupport.callOnFx(() -> host.openTasks));
    }

    /** The service itself: an export stopped by shutdown never delivers a success. */
    @Test
    void anInterruptedExportIsNeverDeliveredAsARenderedPdf() throws Exception {
        PdfExportService service = new PdfExportService();
        List<PdfExportService.Result> results = new CopyOnWriteArrayList<>();
        Path out = temp.resolve("direct.pdf");
        long threadsBefore = exportThreads();
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        service.onProgress(pages -> writing.countDown());
        // Text only a system fallback font can draw: an interrupt closes that font's file mid-read, which is
        // what used to come back as "ok, n characters could not be rendered".
        String text = "int 中文 = 1; // 日本語\n".repeat(40_000);
        service.exportCode(text, "Long.java", false, true, 4, "letter", out, results::add);
        assertTrue(writing.await(30, java.util.concurrent.TimeUnit.SECONDS), "the export should start its pages");
        service.shutdown();
        for (int i = 0; i < 400 && exportThreads() > threadsBefore; i++) {
            Thread.sleep(50);
        }
        FxTestSupport.drainFx();
        Thread.sleep(200);
        FxTestSupport.drainFx();
        for (PdfExportService.Result result : results) {
            assertFalse(result.ok(), "not a finished PDF");
            assertTrue(PdfExportService.cancelled(result), "but a cancelled one: " + result);
            assertEquals(0, result.unrendered());
        }
        assertEquals(0, service.pending());
    }

    @Test
    void theLastExportedFileCanBeOpenedAfterwards() throws Exception {
        create("report.pdf");
        FxTestSupport.runOnFx(() -> {
            exports.openLastExport();
            assertEquals(tr("status.export.none"), host.statuses.get(host.statuses.size() - 1));
            host.active = shortFile();
            exports.exportCodePdf();
        });
        Path report = temp.resolve("report.pdf");
        await("the export", () -> host.statuses.contains(tr("status.pdf.exported", report.toString())));
        assertEquals(List.of(), opened, "an export does not open its result by itself");
        FxTestSupport.runOnFx(() -> exports.openLastExport());
        assertEquals(List.of(report), opened);

        Files.delete(report);
        FxTestSupport.runOnFx(() -> exports.openLastExport());
        assertEquals(tr("status.export.gone", report.toString()), host.statuses.get(host.statuses.size() - 1));
        assertEquals(List.of(report), opened);
    }

    /** A result that arrives after the window closed: the staging folder goes, the report does not happen. */
    @Test
    void aResultAfterShutdownIsDropped() throws Exception {
        create();
        Path target = temp.resolve("late.pdf");
        List<java.util.function.Consumer<Boolean>> callbacks = new ArrayList<>();
        List<Boolean> reported = new ArrayList<>();
        Path[] staging = new Path[1];
        FxTestSupport.runOnFx(() -> {
            exports.<Boolean>staged(
                    target.toFile(),
                    (out, report) -> {
                        staging[0] = out;
                        callbacks.add(report);
                    },
                    ok -> ok,
                    message -> false,
                    reported::add);
            assertTrue(Files.isDirectory(staging[0].getParent()));
            exports.shutdown();
            assertFalse(Files.exists(staging[0].getParent()), "shutdown removed the pending staging folder");
            try {
                // the writer was still at work during shutdown and has finished its file since
                Files.createDirectories(staging[0].getParent());
                Files.writeString(staging[0], "%PDF");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            callbacks.get(0).accept(true);
        });
        assertEquals(List.of(), reported);
        assertFalse(Files.exists(target), "nothing is committed for a closed window");
        assertEquals(List.of(), stagingFolders());
        assertEquals(List.of(), replaced);
    }
}
