package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableRow;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.git.GitRecentChanges;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's Code Metrics card (Map code browser gap 15): a folder's files with their lines, comments,
 * complexity, changes and hotspots, opened from ⋯ or a folder's row menu.
 */
@Tag("fx")
class MapMetricsCardFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;

    MapMetricsCardFxTest() throws Exception {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (map != null) {
            FxTestSupport.runOnFx(() -> {
                map.view().dispose();
                map.stage().close();
            });
        }
        async.close();
    }

    private void writeProject() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        write(
                "core/Big.java",
                "package core;\n\nclass Big {\n    int f(int x) {\n        if (x > 0 && x < 9) {\n"
                        + "            return 1;\n        }\n        for (;;) {}\n    }\n}\n");
        write("core/Small.java", "package core;\n// small\nclass Small {}\n");
        write("ui/view.py", "# a view\ndef show(x):\n    if x:\n        pass\n");
        write("README.md", "# Project\n");
    }

    private void write(String rel, String text) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theProjectsMetricsOpenFromTheMenuWithItsTotalsAndRows() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        FxTestSupport.runOnFx(() -> {
            MenuItem item = FxTestSupport.field(map.view(), "metricsItem");
            item.fire();
        });
        await("measured", () -> card() != null && !card().rowsShown().isEmpty());
        ProjectMapMetricsCard card = card();
        FxTestSupport.runOnFx(() -> {
            assertEquals("", card.subject());
            assertEquals(tr("project.map.metrics.title", "project"), card.title());
            assertSame(card, map.view().metricsCard());
            assertTrue(cards().openCards().stream().anyMatch(open -> open.node() == card));
            assertEquals(
                    List.of("core/Big.java", "ui/view.py", "core/Small.java"),
                    card.rowsShown().stream().map(ProjectMapMetrics.Row::rel).toList(),
                    "no history: the most complex first");
            ProjectMapMetrics.Row big = card.rowsShown().get(0);
            assertEquals(9, big.code());
            assertEquals(3, big.complexity());
            assertEquals(-1, big.changes());
            String summary = card.summaryText();
            assertTrue(summary.startsWith(tr("project.map.metrics.totals", "project", 3, 14, 2, 4)), summary);
            assertTrue(summary.contains(tr("project.map.metrics.noHistory")), summary);
            assertTrue(summary.contains("Java"), summary);

            // History comes: the hottest first.
            card.setChanges(new GitRecentChanges.Result(
                    "abc",
                    false,
                    3,
                    false,
                    Map.of(
                            "core/Small.java", new GitRecentChanges.Stat(9, 0, "Ada"),
                            "ui/view.py", new GitRecentChanges.Stat(9, 0, "Ada")),
                    Map.of("core", new GitRecentChanges.Stat(9, 0, "Ada")),
                    Map.of(),
                    0,
                    ""));
            assertEquals("ui/view.py", card.rowsShown().get(0).rel());
            assertTrue(card.summaryText().contains(tr("project.map.metrics.history")));

            // Parts: core/ and ui/; Enter on core/ looks inside it, Backspace comes back.
            partsButton(card).fire();
            assertTrue(card.showsParts());
            assertEquals(
                    List.of("core", "ui"),
                    card.rowsShown().stream().map(ProjectMapMetrics.Row::rel).toList());
            card.pickRow(0);
            assertTrue(card.enterPicked());
            assertEquals("core", card.subject());
            assertEquals(tr("project.map.metrics.title", "core/"), card.title());
            key(card, KeyCode.BACK_SPACE);
            assertEquals("", card.subject());
            assertFalse(card.goBack());
            filesButton(card).fire();
            assertFalse(card.showsParts());

            // A row's menu: Show on Map, Dependencies.
            int bigRow = indexOf(card, "core/Big.java");
            ContextMenu menu = card.menuOf(bigRow);
            menuItem(menu, tr("project.map.metrics.dependencies")).fire();
            assertEquals("core/Big.java", map.view().dependencyCard().subject());
            menuItem(menu, tr("project.map.metrics.showOnMap")).fire();
        });

        // Enter on a file opens its card.
        FxTestSupport.runOnFx(() -> {
            card.pickRow(indexOf(card, "core/Small.java"));
            key(card.table(), KeyCode.ENTER);
        });
        await(
                "Small's card",
                () -> previews().keySet().stream()
                        .anyMatch(key -> key.toString().endsWith("Small.java")));

        // Escape closes it.
        FxTestSupport.runOnFx(() -> key(card, KeyCode.ESCAPE));
        FxTestSupport.runOnFx(() -> assertNull(map.view().metricsCard()));
    }

    @Test
    void aFoldersRowMenuOpensItAndRefreshMeasuresAgain() throws Exception {
        writeProject();
        map = openMap();
        await("rows", () -> rows().contains(root.resolve("core")));
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = rowMenu(root.resolve("core"));
            menuItem(menu, tr("project.map.metrics.menu")).fire();
            ContextMenu readme = rowMenu(root.resolve("README.md"));
            assertTrue(
                    readme.getItems().stream()
                            .noneMatch(i -> tr("project.map.metrics.menu").equals(i.getText())),
                    "a file has no Code Metrics");
        });
        await("core measured", () -> card() != null && card().rowsShown().size() == 2);
        ProjectMapMetricsCard card = card();
        write("core/Small.java", "package core;\nclass Small {\n    int g(int a) { return a > 0 ? a : -a; }\n}\n");
        Files.setLastModifiedTime(
                root.resolve("core/Small.java"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        FxTestSupport.runOnFx(() -> refreshButton(card).fire());
        await(
                "Small measured again",
                () -> card.rowsShown().stream()
                        .anyMatch(row -> row.rel().endsWith("Small.java") && row.complexity() == 1));
        FxTestSupport.runOnFx(() -> {
            // Asked again on another folder: the same card moves there, Back returns.
            map.view().openMetrics(root.resolve("ui"));
            assertSame(card, card());
            assertEquals("ui", card.subject());
            assertTrue(card.goBack());
            assertEquals("core", card.subject());

            // A double-click on a row, and a part's menu.
            card.table().applyCss();
            card.table().layout();
            List<String> opened = new ArrayList<>();
            card.setOnOpen(path -> opened.add(root.relativize(path).toString().replace('\\', '/')));
            for (Node node : card.table().lookupAll(".table-row-cell")) {
                if (node instanceof TableRow<?> row && row.getItem() != null) {
                    Event.fireEvent(row, click(2));
                }
            }
            assertEquals(2, opened.size(), opened.toString());
            menuItem(card.menuOf(0), tr("project.map.metrics.openCard")).fire();
            assertEquals(3, opened.size());
            card.show(root, "");
            partsButton(card).fire();
            menuItem(card.menuOf(0), tr("project.map.metrics.lookInside")).fire();
            assertFalse(card.subject().isEmpty());
        });
    }

    @Test
    void summariesHeatAndNames() throws Exception {
        Map<String, ProjectMapMetrics.Measure> files = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ProjectMapMetricsCard.MAX_LANGUAGES + 1; i++) {
            files.put("f" + i + ".lang" + i, new ProjectMapMetrics.Measure("L" + i, 10, 10 - i, 0, 1));
        }
        ProjectMapMetrics metrics = new ProjectMapMetrics(files, true);
        String text = ProjectMapMetricsCard.summaryText(
                metrics, "", "x", GitRecentChanges.Result.NONE, ProjectMapMetricsCard.MAX_ROWS + 1);
        assertTrue(text.contains(tr("project.map.metrics.languageShare", "L0", 25)), text);
        assertFalse(text.contains("L4 "), "at most four languages: " + text);
        assertTrue(text.contains(tr("project.map.metrics.rowsCut", ProjectMapMetricsCard.MAX_ROWS)), text);
        assertTrue(text.endsWith(tr("project.map.metrics.truncated", 5)), text);
        assertEquals(0, ProjectMapMetricsCard.heat(0));
        assertEquals(1, ProjectMapMetricsCard.heat(5));
        assertEquals(2, ProjectMapMetricsCard.heat(10));
        assertEquals(3, ProjectMapMetricsCard.heat(30));
        assertEquals(4, ProjectMapMetricsCard.heat(60));
        assertEquals(
                "b/", ProjectMapMetricsCard.rowName("a", new ProjectMapMetrics.Row("a/b", true, 1, 1, 0, 0, -1, 0)));
        assertEquals(
                "a/b.c",
                ProjectMapMetricsCard.rowName("", new ProjectMapMetrics.Row("a/b.c", false, 1, 1, 0, 0, -1, 0)));

        FxTestSupport.runOnFx(() -> {
            ProjectMapMetricsCard card = new ProjectMapMetricsCard();
            assertNull(card.root());
            assertEquals("", card.summaryText());
            card.show(temp, "");
            card.setReading();
            assertEquals(tr("project.map.metrics.reading"), card.summaryText());
            card.setMetrics(ProjectMapMetrics.EMPTY);
            assertTrue(card.rowsShown().isEmpty());
            card.setReading(); // an answer is shown: it stays
            assertFalse(card.summaryText().equals(tr("project.map.metrics.reading")));
            card.setMetrics(metrics);
            // The heat cells: a row with a hotspot is drawn warm.
            card.setChanges(new GitRecentChanges.Result(
                    "h",
                    false,
                    1,
                    false,
                    Map.of("f0.lang0", new GitRecentChanges.Stat(3, 0, "A")),
                    Map.of(),
                    Map.of(),
                    0,
                    ""));
            Stage stage = new Stage();
            stage.setScene(new Scene(card, 700, 500));
            card.place(new ProjectMapPreview.Placement(0, 0, 700, 500));
            stage.show();
            card.applyCss();
            card.layout();
            assertFalse(card.lookupAll(".project-map-metrics-heat-4").isEmpty());
            card.focusContent();
            stage.close();
        });
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static int indexOf(ProjectMapMetricsCard card, String rel) {
        List<ProjectMapMetrics.Row> rows = card.rowsShown();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).rel().equals(rel)) {
                return i;
            }
        }
        throw new AssertionError("no row " + rel);
    }

    private @Nullable ProjectMapMetricsCard card() {
        return cards().metricsCard();
    }

    private static ToggleButton partsButton(ProjectMapMetricsCard card) {
        return FxTestSupport.field(card, "partsButton");
    }

    private static ToggleButton filesButton(ProjectMapMetricsCard card) {
        return FxTestSupport.field(card, "filesButton");
    }

    private static Button refreshButton(ProjectMapMetricsCard card) {
        return FxTestSupport.field(card, "refresh");
    }

    private ContextMenu rowMenu(Path path) {
        return (ContextMenu) FxTestSupport.call(
                map.view(),
                "menuWithMapItems",
                new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                entry(path),
                new ContextMenu());
    }

    private static MenuItem menuItem(ContextMenu menu, String text) {
        for (MenuItem item : menu.getItems()) {
            if (text.equals(item.getText())) {
                return item;
            }
        }
        throw new AssertionError("no " + text + " in "
                + menu.getItems().stream().map(MenuItem::getText).toList());
    }

    private ProjectMapModel.Entry entry(Path path) {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        for (Object box : boxes) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        throw new AssertionError("no row " + path);
    }

    private MapCards cards() {
        return FxTestSupport.field(map.view(), "cards");
    }

    private List<Path> rows() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0])).path())
                .toList();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private Fixture openMap() throws Exception {
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(MapMetricsCardFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(project);
            return new Fixture(view, scene, stage, FxTestSupport.field(view, "surface"));
        });
    }

    private static MouseEvent click(int count) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
                count,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static void key(Node target, KeyCode code) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}
}
