package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import com.editora.git.GitFileStatus;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layout, painting and toolbar of the Project Map: what is on screen at the first view and after Fit, how
 * columns are placed, what the rows and headers show, and whether the controls around the canvas fit.
 */
class ProjectMapLayoutFxTest {

    private static final String STYLES = "/com/editora/styles/";

    @TempDir
    Path temp;

    private ProjectMapView mapView;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void dispose() throws Exception {
        if (mapView != null) {
            FxTestSupport.runOnFx(mapView::dispose);
        }
        FxTestSupport.runOnFx(() -> Messages.init("en")); // the catalog is process-wide
    }

    // ---- first view, Fit and opening a column (U2, U3, C4) -------------------------------------------

    @Test
    void firstViewOpensAtFullSizeWithTheRootAndTheStartOfTheFirstColumn() throws Exception {
        Path project = project();
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(directory(project, null, 0)));
        for (int i = 0; i < 40; i++) {
            entries.add(file(project.resolve(String.format("File%02d.java", i)), project, 1));
        }
        Region surface = show(900, 640);
        setEntries(surface, entries, Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            assertEquals(1.0, zoom(surface), 0.0001, "a project too tall to fit still opens at 100%");
            Object firstColumn = columnBoxForParent(surface, project);
            assertTrue(origin(firstColumn, "y") >= 0, "the first column starts in view, header included");
            assertTrue(origin(firstColumn, "y") < 60);
            assertInView(surface, boxFor(surface, project), "the root row");
            assertInView(surface, boxFor(surface, project.resolve("File00.java")), "the first row");
            assertTrue(edge(firstColumn, "x", "width") <= surface.getWidth());
        });
    }

    @Test
    void firstViewNeverShrinksBelowTheAutoFitFloorAndKeepsTheFirstColumnWhole() throws Exception {
        Path project = project();
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(directory(project, null, 0)));
        for (int i = 0; i < 40; i++) {
            entries.add(file(project.resolve(String.format("AFairlyLongFileName%02d.java", i)), project, 1));
        }
        Region surface = show(345, 640); // the Project tool window's default width
        setEntries(surface, entries, Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            assertTrue(zoom(surface) >= 0.85 - 0.0001, "auto-fit stops at a readable size: " + zoom(surface));
            Object firstColumn = columnBoxForParent(surface, project);
            assertTrue(origin(firstColumn, "x") >= 0 && origin(firstColumn, "y") >= 0, "its header is in view");
            assertTrue(edge(firstColumn, "x", "width") <= surface.getWidth() + 0.5);
            Object rootRow = boxFor(surface, project);
            assertTrue(edge(rootRow, "x", "width") > 0, "the root column stays in view beside it");
        });
    }

    @Test
    void aSmallProjectIsCentredAtFullSizeAndNeedsNoOverview() throws Exception {
        Path project = project();
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), file(project.resolve("README.md"), project, 1)),
                Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            assertEquals(1.0, zoom(surface), 0.0001);
            List<?> columns = FxTestSupport.field(surface, "columnBoxes");
            double left =
                    columns.stream().mapToDouble(box -> origin(box, "x")).min().orElseThrow();
            double right = columns.stream()
                    .mapToDouble(box -> edge(box, "x", "width"))
                    .max()
                    .orElseThrow();
            assertEquals(surface.getWidth() - right, left, 1.0, "centred across the canvas");
            assertNull(FxTestSupport.field(surface, "overviewBox"), "no overview while everything is visible");
        });
    }

    @Test
    void fitAnchorsOnTheSelectedPathWhenTheZoomFloorIsHit() throws Exception {
        Path project = project();
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(directory(project, null, 0)));
        Set<Path> expanded = new HashSet<>(Set.of(project));
        Path parent = project;
        for (int depth = 1; depth <= 12; depth++) {
            Path child = parent.resolve("level" + depth);
            entries.add(directory(child, parent, depth));
            entries.add(file(parent.resolve("Sibling" + depth + ".java"), parent, depth));
            expanded.add(child);
            parent = child;
        }
        Path deepest = parent;
        Region surface = show(445, 520);
        setEntries(surface, entries, expanded);
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, deepest);
            FxTestSupport.invoke(surface, "fitContent");
        });
        FxTestSupport.runOnFx(() -> {
            assertEquals(0.4, zoom(surface), 0.0001, "twelve columns cannot fit: the floor is hit");
            assertInView(surface, boxFor(surface, deepest), "the selected row");
            Object column = columnBoxForParent(surface, deepest.getParent());
            assertTrue(origin(column, "y") >= 0, "its column header is in view");
            assertInView(surface, boxFor(surface, deepest.getParent()), "the nearest ancestor");
            assertTrue(
                    edge(boxFor(surface, project), "x", "width") < 0,
                    "the far end of the path is what gives way, not the selection");
        });
    }

    @Test
    void fitShowsEverythingWhenItCanAndClearsTheZoomBar() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(
                        directory(project, null, 0),
                        directory(src, project, 1),
                        file(project.resolve("README.md"), project, 1),
                        file(src.resolve("Main.java"), src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "zoomBy", new Class<?>[] {double.class}, 0.5);
            FxTestSupport.invoke(surface, "fitContent");
        });
        FxTestSupport.runOnFx(() -> {
            double reserved = FxTestSupport.field(surface, "reservedBottom");
            assertTrue(reserved > 20, "the zoom bar reports the strip it covers: " + reserved);
            List<?> columns = FxTestSupport.field(surface, "columnBoxes");
            for (Object column : columns) {
                assertTrue(origin(column, "x") >= 0 && edge(column, "x", "width") <= surface.getWidth());
                assertTrue(origin(column, "y") >= 0);
                assertTrue(
                        edge(column, "y", "height") <= surface.getHeight() - reserved + 0.5,
                        "fitted content stays above the zoom bar");
            }
        });
    }

    @Test
    void openingALongFolderShowsItsHeaderFirstRowsAndParentRow() throws Exception {
        Path project = project();
        Path big = project.resolve("big");
        List<ProjectMapModel.Entry> collapsed = List.of(directory(project, null, 0), directory(big, project, 1));
        List<ProjectMapModel.Entry> opened = new ArrayList<>(collapsed);
        for (int i = 0; i < 200; i++) {
            opened.add(file(big.resolve(String.format("File%03d.java", i)), big, 2));
        }
        Region surface = show(900, 560);
        setEntries(surface, collapsed, Set.of(project));
        settle();
        setEntries(surface, opened, Set.of(project, big));
        settle();
        FxTestSupport.runOnFx(() -> {
            Object column = columnBoxForParent(surface, big);
            assertTrue(origin(column, "y") >= 0, "the column header is in view, not row 100 of 200");
            assertTrue(origin(column, "y") < surface.getHeight() / 2);
            assertInView(surface, boxFor(surface, big.resolve("File000.java")), "the first row");
            assertInView(surface, boxFor(surface, big), "the row that opened the column");
            assertTrue(origin(column, "x") >= 0 && edge(column, "x", "width") <= surface.getWidth());
        });
    }

    @Test
    void shiftIntoViewMovesAsLittleAsPossibleAndShowsTheStartOfWhatCannotFit() throws Exception {
        Region surface = show(400, 400);
        assertEquals(0.0, shift(surface, 40, 80, 20, 380), "already visible: no movement");
        assertEquals(30.0, shift(surface, -10, 50, 20, 380), "pulled in from the leading edge");
        assertEquals(-40.0, shift(surface, 360, 420, 20, 380), "pulled in from the trailing edge");
        assertEquals(120.0, shift(surface, -100, 900, 20, 380), "too large: aligned on its start");
    }

    // ---- column placement (C8, U4, U16) ---------------------------------------------------------------

    @Test
    void columnsAtDifferentDepthsNeverOverlapInAnyFlow() throws Exception {
        Path project = project();
        Path a = project.resolve("a");
        Path b = project.resolve("b");
        Path sub = a.resolve("sub");
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(
                directory(project, null, 0),
                directory(a, project, 1),
                directory(b, project, 1),
                directory(sub, a, 2),
                // b's column is far wider than a's, and one depth up from sub's tall column.
                file(b.resolve("AVeryLongFileNameThatMakesThisColumnMuchWiderThanItsSiblingColumn.properties"), b, 2)));
        for (int i = 0; i < 40; i++) {
            entries.add(file(sub.resolve(String.format("Deep%02d.java", i)), sub, 3));
        }
        Region surface = show(1000, 640);
        setEntries(surface, entries, Set.of(project, a, b, sub));
        settle();
        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            FxTestSupport.runOnFx(() -> mapView.setRememberedFlow(flow.name(), ignored -> {}));
            settle();
            FxTestSupport.runOnFx(() -> {
                List<?> columns = FxTestSupport.field(surface, "columnBoxes");
                assertEquals(5, columns.size());
                for (int i = 0; i < columns.size(); i++) {
                    for (int j = i + 1; j < columns.size(); j++) {
                        assertFalse(
                                overlaps(columns.get(i), columns.get(j)),
                                flow + ": columns " + describe(columns.get(i)) + " and " + describe(columns.get(j))
                                        + " overlap");
                    }
                }
            });
        }
    }

    @Test
    void headerBandCollapsesWhileTheColumnDetailControlsAreHidden() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path main = src.resolve("Main.java");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), directory(src, project, 1), file(main, src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            Object column = columnBoxForParent(surface, src);
            TextField filter = (TextField) FxTestSupport.call(controls(surface, src), "filter", new Class<?>[0]);
            assertTrue(filter.isVisible());
            assertEquals(75.0, origin(boxFor(surface, main), "y") - origin(column, "y"), 0.01, "title and controls");

            FxTestSupport.call(surface, "zoomBy", new Class<?>[] {double.class}, 0.5);
            FxTestSupport.invoke(surface, "repaint");
            column = columnBoxForParent(surface, src);
            assertFalse(filter.isVisible(), "no room for the controls at 50%");
            assertEquals(
                    (5 + 28) * 0.5,
                    origin(boxFor(surface, main), "y") - origin(column, "y"),
                    0.01,
                    "the rows move up under the title instead of leaving an empty band");
        });
    }

    @Test
    void hiddenFilesCheckboxDropsItsLabelBeforeItIsClipped() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), directory(src, project, 1), file(src.resolve("A.java"), src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            CheckBox hidden = (CheckBox) FxTestSupport.call(controls(surface, src), "showHidden", new Class<?>[0]);
            assertEquals(ContentDisplay.LEFT, hidden.getContentDisplay());
            assertTrue(hidden.getWidth() >= hidden.prefWidth(-1) - 0.5, "the whole label fits at 100%");

            FxTestSupport.call(surface, "zoomBy", new Class<?>[] {double.class}, 0.66);
            FxTestSupport.invoke(surface, "repaint");
            assertTrue(hidden.isVisible(), "the controls are still shown at 66%");
            assertEquals(ContentDisplay.GRAPHIC_ONLY, hidden.getContentDisplay(), "box only, not \"Hid…\"");
            assertNotNull(hidden.getTooltip());
            assertFalse(hidden.getAccessibleText().isBlank(), "and it keeps its name");
        });
    }

    @Test
    void verticalFlowConnectorsStopAtTheCardEdgeInsteadOfCrossingItsHeader() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path main = src.resolve("Main.java");
        Region surface = show(900, 640);
        FxTestSupport.runOnFx(() -> mapView.setRememberedFlow("TOP_TO_BOTTOM", ignored -> {}));
        setEntries(
                surface,
                List.of(directory(project, null, 0), directory(src, project, 1), file(main, src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            // No selection on the path, so the connector is drawn in the plain accent colour.
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, project);
            Object column = columnBoxForParent(surface, src);
            Object row = boxFor(surface, main);
            int x = (int) Math.round(origin(row, "x") + size(row, "width") / 2);
            Image shot = surface.snapshot(null, null);
            PixelReader pixels = shot.getPixelReader();
            Color background = probe(surface, "bgProbe");
            int above = 0;
            for (int y = (int) origin(column, "y") - 12; y < (int) origin(column, "y") - 2; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    above += near(pixels.getColor(x + dx, y), background, 0.1) ? 0 : 1;
                }
            }
            assertTrue(above >= 8, "the connector reaches the card's top edge above the row: " + above);
            // The title band of the header: the short title is at its left, the count at its right.
            Color card = pixels.getColor(x, (int) origin(column, "y") + 4);
            for (int y = (int) origin(column, "y") + 4; y < (int) origin(column, "y") + 22; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    assertTrue(
                            near(pixels.getColor(x + dx, y), card, 0.03),
                            "nothing is drawn through the header at y=" + y);
                }
            }
            assertTrue(origin(row, "y") - origin(column, "y") > 60, "the header band the connector used to cross");
        });
    }

    // ---- reserved areas and empty states (U6, U7) ----------------------------------------------------

    @Test
    void keyboardSelectionStaysClearOfTheZoomBarAndTheOverview() throws Exception {
        Path project = project();
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(directory(project, null, 0)));
        for (int i = 0; i < 60; i++) {
            entries.add(file(project.resolve(String.format("File%02d.java", i)), project, 1));
        }
        Region surface = show(520, 480);
        setEntries(surface, entries, Set.of(project));
        settle();
        for (int i = 0; i < 60; i++) {
            Path target = project.resolve(String.format("File%02d.java", i));
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, target);
                FxTestSupport.invoke(surface, "repaint");
                Object row = boxFor(surface, target);
                double reserved = FxTestSupport.field(surface, "reservedBottom");
                assertTrue(reserved > 20);
                assertTrue(origin(row, "y") >= 0, target.getFileName() + " is cut at the top");
                assertTrue(
                        edge(row, "y", "height") <= surface.getHeight() - reserved + 0.5,
                        target.getFileName() + " sits under the zoom bar");
                Object overview = FxTestSupport.field(surface, "overviewBox");
                if (overview != null) {
                    assertFalse(overlaps(row, overview), target.getFileName() + " sits under the overview");
                }
            });
        }
    }

    @Test
    void columnControlsThatWouldCoverTheOverviewAreHidden() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        List<ProjectMapModel.Entry> entries =
                new ArrayList<>(List.of(directory(project, null, 0), directory(src, project, 1)));
        for (int i = 0; i < 40; i++) {
            entries.add(file(src.resolve(String.format("File%02d.java", i)), src, 2));
        }
        Region surface = show(700, 520);
        setEntries(surface, entries, Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            Object overview = FxTestSupport.field(surface, "overviewBox");
            assertNotNull(overview, "forty rows overflow the canvas");
            Object column = columnBoxForParent(surface, src);
            // Pan the column's header, close button included, onto the overview.
            setDouble(
                    surface, "offsetX", number(surface, "offsetX") + origin(overview, "x") - 100 - origin(column, "x"));
            setDouble(surface, "offsetY", number(surface, "offsetY") + origin(overview, "y") - origin(column, "y"));
            FxTestSupport.invoke(surface, "repaint");
            Object controls = controls(surface, src);
            for (String name : List.of("filter", "showHidden", "pin", "close")) {
                Node control = (Node) FxTestSupport.call(controls, name, new Class<?>[0]);
                assertFalse(control.isVisible(), name + " would paint over the overview and take its clicks");
            }
        });
    }

    @Test
    void nothingMatchingSaysSoAndLeavesTheViewportAlone() throws Exception {
        Path project = project();
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), file(project.resolve("README.md"), project, 1)),
                Set.of(project));
        settle();
        double[] before = new double[3];
        FxTestSupport.runOnFx(() -> {
            before[0] = number(surface, "offsetX");
            before[1] = number(surface, "offsetY");
            before[2] = zoom(surface);
            assertFalse(noMatchesLabel().isVisible());
            mapView.setQuery("zzqqxx");
        });
        waitForFx(() -> noMatchesLabel().isVisible());
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    tr("project.map.noMatches.query", "zzqqxx"),
                    noMatchesLabel().getText());
            assertEquals(before[0], number(surface, "offsetX"), 0.0001);
            assertEquals(before[1], number(surface, "offsetY"), 0.0001);
            assertEquals(before[2], zoom(surface), 0.0001);
            mapView.setQuery("");
            assertFalse(noMatchesLabel().isVisible(), "the message goes as soon as something matches again");
        });
        FxTestSupport.runOnFx(() ->
                FxTestSupport.<ToggleButton>field(mapView, "modifiedFilter").fire());
        waitForFx(() -> noMatchesLabel().isVisible());
        FxTestSupport.runOnFx(() -> assertEquals(
                tr("project.map.noMatches.filters"), noMatchesLabel().getText()));
    }

    @Test
    void aColumnWhoseFilterMatchesNothingKeepsRoomForItsNoMatchesLine() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), directory(src, project, 1), file(src.resolve("A.java"), src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            double withRow = size(columnBoxForParent(surface, src), "height");
            TextField filter = (TextField) FxTestSupport.call(controls(surface, src), "filter", new Class<?>[0]);
            filter.setText("zzzz");
            Object column = columnBoxForParent(surface, src);
            ProjectMapModel.Column model =
                    (ProjectMapModel.Column) FxTestSupport.call(column, "column", new Class<?>[0]);
            assertTrue(model.entries().isEmpty());
            assertEquals(withRow, size(column, "height"), 0.01, "one row of space is kept for the message");
        });
    }

    // ---- rows (U10, U11, U19) ------------------------------------------------------------------------

    @Test
    void selectedRowUsesTheAccentOnlyWhileTheMapHasFocusAndThenShowsAFocusRing() throws Exception {
        Path project = project();
        Path file = project.resolve("Main.java");
        Region surface = show(700, 500);
        setEntries(surface, List.of(directory(project, null, 0), file(file, project, 1)), Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, file);
            Object row = boxFor(surface, file);
            int x = (int) (origin(row, "x") + size(row, "width") * 0.72);
            int inside = (int) (origin(row, "y") + 5);
            int ring = (int) Math.round(origin(row, "y") - 3);
            Color accent = probe(surface, "accentProbe");
            Color subtle = probe(surface, "accentSubtleProbe");
            assertNotEquals(accent, subtle);

            PixelReader unfocused = surface.snapshot(null, null).getPixelReader();
            assertTrue(near(unfocused.getColor(x, inside), subtle, 0.04), "muted selection without focus");
            assertFalse(near(unfocused.getColor(x, ring), accent, 0.2), "and no ring");

            FxTestSupport.call(surface, "setFocused", new Class<?>[] {boolean.class}, true);
            PixelReader focused = surface.snapshot(null, null).getPixelReader();
            assertTrue(near(focused.getColor(x, inside), accent, 0.04), "accent fill with focus");
            assertTrue(near(focused.getColor(x, ring), probe(surface, "focusRingProbe"), 0.2), "ring outside");

            Map<?, ?> icons = FxTestSupport.field(surface, "iconImages");
            assertTrue(
                    icons.keySet().stream()
                            .anyMatch(key -> "project-map-icon-on-accent"
                                    .equals(FxTestSupport.call(key, "statusClass", new Class<?>[0]))),
                    "the selected row's glyph is rasterised in the on-accent ink");
        });
    }

    @Test
    void onAccentGlyphIsDrawnInTheOnEmphasisInkWhateverTheFileStatus() throws Exception {
        Region surface = show(700, 500);
        FxTestSupport.runOnFx(() -> {
            mapView.setStyle("-color-fg-emphasis: #ff00ff;");
            mapView.applyCss();
            Image normal = (Image) FxTestSupport.call(
                    surface,
                    "rasterizeIcon",
                    new Class<?>[] {String.class, boolean.class, String.class},
                    "Main.java",
                    false,
                    GitFileStatus.MODIFIED.cssClass());
            Image onAccent = (Image) FxTestSupport.call(
                    surface,
                    "rasterizeIcon",
                    new Class<?>[] {String.class, boolean.class, String.class},
                    "Main.java",
                    false,
                    "project-map-icon-on-accent");
            assertTrue(hasInk(onAccent, Color.web("#ff00ff")), "on-accent ink");
            assertFalse(hasInk(normal, Color.web("#ff00ff")), "the ordinary glyph keeps its own colour");
        });
    }

    @Test
    void everyFolderRowHasAChevronInItsTrailingZone() throws Exception {
        Path project = project();
        Path open = project.resolve("open");
        Path closed = project.resolve("closed");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(
                        directory(project, null, 0),
                        directory(open, project, 1),
                        directory(closed, project, 1),
                        file(open.resolve("A.java"), open, 2)),
                Set.of(project, open));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, project);
            Image shot = surface.snapshot(null, null);
            Color fill = probe(surface, "surfaceProbe");
            int collapsedInk = 0;
            int expandedInk = 0;
            for (Path folder : List.of(open, closed)) {
                Object row = boxFor(surface, folder);
                javafx.geometry.Rectangle2D zone = (javafx.geometry.Rectangle2D)
                        FxTestSupport.call(surface, "chevronZone", new Class<?>[] {row.getClass()}, row);
                assertEquals(edge(row, "x", "width"), zone.getMaxX(), 0.001, "the zone ends with the row");
                assertEquals(24.0, zone.getWidth(), 0.001);
                assertEquals(size(row, "height"), zone.getHeight(), 0.001);
                assertTrue((boolean) FxTestSupport.call(
                        surface, "chevronHit", new Class<?>[] {row.getClass(), double.class}, row, zone.getMinX() + 2));
                assertFalse((boolean) FxTestSupport.call(
                        surface, "chevronHit", new Class<?>[] {row.getClass(), double.class}, row, zone.getMinX() - 2));
                int ink = 0;
                for (int y = (int) zone.getMinY() + 4; y < zone.getMaxY() - 4; y++) {
                    for (int x = (int) zone.getMinX() + 2; x < zone.getMaxX() - 3; x++) {
                        if (!near(shot.getPixelReader().getColor(x, y), fill, 0.06)) {
                            ink++;
                        }
                    }
                }
                if (folder.equals(open)) {
                    expandedInk = ink;
                } else {
                    collapsedInk = ink;
                }
            }
            assertTrue(collapsedInk > 4, "a collapsed folder shows a chevron too: " + collapsedInk);
            assertTrue(
                    expandedInk > collapsedInk * 3,
                    "an expanded folder's chevron sits in a disc, a different shape: " + expandedInk + " vs "
                            + collapsedInk);
        });
    }

    @Test
    void unsavedAndGitMarksDifferInShapeNotOnlyInColour() throws Exception {
        Path project = project();
        Path unsaved = project.resolve("Unsaved.java");
        Path changed = project.resolve("Changed.java");
        FxTestSupport.runOnFx(() -> {
            mapView = new ProjectMapView(path -> {}, path -> false, unsaved::equals);
            attach(mapView, 900, 640);
        });
        Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(mapView, "surface"));
        FxTestSupport.runOnFx(() -> mapView.setGitStatus(Map.of(changed, GitFileStatus.MODIFIED)));
        setEntries(
                surface,
                List.of(directory(project, null, 0), file(changed, project, 1), file(unsaved, project, 1)),
                Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, project);
            Image shot = surface.snapshot(null, null);
            Color fill = probe(surface, "surfaceProbe");
            int[] dot = markBounds(shot, boxFor(surface, unsaved), fill);
            int[] letter = markBounds(shot, boxFor(surface, changed), fill);
            assertTrue(dot[0] > 0 && letter[0] > 0, "both rows carry a mark");
            assertTrue(
                    letter[1] > dot[1] && letter[0] > dot[0],
                    "the Git letter is taller and has more ink than the unsaved dot: letter "
                            + java.util.Arrays.toString(letter) + ", dot " + java.util.Arrays.toString(dot));
        });
    }

    @Test
    void rowAndConnectorInkReachesThreeToOneInBothEditoraThemes() throws Exception {
        for (String theme : List.of("editora-light", "editora-dark")) {
            Map<String, String> palette = palette(theme);
            Color background = color(palette, "-color-bg-default");
            Color card = color(palette, "-color-bg-subtle");
            Color border = ProjectMapView.rowBorderColor(
                    color(palette, "-color-border-default"), color(palette, "-color-fg-muted"));
            assertTrue(
                    contrast(border, card) >= 3.0,
                    theme + ": a row's border against its card is " + contrast(border, card));
            Color connector = background.interpolate(
                    color(palette, "-color-accent-emphasis"), ProjectMapView.connectorOpacity(false, true));
            assertTrue(
                    contrast(connector, background) >= 3.0,
                    theme + ": an ordinary connector is " + contrast(connector, background));
            assertTrue(
                    contrast(color(palette, "-color-accent-fg"), card) >= 4.5,
                    theme + ": an open file's label is " + contrast(color(palette, "-color-accent-fg"), card));
            assertTrue(
                    contrast(color(palette, "-color-fg-default"), color(palette, "-color-accent-subtle")) >= 4.5,
                    theme + ": the unfocused selection's label");
        }
    }

    // ---- toolbar, breadcrumbs, help and accessibility (U5, U12, U13, U15) ----------------------------

    @Test
    void toolbarFitsTheToolWindowAtItsDefaultAndNarrowWidthsInEveryLanguage() throws Exception {
        for (String language : List.of("en", "es", "de", "fr", "it", "pt")) {
            for (double width : new double[] {345, 260}) {
                FxTestSupport.runOnFx(() -> {
                    Messages.init(language);
                    ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
                    try {
                        attach(view, width, 700);
                        String where = language + " at " + (int) width + " px";
                        Parent filters = (Parent) view.lookup(".project-map-filters");
                        for (Node child : filters.getChildrenUnmodifiable()) {
                            assertWhole(child, where);
                            assertTrue(
                                    child.getBoundsInParent().getMaxX() <= width + 0.5,
                                    where + ": " + describe(child) + " runs off the row");
                        }
                        Parent zoomBar = (Parent) view.lookup(".project-map-zoom");
                        for (Node child : zoomBar.getChildrenUnmodifiable()) {
                            assertWhole(child, where);
                            assertTrue(
                                    child instanceof Label
                                            || ((Button) child).getText().isEmpty(),
                                    where + ": the zoom bar is icons and the percentage only");
                        }
                        assertTrue(
                                zoomBar.getBoundsInParent().getMaxX() <= width,
                                where + ": the zoom bar is wider than the canvas");
                        for (Node node : view.lookupAll(".project-map-nav-button")) {
                            assertWhole(node, where);
                        }
                        assertWhole(view.lookup(".project-map-options"), where);
                        assertTrue(filters.getBoundsInParent().getHeight() <= 120, where + ": the filter block");
                    } finally {
                        view.dispose();
                    }
                });
            }
        }
    }

    @Test
    void optionsMenuHoldsTheSessionOptionsAndOutputActions() throws Exception {
        show(600, 500);
        FxTestSupport.runOnFx(() -> {
            MenuButton options = (MenuButton) mapView.lookup(".project-map-options");
            List<String> items = options.getItems().stream()
                    .map(MenuItem::getText)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            assertEquals(
                    List.of(
                            tr("project.map.navigation.keepZoom"),
                            tr("project.map.navigation.focusNewColumn"),
                            tr("project.map.filter.hideOpenNotes"),
                            tr("project.map.print"),
                            tr("project.map.exportPdf")),
                    items);
            assertFalse(options.getAccessibleText().isBlank());

            Region surface = FxTestSupport.field(mapView, "surface");
            assertTrue(mapView.isKeepZoom() && mapView.isFocusNewColumn(), "both default on");
            mapView.setKeepZoom(false);
            mapView.setFocusNewColumn(false);
            assertFalse((boolean) FxTestSupport.field(surface, "keepZoomOnColumnOpen"), "a restored value applies");
            assertFalse((boolean) FxTestSupport.field(surface, "focusNewColumn"));
            assertFalse(FxTestSupport.<CheckMenuItem>field(mapView, "keepZoomOnOpen")
                    .isSelected());
        });
    }

    @Test
    void everyControlAroundTheCanvasIsNamed() throws Exception {
        show(600, 500);
        FxTestSupport.runOnFx(() -> {
            for (Node node : mapView.lookupAll(".project-map-filter-chip")) {
                ToggleButton chip = (ToggleButton) node;
                assertNotNull(chip.getTooltip(), chip.getText());
                assertNotEquals(chip.getText(), chip.getTooltip().getText(), "the tooltip says what the chip does");
            }
            ComboBox<?> type = FxTestSupport.field(mapView, "typeFilter");
            ComboBox<ProjectMapView.FlowDirection> flow = FxTestSupport.field(mapView, "flowFilter");
            assertEquals(tr("project.map.type.all"), type.getAccessibleText(), "the prefix lives in the name");
            assertEquals(tr("project.map.type.all"), type.getTooltip().getText());
            assertEquals(tr("project.map.flow.left_to_right"), flow.getAccessibleText());
            assertEquals(tr("project.map.type.name.all"), type.getConverter().toString(null), "and not in the face");
            flow.setValue(ProjectMapView.FlowDirection.TOP_TO_BOTTOM);
            assertEquals(tr("project.map.flow.top_to_bottom"), flow.getTooltip().getText());

            List<ButtonBase> glyphButtons = new ArrayList<>();
            mapView.lookupAll(".project-map-nav-button").forEach(node -> glyphButtons.add((ButtonBase) node));
            mapView.lookupAll(".project-map-zoom-control").stream()
                    .filter(Button.class::isInstance)
                    .forEach(node -> glyphButtons.add((ButtonBase) node));
            assertEquals(8, glyphButtons.size(), "back, forward, help and five zoom buttons");
            for (ButtonBase button : glyphButtons) {
                assertTrue(
                        button.getAccessibleText() != null
                                && button.getAccessibleText().length() > 3,
                        "a glyph button is announced by name, not as \"" + button.getText() + "\"");
                assertEquals(button.getAccessibleText(), button.getTooltip().getText());
            }
        });
    }

    @Test
    void helpPopoverListsTheMouseAndKeyboardModel() throws Exception {
        show(600, 500);
        FxTestSupport.runOnFx(() -> {
            Parent help = (Parent) FxTestSupport.call(mapView, "buildHelp", new Class<?>[0]);
            List<String> text = new ArrayList<>();
            for (Node node : help.getChildrenUnmodifiable()) {
                text.add(((Label) node).getText());
            }
            assertEquals(2 + 16 * 2, text.size(), "two headings and sixteen gesture rows");
            assertTrue(text.contains(tr("project.map.help.chevron")));
            assertTrue(text.contains(tr("project.map.help.dragHeader.gesture")));
            assertTrue(text.contains(tr("project.map.help.escape")));
            assertTrue(text.contains(tr("project.map.help.columnFilter")));
            assertTrue(
                    text.contains(
                            new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN).getDisplayText()),
                    "the fit chord is spelled for this platform, not baked into a message");
            assertTrue(text.contains(KeyCode.PAGE_UP.getName() + " / " + KeyCode.PAGE_DOWN.getName()));
        });
    }

    @Test
    void accessibleSelectionSaysRoleStateAndPosition() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path docs = project.resolve("docs");
        Path readme = project.resolve("README.md");
        Region surface = show(900, 640);
        // Loaded out of display order on purpose: positions follow the order rows are shown in.
        setEntries(
                surface,
                List.of(
                        directory(project, null, 0),
                        file(readme, project, 1),
                        directory(src, project, 1),
                        directory(docs, project, 1),
                        file(src.resolve("Main.java"), src, 2)),
                Set.of(project, src));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, src);
            assertEquals(tr("project.map.accessible.folderExpanded", "src", 2, 3), surface.getAccessibleText());
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, docs);
            assertEquals(tr("project.map.accessible.folderCollapsed", "docs", 1, 3), surface.getAccessibleText());
            FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, readme);
            assertEquals(tr("project.map.accessible.file", "README.md", 3, 3), surface.getAccessibleText());
        });
    }

    @Test
    void rowTooltipNamesWhatTheEyeAndTheChevronDo() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path file = project.resolve("Main.java");
        Region surface = show(900, 640);
        setEntries(
                surface,
                List.of(directory(project, null, 0), directory(src, project, 1), file(file, project, 1)),
                Set.of(project));
        settle();
        FxTestSupport.runOnFx(() -> {
            Object fileRow = boxFor(surface, file);
            double y = origin(fileRow, "y") + size(fileRow, "height") / 2;
            hover(surface, edge(fileRow, "x", "width") - 8, y);
            assertEquals("PREVIEW", String.valueOf((Object) FxTestSupport.field(surface, "hoveredAffordance")));
            assertTrue(tooltip(surface, fileRow).startsWith(tr("project.map.node.preview", "Main.java")));
            hover(surface, origin(fileRow, "x") + 40, y);
            assertEquals("NONE", String.valueOf((Object) FxTestSupport.field(surface, "hoveredAffordance")));
            assertTrue(tooltip(surface, fileRow).startsWith(file.toString()), "elsewhere it describes the file");

            Object folderRow = boxFor(surface, src);
            hover(surface, edge(folderRow, "x", "width") - 8, origin(folderRow, "y") + 10);
            assertTrue(tooltip(surface, folderRow).startsWith(tr("project.map.node.expand", "src")));
        });
    }

    @Test
    void tooltipSizesAreLabelledWithTheUnitTheyAreDividedBy() throws Exception {
        Region surface = show(400, 400);
        assertEquals("512 B", formatSize(surface, 512));
        assertEquals("1.0 KiB", formatSize(surface, 1024));
        assertEquals("1.5 MiB", formatSize(surface, 1024 * 1024 * 3 / 2));
    }

    @Test
    void flowDefaultsToLeftToRightAndAStoredChoiceIsKept() throws Exception {
        show(600, 500);
        FxTestSupport.runOnFx(() -> {
            ComboBox<ProjectMapView.FlowDirection> flow = FxTestSupport.field(mapView, "flowFilter");
            assertEquals(ProjectMapView.FlowDirection.LEFT_TO_RIGHT, flow.getValue());
            mapView.setRememberedFlow("BOTTOM_TO_TOP", ignored -> {});
            assertEquals(ProjectMapView.FlowDirection.BOTTOM_TO_TOP, flow.getValue(), "a stored flow is restored");
            mapView.setRememberedFlow(null, ignored -> {});
            assertEquals(ProjectMapView.FlowDirection.LEFT_TO_RIGHT, flow.getValue(), "nothing stored: the default");
            mapView.setRememberedFlow("SIDEWAYS", ignored -> {});
            assertEquals(ProjectMapView.FlowDirection.LEFT_TO_RIGHT, flow.getValue());
        });
    }

    @Test
    void breadcrumbsCollapseTheMiddleAndKeepTheRootAndTheLastTwo() throws Exception {
        assertEquals(0, ProjectMapView.hiddenBreadcrumbs(widths(5, 50, 8), 2, 10, 400, total(5, 50, 8, 2)));
        // Five crumbs of 50 need 298. With the "…" in place of one middle crumb they need 258, of two 196.
        assertEquals(1, ProjectMapView.hiddenBreadcrumbs(widths(5, 50, 8), 2, 10, 260, total(5, 50, 8, 2)));
        assertEquals(2, ProjectMapView.hiddenBreadcrumbs(widths(5, 50, 8), 2, 10, 250, total(5, 50, 8, 2)));
        assertEquals(2, ProjectMapView.hiddenBreadcrumbs(widths(5, 50, 8), 2, 10, 90, total(5, 50, 8, 2)));
        assertEquals(0, ProjectMapView.hiddenBreadcrumbs(widths(3, 50, 8), 2, 10, 60, total(3, 50, 8, 2)));

        Path project = project();
        List<ProjectMapModel.Entry> entries = new ArrayList<>(List.of(directory(project, null, 0)));
        Set<Path> expanded = new HashSet<>(Set.of(project));
        Path parent = project;
        for (int depth = 1; depth <= 7; depth++) {
            Path child = parent.resolve("package" + depth);
            entries.add(directory(child, parent, depth));
            expanded.add(child);
            parent = child;
        }
        Path leaf = parent.resolve("AVeryLongLeafFileNameForTheBreadcrumb.java");
        entries.add(file(leaf, parent, 8));
        Region surface = show(345, 500);
        setField(mapView, "root", project);
        setEntries(surface, entries, expanded);
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, leaf);
            mapView.applyCss();
            mapView.layout();
            HBox crumbs = FxTestSupport.field(mapView, "breadcrumbs");
            crumbs.applyCss();
            crumbs.layout();
            List<String> shown = crumbs.getChildren().stream()
                    .filter(Node::isVisible)
                    .filter(node -> node.getStyleClass().contains("project-map-breadcrumb"))
                    .map(node -> ((Button) node).getText())
                    .toList();
            assertEquals(
                    List.of(
                            project.getFileName().toString(),
                            "package7",
                            leaf.getFileName().toString()),
                    shown,
                    "root, then the last two; the middle collapsed");
            Label ellipsis = FxTestSupport.field(mapView, "breadcrumbEllipsis");
            assertTrue(crumbs.getChildren().contains(ellipsis));
            assertTrue(ellipsis.getTooltip().getText().contains("package3"), "the tooltip lists what is hidden");
            for (Node node : crumbs.getChildren()) {
                if (node instanceof Button crumb
                        && crumb.isVisible()
                        && !crumb.getText().equals(shown.get(2))) {
                    assertTrue(
                            crumb.getWidth() >= crumb.prefWidth(-1) - 0.5,
                            "\"" + crumb.getText() + "\" is not reduced to an ellipsis");
                }
            }
        });
    }

    // ---- style sheet (U20) ---------------------------------------------------------------------------

    @Test
    void previewAndNoteCardStylesResolveInEveryThemeAndCarryNoFixedColours() throws Exception {
        String css = read("app.css");
        String title = rule(css, ".project-map-preview-title .toolbar-icon");
        assertFalse(
                title.contains("-project-file-color"),
                "that token is defined on .project-tree, which a preview card is not inside");
        Matcher rules = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        int noteRules = 0;
        while (rules.find()) {
            if (rules.group(1).contains(".project-map-note-preview")) {
                noteRules++;
                assertFalse(
                        Pattern.compile("#[0-9a-fA-F]{3,8}\\b|rgba?\\(")
                                .matcher(rules.group(2))
                                .find(),
                        "a fixed colour in " + rules.group(1).strip());
            }
        }
        assertTrue(noteRules >= 5);
        for (String theme : List.of("editora-light", "editora-dark")) {
            Map<String, String> palette = palette(theme);
            assertTrue(
                    contrast(color(palette, "-color-fg-default"), color(palette, "-color-warning-subtle")) >= 7.0,
                    theme + ": note text on the note sheet");
        }
    }

    // ---- fixture -------------------------------------------------------------------------------------

    private Path project() {
        return temp.resolve("orbit-service").toAbsolutePath().normalize();
    }

    private static ProjectMapModel.Entry directory(Path path, Path parent, int depth) {
        return new ProjectMapModel.Entry(path, parent, depth, true);
    }

    private static ProjectMapModel.Entry file(Path path, Path parent, int depth) {
        return new ProjectMapModel.Entry(path, parent, depth, false);
    }

    /** A map view in a themed scene of the given size; returns its canvas surface. */
    private Region show(double width, double height) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            mapView = new ProjectMapView(path -> {}, path -> false, path -> false);
            attach(mapView, width, height);
            return FxTestSupport.field(mapView, "surface");
        });
    }

    private static void attach(ProjectMapView view, double width, double height) {
        Scene scene = new Scene(view, width, height);
        scene.getStylesheets().add(Themes.stylesheetFor("Editora Light"));
        scene.getStylesheets()
                .add(ProjectMapLayoutFxTest.class
                        .getResource(STYLES + "app.css")
                        .toExternalForm());
        view.applyCss();
        view.resize(width, height);
        view.layout();
        view.layout(); // wrapping the filter row changes the height the canvas is given
    }

    private void setEntries(Region surface, List<ProjectMapModel.Entry> entries, Set<Path> expanded) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, expanded);
            mapView.applyCss();
            mapView.layout();
        });
    }

    /** Lets the surface's deferred work (first view, showing an opened column, auto-fit) run. */
    private void settle() throws Exception {
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {
            mapView.applyCss();
            mapView.layout();
        });
        FxTestSupport.runOnFx(() -> {});
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue(FxTestSupport.callOnFx(condition::getAsBoolean), "timed out waiting for the FX state");
    }

    private Label noMatchesLabel() {
        return FxTestSupport.field(mapView, "noMatchesLabel");
    }

    private static void assertInView(Region surface, Object box, String what) {
        assertTrue(
                origin(box, "x") >= 0
                        && origin(box, "y") >= 0
                        && edge(box, "x", "width") <= surface.getWidth() + 0.5
                        && edge(box, "y", "height") <= surface.getHeight() + 0.5,
                what + " is not wholly in view: " + describe(box) + " in " + surface.getWidth() + "x"
                        + surface.getHeight());
    }

    /** A labelled control shows its whole text: it is at least as wide as it wants to be. */
    private static void assertWhole(Node node, String where) {
        assertNotNull(node, where);
        if (node instanceof Control control) {
            assertTrue(
                    control.getWidth() >= Math.floor(control.prefWidth(-1)) - 0.5,
                    where + ": " + describe(node) + " is " + control.getWidth() + " wide and needs "
                            + control.prefWidth(-1));
        }
    }

    private static String describe(Object value) {
        if (value instanceof Labeled labeled) {
            return labeled.getClass().getSimpleName() + " \"" + labeled.getText() + "\" " + labeled.getStyleClass();
        }
        if (value instanceof Node node) {
            return node.getClass().getSimpleName() + " " + node.getStyleClass();
        }
        return "[" + origin(value, "x") + ", " + origin(value, "y") + " " + size(value, "width") + "x"
                + size(value, "height") + "]";
    }

    private static Object boxFor(Region surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .filter(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                        .path()
                        .equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + path));
    }

    private static Object columnBoxForParent(Region surface, Path parent) {
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        return boxes.stream()
                .filter(box -> parent.equals(
                        ((ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0])).parent()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column for " + parent));
    }

    private static Object controls(Region surface, Path parent) {
        Map<?, ?> values = FxTestSupport.field(surface, "columnControls");
        return values.entrySet().stream()
                .filter(entry -> parent.equals(FxTestSupport.call(entry.getKey(), "parent", new Class<?>[0])))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static double origin(Object box, String coordinate) {
        return (double) FxTestSupport.call(box, coordinate, new Class<?>[0]);
    }

    private static double size(Object box, String dimension) {
        return (double) FxTestSupport.call(box, dimension, new Class<?>[0]);
    }

    private static double edge(Object box, String origin, String size) {
        return origin(box, origin) + size(box, size);
    }

    private static boolean overlaps(Object first, Object second) {
        return origin(first, "x") < edge(second, "x", "width")
                && edge(first, "x", "width") > origin(second, "x")
                && origin(first, "y") < edge(second, "y", "height")
                && edge(first, "y", "height") > origin(second, "y");
    }

    private static double zoom(Region surface) {
        return FxTestSupport.field(surface, "zoom");
    }

    private static double number(Region surface, String field) {
        return FxTestSupport.field(surface, field);
    }

    private static void setDouble(Region surface, String field, double value) {
        try {
            var declared = surface.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.setDouble(surface, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                var declared = target.getClass().getDeclaredField(field);
                declared.setAccessible(true);
                declared.set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static double shift(Region surface, double low, double high, double min, double max) {
        return (double) FxTestSupport.call(
                surface,
                "shiftIntoView",
                new Class<?>[] {double.class, double.class, double.class, double.class},
                low,
                high,
                min,
                max);
    }

    private static String formatSize(Region surface, long bytes) {
        return (String) FxTestSupport.call(surface, "formatSize", new Class<?>[] {long.class}, bytes);
    }

    private static void hover(Region surface, double x, double y) {
        FxTestSupport.call(surface, "updateAffordance", new Class<?>[] {double.class, double.class}, x, y);
    }

    private static String tooltip(Region surface, Object row) {
        return (String) FxTestSupport.call(
                surface,
                "tooltipText",
                new Class<?>[] {ProjectMapModel.Entry.class},
                FxTestSupport.call(row, "entry", new Class<?>[0]));
    }

    private static Color probe(Region surface, String name) {
        Rectangle probe = FxTestSupport.field(surface, name);
        return (Color) probe.getFill();
    }

    private static boolean near(Color actual, Color expected, double tolerance) {
        return Math.abs(actual.getRed() - expected.getRed()) <= tolerance
                && Math.abs(actual.getGreen() - expected.getGreen()) <= tolerance
                && Math.abs(actual.getBlue() - expected.getBlue()) <= tolerance;
    }

    private static boolean hasInk(Image image, Color ink) {
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                Color pixel = pixels.getColor(x, y);
                if (pixel.getOpacity() > 0.8 && near(pixel, ink, 0.12)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ink count and height of the status mark just before a file row's preview strip. */
    private static int[] markBounds(Image shot, Object row, Color fill) {
        int right = (int) (edge(row, "x", "width") - 31);
        int top = Integer.MAX_VALUE;
        int bottom = -1;
        int ink = 0;
        for (int y = (int) origin(row, "y") + 3; y < edge(row, "y", "height") - 3; y++) {
            for (int x = right - 16; x <= right; x++) {
                if (!near(shot.getPixelReader().getColor(x, y), fill, 0.08)) {
                    ink++;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return new int[] {ink, bottom < 0 ? 0 : bottom - top + 1};
    }

    private static double[] widths(int crumbs, double crumb, double separator) {
        double[] widths = new double[crumbs * 2 - 1];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = i % 2 == 0 ? crumb : separator;
        }
        return widths;
    }

    private static double total(int crumbs, double crumb, double separator, double spacing) {
        return crumbs * crumb + (crumbs - 1) * separator + (crumbs * 2 - 2) * spacing;
    }

    private static String read(String path) throws Exception {
        try (var in = ProjectMapLayoutFxTest.class.getResourceAsStream(STYLES + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    private static String rule(String css, String selector) {
        Matcher m = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
        while (m.find()) {
            for (String candidate : m.group(1).split(",")) {
                if (candidate.strip().replaceAll("\\s+", " ").equals(selector)) {
                    return m.group(2);
                }
            }
        }
        throw new AssertionError("no rule for " + selector);
    }

    /** {@code -color-*} tokens of a control theme's {@code .root} palette. */
    private static Map<String, String> palette(String theme) throws Exception {
        String css = read("atlantafx-themes/" + theme + ".css");
        int start = css.indexOf(".root {");
        String block = css.substring(start, css.indexOf("\n}", start));
        Map<String, String> tokens = new HashMap<>();
        Matcher m = Pattern.compile("(-color-[a-z0-9-]+)\\s*:\\s*([^;]+);").matcher(block);
        while (m.find()) {
            tokens.put(m.group(1), m.group(2).trim());
        }
        return tokens;
    }

    private static Color color(Map<String, String> palette, String token) {
        String value = palette.get(token);
        while (palette.containsKey(value)) {
            value = palette.get(value);
        }
        return Color.web(value);
    }

    private static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(double v) {
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
