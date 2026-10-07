package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests for {@link CsvCoordinator}'s in-editor grid preview (mirroring the Markdown
 * Editor/Split/Preview UI). Pins the Settings/Simple-Mode gate, that a per-buffer grid is injected so a CSV
 * buffer reports {@link EditorBuffer#hasPreview()}, that switching to Split/Preview populates the grid, and
 * that disabling the feature / closing the buffer tears the grid down.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CsvCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        boolean simpleMode = false;
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String lastStatus;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simpleMode;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }

        @Override
        public String bufferBaseName(EditorBuffer buffer) {
            return "data.csv";
        }
    }

    private static final class FakeOps implements CsvCoordinator.Ops {
        int jumps;

        @Override
        public void jumpTo(int line, int col) {
            jumps++;
        }

        @Override
        public void exportPdf(String csvText, String baseName) {}

        @Override
        public void printCsv(String csvText) {}

        @Override
        public void exportExcel(List<List<String>> rows, boolean hasHeader, String baseName) {}

        @Override
        public void exportOds(List<List<String>> rows, boolean hasHeader, String baseName) {}
    }

    private static EditorBuffer csvBuffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("csv");
            b.setContent(content);
            return b;
        });
    }

    @Test
    void isEnabledTracksSettingAndSimpleMode() {
        FakeHost host = new FakeHost();
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        host.settings.setCsvPreview(true);
        assertTrue(c.isEnabled());
        host.simpleMode = true;
        assertFalse(c.isEnabled(), "suppressed in Simple UI mode");
        host.simpleMode = false;
        host.settings.setCsvPreview(false);
        assertFalse(c.isEnabled());
    }

    @Test
    void ensureCsvPreviewInjectsAGridSoTheBufferIsPreviewable() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer b = csvBuffer("a,b\n1,2\n");

        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertTrue(b.hasCsvPreview(), "a grid node is injected");
        assertTrue(b.hasPreview(), "so the Editor/Split/Preview toggle attaches");
        assertNotNull(c.gridNodeFor(b));

        // Idempotent: a second call keeps the same grid (a Node can't be rebuilt under it).
        var grid = c.gridNodeFor(b);
        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertSame(grid, c.gridNodeFor(b));
    }

    @Test
    void nonCsvBufferNeverGetsAGrid() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer md = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("markdown");
            b.setContent("# hi");
            return b;
        });

        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(md));
        assertNull(c.gridNodeFor(md));
        assertFalse(md.hasCsvPreview());
    }

    @Test
    void disabledFeatureDoesNotAttachAndClearsAnExisting() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(false);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer b = csvBuffer("a,b\n1,2\n");

        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertFalse(b.hasCsvPreview(), "off → no grid");

        // Turn it on, attach, then off again → the grid is torn down and the buffer falls back to EDITOR.
        host.settings.setCsvPreview(true);
        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertTrue(b.hasCsvPreview());
        host.settings.setCsvPreview(false);
        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertFalse(b.hasCsvPreview());
        assertNull(c.gridNodeFor(b));
        assertSame(EditorBuffer.MarkdownViewMode.EDITOR, b.getMarkdownViewMode());
    }

    @Test
    void switchingToSplitPopulatesTheGrid() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        host.active = csvBuffer("name,qty\nApple,3\nPear,5\n");
        host.buffers.add(host.active);

        FxTestSupport.runOnFx(() -> {
            c.ensureCsvPreview(host.active);
            host.active.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
        });
        CsvGridPanel grid = (CsvGridPanel) c.gridNodeFor(host.active);
        assertNotNull(grid);
        // The debounced buffer subscription fires csvPreviewRefresh on mode-enter → the grid holds the rows.
        assertTrue(grid.rowCount() >= 2, "grid populated from the buffer text: rows=" + grid.rowCount());
    }

    @Test
    void closingABufferDropsItsGrid() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer b = csvBuffer("a,b\n1,2\n");
        FxTestSupport.runOnFx(() -> c.ensureCsvPreview(b));
        assertNotNull(c.gridNodeFor(b));

        c.onBufferClosed(b);
        assertNull(c.gridNodeFor(b), "the per-buffer grid is dropped on close");
    }

    // --- cell edits ------------------------------------------------------------------------------------

    private static void rebuild(CsvCoordinator c, EditorBuffer b) {
        FxTestSupport.call(c, "rebuild", new Class<?>[] {EditorBuffer.class}, b);
    }

    private static void commit(CsvCoordinator c, EditorBuffer b, int dataRow, int field, String value) {
        FxTestSupport.call(
                c,
                "commitCell",
                new Class<?>[] {EditorBuffer.class, int.class, int.class, String.class},
                b,
                dataRow,
                field,
                value);
    }

    @Test
    void aCellEditOnANarrowedBufferWritesTheRowTheGridShows() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        String doc = "id,name\n1,alice\n2,bob\n3,carol\n4,dave\n";
        EditorBuffer b = csvBuffer(doc);
        FxTestSupport.runOnFx(() -> {
            c.ensureCsvPreview(b);
            assertTrue(b.narrowTo(doc.indexOf("2,bob"), doc.indexOf("4,dave")));
            rebuild(c, b);
            commit(c, b, 2, 1, "CAROL"); // the grid lists the whole file: data row 2 is "3,carol"
        });
        assertEquals("id,name\n1,alice\n2,bob\n3,CAROL\n4,dave\n", FxTestSupport.callOnFx(b::getContent));
        assertEquals(
                "2,bob\n3,CAROL\n", FxTestSupport.callOnFx(() -> b.getArea().getText()));
    }

    @Test
    void aCellEditOutsideTheNarrowedRegionIsRefusedNotMisplaced() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        String doc = "id,name\n1,alice\n2,bob\n3,carol\n4,dave\n";
        EditorBuffer b = csvBuffer(doc);
        FxTestSupport.runOnFx(() -> {
            c.ensureCsvPreview(b);
            assertTrue(b.narrowTo(doc.indexOf("3,carol"), doc.length()));
            rebuild(c, b);
            commit(c, b, 0, 1, "ALICE"); // "1,alice" is held aside by the narrowing
        });
        assertEquals(doc, FxTestSupport.callOnFx(b::getContent), "no other row may take the edit");
        assertEquals(com.editora.i18n.Messages.tr("status.csv.cannotEditHiddenRow"), host.lastStatus);
    }

    @Test
    void aCellEditIsRefusedWhenTheRowIsNoLongerTheOneTheGridShowed() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer b = csvBuffer("id,name\n1,alice\n2,bob\n");
        FxTestSupport.runOnFx(() -> {
            c.ensureCsvPreview(b);
            rebuild(c, b);
            b.getArea().insertText(0, "0,zed\n"); // typed before the debounced re-parse: every row shifted
            commit(c, b, 0, 1, "ALICE");
        });
        assertEquals("0,zed\nid,name\n1,alice\n2,bob\n", FxTestSupport.callOnFx(b::getContent));
        assertEquals(com.editora.i18n.Messages.tr("status.csv.cannotEditChangedRow"), host.lastStatus);
    }

    @Test
    void aCellEditLeavesTheOtherCellsOfTheRowAsTheyWereWritten() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setCsvPreview(true);
        CsvCoordinator c = new CsvCoordinator(host, new FakeOps());
        EditorBuffer b = csvBuffer("id,code,note\n1,\"007\",\"\"\n2, \"x\" ,\"a\"b\n");
        FxTestSupport.runOnFx(() -> {
            c.ensureCsvPreview(b);
            rebuild(c, b);
            commit(c, b, 0, 0, "9");
            commit(c, b, 1, 0, "8"); // a second edit before the debounced re-parse
        });
        assertEquals("id,code,note\n9,\"007\",\"\"\n8, \"x\" ,\"a\"b\n", FxTestSupport.callOnFx(b::getContent));
        FxTestSupport.runOnFx(() -> {
            b.getArea().undo();
            b.getArea().undo();
        });
        assertEquals("id,code,note\n1,\"007\",\"\"\n2, \"x\" ,\"a\"b\n", FxTestSupport.callOnFx(b::getContent));
    }
}
