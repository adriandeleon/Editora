package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 3, cards: a card's Structure column (eviction, reveal, keys, side), card titles, the opening
 * line, placement clear of the zoom bar and overview, open cards in the overview and the ⋯ menu.
 */
@Tag("fx")
class ProjectMapCardsReview3FxTest {

    /** Twelve methods after a licence header: more declarations than the eight-card limit. */
    static String source() {
        StringBuilder text = new StringBuilder();
        text.append(
                "/*\n * Copyright 2026 The Authors.\n *\n * Licensed under the Apache License, Version 2.0.\n */\n");
        text.append("\npackage demo;\n\npublic class Big {\n");
        for (int i = 0; i < 12; i++) {
            text.append("\n    int m").append(i).append("(int a) {\n");
            for (int j = 0; j < 6; j++) {
                text.append("        a += ").append(j).append(";\n");
            }
            text.append("        return a;\n    }\n");
        }
        text.append("}\n");
        return text.toString();
    }

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;

    ProjectMapCardsReview3FxTest() throws Exception {}

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

    // --- C1 / D2 ----------------------------------------------------------------------------------------

    @Test
    void eightDeclarationsOpenedByKeyFromACardsStructureColumnKeepTheFileCardAndItsColumn() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapCardStructure column = showStructure(card);
        FxTestSupport.runOnFx(column::focus);
        for (int i = 0; i < 10; i++) {
            int index = i;
            FxTestSupport.runOnFx(() -> {
                key(column.tree(), KeyCode.DOWN, false);
                key(column.tree(), KeyCode.ENTER, false); // Enter: the declaration's own card
            });
            await("declaration card " + index, () -> previews().size() == Math.min(8, index + 2));
        }
        FxTestSupport.drainFx(); // an orphan check would run now
        FxTestSupport.runOnFx(() -> {
            assertSame(card, previews().get(big), "the file card is never the one evicted");
            assertSame(column, cardStructures().get(card), "nor its Structure column");
            assertEquals(8, previews().size(), "seven declaration cards beside the file card");
            assertTrue(column.contains(map.scene().getFocusOwner()), "the keyboard stays in the column");
            Path last = column.tree()
                    .getSelectionModel()
                    .getSelectedItem()
                    .getValue()
                    .node()
                    .path();
            assertTrue(previews().containsKey(last), "the declaration just opened is there");
        });
    }

    @Test
    void theOldestCardOfAnotherGroupGoesBeforeAColumnsDeclarationCards() throws Exception {
        Path big = writeBig();
        Path other = Files.writeString(root.resolve("other.txt"), "other\n");
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapCardStructure column = showStructure(card);
        ProjectMapPreview plain = openFileCard(other);
        FxTestSupport.runOnFx(column::focus);
        for (int i = 0; i < 7; i++) {
            FxTestSupport.runOnFx(() -> {
                key(column.tree(), KeyCode.DOWN, false);
                key(column.tree(), KeyCode.ENTER, false);
            });
        }
        await("eight cards", () -> previews().size() == 8);
        FxTestSupport.runOnFx(() -> {
            assertFalse(previews().containsValue(plain), "the unrelated card, used least, went");
            assertSame(card, previews().get(big));
        });
    }

    // --- E8 ---------------------------------------------------------------------------------------------

    @Test
    void aClickOrEnterInACardsStructureColumnOpensTheDeclarationsCardAndShiftScrollsTheCardToIt() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapCardStructure column = showStructure(card);
        String before = FxTestSupport.callOnFx(() -> card.editor().getSelectedText());
        FxTestSupport.runOnFx(() -> {
            column.focus();
            clickRow(column, "m9(int)", false);
        });
        await(
                "a click opens the declaration's own card (Map Structure previews)",
                () -> previews().size() == 2);
        FxTestSupport.runOnFx(() -> {
            assertEquals(before, card.editor().getSelectedText(), "the file card is left as it was");
            selectRow(column, "m2(int)");
            key(column.tree(), KeyCode.ENTER, false);
        });
        await("so does Enter", () -> previews().size() == 3);
        int line = lineOf(source(), "int m5(");
        FxTestSupport.runOnFx(() -> {
            assertTrue(column.contains(map.scene().getFocusOwner()), "the keyboard stays in the column");
            clickRow(column, "m5(int)", true);
        });
        await("Shift+click scrolls the card to m5", () -> firstVisible(card) <= line && line <= lastVisible(card));
        FxTestSupport.runOnFx(() -> {
            assertEquals(3, previews().size(), "no declaration card was opened");
            String selected = card.editor().getSelectedText();
            assertTrue(selected.startsWith("    int m5(int a) {"), selected);
            assertTrue(selected.strip().endsWith("}"), "the declaration's lines are marked: " + selected);
            selectRow(column, "m7(int)");
            key(column.tree(), KeyCode.ENTER, true);
        });
        int second = lineOf(source(), "int m7(");
        await("Shift+Enter scrolls too", () -> firstVisible(card) <= second && second <= lastVisible(card));
        FxTestSupport.runOnFx(() -> assertEquals(3, previews().size()));
    }

    // --- D7 ---------------------------------------------------------------------------------------------

    @Test
    void theStructureCommandAndTheCardMenuReachACardsColumnAndItsEmacsMovesWork() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            card.focusContent();
            // The Structure command typed in a card (Alt+7 in the Emacs and IntelliJ keymaps) is the card's own.
            assertTrue(card.runMarkerCommand(ProjectMapPreview.STRUCTURE_COMMAND));
            assertTrue(card.structureShown());
        });
        ProjectMapCardStructure column = showStructure(card);
        await(
                "the keyboard is in the column",
                () -> column.contains(map.scene().getFocusOwner())
                        && !column.tree().getSelectionModel().isEmpty());
        FxTestSupport.runOnFx(() -> {
            int before = column.tree().getSelectionModel().getSelectedIndex();
            Event.fireEvent(
                    column.tree(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, true, false, false));
            assertEquals(before + 1, column.tree().getSelectionModel().getSelectedIndex(), "C-n moves down");
            Event.fireEvent(
                    column.tree(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.P, false, true, false, false));
            assertEquals(before, column.tree().getSelectionModel().getSelectedIndex(), "C-p moves up");
            @SuppressWarnings("unchecked")
            var back = (java.util.function.Predicate<String>)
                    column.node().getProperties().get(com.editora.command.KeyDispatcher.HANDLED_COMMANDS);
            assertTrue(back.test(ProjectMapPreview.STRUCTURE_COMMAND), "typed in the column it goes back");
            assertSame(card.editor(), map.scene().getFocusOwner());

            FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
            javafx.scene.control.ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");
            javafx.scene.control.MenuItem hide = ProjectMapPreview.flatten(menu.getItems()).stream()
                    .filter(item -> com.editora.i18n.Messages.tr("project.map.preview.hideStructure")
                            .equals(item.getText()))
                    .findFirst()
                    .orElseThrow();
            hide.fire();
            assertFalse(card.structureShown(), "the card menu takes it off");
            FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
            ProjectMapPreview.flatten(menu.getItems()).stream()
                    .filter(item -> com.editora.i18n.Messages.tr("project.map.preview.showStructure")
                            .equals(item.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
            assertTrue(card.structureShown(), "and puts it back");
        });
    }

    // --- B18 / C11 ---------------------------------------------------------------------------------------

    @Test
    void aCodeCardOpensPastItsLicenceHeaderAndItsTitleOutlastsTheStatus() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path file = Files.writeString(root.resolve("ReconcilerControllerManager.java"), source());
        map = openMap();
        ProjectMapPreview card = openFileCard(file);
        int opening = lineOf(source(), "package demo;");
        await("the card opens on the package line", () -> firstVisible(card) == opening);
        FxTestSupport.runOnFx(() -> {
            assertEquals(opening, card.editor().getCurrentParagraph(), "the caret starts there too");
            javafx.scene.control.Label status = FxTestSupport.field(card, "status");
            status.setText("Preview truncated · Colours for the first 3048 lines · Unsaved changes in the editor");
            card.resize(ProjectMapPreview.MIN_WIDTH, card.getHeight());
            card.applyCss();
            card.layout();
            javafx.scene.layout.HBox bar = FxTestSupport.field(card, "titleBar");
            bar.layout();
            javafx.scene.control.Label title = FxTestSupport.field(card, "title");
            javafx.scene.text.Text name = new javafx.scene.text.Text("ReconcilerControllerManager.java");
            name.setFont(title.getFont());
            double wanted = Math.min(name.getLayoutBounds().getWidth(), ProjectMapPreview.MIN_WIDTH * 0.5);
            assertTrue(title.getWidth() >= wanted - 1, "the file's name keeps its room: " + title.getWidth());
            assertTrue(status.getWidth() < title.getWidth(), "the status gives way first");
            assertNotNull(status.getTooltip(), "and is read in full from its tooltip");
            javafx.scene.control.Button open = FxTestSupport.field(card, "open");
            // At least its preferred width (macOS snaps the laid-out width up to whole pixels).
            assertTrue(
                    open.getWidth() >= open.prefWidth(-1) - 0.5,
                    "Open is never cut to O…: " + open.getWidth() + " < " + open.prefWidth(-1));
        });
    }

    // --- D5 ---------------------------------------------------------------------------------------------

    @Test
    void aPanFrameOrADragDrawsTheCardConnectorsOnceHoweverManyCardsAndAttachmentsMoved() throws Exception {
        Path big = writeBig();
        List<Path> others = List.of(
                Files.writeString(root.resolve("a.txt"), "a\n"),
                Files.writeString(root.resolve("b.txt"), "b\n"),
                Files.writeString(root.resolve("c.txt"), "c\n"));
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        showStructure(card);
        for (Path other : others) {
            openFileCard(other);
        }
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            MapCards cards = FxTestSupport.field(map.view(), "cards");
            long before = cards.connectorDraws;
            double offset = FxTestSupport.field(map.surface(), "offsetX");
            setDouble(map.surface(), "offsetX", offset + 40); // a pan frame: four cards and a column move
            FxTestSupport.invoke(map.surface(), "paintNow");
            assertEquals(before + 1, cards.connectorDraws, "one connector draw for the frame");

            long dragged = cards.connectorDraws;
            for (int i = 1; i <= 5; i++) {
                card.relocate(card.getLayoutX() + 3, card.getLayoutY()); // five drag events in one pulse
            }
            assertEquals(dragged, cards.connectorDraws, "nothing drawn per event");
            map.view().layout(); // the pulse's layout pass
            assertEquals(dragged + 1, cards.connectorDraws, "one draw for all of them");
        });
    }

    // --- D8 ---------------------------------------------------------------------------------------------

    @Test
    void anOutlineThatFeedsOnlyACardsColumnLeavesTheMapsRowsAlone() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapCardStructure column = showStructure(card);
        Object rowsBefore = FxTestSupport.callOnFx(() -> FxTestSupport.field(map.surface(), "entryByPath"));
        Files.writeString(big, source().replace("int m3(int a) {", "int m3(int a) {\n    }\n\n    void added() {"));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(map.view(), "refreshCards", new Class<?>[] {boolean.class}, false));
        await("the card's column follows the edit", () -> labels(column).contains("added()"));
        FxTestSupport.runOnFx(() -> assertSame(
                rowsBefore,
                FxTestSupport.field(map.surface(), "entryByPath"),
                "the map's row list was not rebuilt for an outline only the card shows"));
    }

    // --- C9 / E11 ---------------------------------------------------------------------------------------

    @Test
    void aCardsStructureColumnStaysOnItsRightWhenItsCardIsDraggedToTheEdge() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapCardStructure column = showStructure(card);
        FxTestSupport.runOnFx(() -> {
            assertEquals(CardAttachment.Side.RIGHT, column.side(), "on the card's right");
            Region host = (Region) card.getParent();
            double width = card.getWidth() * card.mapScale();
            card.relocate(host.getWidth() - width - 10, card.getLayoutY()); // dragged against the right edge
            map.view().layout(); // the pulse's layout pass
            assertEquals(CardAttachment.Side.RIGHT, column.side(), "still on its right (Map Structure right)");
            double[] drawn = column.bounds();
            assertTrue(drawn[0] >= card.getLayoutX() + width, "right of the card, past the map area if need be");
            card.relocate(14, card.getLayoutY()); // and against the left edge
            map.view().layout();
            assertEquals(CardAttachment.Side.RIGHT, column.side());
        });
    }

    @Test
    void aStructureColumnOpenedOnACardAgainstTheRightEdgeStepsTheCardLeftToFit() throws Exception {
        Path big = writeBig();
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        FxTestSupport.runOnFx(() -> {
            Region host = (Region) card.getParent();
            card.relocate(host.getWidth() - card.getWidth() * card.mapScale() - 10, card.getLayoutY());
        });
        ProjectMapCardStructure column = showStructure(card);
        FxTestSupport.runOnFx(() -> {
            Region host = (Region) card.getParent();
            double[] drawn = column.bounds();
            assertEquals(CardAttachment.Side.RIGHT, column.side(), "on the card's right, not its left");
            assertTrue(drawn[0] >= card.getLayoutX() + card.getWidth() * card.mapScale(), "right of the card");
            assertTrue(
                    drawn[0] + drawn[2] <= host.getWidth() - ProjectMapPreview.EDGE_MARGIN + 0.5,
                    "inside the map area: the card stepped left for it");
        });
    }

    @Test
    void cardPlacementKeepsClearOfTheZoomBarAndTheOverview() throws Exception {
        double[] zoomBar = {0, 640, 260, 60, MapCards.CHROME_WEIGHT};
        double[] overview = {840, 600, 150, 90, MapCards.CHROME_WEIGHT};
        double[] row = {30, 610, 164, 24};
        double[] column = {20, 20, 180, 660};
        FxTestSupport.runOnFx(() -> {
            javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
            pane.resize(1000, 700);
            MapCards cards = new MapCards(
                    new StubHost(
                            new MapCards.Site(row, column, column, List.of(new double[] {20, 20, 180, 660, 6})),
                            List.of(zoomBar, overview)),
                    new ProjectMapStructure(path -> null),
                    pane);
            Region card = new Region();
            ProjectMapPreview.Placement placed = cards.previewPlacement(
                    card, Path.of("/p/a.txt"), 640, 420, 340, 220, pane.getWidth(), pane.getHeight());
            for (double[] chrome : List.of(zoomBar, overview)) {
                assertEquals(
                        0,
                        MapCards.cardOverlapCost(
                                placed.x(), placed.y(), placed.width(), placed.height(), List.of(chrome)),
                        1e-9,
                        "covers nothing of " + java.util.Arrays.toString(chrome) + ": " + placed);
            }
            assertTrue(placed.height() >= 220 && placed.width() >= 340, "and keeps at least its minimum: " + placed);

            // A row that is not on the map: the far edge, ending above the overview.
            MapCards off = new MapCards(
                    new StubHost(
                            new MapCards.Site(null, new double[4], new double[4], List.of()),
                            List.of(zoomBar, new double[] {700, 300, 290, 390, MapCards.CHROME_WEIGHT})),
                    new ProjectMapStructure(path -> null),
                    pane);
            ProjectMapPreview.Placement far = off.previewPlacement(
                    card, Path.of("/p/b.txt"), 640, 420, 340, 220, pane.getWidth(), pane.getHeight());
            assertTrue(far.y() + far.height() <= 300, "above the overview: " + far);
        });
    }

    // --- C10 --------------------------------------------------------------------------------------------

    @Test
    void openCardsShowInTheOverviewAndTheOptionsMenuAndEitherBringsOneBack() throws Exception {
        Path big = writeBig();
        Path other = Files.writeString(root.resolve("other.txt"), "other\n");
        map = openMap();
        ProjectMapPreview card = openFileCard(big);
        ProjectMapPreview second = openFileCard(other);
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "panBy", new Class<?>[] {double.class, double.class}, -4000.0, -3000.0);
            FxTestSupport.invoke(map.surface(), "paintNow");
            Region host = (Region) card.getParent();
            assertTrue(card.getLayoutX() + card.getWidth() < 0, "the map moved on: the cards went with it");

            // The options menu lists them, the most recently used first, and brings one back.
            FxTestSupport.call(map.view(), "fillOpenCardsMenu", new Class<?>[0]);
            javafx.scene.control.Menu menu = FxTestSupport.field(map.view(), "openCardsMenu");
            List<String> labels = menu.getItems().stream()
                    .map(javafx.scene.control.MenuItem::getText)
                    .toList();
            assertEquals(List.of("other.txt", "Big.java"), labels);
            menu.getItems().get(1).fire();
            assertInside(card, host);
            assertSame(card.editor(), map.scene().getFocusOwner(), "with the keyboard in it");

            // The overview marks each card; a press on a mark brings that card back.
            FxTestSupport.call(map.surface(), "panBy", new Class<?>[] {double.class, double.class}, 3000.0, 2500.0);
            FxTestSupport.invoke(map.surface(), "paintNow");
            Object overview = FxTestSupport.field(map.surface(), "overviewBox");
            assertNotNull(overview, "the map is off screen: the overview shows");
            double[] mark = (double[]) FxTestSupport.call(
                    map.surface(),
                    "overviewMark",
                    new Class<?>[] {overview.getClass(), double[].class},
                    overview,
                    MapCards.drawnBounds(second));
            double ox = (double) FxTestSupport.call(overview, "x", new Class<?>[0]);
            double oy = (double) FxTestSupport.call(overview, "y", new Class<?>[0]);
            javafx.scene.Node canvas = FxTestSupport.field(map.surface(), "overviewCanvas");
            double x = mark[0] + mark[2] / 2;
            double y = mark[1] + mark[3] / 2;
            javafx.geometry.Point2D scene = canvas.localToScene(x, y);
            Event.fireEvent(
                    canvas,
                    new MouseEvent(
                            MouseEvent.MOUSE_PRESSED,
                            x,
                            y,
                            scene.getX(),
                            scene.getY(),
                            MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            false,
                            false,
                            true,
                            new PickResult(canvas, scene.getX(), scene.getY())));
            assertTrue(ox >= 0 && oy >= 0);
            assertInside(second, host);
        });
    }

    private static void assertInside(Region card, Region host) {
        double[] drawn = MapCards.drawnBounds(card);
        assertTrue(
                drawn[0] >= 0
                        && drawn[1] >= 0
                        && drawn[0] + drawn[2] <= host.getWidth()
                        && drawn[1] + drawn[3] <= host.getHeight(),
                "the card is back in view: " + java.util.Arrays.toString(drawn));
    }

    /** A map with one row's site and the chrome given, for placement alone. */
    private record StubHost(MapCards.Site site, List<double[]> chrome) implements MapCards.Host {
        @Override
        public double[] rowBox(Path path) {
            return site.row();
        }

        @Override
        public MapCards.Site site(Path path) {
            return site;
        }

        @Override
        public List<double[]> columns() {
            return List.of();
        }

        @Override
        public double zoom() {
            return 1;
        }

        @Override
        public boolean horizontalFlow() {
            return true;
        }

        @Override
        public boolean narrowsAway(Path file) {
            return false;
        }

        @Override
        public java.util.Set<Path> expandedSnapshot() {
            return java.util.Set.of();
        }

        @Override
        public Path root() {
            return Path.of("/p");
        }

        @Override
        public javafx.scene.paint.Color accent() {
            return javafx.scene.paint.Color.BLUE;
        }

        @Override
        public javafx.scene.paint.Color note() {
            return javafx.scene.paint.Color.ORANGE;
        }

        @Override
        public void focusMap() {}

        @Override
        public boolean onScreen() {
            return true;
        }

        @Override
        public void status(String message) {}

        @Override
        public ProjectMapPreview.Content content(Path file) {
            return null;
        }

        @Override
        public boolean isOpen(Path file) {
            return false;
        }

        @Override
        public boolean isModified(Path file) {
            return false;
        }

        @Override
        public javafx.scene.control.ContextMenu symbolMenu(ProjectMapModel.Entry entry, Runnable preview) {
            return new javafx.scene.control.ContextMenu();
        }

        @Override
        public boolean hideOpenNotes() {
            return false;
        }

        @Override
        public void showOpenNotes() {}

        @Override
        public void openFile(Path file) {}

        @Override
        public void openAt(Path file, int line) {}

        @Override
        public void openNarrowed(Path file, com.editora.text.FoldRegions.Region block) {}

        @Override
        public void panBy(double dx, double dy) {}
    }

    private static void setDouble(Object target, String name, double value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // --- fixture ----------------------------------------------------------------------------------------

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, Region surface) {}

    private Path writeBig() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        return Files.writeString(root.resolve("Big.java"), source());
    }

    private Fixture openMap() throws Exception {
        if (root == null) {
            root = Files.createDirectories(temp.resolve("project")).toRealPath();
        }
        Path project = root;
        Fixture fixture = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapCardsReview3FxTest.class
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
        return fixture;
    }

    private ProjectMapPreview openFileCard(Path file) throws Exception {
        await("row " + file.getFileName(), () -> rows().contains(file));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, file);
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, file);
        });
        await(
                "card " + file.getFileName(),
                () -> previews().containsKey(file)
                        && !previews().get(file).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(file));
    }

    private ProjectMapCardStructure showStructure(ProjectMapPreview card) throws Exception {
        FxTestSupport.runOnFx(() -> card.setStructureShown(true));
        await("the column lists the methods", () -> {
            ProjectMapCardStructure column = cardStructures().get(card);
            return column != null && labels(column).contains("m11(int)");
        });
        return FxTestSupport.callOnFx(() -> cardStructures().get(card));
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

    private Map<ProjectMapPreview, ProjectMapCardStructure> cardStructures() {
        return FxTestSupport.field(map.view(), "cardStructures");
    }

    private static List<String> labels(ProjectMapCardStructure column) {
        return column.visibleRows().stream()
                .map(row ->
                        row.node() == null ? row.message() : row.node().symbol().label())
                .toList();
    }

    private static void selectRow(ProjectMapCardStructure column, String label) {
        var tree = column.tree();
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            ProjectMapStructure.Node node = tree.getTreeItem(i).getValue().node();
            if (node != null && node.symbol().label().equals(label)) {
                tree.getSelectionModel().select(i);
                tree.scrollTo(i);
                tree.layout();
                return;
            }
        }
        throw new AssertionError("no row " + label);
    }

    /** A real press, release and click on the row's cell, through the scene's dispatch chain. */
    private static void clickRow(ProjectMapCardStructure column, String label, boolean shift) {
        selectRow(column, label);
        column.tree().applyCss();
        column.tree().layout();
        TreeCell<?> cell = column.tree().lookupAll(".tree-cell").stream()
                .filter(node -> node instanceof TreeCell<?> c
                        && !c.isEmpty()
                        && c.getItem() instanceof ProjectMapCardStructure.Row row
                        && row.node() != null
                        && row.node().symbol().label().equals(label))
                .map(node -> (TreeCell<?>) node)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cell " + label));
        double x = cell.getWidth() - 20;
        double y = cell.getHeight() / 2;
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            javafx.geometry.Point2D scene = cell.localToScene(x, y);
            Event.fireEvent(
                    cell,
                    new MouseEvent(
                            type,
                            x,
                            y,
                            scene.getX(),
                            scene.getY(),
                            MouseButton.PRIMARY,
                            1,
                            shift,
                            false,
                            false,
                            false,
                            type == MouseEvent.MOUSE_PRESSED,
                            false,
                            false,
                            false,
                            false,
                            true,
                            new PickResult(cell, scene.getX(), scene.getY())));
        }
    }

    static void key(Node target, KeyCode code, boolean shift) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    private static int lineOf(String text, String needle) {
        int at = text.indexOf(needle);
        return (int) text.substring(0, at).chars().filter(c -> c == '\n').count();
    }

    private static int firstVisible(ProjectMapPreview card) {
        try {
            return card.editor().firstVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return -1;
        }
    }

    private static int lastVisible(ProjectMapPreview card) {
        try {
            return card.editor().lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return -1;
        }
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }
}
