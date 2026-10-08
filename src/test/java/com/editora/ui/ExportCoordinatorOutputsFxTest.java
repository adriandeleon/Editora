package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.print.PrintService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ExportCoordinator} writes and says for its outputs other than PDF: Word / OpenDocument /
 * spreadsheet / HTML / timeline-JSON / CSV files, and the printed page — through a
 * {@link PrintPreviewFxTest.FakeJob}, so no {@code PrinterJob} is created and nothing reaches a printer. The
 * Save dialog is replaced by a file in a temporary directory.
 */
@Tag("fx")
class ExportCoordinatorOutputsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public synchronized void setStatus(String message) {
            statuses.add(message);
            notifyAll();
        }

        @Override
        public void openExternalUrl(String url) {
            opened.add("external " + url);
        }

        /** Blocks until a status matching {@code wanted} has been reported, and returns it. */
        synchronized String await(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (true) {
                for (String s : statuses) {
                    if (wanted.test(s)) {
                        return s;
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("no such status; got " + statuses);
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }

        synchronized String last() {
            return statuses.isEmpty() ? null : statuses.getLast();
        }
    }

    private final Host host = new Host();
    private final List<PrintPreviewFxTest.FakeJob> jobs = new ArrayList<>();
    private final List<String> saveDialogs = new ArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private File destination;
    private ExportCoordinator exports;

    @BeforeEach
    void create() throws Exception {
        FxTestSupport.runOnFx(() -> {
            exports = new ExportCoordinator(
                    host, new MermaidCoordinator(host), null, null, path -> host.opened.add("tab " + path), chooser -> {
                        saveDialogs.add(chooser.getInitialFileName());
                        return destination;
                    });
            exports.printJobs = () -> {
                try {
                    PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(PrintPreviewFxTest.letter());
                    jobs.add(job);
                    return job;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            previews().forEach(Stage::close);
            exports.shutdown();
            buffers.forEach(EditorBuffer::dispose);
        });
    }

    // --- plumbing ---------------------------------------------------------------------------------------

    private EditorBuffer buffer(String name, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName(name);
            b.setContent(content);
            buffers.add(b);
            host.active = b;
            return b;
        });
    }

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    private File target(String name) {
        destination = dir.resolve(name).toFile();
        return destination;
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

    private PrintPreview openPreview() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(exports, "openPreview"));
    }

    /**
     * Runs a print request and waits for what its preparation ends in: the preview window, or the report of
     * a failure. The watcher is installed before the request, so the outcome cannot be missed.
     */
    private List<PrintService.Result> print(Runnable request) throws Exception {
        List<PrintService.Result> reported = new ArrayList<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        javafx.collections.ListChangeListener<Window> opened = change -> {
            if (!previews().isEmpty()) {
                done.countDown();
            }
        };
        fx(() -> {
            exports.printReporter = r -> {
                reported.add(r);
                done.countDown();
            };
            Window.getWindows().addListener(opened);
            request.run();
            if (!FxTestSupport.<Boolean>field(exports, "printPreparing")
                    && previews().isEmpty()) {
                done.countDown(); // refused before any preparation started
            }
        });
        try {
            assertTrue(done.await(60, TimeUnit.SECONDS), "the print preparation finished; statuses " + host.statuses);
        } finally {
            fx(() -> Window.getWindows().removeListener(opened));
        }
        FxTestSupport.drainFx();
        return reported;
    }

    private int pageCount(PrintPreview preview) throws Exception {
        return FxTestSupport.callOnFx(
                () -> FxTestSupport.<PrintService.Pages>field(preview, "pages").count());
    }

    private static String docxText(File f) throws IOException {
        try (InputStream in = Files.newInputStream(f.toPath());
                XWPFDocument doc = new XWPFDocument(in)) {
            return doc.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
        }
    }

    private List<String> filesInDir() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    // --- printing ---------------------------------------------------------------------------------------

    @Test
    void printingCodeOpensAPreviewOfItsPagesAndPrintSendsThemToTheJob() throws Exception {
        buffer("Main.java", "class Main {\n    int x;\n}\n");
        assertEquals(List.of(), print(exports::printCode));
        assertEquals(List.of(tr("status.print.preparing")), host.statuses);
        PrintPreview preview = openPreview();
        assertNotNull(preview);
        assertEquals(1, pageCount(preview));

        fx(() -> {
            exports.printReporter =
                    r -> FxTestSupport.call(exports, "reportPrint", new Class<?>[] {PrintService.Result.class}, r);
            FxTestSupport.<Button>field(preview, "print").fire();
        });
        host.await(tr("status.print.done")::equals);
        assertEquals(1, jobs.size());
        assertEquals(1, jobs.getFirst().printed.size(), "one page reached the job");
        assertEquals(1, jobs.getFirst().ended);
        assertEquals(0, jobs.getFirst().cancelled);
    }

    @Test
    void aBlankBufferIsNotWorthAJob() throws Exception {
        buffer("empty.txt", "  \n\n");
        print(exports::printCode);
        print(exports::printPreview);
        buffer("empty.md", "  \n");
        print(exports::printPreview);
        assertEquals(
                List.of(tr("status.print.nothing"), tr("status.print.noPreview"), tr("status.print.nothing")),
                host.statuses);
        assertEquals(0, jobs.size());
    }

    @Test
    void printingAMarkdownPreviewPaginatesTheDocument() throws Exception {
        StringBuilder md = new StringBuilder("# Report\n\n");
        for (int i = 0; i < 120; i++) {
            md.append("Paragraph ").append(i).append(" of the report, long enough to take a line.\n\n");
        }
        buffer("report.md", md.toString());
        assertEquals(List.of(), print(exports::printPreview));
        PrintPreview preview = openPreview();
        assertNotNull(preview);
        assertTrue(pageCount(preview) > 1, "120 paragraphs do not fit one Letter page");
    }

    @Test
    void printingAnSvgPreviewRasterisesItOntoAPage() throws Exception {
        EditorBuffer svg = buffer(
                "badge.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
                        + "<rect width=\"40\" height=\"20\" fill=\"#0a0\"/></svg>");
        print(exports::printPreview);
        assertEquals(List.of(tr("status.print.noPreview")), host.statuses, "the SVG preview is switched off");

        fx(() -> svg.setSvgPreviewEnabled(true));
        assertEquals(List.of(), print(exports::printPreview));
        assertEquals(1, pageCount(openPreview()));
    }

    @Test
    void aPrintFailureIsShownWithItsReasonAndTheJobIsCancelled() throws Exception {
        PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(PrintPreviewFxTest.letter());
        DialogPane dialog = FxDialogs.duringHeader(
                () -> exports.openPrintPreview(job, new PrintService.Prepared(null, "the renderer exited with 1")),
                tr("dialog.print.failed"),
                ButtonBar.ButtonData.OK_DONE);
        assertNotNull(dialog, "a failed print is not only a status line");
        assertEquals("the renderer exited with 1", dialog.getContentText());
        assertEquals(tr("status.print.failed", "the renderer exited with 1"), host.last());
        assertEquals(1, job.cancelled);
        assertNull(openPreview());

        // A failure with no message still says something.
        DialogPane bare = FxDialogs.duringHeader(
                () -> exports.openPrintPreview(
                        new PrintPreviewFxTest.FakeJob(silentLetter()), new PrintService.Prepared(null, " ")),
                tr("dialog.print.failed"),
                ButtonBar.ButtonData.OK_DONE);
        assertEquals(tr("dialog.export.noDetails"), bare.getContentText());
    }

    private static javafx.print.PageLayout silentLetter() {
        try {
            return PrintPreviewFxTest.letter();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- Word / OpenDocument ----------------------------------------------------------------------------

    @Test
    void aMarkdownDocumentIsExportedToWordAndCanBeOpenedAfterwards() throws Exception {
        buffer("notes.md", "# Title\n\nSome **bold** text.\n");
        File out = target("notes"); // typed without an extension
        fx(exports::exportPreviewDocx);
        File written = new File(out.getParentFile(), "notes.docx");
        host.await(tr("status.office.exported", written.toString())::equals);

        assertEquals(List.of("notes.docx"), saveDialogs, "the dialog proposes the document's name");
        assertEquals("Title\nSome bold text.", docxText(written));
        assertEquals(List.of("notes.docx"), filesInDir(), "no staging folder is left beside it");
        assertEquals(tr("status.office.exporting"), host.statuses.getFirst());

        fx(exports::openLastExport);
        assertEquals(
                List.of("external " + written.toPath().toUri()),
                host.opened,
                "an office file opens outside the editor");
    }

    @Test
    void aMarkdownDocumentIsExportedToOpenDocumentText() throws Exception {
        buffer("notes.md", "# Title\n\nBody.\n");
        File out = target("notes.odt");
        fx(exports::exportPreviewOdt);
        host.await(tr("status.office.exported", out.toString())::equals);
        try (ZipFile zip = new ZipFile(out)) {
            assertEquals("mimetype", zip.entries().nextElement().getName());
            String content =
                    new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes(), "UTF-8");
            assertTrue(content.contains(">Title<") && content.contains(">Body.<"), content);
        }
    }

    @Test
    void anOfficeExportNeedsAMarkdownDocumentAndADestination() throws Exception {
        fx(exports::exportPreviewDocx); // no buffer
        buffer("Main.java", "class Main {}");
        fx(exports::exportPreviewOdt);
        assertEquals(List.of(tr("status.office.notMarkdown"), tr("status.office.notMarkdown")), host.statuses);

        buffer("notes.md", "# Title\n");
        destination = null; // the Save dialog was cancelled
        fx(exports::exportPreviewDocx);
        assertEquals(2, host.statuses.size(), "a cancelled dialog exports nothing and says nothing");
        assertEquals(List.of(), filesInDir());
    }

    @Test
    void anOfficeExportThatCannotBeWrittenIsShownWithItsReasonAndLeavesNothingBehind() throws Exception {
        buffer("notes.md", "# Title\n");
        Path blocker = Files.writeString(dir.resolve("not-a-folder"), "a file");
        destination = blocker.resolve("notes.docx").toFile(); // its "folder" is a file
        try (AsyncTestScope async = new AsyncTestScope()) {
            // The failure arrives from the export thread, so the dialog is answered whenever it appears.
            java.util.concurrent.CountDownLatch shown = SaveDecisionsFxTest.answerDialog(
                    async, tr("dialog.officeExport.failed"), ButtonBar.ButtonData.OK_DONE);
            fx(exports::exportPreviewDocx);
            async.await(shown, "the export-failed dialog");
        }
        String prefix = tr("status.office.exportFailed", "");
        String failed = host.await(s -> s.startsWith(prefix));
        assertFalse(failed.substring(prefix.length()).isBlank(), "the reason is given: " + failed);
        assertEquals(List.of("not-a-folder"), filesInDir());

        fx(exports::openLastExport);
        assertEquals(tr("status.export.none"), host.last(), "a failed export is not the last export");
    }

    // --- spreadsheets -----------------------------------------------------------------------------------

    @Test
    void csvRowsAreExportedToExcelWithTheirHeader() throws Exception {
        File out = target("table.xlsx");
        fx(() -> exports.csvExportSpreadsheet(
                List.of(List.of("name", "n"), List.of("a", "1")), true, "table.csv", true));
        host.await(tr("status.office.exported", out.toString())::equals);
        assertEquals(List.of("table.xlsx"), saveDialogs);
        try (InputStream in = Files.newInputStream(out.toPath());
                XSSFWorkbook book = new XSSFWorkbook(in)) {
            var sheet = book.getSheetAt(0);
            assertEquals("name", sheet.getRow(0).getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(0).getCell(0).getCellStyle().getFont().getBold(), "the header row is bold");
            assertEquals(2, sheet.getPhysicalNumberOfRows());
        }
    }

    @Test
    void csvTextIsExportedToAnOpenDocumentSpreadsheet() throws Exception {
        buffer("table.csv", "name,n\na,1\n");
        File out = target("table.ods");
        fx(() -> exports.csvExportSpreadsheet("name,n\na,1\n", "table.csv", false));
        host.await(tr("status.office.exported", out.toString())::equals);
        try (ZipFile zip = new ZipFile(out)) {
            String content =
                    new String(zip.getInputStream(zip.getEntry("content.xml")).readAllBytes(), "UTF-8");
            assertTrue(content.contains("name") && content.contains(">a<"), content);
        }

        File rows = target("rows.ods");
        fx(() -> exports.csvExportSpreadsheet(List.of(List.of("x", "y")), false, null, false));
        host.await(tr("status.office.exported", rows.toString())::equals);
        assertEquals("document.ods", saveDialogs.getLast(), "with no name to go by, a default");
    }

    @Test
    void anEmptyTableExportsNothing() throws Exception {
        target("never.xlsx");
        fx(() -> {
            exports.csvExportSpreadsheet(null, true, "t", true);
            exports.csvExportSpreadsheet(List.of(), true, "t", false);
            exports.csvExportSpreadsheet("", "t", true);
            exports.csvExportSpreadsheet("  \n", "t", false);
        });
        String empty = tr("status.csv.empty");
        assertEquals(List.of(empty, empty, empty, empty), host.statuses);
        assertEquals(List.of(), saveDialogs, "no Save dialog for nothing");

        destination = null;
        fx(() -> {
            exports.csvExportSpreadsheet(List.of(List.of("a")), false, "t", true);
            exports.csvExportSpreadsheet("a,b\n", "t", true);
        });
        assertEquals(4, host.statuses.size(), "a cancelled dialog exports nothing");
        assertEquals(List.of(), filesInDir());
    }

    // --- HTML, timeline JSON, CSV text ------------------------------------------------------------------

    @Test
    void aMarkdownDocumentIsExportedToHtmlAndShownInATab() throws Exception {
        buffer("guide.final.md", "# Guide\n\nText.\n");
        File out = target("guide.html");
        fx(exports::exportPreviewHtml);
        assertEquals(tr("status.html.exported", out.toString()), host.last());
        assertEquals(List.of("guide.final.html"), saveDialogs, "only the last extension is replaced");
        String html = Files.readString(out.toPath());
        assertTrue(html.contains("<title>guide.final</title>"), html);
        assertTrue(html.contains("Guide") && html.contains("Text."), html);
        assertEquals(List.of("tab " + out.toPath()), host.opened);
    }

    @Test
    void anHtmlExportNeedsMarkdownAndReportsAWriteFailure() throws Exception {
        buffer("Main.java", "class Main {}");
        fx(exports::exportPreviewHtml);
        assertEquals(tr("status.html.notMarkdown"), host.last());

        buffer("guide.md", "# Guide\n");
        Path blocker = Files.writeString(dir.resolve("not-a-folder"), "a file");
        destination = blocker.resolve("guide.html").toFile();
        fx(exports::exportPreviewHtml);
        assertTrue(host.last().startsWith(tr("status.html.exportFailed", "").strip()), host.last());
        assertEquals(List.of(), host.opened, "nothing to show");
    }

    @Test
    void aTimelineIsExportedAsJson() throws Exception {
        EditorBuffer b = buffer("plan.mw", "2024-01-01: Kickoff\n2024-02-01: Launch\n");
        fx(() -> b.setLanguageOverride("markwhen"));
        File out = target("plan.json");
        fx(exports::exportMarkwhenJson);
        assertEquals(tr("status.markwhen.jsonExported", "plan.json"), host.last());
        assertEquals(List.of("plan.json"), saveDialogs);
        String json = Files.readString(out.toPath());
        assertTrue(json.contains("Kickoff") && json.contains("Launch"), json);

        Path blocker = Files.writeString(dir.resolve("not-a-folder"), "a file");
        destination = blocker.resolve("plan.json").toFile();
        fx(exports::exportMarkwhenJson);
        assertTrue(host.last().startsWith(tr("status.markwhen.exportFailed", "").strip()), host.last());

        destination = null;
        fx(exports::exportMarkwhenJson);
        assertEquals(3, saveDialogs.size());

        buffer("notes.md", "# not a timeline");
        fx(exports::exportMarkwhenJson);
        assertEquals(tr("status.markwhen.notMarkwhen"), host.last());
        host.active = null;
        fx(exports::exportMarkwhenJson);
        assertEquals(tr("status.markwhen.notMarkwhen"), host.last());
    }

    @Test
    void aMarkdownTableIsExportedAsACsvFile() throws Exception {
        File out = target("table");
        fx(() -> exports.exportCsvTextToFile("a,b\n1,2\n", "table.md"));
        File written = new File(dir.toFile(), "table.csv");
        assertEquals("a,b\n1,2\n", Files.readString(written.toPath()));
        assertEquals(tr("status.csv.exported", "table.csv"), host.last());
        assertEquals(out.getParentFile(), written.getParentFile());

        Path blocker = Files.writeString(dir.resolve("not-a-folder"), "a file");
        destination = blocker.resolve("table.csv").toFile();
        fx(() -> exports.exportCsvTextToFile("a\n", "table.md"));
        assertTrue(host.last().startsWith(tr("status.csv.exportFailed", "").strip()), host.last());

        destination = null;
        int before = host.statuses.size();
        fx(() -> exports.exportCsvTextToFile("a\n", "table.md"));
        assertEquals(before, host.statuses.size());
    }

    // --- the destination --------------------------------------------------------------------------------

    @Test
    void aNameTypedWithoutItsExtensionAsksBeforeReplacingTheFileWithTheExtension() throws Exception {
        Path existing = Files.writeString(dir.resolve("table.csv"), "someone's work\n");
        target("table"); // the dialog agreed to "table"; "table.csv" is another file
        String question = tr("dialog.saveAs.overwrite.header", "table.csv");

        assertNotNull(FxDialogs.duringHeader(
                () -> exports.exportCsvTextToFile("new\n", "t"), question, ButtonBar.ButtonData.CANCEL_CLOSE));
        assertEquals("someone's work\n", Files.readString(existing));
        assertEquals(List.of(), host.statuses, "declined: nothing exported, nothing said");

        FxDialogs.duringHeader(() -> exports.exportCsvTextToFile("new\n", "t"), question, null);
        assertEquals("someone's work\n", Files.readString(existing), "closing the question is a no");

        FxDialogs.duringHeader(() -> exports.exportCsvTextToFile("new\n", "t"), question, ButtonBar.ButtonData.OK_DONE);
        assertEquals("new\n", Files.readString(existing));
    }

    @Test
    void theLastExportIsReportedGoneWhenItWasDeletedAndATextExportOpensInATab() throws Exception {
        fx(exports::openLastExport);
        assertEquals(tr("status.export.none"), host.last());

        buffer("notes.md", "# Title\n");
        File out = target("notes.odt");
        fx(exports::exportPreviewOdt);
        host.await(tr("status.office.exported", out.toString())::equals);
        Files.delete(out.toPath());
        fx(exports::openLastExport);
        assertEquals(tr("status.export.gone", out.toString()), host.last());
        assertEquals(List.of(), host.opened);
    }

    // --- PDF of a preview, of a selection, of the Project Map ---------------------------------------------

    private static boolean isPdf(File f) throws IOException {
        byte[] head = new byte[5];
        try (InputStream in = Files.newInputStream(f.toPath())) {
            return in.read(head) == 5 && new String(head, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        }
    }

    @Test
    void aMarkdownPreviewIsExportedToPdfAndOpensInATabAfterwards() throws Exception {
        buffer("notes.md", "# Title\n\nSome text.\n");
        File out = target("notes.pdf");
        fx(exports::exportPreviewPdf);
        host.await(tr("status.pdf.exported", out.toString())::equals);
        assertTrue(isPdf(out));
        assertEquals(List.of("notes.pdf"), saveDialogs);
        assertEquals(tr("status.pdf.exporting"), host.statuses.getFirst());
        assertEquals(List.of("notes.pdf"), filesInDir(), "no staging folder is left beside it");

        fx(exports::openLastExport);
        assertEquals(List.of("tab " + out.toPath()), host.opened, "a PDF opens in the editor's own viewer");
    }

    @Test
    void anSvgPreviewIsExportedToPdf() throws Exception {
        EditorBuffer svg = buffer(
                "badge.svg",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" height=\"20\">"
                        + "<rect width=\"40\" height=\"20\" fill=\"#0a0\"/></svg>");
        File out = target("badge.pdf");
        fx(exports::exportPreviewPdf);
        assertEquals(List.of(tr("status.pdf.noPreview")), host.statuses, "the SVG preview is switched off");
        assertEquals(List.of(), saveDialogs, "told before the Save dialog, not after");

        fx(() -> {
            svg.setSvgPreviewEnabled(true);
            exports.exportPreviewPdf();
        });
        host.await(tr("status.pdf.exported", out.toString())::equals);
        assertTrue(isPdf(out));
    }

    @Test
    void aPreviewExportWithNoBufferOrACancelledDialogWritesNothing() throws Exception {
        fx(exports::exportPreviewPdf);
        assertEquals(tr("status.pdf.noPreview"), host.last());
        buffer("notes.md", "# Title\n");
        destination = null;
        fx(() -> {
            exports.exportPreviewPdf();
            exports.exportCodePdf();
            exports.exportSelectionPdf();
        });
        assertEquals(
                List.of(tr("status.pdf.noPreview"), tr("status.print.noSelection")),
                host.statuses,
                "two cancelled dialogs say nothing; no selection says so");
        assertEquals(List.of(), filesInDir());
    }

    @Test
    void theSelectedLinesArePrintedOrExportedWithTheirPlaceInTheFile() throws Exception {
        EditorBuffer b = buffer("Main.java", "line one\nline two\nline three\nline four\n");
        fx(() -> b.getArea().selectRange(12, 22)); // inside "line two" .. inside "line three"
        ExportCoordinator.SelectedLines selected = FxTestSupport.callOnFx(exports::selectedLines);
        assertEquals(2, selected.lines().firstLine(), "numbered as in the file");
        assertEquals(
                "line two\nline three",
                selected.text()
                        .substring(selected.lines().start(), selected.lines().end())
                        .strip(),
                "widened to whole lines");

        assertEquals(List.of(), print(exports::printSelection));
        assertEquals(1, pageCount(openPreview()));
        fx(() -> previews().forEach(Stage::close));

        File out = target("selection.pdf");
        fx(exports::exportSelectionPdf);
        host.await(tr("status.pdf.exported", out.toString())::equals);
        assertTrue(isPdf(out));
    }

    @Test
    void printingASelectionNeedsABufferASelectionAndAPrinter() throws Exception {
        print(exports::printSelection);
        assertEquals(tr("status.noFileOpen"), host.last());
        fx(() -> exports.activeTabContent = () -> "a PDF viewer");
        print(exports::printSelection);
        assertEquals(tr("status.print.noText"), host.last(), "a tab is open, but it has no text");

        EditorBuffer b = buffer("Main.java", "line one\nline two\n");
        print(exports::printSelection);
        assertEquals(tr("status.print.noSelection"), host.last());

        fx(() -> {
            b.getArea().selectRange(0, 4);
            exports.printJobs = () -> null;
        });
        print(exports::printSelection);
        assertEquals(tr("status.print.noPrinter"), host.last());
        assertEquals(0, jobs.size());
    }

    @Test
    void aTabThatIsNotTextSaysWhyItCannotBePrintedOrExported() throws Exception {
        fx(() -> {
            exports.printActive();
            exports.exportActivePdf();
        });
        assertEquals(List.of(tr("status.noFileOpen"), tr("status.noFileOpen")), host.statuses);

        fx(() -> {
            exports.activeTabContent = () -> "a hex viewer";
            exports.printActive();
            exports.exportActivePdf();
        });
        assertEquals(List.of(tr("status.print.noText"), tr("status.pdf.noText")), host.statuses.subList(2, 4));
    }

    @Test
    void anImageTabWhosePictureDidNotLoadCannotBePrintedOrExported() throws Exception {
        ImageViewerPane pane = FxTestSupport.callOnFx(() -> new ImageViewerPane(dir.resolve("missing.png")));
        try {
            fx(() -> {
                exports.activeTabContent = () -> pane;
                exports.printActive();
                exports.exportActivePdf();
            });
            assertEquals(List.of(tr("status.print.imageNotLoaded"), tr("status.print.imageNotLoaded")), host.statuses);
            assertEquals(0, jobs.size(), "no job is made for a picture that is not there");
            assertEquals(List.of(), saveDialogs);
        } finally {
            fx(pane::dispose);
        }
    }

    /** A Project Map of one 10×10 page; {@code empty} renders nothing, as a map with no cards does. */
    private static ProjectMapOutput map(boolean landscape, boolean empty) {
        return new ProjectMapOutput() {
            @Override
            public boolean landscape() {
                return landscape;
            }

            @Override
            public Rendered render(double pageWidth, double pageHeight) {
                if (empty) {
                    return null;
                }
                ProjectMapOutputPlan.Plan plan = ProjectMapOutputPlan.plan(
                        new ProjectMapOutputPlan.Box(0, 0, 10, 10), List.of(), pageWidth, pageHeight);
                return new Rendered(List.of(new javafx.scene.image.WritableImage(10, 10)), 1, plan);
            }
        };
    }

    @Test
    void theProjectMapIsExportedToPdf() throws Exception {
        File out = target("map.pdf");
        fx(() -> exports.exportProjectMapPdf(map(true, false), "My Project"));
        host.await(tr("status.pdf.exported", out.toString())::equals);
        assertTrue(isPdf(out));
        assertEquals(List.of("My Project.pdf"), saveDialogs);

        fx(() -> exports.exportProjectMapPdf(map(false, true), "My Project"));
        assertEquals(tr("status.projectMap.outputEmpty"), host.last());

        destination = null;
        int before = host.statuses.size();
        fx(() -> exports.exportProjectMapPdf(map(false, false), null));
        assertEquals(before, host.statuses.size(), "a cancelled dialog renders nothing");
        assertEquals("document.pdf", saveDialogs.getLast());
    }

    @Test
    void theProjectMapIsPrintedOnALandscapePageWhenItIsWide() throws Exception {
        boolean[] askedForLandscape = {false};
        fx(() -> exports.printJobs = () -> {
            PrintPreviewFxTest.FakeJob job = new PrintPreviewFxTest.FakeJob(silentLetter());
            jobs.add(job);
            return new PrintPreview.Job() { // the fake job, plus a record of the orientation request
                @Override
                public javafx.print.PageLayout layout() {
                    return job.layout();
                }

                @Override
                public String printerName() {
                    return job.printerName();
                }

                @Override
                public boolean showPageSetup(Window owner) {
                    return job.showPageSetup(owner);
                }

                @Override
                public boolean showPrintDialog(Window owner) {
                    return job.showPrintDialog(owner);
                }

                @Override
                public javafx.print.PageRange[] pageRanges() {
                    return job.pageRanges();
                }

                @Override
                public boolean printPage(javafx.print.PageLayout pageLayout, javafx.scene.Node page) {
                    return job.printPage(pageLayout, page);
                }

                @Override
                public boolean endJob() {
                    return job.endJob();
                }

                @Override
                public void cancel() {
                    job.cancel();
                }

                @Override
                public void useLandscape() {
                    askedForLandscape[0] = true;
                }
            };
        });
        assertEquals(List.of(), print(() -> exports.printProjectMap(map(true, false))));
        assertTrue(askedForLandscape[0]);
        assertEquals(1, pageCount(openPreview()));
        assertEquals(tr("status.print.preparing"), host.last());
    }

    @Test
    void anEmptyProjectMapCancelsItsPrinterJob() throws Exception {
        print(() -> exports.printProjectMap(map(false, true)));
        assertEquals(tr("status.projectMap.outputEmpty"), host.last());
        assertEquals(1, jobs.size());
        assertEquals(1, jobs.getFirst().cancelled, "a job that will not be used is given back");
        assertNull(openPreview());
    }
}
