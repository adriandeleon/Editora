package com.editora.ui;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
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
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import com.editora.command.KeyDispatcher;
import com.editora.git.GitFileStatus;
import com.editora.i18n.Messages;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review's navigation findings (E1, B2–B7, B12, B16, B17, B22–B24, B26, C8, C18, E8): reveal,
 * selection, history, overview, column drag, hit-testing order, Tab, accessibility, the help popover and the
 * Map's row menu. Pointer input goes through the headless Glass robot where picking matters (what is on top),
 * and every key is fired at a node of a shown scene, so the scene's filters see it first; the window tests
 * add the real {@code KeyDispatcher}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapNavReviewFxTest {

    @TempDir
    Path temp;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // --- a map on its own in a shown scene ---

    /** {@code project/} with {@code a/f0…f9.txt}, {@code b/x.txt}, {@code many/m000…}, and {@code README.md}. */
    private record Harness(
            Stage stage,
            ProjectMapView view,
            Region surface,
            Path project,
            Path a,
            Path b,
            Path many,
            Path readme,
            List<Path> opened,
            List<String> statuses)
            implements AutoCloseable {

        Path file(int index) {
            return a.resolve("f" + index + ".txt");
        }

        Path item(int index) {
            return many.resolve(String.format("m%03d.txt", index));
        }

        Scene scene() {
            return stage.getScene();
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open(int manyFiles) throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 10; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i);
        }
        Path b = Files.createDirectory(project.resolve("b"));
        Files.writeString(b.resolve("x.txt"), "x");
        Path many = Files.createDirectory(project.resolve("many"));
        for (int i = 0; i < manyFiles; i++) {
            Files.writeString(many.resolve(String.format("m%03d.txt", i)), "m");
        }
        Path readme = Files.writeString(project.resolve("README.md"), "# hello\n\n## part\n");
        List<Path> opened = new CopyOnWriteArrayList<>();
        List<String> statuses = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(opened::add, path -> false, path -> false);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 900, 700));
            stage.show();
            view.setRoot(project);
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            return new Harness(stage, view, surface, project, a, b, many, readme, opened, statuses);
        });
        waitForFx(() -> contains(h.surface(), readme));
        FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(0)));
        waitForFx(() -> contains(h.surface(), h.file(0)) && h.file(0).equals(selected(h.surface())));
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(h.surface(), "resetViewport");
            h.scene().getRoot().applyCss();
            h.scene().getRoot().layout();
            h.surface().requestFocus();
        });
        return h;
    }

    // E1 / B1

    @Test
    void aSelectionThatHadToBeLoadedFirstIsBroughtIntoView() throws Exception {
        try (Harness h = open(150)) {
            // A reveal of a row whose folder is closed (Back/Forward and breadcrumb crumbs take this path too).
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.item(140)));
            waitForFx(() -> h.item(140).equals(selected(h.surface())));
            settle();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                assertInView(h.surface(), h.item(140), "a revealed row that had to be loaded");
                List<Path> history = FxTestSupport.field(h.view(), "selectionHistory");
                assertEquals(h.item(140), history.getLast(), "and it is part of the history");
            });

            // A search's first match, in a folder the search has to open.
            FxTestSupport.runOnFx(() -> closeButtonOf(h.surface(), h.many()).fire());
            waitForFx(() -> !contains(h.surface(), h.item(140)));
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "resetViewport");
                h.view().setQuery("m120");
                h.view().setSearchMatches("m120", List.of(h.item(120)));
            });
            waitForFx(() -> h.item(120).equals(selected(h.surface())));
            settle();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                assertInView(h.surface(), h.item(120), "a search's first match");
            });
        }
    }

    // B16

    @Test
    void hideUnmatchedMovesTheSelectionToANearbyDrawnRowAndStepsStartWhereTheRowWas() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(3));
                h.view()
                        .setGitStatus(Map.of(
                                h.file(1), GitFileStatus.MODIFIED,
                                h.file(5), GitFileStatus.MODIFIED,
                                h.file(8), GitFileStatus.MODIFIED));
                FxTestSupport.<ToggleButton>field(h.view(), "gitFilter").fire();
                FxTestSupport.<CheckBox>field(h.view(), "hideUnmatched").setSelected(true);
                assertEquals(h.file(5), selected(h.surface()), "the nearest drawn row of its column (after it first)");
                assertNotNull(boxFor(h.surface(), h.file(5)));

                // A selection that arrives hidden (a pending selection): an arrow starts from where it would be.
                FxTestSupport.call(h.surface(), "setSelected", new Class<?>[] {Path.class}, h.file(3));
                press(h.surface(), KeyCode.DOWN);
                assertEquals(h.file(5), selected(h.surface()), "Down goes to the first drawn row after it");
                FxTestSupport.call(h.surface(), "setSelected", new Class<?>[] {Path.class}, h.file(3));
                press(h.surface(), KeyCode.UP);
                assertEquals(h.file(1), selected(h.surface()), "Up to the first drawn row before it");
                FxTestSupport.call(h.surface(), "setSelected", new Class<?>[] {Path.class}, h.file(9));
                press(h.surface(), KeyCode.DOWN);
                assertEquals(h.file(1), selected(h.surface()), "and a single step past the end wraps");

                // Nothing left in its column: its folder, the nearest drawn ancestor.
                h.view().setGitStatus(Map.of(h.readme(), GitFileStatus.MODIFIED));
                assertEquals(h.project(), selected(h.surface()));
            });
        }
    }

    // B17

    @Test
    void loadingMoreContinuesFromTheFirstNewRowAndTheTrailNeverEndsInAPlaceholder() throws Exception {
        try (Harness h = open(350)) {
            Path moreRow = h.many().resolve(ProjectMapModel.PLACEHOLDER_NAME);
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.item(0)));
            waitForFx(() -> contains(h.surface(), moreRow));
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), moreRow);
                HBox crumbs = FxTestSupport.field(h.view(), "breadcrumbs");
                List<String> trail = crumbs.getChildren().stream()
                        .filter(node -> node instanceof Button)
                        .map(node -> ((Button) node).getText())
                        .toList();
                assertEquals("many", trail.getLast(), "the trail stops at the folder: " + trail);
                h.surface().requestFocus();
                press(h.surface(), KeyCode.ENTER);
            });
            waitForFx(() -> contains(h.surface(), h.item(349)));
            settle();
            FxTestSupport.runOnFx(() -> {
                assertEquals(h.item(300), selected(h.surface()), "the first row that arrived");
                FxTestSupport.invoke(h.surface(), "repaint");
                assertInView(h.surface(), h.item(300), "and it is in view");
            });
        }
    }

    // B26

    @Test
    void clearingTheSearchReturnsToThePreSearchSelectionUnlessTheUserPickedAnother() throws Exception {
        try (Harness h = open(0)) {
            Path x = h.b().resolve("x.txt");
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(7));
                h.view().setQuery("x");
                h.view().setSearchMatches("x", List.of(x));
            });
            waitForFx(() -> x.equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> h.view().setQuery(""));
            waitForFx(() -> !contains(h.surface(), x));
            settle();
            assertEquals(h.file(7), FxTestSupport.callOnFx(() -> selected(h.surface())), "back to where it was");

            // A row the user chose during the search, and that stays on the map, is kept.
            FxTestSupport.runOnFx(() -> {
                h.view().setQuery("x");
                h.view().setSearchMatches("x", List.of(x));
            });
            waitForFx(() -> x.equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.readme());
                h.view().setQuery("");
            });
            waitForFx(() -> !contains(h.surface(), x));
            settle();
            assertEquals(h.readme(), FxTestSupport.callOnFx(() -> selected(h.surface())));
        }
    }

    // B2 / B3

    @Test
    void theOverviewHugsTheMapClampsItsClicksAndStepsAsideInASmallWindow() throws Exception {
        try (Harness h = open(150)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.item(10)));
            waitForFx(() -> h.item(10).equals(selected(h.surface())));
            settle();
            Canvas overview = FxTestSupport.callOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                return FxTestSupport.field(h.surface(), "overviewCanvas");
            });
            FxTestSupport.runOnFx(() -> {
                assertTrue(overview.isVisible(), "a 150-row column runs off-screen: the overview is shown");
                assertTrue(overview.getWidth() <= 150 && overview.getHeight() <= 86);
                assertTrue(
                        overview.getWidth() < 100,
                        "one tall column makes a narrow frame, not a wide empty one: " + overview.getWidth());
            });
            // A robot press on the frame's very corner, past the drawn map: the view stays on the map.
            Point2D corner = FxTestSupport.callOnFx(() -> overview.localToScreen(1, 1));
            FxTestSupport.runOnFx(() -> {
                Robot robot = new Robot();
                robot.mouseMove(corner.getX(), corner.getY());
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseRelease(MouseButton.PRIMARY);
            });
            settle();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                assertTrue(anyColumnInView(h.surface()), "a press at the frame's corner keeps a column in view");
            });

            // Dragging inside the overview moves the view along.
            double before = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetY"));
            Bounds frame = FxTestSupport.callOnFx(() -> overview.localToScreen(overview.getBoundsInLocal()));
            FxTestSupport.runOnFx(() -> {
                Robot robot = new Robot();
                double cx = frame.getCenterX();
                robot.mouseMove(cx, frame.getMinY() + 4);
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseMove(cx, frame.getMinY() + frame.getHeight() / 2);
                robot.mouseMove(cx, frame.getMaxY() - 4);
            });
            settle();
            FxTestSupport.runOnFx(() -> new Robot().mouseRelease(MouseButton.PRIMARY));
            settle();
            double after = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetY"));
            assertTrue(
                    after < before - 100, "dragging down the overview scrolls down the map: " + before + " → " + after);

            // A small window: the overview would cover too much of the map, and is left out.
            FxTestSupport.runOnFx(() -> {
                h.stage().setWidth(560);
                h.stage().setHeight(380);
            });
            settle();
            FxTestSupport.runOnFx(() -> {
                h.scene().getRoot().layout();
                FxTestSupport.invoke(h.surface(), "repaint");
                assertFalse(overview.isVisible());
                assertTrue(FxTestSupport.<Object>field(h.surface(), "overviewBox") == null);
            });
        }
    }

    // C8 / B3

    @Test
    void theZoomBarAndTheOverviewStayAboveCardsAndTakeTheirClicks() throws Exception {
        try (Harness h = open(150)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.item(10)));
            waitForFx(() -> h.item(10).equals(selected(h.surface())));
            settle();
            Region card = FxTestSupport.callOnFx(() -> {
                select(h.surface(), h.readme());
                FxTestSupport.call(h.view(), "previewSelection", new Class<?>[] {Path.class}, h.readme());
                Map<Path, ProjectMapPreview> previews = FxTestSupport.field(h.view(), "previews");
                ProjectMapPreview preview = previews.values().iterator().next();
                // Moved over the bottom of the map, where the zoom bar and the overview are, and raised.
                preview.relocate(0, 0);
                preview.toFront();
                return preview;
            });
            settle();
            Button zoomOut = FxTestSupport.callOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                card.resize(h.surface().getWidth(), h.surface().getHeight());
                card.toFront();
                HBox bar = (HBox) h.view().lookup(".project-map-zoom");
                assertFalse(bar.isPickOnBounds(), "only the buttons take clicks, not the gaps");
                return (Button) bar.getChildren().getFirst();
            });
            settle();
            double zoomBefore = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "zoom"));
            FxTestSupport.runOnFx(() -> robotClick(zoomOut));
            settle();
            double zoomAfter = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "zoom"));
            assertEquals(zoomBefore * 0.9, zoomAfter, 0.001, "Zoom out was reached through the card over it");

            Canvas overview = FxTestSupport.callOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                return FxTestSupport.field(h.surface(), "overviewCanvas");
            });
            assertTrue(FxTestSupport.callOnFx(overview::isVisible));
            double offsetBefore = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetY"));
            Bounds frame = FxTestSupport.callOnFx(() -> overview.localToScreen(overview.getBoundsInLocal()));
            FxTestSupport.runOnFx(() -> {
                Robot robot = new Robot();
                robot.mouseMove(frame.getCenterX(), frame.getMaxY() - 3);
                robot.mouseClick(MouseButton.PRIMARY);
            });
            settle();
            double offsetAfter = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetY"));
            assertNotEquals(offsetBefore, offsetAfter, 0.5, "the overview took the click, not the card under it");
        }
    }

    // B4

    @Test
    void aColumnPushedBackByItsNeighbourMovesAtOnceWhenDraggedTheOtherWay() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.b().resolve("x.txt")));
            waitForFx(() -> contains(h.surface(), h.b().resolve("x.txt")));
            settle();
            double startY = FxTestSupport.callOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "resetViewport");
                FxTestSupport.invoke(h.surface(), "repaint");
                return origin(columnBoxForParent(h.surface(), h.b()), "y");
            });
            // Up into the a/ column above it: the packing holds it where it is.
            robotDrag(h.surface(), h.b(), -250);
            double blockedY = FxTestSupport.callOnFx(() -> origin(columnBoxForParent(h.surface(), h.b()), "y"));
            assertEquals(startY, blockedY, 1.0, "the packed neighbour holds it");
            // Back down: it follows at once instead of first unwinding the hidden 250 px.
            // (60 px: the robot pointer must stay inside the 700 px window for every step to arrive.)
            robotDrag(h.surface(), h.b(), 60);
            double movedY = FxTestSupport.callOnFx(() -> origin(columnBoxForParent(h.surface(), h.b()), "y"));
            assertEquals(startY + 60, movedY, 2.0, "moved " + (movedY - startY) + ", expected 60");
        }
    }

    // B24 / B12

    @Test
    void aDragFromARowPansAndAClickStillOpensUnlessSingleClickIsOff() throws Exception {
        try (Harness h = open(0)) {
            Point2D row = FxTestSupport.callOnFx(() -> rowCenterOnScreen(h.surface(), h.readme()));
            double before = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetX"));
            FxTestSupport.runOnFx(() -> {
                Robot robot = new Robot();
                robot.mouseMove(row.getX(), row.getY());
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseMove(row.getX() + 50, row.getY() + 15);
                robot.mouseMove(row.getX() + 100, row.getY() + 30);
                robot.mouseRelease(MouseButton.PRIMARY);
            });
            settle();
            double after = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.surface(), "offsetX"));
            assertEquals(before + 100, after, 1.0, "a primary drag from a row pans the map");
            assertTrue(h.opened().isEmpty(), "and its release is not a click");

            // A click (even one that wobbles a pixel or two) still opens the file by default.
            Point2D moved = FxTestSupport.callOnFx(() -> rowCenterOnScreen(h.surface(), h.readme()));
            FxTestSupport.runOnFx(() -> {
                Robot robot = new Robot();
                robot.mouseMove(moved.getX(), moved.getY());
                robot.mousePress(MouseButton.PRIMARY);
                robot.mouseMove(moved.getX() + 2, moved.getY() + 1);
                robot.mouseRelease(MouseButton.PRIMARY);
            });
            settle();
            assertEquals(List.of(h.readme()), h.opened());

            // "Single-click opens files" off: a click selects, a double-click opens.
            FxTestSupport.runOnFx(() -> {
                CheckMenuItem option = FxTestSupport.field(h.view(), "singleClickOpens");
                assertTrue(option.isSelected(), "on by default");
                h.view().setSingleClickOpens(false);
                assertFalse(h.view().isSingleClickOpens());
            });
            // A click on another row first, so the next click on README is a first click, not a double-click's
            // second (the wobble click above was on README a moment ago).
            Point2D other = FxTestSupport.callOnFx(() -> rowCenterOnScreen(h.surface(), h.file(0)));
            FxTestSupport.runOnFx(() -> robotClickAt(other));
            settle();
            Point2D readme = FxTestSupport.callOnFx(() -> rowCenterOnScreen(h.surface(), h.readme()));
            FxTestSupport.runOnFx(() -> robotClickAt(readme));
            settle();
            assertEquals(List.of(h.readme()), h.opened(), "a single click opens nothing more");
            assertEquals(h.readme(), FxTestSupport.callOnFx(() -> selected(h.surface())), "it selects the row");
            FxTestSupport.runOnFx(() -> {
                Object box = boxFor(h.surface(), h.readme());
                double x = center(box, "x", "width") - 30;
                double y = center(box, "y", "height");
                fire(h.surface(), MouseEvent.MOUSE_PRESSED, x, y, 2);
                fire(h.surface(), MouseEvent.MOUSE_RELEASED, x, y, 2);
                fire(h.surface(), MouseEvent.MOUSE_CLICKED, x, y, 2);
            });
            assertEquals(List.of(h.readme(), h.readme()), h.opened(), "a double-click opens it");
        }
    }

    // B6

    @Test
    void theRowMenuHasTheMapsOwnItemsAndTheyAct() throws Exception {
        try (Harness h = open(0)) {
            AtomicReference<ContextMenu> shown = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                h.view().setMarkerStates(path -> false, path -> path.equals(h.readme()));
                h.view().setContextMenuFactory(entry -> {
                    ContextMenu menu = new ContextMenu(new MenuItem("Rename"));
                    shown.set(menu);
                    return menu;
                });
                select(h.surface(), h.readme());
                keyboardMenu(h.surface());
                List<String> labels = shown.get().getItems().stream()
                        .map(MenuItem::getText)
                        .filter(Objects::nonNull)
                        .toList();
                assertEquals(
                        List.of(
                                tr("project.map.menu.preview"),
                                tr("project.map.menu.showStructure"),
                                tr("project.map.menu.personalNotes"),
                                "Rename"),
                        labels,
                        "README is in the first column (the root's children), which has no close button");
                assertTrue(shown.get().getItems().getFirst().getProperties().containsKey(ProjectPanel.STAYS_IN_MAP));
                shown.get().getItems().getFirst().fire();
                shown.get().hide();
                Map<Path, ProjectMapPreview> previews = FxTestSupport.field(h.view(), "previews");
                assertEquals(1, previews.size(), "Preview opened the card");

                select(h.surface(), h.file(3));
                keyboardMenu(h.surface());
                MenuItem close = shown.get().getItems().stream()
                        .filter(item -> tr("project.map.menu.closeColumn").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                close.fire();
                shown.get().hide();
                assertEquals(h.a(), selected(h.surface()), "Close Column closed the column the row is in");
            });
            waitForFx(() -> !contains(h.surface(), h.file(3)));
        }
    }

    // B7

    @Test
    void tabStopsOnlyAtColumnControlsInsideTheViewport() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.b().resolve("x.txt")));
            waitForFx(() -> contains(h.surface(), h.b().resolve("x.txt")));
            settle();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "repaint");
                List<Control> controls = columnControls(h.surface());
                assertTrue(controls.stream().anyMatch(Node::isFocusTraversable), "on-screen controls are stops");
                // Pan every column off the left edge.
                double offset = FxTestSupport.field(h.surface(), "offsetX");
                setField(h.surface(), "offsetX", offset - 5000);
                FxTestSupport.invoke(h.surface(), "repaint");
                for (Control control : controls) {
                    assertFalse(control.isFocusTraversable(), "off-screen: " + control.getStyleClass());
                }
                h.surface().requestFocus();
                press(h.surface(), KeyCode.TAB);
                Node owner = h.scene().getFocusOwner();
                assertFalse(controls.contains(owner), "Tab does not land on an off-screen column control: " + owner);
                assertNotEquals(h.surface(), owner, "but it did move on");
            });
        }
    }

    // B5

    @Test
    void theSurfaceIsAGroupWhoseTextNamesTheSelectionItsStatesAndTheDrawnRows() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> {
                assertEquals(AccessibleRole.PARENT, h.surface().getAccessibleRole());
                h.view().setGitStatus(Map.of(h.file(1), GitFileStatus.MODIFIED));
                h.view().setMarkerStates(path -> path.equals(h.file(1)), path -> false);
                select(h.surface(), h.file(1));
                String text = h.surface().getAccessibleText();
                assertTrue(text.startsWith(tr("project.map.accessible.file", "f1.txt", 2, 10)), text);
                assertTrue(text.contains(tr("project.map.tooltip.gitChanged")), text);
                assertTrue(text.contains(tr("project.map.tooltip.bookmarked")), text);

                // Only the rows the column draws count.
                TextField filter = filterOf(h.surface(), h.a());
                filter.setText("f1");
                assertEquals(
                        tr(
                                "project.map.accessible.withStates",
                                tr("project.map.accessible.file", "f1.txt", 1, 1),
                                tr("project.map.tooltip.gitChanged") + ", " + tr("project.map.tooltip.bookmarked")),
                        h.surface().getAccessibleText());
                FxTestSupport.call(h.surface(), "setSelected", new Class<?>[] {Path.class}, h.file(4));
                assertTrue(
                        h.surface().getAccessibleText().contains(tr("project.map.accessible.state.filtered")),
                        "a row the filter hid says so");
            });
        }
    }

    // E8 / B21

    @Test
    void theHelpPopoverCoversTheWholeModelInTheUsersLanguage() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> {
                List<String> text = labels((Parent) FxTestSupport.call(h.view(), "buildHelp", new Class<?>[0]));
                for (String key : List.of(
                        "project.map.help.intro",
                        "project.map.help.cards",
                        "project.map.help.shiftEnter",
                        "project.map.help.rename",
                        "project.map.help.delete",
                        "project.map.help.menu",
                        "project.map.help.siblings",
                        "project.map.help.rightClick",
                        "project.map.help.middleDrag",
                        "project.map.help.overview",
                        "project.map.help.noteBadge",
                        "project.map.help.cardNotes",
                        "project.map.help.hideUnmatched")) {
                    assertTrue(text.contains(tr(key)), "missing " + key);
                }
            });
            try {
                FxTestSupport.runOnFx(() -> {
                    Messages.init("de");
                    List<String> text = labels((Parent) FxTestSupport.call(h.view(), "buildHelp", new Class<?>[0]));
                    assertTrue(text.contains("Eingabe / Leertaste"), "key names in German: " + text);
                    assertTrue(text.contains("Bild auf / Bild ab"));
                    assertFalse(text.contains("Enter / Space"));
                    assertFalse(text.stream().anyMatch(line -> line.contains("Page Up")));
                });
            } finally {
                FxTestSupport.runOnFx(() -> Messages.init("en"));
            }
        }
    }

    // B22

    @Test
    void clearIsAResetNotADeleteInGermanAndItalian() throws Exception {
        assertEquals("Zurücksetzen", catalog("de").getProperty("project.map.filter.clear"));
        assertEquals("Azzera", catalog("it").getProperty("project.map.filter.clear"));
        assertNotEquals(
                catalog("de").getProperty("action.delete", "Löschen"),
                catalog("de").getProperty("project.map.filter.clear"));
    }

    // B23

    @Test
    void clearResetsTheSearchButLeavesTheHideNotesOption() throws Exception {
        try (Harness h = open(0)) {
            AtomicBoolean searchCleared = new AtomicBoolean();
            FxTestSupport.runOnFx(() -> {
                h.view().setEscapeActions(() -> searchCleared.set(true), () -> {});
                CheckMenuItem hideNotes = FxTestSupport.field(h.view(), "hideOpenNotes");
                hideNotes.setSelected(true);
                h.view().setQuery("f1");
                Button clear = (Button) h.view().lookup(".project-map-filter-clear");
                clear.fire();
                assertTrue(hideNotes.isSelected(), "the ⋯ option is not a filter");
                assertTrue(searchCleared.get(), "the search is cleared with the filters");
                assertTrue(tr("project.map.filter.clear.help")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("search"));
            });
        }
    }

    // C18

    @Test
    void closingOrTabbingOutOfACardHandsTheKeyboardBackToTheMap() throws Exception {
        try (Harness h = open(0)) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.readme());
                FxTestSupport.call(h.view(), "previewSelection", new Class<?>[] {Path.class}, h.readme());
            });
            settle();
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = onlyCard(h.view());
                card.focusContent();
                assertTrue(within(h.scene().getFocusOwner(), card), "precondition: the card has the keyboard");
                Button close = (Button) card.lookupAll(".project-map-preview-button").stream()
                        .filter(node -> tr("project.map.preview.close").equals(node.getAccessibleText()))
                        .findFirst()
                        .orElseThrow();
                close.fire();
                assertSame(h.surface(), h.scene().getFocusOwner(), "× hands the keyboard to the map");

                select(h.surface(), h.readme());
                FxTestSupport.call(h.view(), "previewSelection", new Class<?>[] {Path.class}, h.readme());
            });
            settle();
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = onlyCard(h.view());
                card.focusContent();
                Node text = h.scene().getFocusOwner();
                Event.fireEvent(
                        text, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            });
            settle();
            FxTestSupport.runOnFx(() -> assertSame(
                    h.surface(), h.scene().getFocusOwner(), "Tab out of the card returns to the map, not the toolbar"));

            // Shift+Enter closes the card it opened and keeps the keyboard on the map (B6).
            FxTestSupport.runOnFx(() -> {
                Map<Path, ProjectMapPreview> previews = FxTestSupport.field(h.view(), "previews");
                pressShift(h.surface(), KeyCode.ENTER);
                assertTrue(previews.isEmpty(), "Shift+Enter closed the README card");
                pressShift(h.surface(), KeyCode.ENTER);
                assertEquals(1, previews.size(), "and opens it again");
                assertSame(h.surface(), h.scene().getFocusOwner());
            });
        }
    }

    // --- a fully wired window: the scene's KeyDispatcher sees each key first ---

    @Test
    void shiftEnterReachesTheMapUnderAKeymapThatBindsItAndEscapeRestoresTheSelection() throws Exception {
        if (fx == null) {
            fx = FxWindowFixture.create();
        }
        Path project = Files.createTempDirectory(temp, "window-project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 4; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i);
        }
        Path readme = Files.writeString(project.resolve("README.md"), "hello");
        ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap("intellij"); // Shift+Enter is "insert line below" there
            fx.windowManager.reloadSharedKeymap();
            if (panel.getScene() == null) {
                registry.run("tool.project");
            }
            panel.setRoot(project);
            panel.openMapWindow(true);
        });
        waitForFx(() -> contains(surface, readme));
        FxTestSupport.runOnFx(() -> view.revealPath(a.resolve("f2.txt")));
        waitForFx(() -> a.resolve("f2.txt").equals(selected(surface)));
        settle();
        Scene scene = FxTestSupport.callOnFx(surface::getScene);
        FxTestSupport.runOnFx(() -> {
            surface.requestFocus();
            assertTrue(ProjectMapView.claimedChords(com.editora.command.KeymapManager.isMac())
                    .contains("S-enter"));
            assertTrue(
                    ((java.util.Set<?>) surface.getProperties().get(KeyDispatcher.CLAIMED_KEYS)).contains("S-enter"));
            Map<Path, ProjectMapPreview> previews = FxTestSupport.field(view, "previews");
            KeyEvent shiftEnter = pressShift(surface, KeyCode.ENTER);
            assertTrue(shiftEnter.isConsumed());
            assertEquals(1, previews.size(), "the map got Shift+Enter, not the keymap");
        });

        // Escape clears the search and returns to the selection before it.
        TextField search = FxTestSupport.field(panel, "mapFilterField");
        FxTestSupport.runOnFx(() -> {
            press(surface, KeyCode.ESCAPE); // closes the card first
            search.setText("README");
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
        waitForFx(() -> readme.equals(selected(surface)));
        FxTestSupport.runOnFx(() -> {
            surface.requestFocus();
            press(surface, KeyCode.ESCAPE);
            assertEquals("", search.getText());
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
            assertSame(surface, scene.getFocusOwner());
        });
        waitForFx(() -> a.resolve("f2.txt").equals(selected(surface)));
    }

    // --- helpers ---

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(120);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
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

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Properties catalog(String lang) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = ProjectMapNavReviewFxTest.class.getResourceAsStream(
                "/com/editora/i18n/messages_" + lang + ".properties")) {
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static List<String> labels(Parent root) {
        List<String> text = new ArrayList<>();
        for (Node node : root.getChildrenUnmodifiable()) {
            if (node instanceof Label label) {
                text.add(label.getText());
            } else if (node instanceof Parent parent) {
                text.addAll(labels(parent));
            }
        }
        return text;
    }

    private static ProjectMapPreview onlyCard(ProjectMapView view) {
        Map<Path, ProjectMapPreview> previews = FxTestSupport.field(view, "previews");
        assertEquals(1, previews.size());
        return previews.values().iterator().next();
    }

    private static boolean within(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
    }

    @SuppressWarnings("unchecked")
    private static Path selected(Region surface) {
        return ((Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
    }

    private static void select(Region surface, Path path) {
        FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, path);
    }

    private static Object boxFor(Region surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .filter(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                        .path()
                        .equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row drawn for " + path));
    }

    private static void assertInView(Region surface, Path path, String what) {
        Object box = boxFor(surface, path);
        double x = origin(box, "x");
        double y = origin(box, "y");
        assertTrue(
                x >= 0
                        && y >= 0
                        && x + origin(box, "width") <= surface.getWidth()
                        && y + origin(box, "height") <= surface.getHeight(),
                what + " lies in the " + surface.getWidth() + "×" + surface.getHeight() + " view: at " + x + ", " + y);
    }

    private static boolean anyColumnInView(Region surface) {
        List<?> columns = FxTestSupport.field(surface, "columnBoxes");
        return columns.stream()
                .anyMatch(box -> origin(box, "x") < surface.getWidth()
                        && origin(box, "x") + origin(box, "width") > 0
                        && origin(box, "y") < surface.getHeight()
                        && origin(box, "y") + origin(box, "height") > 0);
    }

    private static Object columnBoxForParent(Region surface, Path parent) {
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        return boxes.stream()
                .filter(box -> parent.equals(
                        ((ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0])).parent()))
                .findFirst()
                .orElseThrow();
    }

    private static Object keyedColumnValue(Region surface, String field, Path parent) {
        Map<?, ?> values = FxTestSupport.field(surface, field);
        return values.entrySet().stream()
                .filter(entry -> Objects.equals(parent, FxTestSupport.call(entry.getKey(), "parent", new Class<?>[0])))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();
    }

    private static TextField filterOf(Region surface, Path parent) {
        return (TextField)
                FxTestSupport.call(keyedColumnValue(surface, "columnControls", parent), "filter", new Class<?>[0]);
    }

    private static Button closeButtonOf(Region surface, Path parent) {
        return (Button)
                FxTestSupport.call(keyedColumnValue(surface, "columnControls", parent), "close", new Class<?>[0]);
    }

    private static List<Control> columnControls(Region surface) {
        Map<?, ?> values = FxTestSupport.field(surface, "columnControls");
        List<Control> controls = new ArrayList<>();
        for (Object record : values.values()) {
            for (String part : List.of("filter", "showHidden", "pin", "close")) {
                controls.add((Control) FxTestSupport.call(record, part, new Class<?>[0]));
            }
        }
        return controls;
    }

    private static double origin(Object box, String coordinate) {
        return (double) FxTestSupport.call(box, coordinate, new Class<?>[0]);
    }

    private static double center(Object box, String origin, String size) {
        return origin(box, origin) + origin(box, size) / 2;
    }

    private static Point2D rowCenterOnScreen(Region surface, Path path) {
        FxTestSupport.invoke(surface, "repaint");
        Object box = boxFor(surface, path);
        // Left of centre: clear of the eye and Structure icons at the row's trailing end.
        return surface.localToScreen(origin(box, "x") + 40, center(box, "y", "height"));
    }

    private static void robotClick(Node node) {
        Bounds at = node.localToScreen(node.getBoundsInLocal());
        robotClickAt(new Point2D(at.getCenterX(), at.getCenterY()));
    }

    private static void robotClickAt(Point2D at) {
        Robot robot = new Robot();
        robot.mouseMove(at.getX(), at.getY());
        robot.mouseClick(MouseButton.PRIMARY);
    }

    /** A robot drag of {@code parent}'s column header by {@code dy}, in steps, with a settle after. */
    private static void robotDrag(Region surface, Path parent, double dy) throws Exception {
        Point2D start = FxTestSupport.callOnFx(() -> {
            FxTestSupport.invoke(surface, "repaint");
            Object column = columnBoxForParent(surface, parent);
            return surface.localToScreen(origin(column, "x") + 30, origin(column, "y") + 6);
        });
        FxTestSupport.runOnFx(() -> {
            Robot robot = new Robot();
            robot.mouseMove(start.getX(), start.getY());
            robot.mousePress(MouseButton.PRIMARY);
            for (int step = 1; step <= 5; step++) {
                robot.mouseMove(start.getX(), start.getY() + dy * step / 5);
            }
        });
        settle();
        FxTestSupport.runOnFx(() -> new Robot().mouseRelease(MouseButton.PRIMARY));
        settle();
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(surface, "repaint"));
    }

    /** What the Menu key / Shift+F10 deliver to the focused surface. */
    private static void keyboardMenu(Region surface) {
        double x = surface.getWidth() / 4;
        double y = surface.getHeight() / 2;
        Point2D scene = surface.localToScene(x, y);
        Point2D screen = surface.localToScreen(x, y);
        Event.fireEvent(
                surface,
                new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        scene.getX(),
                        scene.getY(),
                        screen.getX(),
                        screen.getY(),
                        true,
                        new PickResult(surface, scene.getX(), scene.getY())));
    }

    private static KeyEvent press(Node target, KeyCode code) {
        return dispatch(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private static KeyEvent pressShift(Node target, KeyCode code) {
        return dispatch(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, true, false, false, false));
    }

    /** Dispatches {@code event} at {@code target} and returns an event whose consumed state is the outcome. */
    private static KeyEvent dispatch(Node target, KeyEvent event) {
        boolean[] reachedEnd = {false};
        javafx.event.EventHandler<KeyEvent> probe = seen -> reachedEnd[0] = true;
        javafx.stage.Window window = target.getScene().getWindow();
        window.addEventHandler(event.getEventType(), probe);
        try {
            Event.fireEvent(target, event);
        } finally {
            window.removeEventHandler(event.getEventType(), probe);
        }
        if (!reachedEnd[0]) {
            event.consume();
        }
        return event;
    }

    /** A primary-button event at the surface point {@code (x, y)} with the given click count. */
    private static void fire(Region surface, EventType<MouseEvent> type, double x, double y, int clicks) {
        Point2D scene = surface.localToScene(x, y);
        Event.fireEvent(
                surface,
                new MouseEvent(
                        type,
                        scene.getX(),
                        scene.getY(),
                        scene.getX(),
                        scene.getY(),
                        MouseButton.PRIMARY,
                        clicks,
                        false,
                        false,
                        false,
                        false,
                        type == MouseEvent.MOUSE_PRESSED,
                        false,
                        false,
                        false,
                        false,
                        true,
                        new PickResult(surface, scene.getX(), scene.getY())));
    }
}
