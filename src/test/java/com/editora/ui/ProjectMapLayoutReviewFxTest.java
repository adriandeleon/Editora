package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review's layout findings (2026-10-29): the root as the first column's title (E5), compact
 * folder chains (E4), long names (B15), faded rows (B19), the canvas and toolbar fonts and targets (B13, B25),
 * one Canvas draw per pulse (D5), the cached layout (D8) and the breadcrumb that changes only its tail (D15).
 * Keys and clicks are fired at the shown scene, so they take the route real input does.
 */
class ProjectMapLayoutReviewFxTest {

    private static final String STYLES = "/com/editora/styles/";

    @TempDir
    Path temp;

    private final List<Stage> stages = new ArrayList<>();
    private final List<ProjectMapView> views = new ArrayList<>();

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            views.forEach(ProjectMapView::dispose);
            stages.forEach(Stage::hide);
        });
    }

    // ---- E4: compact folder chains --------------------------------------------------------------------

    @Test
    void aRunOfOneRowColumnsFoldsIntoTheColumnItLeadsTo() {
        Path root = temp.resolve("p").toAbsolutePath().normalize();
        Path src = root.resolve("src");
        Path main = src.resolve("main");
        Path java = main.resolve("java");
        Path app = java.resolve("App.java");
        List<ProjectMapModel.Entry> entries = List.of(
                directory(root, null, 0),
                directory(src, root, 1),
                file(root.resolve("pom.xml"), root, 1),
                directory(main, src, 2),
                directory(java, main, 3),
                file(app, java, 4),
                file(java.resolve("Util.java"), java, 4));
        List<ProjectMapModel.Column> compact = ProjectMapModel.columnsById(entries, Map.of(), Map.of(), null, true);
        assertEquals(3, compact.size(), "the root row, the root's children and one column for src/main/java");
        ProjectMapModel.Column chain = compact.getLast();
        assertEquals(java, chain.parent(), "paths stay those of the real folders");
        assertEquals(List.of(src, main, java), chain.chain());
        assertEquals(src, chain.anchor(), "it hangs from the src row");
        assertTrue(chain.compacted());

        assertEquals(
                5,
                ProjectMapModel.columnsById(entries, Map.of(), Map.of(), null, false)
                        .size(),
                "without the option every folder has its own column");
        // A column with a filter of its own keeps its controls: the run stops there, it is drawn.
        Map<ProjectMapModel.ColumnId, String> queries = Map.of(new ProjectMapModel.ColumnId(3, main), "ja");
        List<ProjectMapModel.Column> filtered = ProjectMapModel.columnsById(entries, queries, Map.of(), null, true);
        assertEquals(4, filtered.size());
        assertEquals(main, filtered.get(2).parent());
        assertEquals(List.of(src, main), filtered.get(2).chain());
        assertFalse(filtered.get(3).compacted());
    }

    @Test
    void theRootsChildrenAreNeverFoldedAway() {
        Path root = temp.resolve("p").toAbsolutePath().normalize();
        Path src = root.resolve("src");
        List<ProjectMapModel.Entry> entries = List.of(
                directory(root, null, 0),
                directory(src, root, 1),
                file(src.resolve("A.java"), src, 2),
                file(src.resolve("B.java"), src, 2));
        List<ProjectMapModel.Column> columns = ProjectMapModel.columnsById(entries, Map.of(), Map.of(), null, true);
        assertEquals(3, columns.size());
        assertTrue(columns.stream().noneMatch(ProjectMapModel.Column::compacted));
    }

    @Test
    void openingTheFirstFolderOfAChainListsTheWholeChainInOneLoad() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Path src = Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Path app = Files.writeString(src.resolve("App.java"), "class App {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path top = root.resolve("src");
        ProjectMapModel.Snapshot compact = ProjectMapModel.load(new ProjectMapModel.Request(
                root, Set.of(root, top), false, Map.of(), Map.of(), List.of(), null, () -> false, true));
        assertTrue(compact.entries().stream().anyMatch(entry -> entry.path().equals(app)));
        assertTrue(compact.loadedDirectories().containsAll(Set.of(src, src.getParent(), top.resolve("main"))));
        ProjectMapModel.Snapshot plain =
                ProjectMapModel.load(new ProjectMapModel.Request(root, Set.of(root, top), false));
        assertFalse(plain.entries().stream().anyMatch(entry -> entry.path().equals(app)));
    }

    @Test
    void aChainIsOneColumnThatTheKeysAndTheBreadcrumbStillReachFolderByFolder() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Path acme = Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Path app = Files.writeString(acme.resolve("App.java"), "class App {}");
        Files.writeString(acme.resolve("Util.java"), "class Util {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path src = root.resolve("src");
        Path java = root.resolve("src/main/java");
        Shown map = open(root, 1100, 700);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(map.view, "toggleDirectory", new Class<?>[] {Path.class}, src));
        waitForFx(() -> contains(map.surface, app));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            List<?> columns = FxTestSupport.field(map.surface, "columnBoxes");
            assertEquals(2, columns.size(), "the root's children and src/main/java/com/acme");
            ProjectMapModel.Column chain = column(columns.getLast());
            assertEquals(acme, chain.parent());
            assertEquals(
                    "src / main / java / com / acme",
                    FxTestSupport.call(
                            map.surface, "columnTitle", new Class<?>[] {ProjectMapModel.Column.class}, chain));
            assertNull(nodeBox(map.surface, java), "the folders of the chain have no rows");

            // Right from the src row goes into the chain's column; Left comes back to the row it hangs from.
            select(map.surface, src);
            map.surface.requestFocus();
            press(map.surface, KeyCode.RIGHT);
            assertEquals(app, selected(map.surface));
            press(map.surface, KeyCode.LEFT);
            assertEquals(src, selected(map.surface));
            press(map.surface, KeyCode.RIGHT);
            press(map.surface, KeyCode.BACK_SPACE);
            assertEquals(src, selected(map.surface), "Backspace too");

            // The breadcrumb still names every folder, and selecting one selects the column's title.
            select(map.surface, app);
            HBox crumbs = FxTestSupport.field(map.view, "breadcrumbs");
            Button javaCrumb = crumbs.getChildren().stream()
                    .filter(node -> node instanceof Button button && "java".equals(button.getText()))
                    .map(Button.class::cast)
                    .findFirst()
                    .orElseThrow();
            javaCrumb.fire();
            assertEquals(java, selected(map.surface));
            assertEquals(
                    acme,
                    column(FxTestSupport.call(map.surface, "headerFor", new Class<?>[] {Path.class}, java))
                            .parent(),
                    "the chain's column title stands for the folder");
            press(map.surface, KeyCode.RIGHT);
            assertEquals(app, selected(map.surface), "and the arrow steps into its rows");
            press(map.surface, KeyCode.LEFT);
            assertEquals(src, selected(map.surface), "and back past the chain to its first row");
        });

        // The column's × folds the whole chain back into the src row.
        FxTestSupport.runOnFx(() -> {
            Button close = (Button)
                    FxTestSupport.call(keyedColumnValue(map.surface, "columnControls", acme), "close", new Class<?>[0]);
            close.fire();
        });
        waitForFx(() -> !contains(map.surface, app));
        FxTestSupport.runOnFx(() -> assertFalse(map.view.expandedDirectories().contains(src)));
    }

    @Test
    void turningCompactChainsOffKeepsTheChainOpenOneColumnPerFolder() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Path acme = Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Path app = Files.writeString(acme.resolve("App.java"), "class App {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path src = root.resolve("src");
        Shown map = open(root, 1300, 700);
        FxTestSupport.runOnFx(() -> {
            assertTrue(map.view.isCompactChains(), "on by default");
            MenuButton options = (MenuButton) map.view.lookup(".project-map-options");
            assertTrue(options.getItems().stream()
                    .anyMatch(item -> item instanceof CheckMenuItem check
                            && tr("project.map.layout.compactChains").equals(check.getText())
                            && check.isSelected()));
            FxTestSupport.call(map.view, "toggleDirectory", new Class<?>[] {Path.class}, src);
        });
        waitForFx(() -> contains(map.surface, app));
        FxTestSupport.runOnFx(() -> map.view.setCompactChains(false));
        waitForFx(() -> FxTestSupport.<List<?>>field(map.surface, "columnBoxes").size() == 6);
        FxTestSupport.runOnFx(() -> {
            assertTrue(contains(map.surface, app), "the folders the chain opened stay open");
            assertTrue(map.view.expandedDirectories().contains(acme));
            assertNotNull(nodeBox(map.surface, root.resolve("src/main/java")), "each folder has its row again");
        });
    }

    // ---- E5: the root is the first column's title -----------------------------------------------------

    @Test
    void theRootIsTheFirstColumnsTitleAndItsMenuAndKeysWorkThere() throws Exception {
        Path root = Files.createDirectory(temp.resolve("orbit")).toRealPath();
        Path docs = Files.createDirectory(root.resolve("docs"));
        Path readme = Files.writeString(root.resolve("README.md"), "# Orbit");
        Shown map = open(root, 900, 600);
        List<ProjectMapModel.Entry> menus = new CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> {
            Function<ProjectMapModel.Entry, ContextMenu> factory = entry -> {
                menus.add(entry);
                return null;
            };
            FxTestSupport.call(map.surface, "setContextMenuFactory", new Class<?>[] {Function.class}, factory);
            List<?> columns = FxTestSupport.field(map.surface, "columnBoxes");
            assertEquals(1, columns.size(), "no one-row root column");
            Object first = columns.getFirst();
            assertEquals(root, column(first).parent());
            assertEquals(
                    "orbit",
                    FxTestSupport.call(
                            map.surface, "columnTitle", new Class<?>[] {ProjectMapModel.Column.class}, column(first)));
            assertNull(nodeBox(map.surface, root));
            assertFalse(
                    (boolean) FxTestSupport.call(map.surface, "closeFits", new Class<?>[] {first.getClass()}, first),
                    "the first column has no ×: there is no row to fold it into");

            // Home selects the first row; Left from it selects the root, on the column's title.
            map.surface.requestFocus();
            press(map.surface, KeyCode.HOME);
            assertEquals(docs, selected(map.surface));
            press(map.surface, KeyCode.LEFT);
            assertEquals(root, selected(map.surface));
            assertEquals(
                    root,
                    column(FxTestSupport.call(map.surface, "headerFor", new Class<?>[] {Path.class}, root))
                            .parent(),
                    "the first column's title stands for the root");
            press(map.surface, KeyCode.DOWN);
            assertEquals(docs, selected(map.surface), "Down from the root enters its column");

            // Right-click on the title opens the root's menu (the root is selected for it).
            double x = origin(first, "x") + 30;
            double y = origin(first, "y") + 8;
            fireContextMenu(map.surface, x, y, false);
            assertEquals(root, menus.getLast().path());
            assertEquals(root, selected(map.surface));
            // …and so do Menu / Shift+F10 while the root is selected.
            menus.clear();
            fireContextMenu(map.surface, 5, 5, true);
            assertEquals(
                    List.of(root),
                    menus.stream().map(ProjectMapModel.Entry::path).toList());

            // The breadcrumb's first crumb selects the root again.
            select(map.surface, readme);
            HBox crumbs = FxTestSupport.field(map.view, "breadcrumbs");
            ((Button) crumbs.getChildren().getFirst()).fire();
            assertEquals(root, selected(map.surface));
        });
    }

    // ---- B15: long names ------------------------------------------------------------------------------

    @Test
    void aLongNameIsCutInTheMiddleAndTheColumnStopsAtTheWidthCap() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        String longName =
                "messages_generated_from_the_translation_memory_export_of_the_whole_product_catalogue.properties";
        Path long1 = Files.writeString(root.resolve(longName), "x=y");
        Files.writeString(root.resolve("a.txt"), "a");
        Shown map = open(root, 1000, 600);
        FxTestSupport.runOnFx(() -> {
            Object row = boxFor(map.surface, long1);
            double zoom = FxTestSupport.field(map.surface, "zoom");
            assertEquals(360, origin(row, "width") / zoom, 0.01, "a row is at most 360 px wide");
            String cut = (String) FxTestSupport.call(
                    map.surface,
                    "elideMiddle",
                    new Class<?>[] {String.class, double.class, double.class},
                    longName,
                    12.0,
                    200.0);
            assertTrue(cut.contains("…"));
            assertTrue(cut.startsWith("messages_"), cut);
            assertTrue(cut.endsWith(".properties"), "the extension survives: " + cut);
            assertTrue(
                    (double) FxTestSupport.call(map.surface, "measuredLabelWidth", new Class<?>[] {String.class}, cut)
                            <= 200.0);
        });
        FxTestSupport.runOnFx(() -> {
            assertEquals("ab…yz", middleCut(map.surface, "abcdefwxyz", 4));
            assertEquals("abc", middleCut(map.surface, "abc", 5));
            assertEquals("a…\uD83D\uDE00", middleCut(map.surface, "abc\uD83D\uDE00", 3), "no half emoji");
        });
    }

    private static String middleCut(Region surface, String text, int kept) {
        return (String) FxTestSupport.call(surface, "middleCut", new Class<?>[] {String.class, int.class}, text, kept);
    }

    // ---- B19: faded rows ------------------------------------------------------------------------------

    @Test
    void aFadedLabelRecedesButKeepsAReadableContrastInBothEditoraThemes() throws Exception {
        for (String theme : List.of("editora-light", "editora-dark")) {
            Map<String, String> palette = palette(theme);
            Color ground = color(palette, "-color-bg-subtle");
            Color text = color(palette, "-color-fg-default");
            Color faded = ProjectMapView.fadedInk(text, ground, 3.5, 0.62);
            double contrast = ProjectMapView.contrastRatio(faded, ground);
            assertTrue(contrast >= 3.5, theme + ": " + contrast);
            assertTrue(
                    contrast < ProjectMapView.contrastRatio(text, ground) - 2,
                    theme + ": a faded label is visibly fainter: " + contrast);
        }
        Color weak = Color.web("#999999");
        assertEquals(weak, ProjectMapView.fadedInk(weak, Color.WHITE, 3.5, 0.62), "ink already under the floor stays");
    }

    // ---- B13 / B25: fonts and targets -----------------------------------------------------------------

    @Test
    void theCanvasDrawsInTheUiFontAndTheToolbarFollowsTheUiSize() throws Exception {
        Fonts.load();
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Files.writeString(root.resolve("README.md"), "# P");
        Files.createDirectory(root.resolve("src"));
        Shown map = open(root, 1000, 600, ".root { -fx-font-family: \"Inter\"; -fx-font-size: 14px; }");
        FxTestSupport.runOnFx(() -> {
            Font row = (Font) FxTestSupport.call(map.surface, "rowFont", new Class<?>[] {boolean.class}, false);
            assertEquals("Inter", row.getFamily(), "rows are drawn in the UI family, not the logical System font");
            javafx.scene.text.Text measurer = FxTestSupport.field(map.surface, "textMeasurer");
            assertEquals("Inter", measurer.getFont().getFamily(), "and measured in it");

            ToggleButton chip = FxTestSupport.field(map.view, "openFilter");
            assertEquals(14 * 0.85, chip.getFont().getSize(), 0.01, "chips are 0.85em of the UI size");
            for (Node node : map.view.lookupAll(".project-map-nav-button")) {
                Region target = (Region) node;
                assertTrue(
                        target.getWidth() >= 24 && target.getHeight() >= 24,
                        "a 24x24 target: " + target.getWidth() + "x" + target.getHeight());
            }
            CheckBox hidden = (CheckBox) FxTestSupport.call(
                    keyedColumnValue(map.surface, "columnControls", root), "showHidden", new Class<?>[0]);
            assertEquals(14 * 0.85, hidden.getFont().getSize(), 0.01, "the column's Show hidden is not 9 px");
        });
        String css = read("app.css");
        for (String selector : List.of(
                ".project-map-breadcrumb",
                ".project-map-filter-chip",
                ".project-map-hide-unmatched",
                ".project-map-column-filter",
                ".project-map-column-hidden")) {
            Matcher size = Pattern.compile(Pattern.quote(selector) + "[^{]*\\{[^}]*-fx-font-size:\\s*([^;]+);")
                    .matcher(css);
            assertTrue(size.find(), selector);
            assertEquals("0.85em", size.group(1).strip(), selector);
        }
    }

    // ---- D5 / D8: one draw per pulse, a cached layout -------------------------------------------------

    @Test
    void aBurstOfChangesDrawsTheCanvasOnceAndAPanOrHoverReusesTheLayout() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Path a = Files.createDirectory(root.resolve("a"));
        for (int i = 0; i < 30; i++) {
            Files.writeString(a.resolve(String.format("f%02d.txt", i)), "f");
        }
        Files.writeString(root.resolve("README.md"), "# P");
        Shown map = open(root, 1000, 700);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(map.view, "toggleDirectory", new Class<?>[] {Path.class}, a));
        waitForFx(() -> contains(map.surface, a.resolve("f29.txt")));
        FxTestSupport.drainFx();
        long[] before = new long[2];
        FxTestSupport.runOnFx(() -> {
            before[0] = FxTestSupport.field(map.surface, "completedPaints");
            before[1] = FxTestSupport.field(map.surface, "layoutsComputed");
            // Selections, a reload's worth of state changes and a sweep of the pointer over twenty rows.
            for (int i = 0; i < 20; i++) {
                select(map.surface, a.resolve(String.format("f%02d.txt", i)));
                Object row = boxFor(map.surface, a.resolve(String.format("f%02d.txt", i)));
                fireMouse(map.surface, MouseEvent.MOUSE_MOVED, origin(row, "x") + 20, origin(row, "y") + 10);
                assertEquals(
                        a.resolve(String.format("f%02d.txt", i)),
                        FxTestSupport.field(map.surface, "hovered"),
                        "hit-testing sees the current layout at once");
            }
            map.view.refreshStates();
            assertEquals(
                    before[0],
                    (long) FxTestSupport.field(map.surface, "completedPaints"),
                    "nothing is drawn synchronously");
            assertEquals(
                    before[1],
                    (long) FxTestSupport.field(map.surface, "layoutsComputed"),
                    "a selection or a hover computes no layout");
        });
        waitForFx(() -> !FxTestSupport.<Boolean>field(map.surface, "viewportRepaintPending"));
        FxTestSupport.runOnFx(() -> {
            long paints = FxTestSupport.field(map.surface, "completedPaints");
            assertTrue(paints - before[0] <= 2, "one draw per pulse, not one per change: " + (paints - before[0]));
            // A pan reuses the layout; it is only projected again.
            long layouts = FxTestSupport.field(map.surface, "layoutsComputed");
            FxTestSupport.call(map.surface, "zoomBy", new Class<?>[] {double.class}, 1.0);
            double offset = FxTestSupport.field(map.surface, "offsetX");
            setField(map.surface, "offsetX", offset - 40);
            FxTestSupport.invoke(map.surface, "repaint");
            assertEquals(layouts, (long) FxTestSupport.field(map.surface, "layoutsComputed"));
            // A change of what is laid out does compute one.
            FxTestSupport.call(map.surface, "setCompactChains", new Class<?>[] {boolean.class}, false);
            assertEquals(layouts + 1, (long) FxTestSupport.field(map.surface, "layoutsComputed"));
        });
    }

    // ---- D15: the breadcrumb changes only its tail -----------------------------------------------------

    @Test
    void theBreadcrumbKeepsTheCrumbsTheTwoSelectionsShare() throws Exception {
        Path root = Files.createDirectory(temp.resolve("p")).toRealPath();
        Path a = Files.createDirectory(root.resolve("a"));
        Path one = Files.writeString(a.resolve("one.txt"), "1");
        Path two = Files.writeString(a.resolve("two.txt"), "2");
        Files.writeString(root.resolve("README.md"), "# P");
        Shown map = open(root, 1000, 600);
        FxTestSupport.runOnFx(() -> map.view.revealPath(one));
        waitForFx(() -> one.equals(selected(map.surface)));
        FxTestSupport.runOnFx(() -> {
            HBox crumbs = FxTestSupport.field(map.view, "breadcrumbs");
            List<Node> first = List.copyOf(crumbs.getChildren());
            assertEquals(5, first.size(), "p › a › one.txt");
            assertNull(((Labeled) first.getFirst()).getTooltip(), "a tooltip is made only when it is wanted");

            select(map.surface, two);
            List<Node> second = List.copyOf(crumbs.getChildren());
            assertEquals(5, second.size());
            for (int i = 0; i < 4; i++) {
                assertSame(first.get(i), second.get(i), "the shared crumbs and separators are kept: " + i);
            }
            assertNotSame(first.get(4), second.get(4));
            assertEquals("two.txt", ((Labeled) second.get(4)).getText());

            select(map.surface, two);
            assertEquals(second, List.copyOf(crumbs.getChildren()), "the same selection changes nothing");

            Button rootCrumb = (Button) second.getFirst();
            Event.fireEvent(
                    rootCrumb,
                    new MouseEvent(
                            MouseEvent.MOUSE_ENTERED,
                            1,
                            1,
                            1,
                            1,
                            MouseButton.NONE,
                            0,
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
                            null));
            assertNotNull(rootCrumb.getTooltip());
            assertEquals(root.toString(), rootCrumb.getTooltip().getText());
        });
    }

    // ---- harness ----------------------------------------------------------------------------------------

    private record Shown(Stage stage, ProjectMapView view, Region surface) {}

    private Shown open(Path root, double width, double height) throws Exception {
        return open(root, width, height, null);
    }

    /** A map of {@code root} in a shown, styled window (left to right), loaded and settled. */
    private Shown open(Path root, double width, double height, String extraCss) throws Exception {
        AtomicReference<Shown> holder = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(view), width, height);
            scene.getStylesheets().add(Themes.stylesheetFor("Editora Light"));
            scene.getStylesheets()
                    .add(ProjectMapLayoutReviewFxTest.class
                            .getResource(STYLES + "app.css")
                            .toExternalForm());
            if (extraCss != null) {
                scene.getStylesheets()
                        .add("data:text/css;base64,"
                                + java.util.Base64.getEncoder()
                                        .encodeToString(extraCss.getBytes(StandardCharsets.UTF_8)));
            }
            stage.setScene(scene);
            stage.show();
            view.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});
            view.setRoot(root);
            stages.add(stage);
            views.add(view);
            holder.set(new Shown(stage, view, FxTestSupport.field(view, "surface")));
        });
        Shown map = holder.get();
        waitForFx(
                () -> !FxTestSupport.<List<?>>field(map.surface, "columnBoxes").isEmpty());
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            map.stage.getScene().getRoot().applyCss();
            map.stage.getScene().getRoot().layout();
        });
        return map;
    }

    private static ProjectMapModel.Entry directory(Path path, Path parent, int depth) {
        return new ProjectMapModel.Entry(path, parent, depth, true);
    }

    private static ProjectMapModel.Entry file(Path path, Path parent, int depth) {
        return new ProjectMapModel.Entry(path, parent, depth, false);
    }

    private static ProjectMapModel.Column column(Object columnBox) {
        return (ProjectMapModel.Column) FxTestSupport.call(columnBox, "column", new Class<?>[0]);
    }

    private static boolean contains(Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
    }

    private static Object nodeBox(Region surface, Path path) {
        return FxTestSupport.call(surface, "nodeBox", new Class<?>[] {Path.class}, path);
    }

    private static Object boxFor(Region surface, Path path) {
        Object box = nodeBox(surface, path);
        if (box == null) {
            throw new AssertionError("no row drawn for " + path);
        }
        return box;
    }

    @SuppressWarnings("unchecked")
    private static Path selected(Region surface) {
        return ((Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(FxTestSupport.field(surface, "selected"));
    }

    private static void select(Region surface, Path path) {
        FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, path);
    }

    private static Object keyedColumnValue(Region surface, String field, Path parent) {
        Map<?, ?> values = FxTestSupport.field(surface, field);
        return values.entrySet().stream()
                .filter(entry -> parent.equals(FxTestSupport.call(entry.getKey(), "parent", new Class<?>[0])))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static double origin(Object box, String coordinate) {
        return (double) FxTestSupport.call(box, coordinate, new Class<?>[0]);
    }

    private static void setField(Object target, String name, double value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A plain key press fired at the surface through the scene, as a real key travels. */
    private static void press(Region surface, KeyCode code) {
        Event.fireEvent(surface, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static void fireMouse(Region surface, EventType<MouseEvent> type, double x, double y) {
        Point2D scene = surface.localToScene(x, y);
        Event.fireEvent(
                surface,
                new MouseEvent(
                        type,
                        scene.getX(),
                        scene.getY(),
                        scene.getX(),
                        scene.getY(),
                        MouseButton.NONE,
                        0,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        new PickResult(surface, scene.getX(), scene.getY())));
    }

    private static void fireContextMenu(Region surface, double x, double y, boolean keyboard) {
        Point2D scene = surface.localToScene(x, y);
        Point2D screen = surface.localToScreen(x, y);
        Event.fireEvent(
                surface,
                new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        scene.getX(),
                        scene.getY(),
                        screen == null ? 0 : screen.getX(),
                        screen == null ? 0 : screen.getY(),
                        keyboard,
                        new PickResult(surface, scene.getX(), scene.getY())));
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for the map");
    }

    private static String read(String path) throws Exception {
        try (var in = ProjectMapLayoutReviewFxTest.class.getResourceAsStream(STYLES + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?s)/\\*.*?\\*/", "");
        }
    }

    /** {@code -color-*} tokens of a bundled control theme, aliases resolved by {@link #color}. */
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
}
