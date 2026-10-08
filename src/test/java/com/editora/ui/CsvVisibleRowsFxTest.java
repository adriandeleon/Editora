package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CSV Print, Export to PDF and the two spreadsheet exports put out what the grid shows — its filter, its
 * sort order and its header setting — while the grid is on screen, and the whole file in source mode. They
 * used to write every row in file order with row 1 as the header whatever the grid showed.
 */
@Tag("fx")
class CsvVisibleRowsFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();
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
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public String bufferBaseName(EditorBuffer buffer) {
            return "data.csv";
        }

        String last() {
            return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
        }
    }

    private static final String CSV = "name,qty\ncherry,3\napple alpha,20\nbanana alpha,7\n";

    private final Host host = new Host();
    private CsvCoordinator csv;
    private ExportCoordinator exports;
    private Path destination;
    private int jobsCreated;

    /** A CSV buffer with its grid attached, wired to an export coordinator as the window wires them. */
    private void create(EditorBuffer.MarkdownViewMode mode) throws Exception {
        host.settings.setCsvPreview(true);
        FxTestSupport.runOnFx(() -> {
            exports = new ExportCoordinator(host, null, null, null, path -> {}, chooser -> destination.toFile());
            exports.printJobs = () -> {
                jobsCreated++;
                try {
                    return new PrintPreviewFxTest.FakeJob(PrintPreviewFxTest.letter());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            exports.printReporter = r -> {};
            csv = new CsvCoordinator(host, new CsvCoordinator.Ops() {
                @Override
                public void jumpTo(int line, int col) {}

                @Override
                public void exportPdf(String csvText, String baseName) {
                    exports.csvExportPdf(csvText, baseName);
                }

                @Override
                public void printCsv(String csvText) {
                    exports.csvPrint(csvText);
                }

                @Override
                public void exportSpreadsheet(String csvText, String baseName, boolean xlsx) {
                    exports.csvExportSpreadsheet(csvText, baseName, xlsx);
                }
            });
            exports.csvShown = csv::shownFor;
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("csv");
            b.setContent(CSV);
            host.active = b;
            csv.ensureCsvPreview(b);
            b.setMarkdownViewMode(mode);
            FxTestSupport.call(csv, "rebuild", new Class<?>[] {EditorBuffer.class}, b);
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

    /** Filters the grid to the "alpha" rows, sorts by the second column descending, and sets the header box. */
    private void filterSortAndSetHeader(boolean header) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CsvGridPanel grid = (CsvGridPanel) csv.gridNodeFor(host.active);
            FxTestSupport.<CheckBox>field(grid, "headerToggle").setSelected(header);
            FxTestSupport.<TextField>field(grid, "filterField").setText("alpha");
            try {
                var column = CsvGridPanel.class.getDeclaredField("sortColumn");
                column.setAccessible(true);
                column.setInt(grid, 1);
                var ascending = CsvGridPanel.class.getDeclaredField("sortAscending");
                ascending.setAccessible(true);
                ascending.setBoolean(grid, false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            FxTestSupport.invoke(grid, "applyView");
        });
    }

    /** Runs a registered {@code csv.*} command — the palette's route, and the grid menu's. */
    private void run(String id) throws Exception {
        com.editora.command.CommandRegistry registry = new com.editora.command.CommandRegistry();
        FxTestSupport.runOnFx(() -> {
            csv.registerCommands(registry);
            registry.get(id).orElseThrow().run();
        });
    }

    private void awaitExport(String exportingStatus) throws Exception {
        for (int i = 0; i < 400; i++) {
            String last = FxTestSupport.callOnFx(host::last);
            if (Files.exists(destination) && last != null && !exportingStatus.equals(last)) {
                break;
            }
            Thread.sleep(50);
        }
        FxTestSupport.drainFx();
        assertTrue(Files.exists(destination), "the export should have written " + destination);
    }

    private List<String> pdfLines() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(destination.toFile())) {
            return new org.apache.pdfbox.text.PDFTextStripper()
                    .getText(doc)
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty())
                    .toList();
        }
    }

    @Test
    void thePdfHoldsTheVisibleRowsInDisplayedOrderUnderTheHeader() throws Exception {
        destination = temp.resolve("filtered.pdf");
        create(EditorBuffer.MarkdownViewMode.PREVIEW);
        filterSortAndSetHeader(true);
        run("csv.exportPdf");
        awaitExport(tr("status.pdf.exporting"));
        List<String> lines = pdfLines();
        assertEquals(List.of("name qty", "apple alpha 20", "banana alpha 7"), lines.subList(0, 3), lines.toString());
        assertFalse(String.join("\n", lines).contains("cherry"), "the filtered-out row is not exported");
        String status = FxTestSupport.callOnFx(host::last);
        assertEquals(
                tr("status.pdf.exported", destination.toString()) + " " + tr("status.csv.filteredRows", 2, 3),
                status,
                "a filtered export says how many rows it holds");
    }

    @Test
    void withTheHeaderBoxOffEveryRowIsDataIncludingTheFirst() throws Exception {
        destination = temp.resolve("noheader.pdf");
        create(EditorBuffer.MarkdownViewMode.SPLIT);
        filterSortAndSetHeader(false);
        CsvGridPanel.Shown shown = FxTestSupport.callOnFx(() -> exports.csvRows(CSV));
        assertNull(shown.header(), "no header row");
        assertEquals(List.of(List.of("apple alpha", "20"), List.of("banana alpha", "7")), shown.rows());
        assertEquals(4, shown.totalRows(), "row 1 counts as data now");
        // The rendered-preview route (preview.exportPdf on a CSV) goes through the same method.
        FxTestSupport.runOnFx(() -> exports.exportPreviewPdf());
        awaitExport(tr("status.pdf.exporting"));
        List<String> lines = pdfLines();
        assertEquals(List.of("apple alpha 20", "banana alpha 7"), lines.subList(0, 2), lines.toString());
        assertFalse(String.join("\n", lines).contains("name"), "row 1 is filtered out like any other row");
    }

    /** No grid on screen, no grid state to follow: the whole file, as before. */
    @Test
    void inSourceModeTheWholeFileGoesOutInFileOrder() throws Exception {
        destination = temp.resolve("whole.pdf");
        create(EditorBuffer.MarkdownViewMode.EDITOR);
        filterSortAndSetHeader(false); // state left over in a grid that is not showing
        run("csv.exportPdf");
        awaitExport(tr("status.pdf.exporting"));
        List<String> lines = pdfLines();
        assertEquals(
                List.of("name qty", "cherry 3", "apple alpha 20", "banana alpha 7"),
                lines.subList(0, 4),
                lines.toString());
        assertEquals(tr("status.pdf.exported", destination.toString()), FxTestSupport.callOnFx(host::last));
    }

    @Test
    void printPreparesTheVisibleRowsAndSaysHowMany() throws Exception {
        create(EditorBuffer.MarkdownViewMode.PREVIEW);
        filterSortAndSetHeader(true);
        run("csv.print");
        for (int i = 0; i < 200 && FxTestSupport.callOnFx(() -> previews().isEmpty()); i++) {
            Thread.sleep(50);
        }
        assertEquals(1, FxTestSupport.callOnFx(() -> previews().size()), "the Print Preview opens");
        assertEquals(1, jobsCreated);
        assertTrue(
                host.statuses.contains(tr("status.print.preparing") + " " + tr("status.csv.filteredRows", 2, 3)),
                host.statuses.toString());
        // What was handed to the print pipeline is what csvRows returns: the two visible rows, sorted.
        CsvGridPanel.Shown shown = FxTestSupport.callOnFx(() -> exports.csvRows(CSV));
        assertEquals(List.of("name", "qty"), shown.header());
        assertEquals(List.of(List.of("apple alpha", "20"), List.of("banana alpha", "7")), shown.rows());
    }

    @Test
    void theSpreadsheetExportsFollowTheSameRows() throws Exception {
        destination = temp.resolve("filtered.xlsx");
        create(EditorBuffer.MarkdownViewMode.PREVIEW);
        filterSortAndSetHeader(true);
        run("csv.exportExcel");
        awaitExport(tr("status.office.exporting"));
        List<String> cells = new ArrayList<>();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(destination.toFile())) {
            for (org.apache.poi.ss.usermodel.Row row : book.getSheetAt(0)) {
                cells.add(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(row.getCell(0)));
            }
        }
        assertEquals(List.of("name", "apple alpha", "banana alpha"), cells);
        assertEquals(
                tr("status.office.exported", destination.toString()) + " " + tr("status.csv.filteredRows", 2, 3),
                FxTestSupport.callOnFx(host::last));
    }

    /** An edit made a moment ago is in the output even though the grid's debounced re-parse has not run. */
    @Test
    void theGridIsBroughtUpToDateBeforeItIsPutOut() throws Exception {
        create(EditorBuffer.MarkdownViewMode.PREVIEW);
        CsvGridPanel.Shown shown = FxTestSupport.callOnFx(() -> {
            host.active.setContent(CSV + "date,1\n");
            return exports.csvRows(host.active.getContent());
        });
        assertEquals(4, shown.rows().size());
        assertEquals(List.of("date", "1"), shown.rows().get(3));
        assertFalse(shown.filtered());
    }
}
