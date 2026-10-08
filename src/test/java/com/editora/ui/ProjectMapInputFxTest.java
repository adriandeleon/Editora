package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.ui.ProjectMapView.FlowDirection;
import com.editora.ui.ProjectMapView.WheelAction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyboard and pointer input on the Project Map canvas.
 *
 * <p>Every event here is <em>fired</em> at a node of a shown scene, so it travels the route a real key or
 * click does. The second half does so inside a fully wired window, under several keymaps: the scene-level
 * {@code KeyDispatcher} sees each key before the map, and tests that called the map's handlers reflectively
 * could not notice that half of its documented chords never arrived.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapInputFxTest {

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

    // --- pure decisions ---

    @Test
    void singleSiblingStepsWrapAndPageMovesClamp() {
        assertEquals(0, ProjectMapView.siblingIndex(9, 1, 10), "Down on the last row wraps");
        assertEquals(9, ProjectMapView.siblingIndex(0, -1, 10), "Up on the first row wraps");
        assertEquals(9, ProjectMapView.siblingIndex(0, 10, 10), "Page Down in a ten-row column reaches the end");
        assertEquals(6, ProjectMapView.siblingIndex(3, 10, 7));
        assertEquals(199, ProjectMapView.siblingIndex(195, 10, 200), "…and does not wrap to the top");
        assertEquals(0, ProjectMapView.siblingIndex(4, -10, 200));
        assertEquals(14, ProjectMapView.siblingIndex(4, 10, 200));
    }

    @Test
    void wheelEventsPanOnTheHorizontalAxisAndIgnoreInertiaForZoom() {
        assertEquals(WheelAction.ZOOM, ProjectMapView.wheelAction(0, 120, false, false, false));
        assertEquals(WheelAction.PAN_X, ProjectMapView.wheelAction(35, 0, false, false, false), "touchpad swipe");
        assertEquals(WheelAction.PAN_X, ProjectMapView.wheelAction(35, 4, false, false, true));
        assertEquals(WheelAction.NONE, ProjectMapView.wheelAction(0, 12, false, false, true), "inertia never zooms");
        assertEquals(WheelAction.PAN_X, ProjectMapView.wheelAction(0, 120, true, false, false), "Shift+wheel");
        assertEquals(
                WheelAction.PAN_X,
                ProjectMapView.wheelAction(120, 0, true, false, false),
                "Shift+wheel already reported as a horizontal delta");
        assertEquals(WheelAction.PAN_Y, ProjectMapView.wheelAction(0, 120, false, true, false), "Alt+wheel");
        assertEquals(WheelAction.NONE, ProjectMapView.wheelAction(0, 0, true, false, false));
    }

    @Test
    void aDraggedColumnsOffsetIsStoredAsTheLayoutUsesIt() {
        assertEquals(0, ProjectMapView.flowLimitedOffset(FlowDirection.LEFT_TO_RIGHT, true, true, -300));
        assertEquals(40, ProjectMapView.flowLimitedOffset(FlowDirection.LEFT_TO_RIGHT, true, true, 40));
        assertEquals(-300, ProjectMapView.flowLimitedOffset(FlowDirection.LEFT_TO_RIGHT, false, true, -300));
        assertEquals(0, ProjectMapView.flowLimitedOffset(FlowDirection.RIGHT_TO_LEFT, true, true, 300));
        assertEquals(0, ProjectMapView.flowLimitedOffset(FlowDirection.TOP_TO_BOTTOM, false, true, -300));
        assertEquals(0, ProjectMapView.flowLimitedOffset(FlowDirection.BOTTOM_TO_TOP, false, true, 300));
        assertEquals(-300, ProjectMapView.flowLimitedOffset(FlowDirection.LEFT_TO_RIGHT, true, false, -300), "root");
    }

    @Test
    void spansAreShiftedJustFarEnoughIntoTheViewport() {
        assertEquals(0, ProjectMapView.shiftIntoView(50, 100, 400, 20));
        assertEquals(70, ProjectMapView.shiftIntoView(-50, 100, 400, 20));
        assertEquals(-120, ProjectMapView.shiftIntoView(400, 100, 400, 20));
        assertEquals(-80, ProjectMapView.shiftIntoView(100, 900, 400, 20), "too long: align its start");
    }

    @Test
    void theSurfaceClaimsThePlatformsFitChord() {
        assertTrue(ProjectMapView.claimedChords(false).containsAll(Set.of("M-left", "M-right", "C-0", "C-n", "C-p")));
        assertTrue(ProjectMapView.claimedChords(true).contains("Cmd-0"));
        assertFalse(ProjectMapView.claimedChords(true).contains("C-0"));
        assertTrue(ProjectMapView.claimedChords(true).containsAll(Set.of("f2", "delete")));
    }

    // --- a map on its own in a shown scene ---

    /**
     * {@code project/} holding {@code a/f0.txt … f9.txt}, {@code b/x.txt} and {@code README.md}, shown left to
     * right at 100% with {@code a} expanded.
     */
    private record Harness(
            Stage stage,
            ProjectMapView view,
            Region surface,
            Path project,
            Path a,
            Path b,
            Path readme,
            List<Path> opened,
            List<String> statuses)
            implements AutoCloseable {

        Path file(int index) {
            return a.resolve("f" + index + ".txt");
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

    private Harness open() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 10; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i);
        }
        Path b = Files.createDirectory(project.resolve("b"));
        Files.writeString(b.resolve("x.txt"), "x");
        Path readme = Files.writeString(project.resolve("README.md"), "hello");
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
            return new Harness(stage, view, surface, project, a, b, readme, opened, statuses);
        });
        waitForFx(() -> contains(h.surface(), readme));
        FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(0)));
        waitForFx(() -> contains(h.surface(), h.file(0)) && h.file(0).equals(selected(h.surface())));
        FxTestSupport.drainFx(); // the deferred fit / "show the opened column" of the loads above
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(h.surface(), "resetViewport");
            h.scene().getRoot().applyCss();
            h.scene().getRoot().layout();
            h.surface().requestFocus();
        });
        return h;
    }

    @Test
    void slashIsMatchedByCharacterAndNeverTypedIntoTheFilter() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(3));
                TextField filter = filterOf(h.surface(), h.a());
                filter.setText("f");

                // A Spanish or German layout: the slash is Shift+7, so the key code is a digit.
                typeChar(h.scene(), KeyCode.DIGIT7, true, "/");
                assertSame(filter, h.scene().getFocusOwner(), "the selected column's filter has the focus");
                assertEquals("f", filter.getText(), "the slash itself must not land in the field");
                assertEquals("f", filter.getSelectedText(), "the existing text is selected, ready to replace");

                // A US layout, and the numpad: the press alone does nothing, the character does it.
                h.surface().requestFocus();
                typeChar(h.scene(), KeyCode.SLASH, false, "/");
                assertSame(filter, h.scene().getFocusOwner());
                assertEquals("f", filter.getText());
                h.surface().requestFocus();
                typeChar(h.scene(), KeyCode.DIVIDE, false, "/");
                assertSame(filter, h.scene().getFocusOwner());
                assertEquals("f", filter.getText());

                // In the field the slash is an ordinary character again.
                KeyEvent inField =
                        new KeyEvent(KeyEvent.KEY_TYPED, "/", "", KeyCode.UNDEFINED, false, false, false, false);
                Event.fireEvent(filter, inField);
                assertSame(filter, h.scene().getFocusOwner());
            });
        }
    }

    @Test
    void slashBringsBackAFilterHiddenByLowZoomAndSaysWhyOnTheRootColumn() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(3));
                TextField filter = filterOf(h.surface(), h.a());
                FxTestSupport.call(
                        h.surface(),
                        "setZoom",
                        new Class<?>[] {double.class, double.class, double.class},
                        0.4,
                        0.0,
                        0.0);
                FxTestSupport.invoke(h.surface(), "repaint");
                assertFalse(filter.isVisible(), "precondition: at 40% the column controls are hidden");

                typeChar(h.scene(), KeyCode.SLASH, false, "/");
                assertTrue(filter.isVisible(), "the map zoomed in far enough for the filter to return");
                assertSame(filter, h.scene().getFocusOwner());
                double zoom = FxTestSupport.field(h.surface(), "zoom");
                assertTrue(zoom > 0.4 && zoom <= 1.0, "zoom " + zoom);
                assertTrue(
                        filter.getLayoutX() >= 0
                                && filter.getLayoutY() >= 0
                                && filter.getLayoutX() + filter.getWidth()
                                        <= h.surface().getWidth()
                                && filter.getLayoutY() + filter.getHeight()
                                        <= h.surface().getHeight(),
                        "and it was scrolled into the viewport");
                assertTrue(h.statuses().isEmpty());

                // The project column has no filter: say so rather than swallow the key.
                h.surface().requestFocus();
                select(h.surface(), h.project());
                typeChar(h.scene(), KeyCode.SLASH, false, "/");
                assertSame(h.surface(), h.scene().getFocusOwner());
                assertEquals(List.of(Messages.tr("project.map.status.rootColumnNoFilter")), h.statuses());
            });
        }
    }

    @Test
    void theKeyboardContextMenuIsTheSelectedRowsAndOpensAtThatRow() throws Exception {
        try (Harness h = open()) {
            AtomicReference<ProjectMapModel.Entry> requested = new AtomicReference<>();
            AtomicReference<ContextMenu> shown = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                h.view().setContextMenuFactory(entry -> {
                    requested.set(entry);
                    ContextMenu menu = new ContextMenu(new MenuItem("Rename"));
                    shown.set(menu);
                    return menu;
                });
                select(h.surface(), h.file(5));
                // What the Menu key / Shift+F10 deliver: a point at a quarter of the width and half the height
                // of the focus owner, wherever the selection is.
                double x = h.surface().getWidth() / 4;
                double y = h.surface().getHeight() / 2;
                Point2D scene = h.surface().localToScene(x, y);
                Point2D screen = h.surface().localToScreen(x, y);
                ContextMenuEvent event = new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        scene.getX(),
                        scene.getY(),
                        screen.getX(),
                        screen.getY(),
                        true,
                        new PickResult(h.surface(), scene.getX(), scene.getY()));
                Event.fireEvent(h.surface(), event);

                assertEquals(h.file(5), requested.get().path(), "the menu belongs to the selection");
                assertEquals(h.file(5), selected(h.surface()), "and the selection did not move");
                Object row = boxFor(h.surface(), h.file(5));
                Point2D below = h.surface().localToScreen(origin(row, "x"), edge(row, "y", "height"));
                assertEquals(below.getY(), shown.get().getAnchorY(), 1.0, "anchored under the selected row");
                assertTrue(
                        shown.get().getAnchorX() >= below.getX()
                                && shown.get().getAnchorX() <= below.getX() + width(row),
                        "and within it");
                shown.get().hide();
            });
        }
    }

    @Test
    void pageUpAndPageDownStopAtTheEndsOfTheColumn() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(0));
                press(h.surface(), KeyCode.PAGE_DOWN);
                assertEquals(h.file(9), selected(h.surface()), "ten rows: Page Down reaches the last one");
                press(h.surface(), KeyCode.PAGE_DOWN);
                assertEquals(h.file(9), selected(h.surface()));
                press(h.surface(), KeyCode.PAGE_UP);
                assertEquals(h.file(0), selected(h.surface()));
                press(h.surface(), KeyCode.UP);
                assertEquals(h.file(9), selected(h.surface()), "a single step still wraps");
            });
        }
    }

    @Test
    void enterInAColumnFilterSelectsWhatItFoundAndAHiddenSelectionIsNotActivated() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.a()); // the folder that owns the column, as after expanding it
                TextField filter = filterOf(h.surface(), h.a());
                filter.requestFocus();
                filter.setText("f7");
                press(filter, KeyCode.ENTER);
                assertSame(h.surface(), h.scene().getFocusOwner(), "Enter returns to the map");
                assertEquals(h.file(7), selected(h.surface()), "with the first remaining row selected");

                press(h.surface(), KeyCode.ENTER);
                assertEquals(List.of(h.file(7)), h.opened(), "so the next Enter opens the match");
                assertTrue(h.view().expandedDirectories().contains(h.a()), "instead of collapsing the folder");

                // A filter that matches nothing leaves the selection on a row that is no longer drawn.
                filter.requestFocus();
                filter.setText("zzzz");
                press(filter, KeyCode.ENTER);
                press(h.surface(), KeyCode.ENTER);
                press(h.surface(), KeyCode.SPACE);
                assertEquals(List.of(h.file(7)), h.opened(), "nothing that is not shown is opened");
            });
        }
    }

    @Test
    void aHeaderDragStopsAtTheParentAndItsReleaseIsNotAClick() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.readme());
                Object column = columnBoxForParent(h.surface(), h.a());
                double x = origin(column, "x") + 30;
                double y = origin(column, "y") + 6;
                Object layout = columnLayoutFor(h.surface(), h.a());

                fire(h.surface(), MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY, false);
                fire(h.surface(), MouseEvent.MOUSE_DRAGGED, x - 300, y, MouseButton.PRIMARY, false);
                assertEquals(
                        0.0,
                        (double) FxTestSupport.field(layout, "x"),
                        0.001,
                        "towards its parent the column stops, and so does the stored offset");
                fire(h.surface(), MouseEvent.MOUSE_DRAGGED, x + 40, y, MouseButton.PRIMARY, false);
                assertEquals(40.0, (double) FxTestSupport.field(layout, "x"), 0.001, "…so dragging back out works");

                // The button is released over a row: that row is neither selected nor opened.
                FxTestSupport.invoke(h.surface(), "repaint");
                Object row = boxFor(h.surface(), h.file(2));
                double rowX = center(row, "x", "width");
                double rowY = center(row, "y", "height");
                fire(h.surface(), MouseEvent.MOUSE_RELEASED, rowX, rowY, MouseButton.PRIMARY, false);
                fire(h.surface(), MouseEvent.MOUSE_CLICKED, rowX, rowY, MouseButton.PRIMARY, false);
                fire(h.surface(), MouseEvent.MOUSE_CLICKED, rowX, rowY, MouseButton.PRIMARY, true);
                assertEquals(h.readme(), selected(h.surface()));
                assertTrue(h.opened().isEmpty());

                // A real click afterwards works as before.
                fire(h.surface(), MouseEvent.MOUSE_PRESSED, rowX, rowY, MouseButton.PRIMARY, true);
                fire(h.surface(), MouseEvent.MOUSE_RELEASED, rowX, rowY, MouseButton.PRIMARY, true);
                fire(h.surface(), MouseEvent.MOUSE_CLICKED, rowX, rowY, MouseButton.PRIMARY, true);
                assertEquals(List.of(h.file(2)), h.opened());
            });
        }
    }

    @Test
    void aMiddleButtonDragPansEvenWhenItStartsOnARow() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                Object row = boxFor(h.surface(), h.readme());
                double x = center(row, "x", "width");
                double y = center(row, "y", "height");
                double before = FxTestSupport.field(h.surface(), "offsetX");
                fire(h.surface(), MouseEvent.MOUSE_PRESSED, x, y, MouseButton.MIDDLE, true);
                fire(h.surface(), MouseEvent.MOUSE_DRAGGED, x + 150, y, MouseButton.MIDDLE, false);
                fire(h.surface(), MouseEvent.MOUSE_RELEASED, x + 150, y, MouseButton.MIDDLE, false);
                assertEquals(before + 150, (double) FxTestSupport.field(h.surface(), "offsetX"), 0.001);
                assertTrue(h.opened().isEmpty());
            });
        }
    }

    @Test
    void theHoverFollowsTheRowsUnderARestingPointerAndClearsWhenItLeaves() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                Object row = boxFor(h.surface(), h.readme());
                double x = center(row, "x", "width");
                double y = center(row, "y", "height");
                fire(h.surface(), MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE, true);
                assertEquals(h.readme(), FxTestSupport.<Path>field(h.surface(), "hovered"));

                // The rows move 300 px under the pointer (a keyboard reveal, a zoom button, a pan).
                Event.fireEvent(h.surface(), scroll(h.surface(), x, y, 0, 300, false, true, false));
                FxTestSupport.invoke(h.surface(), "repaint");
                assertNotEquals(
                        h.readme(),
                        FxTestSupport.<Path>field(h.surface(), "hovered"),
                        "the highlight must not stay on a row that is no longer under the pointer");

                Object moved = boxFor(h.surface(), h.readme());
                fire(
                        h.surface(),
                        MouseEvent.MOUSE_MOVED,
                        center(moved, "x", "width"),
                        center(moved, "y", "height"),
                        MouseButton.NONE,
                        true);
                assertEquals(h.readme(), FxTestSupport.<Path>field(h.surface(), "hovered"));
                fire(h.surface(), MouseEvent.MOUSE_EXITED, -5, 40, MouseButton.NONE, true);
                assertNull(FxTestSupport.<Path>field(h.surface(), "hovered"), "nothing is hovered outside the map");
                FxTestSupport.invoke(h.surface(), "repaint");
                assertNull(FxTestSupport.<Path>field(h.surface(), "hovered"));
            });
        }
    }

    @Test
    void historyFoldsARunOfSiblingMovesIsCappedAndAColumnCloseLeavesAnUnrelatedSelection() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                List<Path> history = FxTestSupport.field(h.view(), "selectionHistory");
                select(h.surface(), h.readme());
                select(h.surface(), h.file(0));
                int before = history.size();
                for (int i = 0; i < 6; i++) {
                    press(h.surface(), KeyCode.DOWN);
                }
                press(h.surface(), KeyCode.PAGE_DOWN);
                assertEquals(h.file(9), selected(h.surface()));
                assertEquals(before + 1, history.size(), "seven sibling moves are one history entry");

                pressWith(h.surface(), KeyCode.LEFT, false, true, false);
                assertEquals(h.file(0), selected(h.surface()), "Back returns to where the run started");
                pressWith(h.surface(), KeyCode.LEFT, false, true, false);
                assertEquals(h.readme(), selected(h.surface()));
                pressWith(h.surface(), KeyCode.RIGHT, false, true, false);
                pressWith(h.surface(), KeyCode.RIGHT, false, true, false);
                assertEquals(h.file(9), selected(h.surface()), "and Forward to where it ended");

                for (int i = 0; i < 150; i++) {
                    select(h.surface(), i % 2 == 0 ? h.readme() : h.file(1));
                }
                assertEquals(ProjectMapView.MAX_SELECTION_HISTORY, history.size(), "the history is bounded");
                assertEquals(h.file(1), history.getLast());
                Button back = FxTestSupport.field(h.view(), "backButton");
                assertFalse(back.isDisabled());

                // Closing a column moves the selection only when it was inside the closed branch.
                select(h.surface(), h.readme());
                closeButtonOf(h.surface(), h.a()).fire();
                assertEquals(h.readme(), selected(h.surface()), "a selection elsewhere stays put");
            });
            waitForFx(() -> !contains(h.surface(), h.file(0)));
            assertEquals(h.readme(), FxTestSupport.callOnFx(() -> selected(h.surface())));

            FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(4)));
            waitForFx(() -> h.file(4).equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> {
                List<Path> history = FxTestSupport.field(h.view(), "selectionHistory");
                closeButtonOf(h.surface(), h.a()).fire();
                assertEquals(h.a(), selected(h.surface()), "a selection under the closed branch moves to its folder");
                assertEquals(h.a(), history.getLast(), "and that move is part of the history");
            });
        }
    }

    @Test
    void aClickHitTestsTheViewportAsItIsNotAsItWasLastPainted() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                Object row = boxFor(h.surface(), h.readme());
                double x = center(row, "x", "width");
                double y = center(row, "y", "height");
                // A wheel pan is repainted on the next pulse; the click arrives before it.
                Event.fireEvent(h.surface(), scroll(h.surface(), 600, 500, 0, 300, false, true, false));
                fire(h.surface(), MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY, true);
                fire(h.surface(), MouseEvent.MOUSE_RELEASED, x, y, MouseButton.PRIMARY, true);
                fire(h.surface(), MouseEvent.MOUSE_CLICKED, x, y, MouseButton.PRIMARY, true);
                assertTrue(h.opened().isEmpty(), "README.md is no longer where the click landed");
            });
        }
    }

    @Test
    void f2AndDeleteRunTheRowActionsOnTheSelectedEntry() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                List<Path> renamed = new ArrayList<>();
                List<Path> deleted = new ArrayList<>();
                h.view().setRowActions(entry -> renamed.add(entry.path()), entry -> deleted.add(entry.path()));
                select(h.surface(), h.readme());
                assertTrue(press(h.surface(), KeyCode.F2).isConsumed());
                assertTrue(press(h.surface(), KeyCode.DELETE).isConsumed());
                assertEquals(List.of(h.readme()), renamed);
                assertEquals(List.of(h.readme()), deleted);

                // In a column filter both keys are text-editing keys.
                TextField filter = filterOf(h.surface(), h.a());
                press(filter, KeyCode.F2);
                press(filter, KeyCode.DELETE);
                assertEquals(1, renamed.size());
                assertEquals(1, deleted.size());
            });
        }
    }

    @Test
    void aClickOnAnOpenFolderSelectsItAndOnlyItsChevronCollapsesIt() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.readme());
                // Pan until the top of the folder's column is 300 px above the viewport.
                double top = origin(columnBoxForParent(h.surface(), h.a()), "y");
                Event.fireEvent(h.surface(), scroll(h.surface(), 600, 500, 0, -300 - top, false, true, false));
                FxTestSupport.invoke(h.surface(), "repaint");
                assertEquals(-300, origin(columnBoxForParent(h.surface(), h.a()), "y"), 0.5, "precondition");

                Object row = boxFor(h.surface(), h.a());
                click(h.surface(), origin(row, "x") + 40, center(row, "y", "height"));
                assertEquals(h.a(), selected(h.surface()));
                assertTrue(h.view().expandedDirectories().contains(h.a()), "an open folder is not collapsed");
                Object column = columnBoxForParent(h.surface(), h.a());
                assertTrue(
                        origin(column, "y") >= 0 && origin(column, "x") >= 0,
                        "its column is brought into view, header first: y=" + origin(column, "y"));

                row = boxFor(h.surface(), h.a());
                click(h.surface(), edge(row, "x", "width") - 8, center(row, "y", "height"));
                assertFalse(h.view().expandedDirectories().contains(h.a()), "the chevron collapses it");

                // A closed folder opens from anywhere on its row.
                Object closed = boxFor(h.surface(), h.b());
                click(h.surface(), origin(closed, "x") + 40, center(closed, "y", "height"));
                assertTrue(h.view().expandedDirectories().contains(h.b()));
            });
        }
    }

    @Test
    void escapeClosesCardsThenClearsTheSearchThenLeavesAndNeverRefits() throws Exception {
        try (Harness h = open()) {
            AtomicInteger cleared = new AtomicInteger();
            AtomicInteger left = new AtomicInteger();
            FxTestSupport.runOnFx(() -> {
                h.view().setEscapeActions(cleared::incrementAndGet, left::incrementAndGet);
                FxTestSupport.call(h.surface(), "zoomBy", new Class<?>[] {double.class}, 1.3);
                FxTestSupport.invoke(h.surface(), "repaint");
                double zoom = FxTestSupport.field(h.surface(), "zoom");

                Object row = boxFor(h.surface(), h.readme());
                click(h.surface(), edge(row, "x", "width") - 8, center(row, "y", "height")); // its preview icon
                Map<Path, ?> previews = FxTestSupport.field(h.view(), "previews");
                assertEquals(1, previews.size(), "precondition: a preview card is open");
                h.surface().requestFocus();

                press(h.surface(), KeyCode.ESCAPE);
                assertTrue(previews.isEmpty(), "first the open cards");
                assertEquals(0, cleared.get() + left.get());

                h.view().setQuery("read");
                press(h.surface(), KeyCode.ESCAPE);
                assertEquals(1, cleared.get(), "then the search query");
                assertEquals(0, left.get());

                h.view().setQuery("");
                press(h.surface(), KeyCode.ESCAPE);
                assertEquals(1, left.get(), "then back to the editor");
                assertEquals(1, cleared.get());
                assertEquals(zoom, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001, "Escape no longer fits");
            });
        }
    }

    @Test
    void horizontalSwipesPanInertiaDoesNotZoomAndAPinchZooms() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                double zoom = FxTestSupport.field(h.surface(), "zoom");
                double offsetX = FxTestSupport.field(h.surface(), "offsetX");
                ScrollEvent swipe = scroll(h.surface(), 400, 300, 35, 0, false, false, false);
                Event.fireEvent(h.surface(), swipe);
                assertEquals(offsetX + 35, (double) FxTestSupport.field(h.surface(), "offsetX"), 0.001);
                assertEquals(zoom, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001);

                for (int i = 0; i < 30; i++) {
                    Event.fireEvent(h.surface(), scroll(h.surface(), 400, 300, 0, 12, false, false, true));
                }
                assertEquals(zoom, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001, "inertia");

                Event.fireEvent(h.surface(), scroll(h.surface(), 400, 300, 0, 120, false, false, false));
                double wheeled = FxTestSupport.field(h.surface(), "zoom");
                assertTrue(wheeled > zoom, "a real wheel step still zooms");

                Event.fireEvent(
                        h.surface(),
                        new ZoomEvent(
                                ZoomEvent.ZOOM,
                                400,
                                300,
                                400,
                                300,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                                0.8,
                                0.8,
                                new PickResult(h.surface(), 400, 300)));
                assertEquals(wheeled * 0.8, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001, "pinch");
            });
        }
    }

    @Test
    void hidingTheFocusedColumnFilterReturnsTheKeyboardToTheMap() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(3));
                typeChar(h.scene(), KeyCode.SLASH, false, "/");
                assertSame(filterOf(h.surface(), h.a()), h.scene().getFocusOwner(), "precondition");
                FxTestSupport.call(h.surface(), "zoomBy", new Class<?>[] {double.class}, 0.3); // clamps at the floor
                FxTestSupport.invoke(h.surface(), "repaint");
                assertFalse(filterOf(h.surface(), h.a()).isVisible(), "precondition: the controls are hidden");
            });
            try {
                waitForFx(() -> h.scene().getFocusOwner() == h.surface());
            } catch (AssertionError timeout) {
                throw new AssertionError("focus stayed on "
                        + FxTestSupport.callOnFx(() -> h.scene().getFocusOwner()));
            }
            FxTestSupport.runOnFx(() -> {
                Path before = selected(h.surface());
                press(h.surface(), KeyCode.DOWN);
                assertNotEquals(before, selected(h.surface()), "the arrow keys navigate again");
            });
        }
    }

    // --- a fully wired window: every key passes the scene's KeyDispatcher first ---

    private record MapWindow(
            ProjectPanel panel,
            ProjectMapView view,
            Region surface,
            Scene scene,
            TabPane tabs,
            Path project,
            Path a,
            Path readme) {

        Path file(int index) {
            return a.resolve("f" + index + ".txt");
        }
    }

    private MapWindow window(String keymap) throws Exception {
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
        TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap(keymap);
            fx.windowManager.reloadSharedKeymap();
            if (panel.getScene() == null) {
                registry.run("tool.project");
            }
            panel.setRoot(project);
            FxTestSupport.<ToggleButton>field(panel, "mapModeButton").setSelected(true);
        });
        assertTrue(FxTestSupport.callOnFx(() -> surface.getScene() != null), "the Project tool window shows the map");
        Scene scene = FxTestSupport.callOnFx(surface::getScene);
        MapWindow w = new MapWindow(panel, view, surface, scene, tabs, project, a, readme);
        waitForFx(() -> contains(surface, readme));
        FxTestSupport.runOnFx(() -> view.revealPath(w.file(0)));
        waitForFx(() -> w.file(0).equals(selected(surface)));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            surface.requestFocus();
            assertSame(surface, scene.getFocusOwner(), "precondition: the map surface has the keyboard");
        });
        return w;
    }

    /** The platform shortcut modifier, as the GUI keymaps use it: Ctrl, or Cmd on macOS. */
    private static KeyEvent shortcut(Node target, KeyCode code) {
        boolean mac = KeymapManager.isMac();
        return pressWith(target, code, !mac, false, mac);
    }

    private void theMapsOwnChordsReachIt(String keymap) throws Exception {
        MapWindow w = window(keymap);
        FxTestSupport.runOnFx(() -> {
            String in = keymap + ": ";
            List<Path> history = FxTestSupport.field(w.view(), "selectionHistory");
            int tabs = w.tabs().getTabs().size();

            // History: Alt+Left / Alt+Right. No keymap binds them, so the dispatcher swallowed them.
            select(w.surface(), w.readme());
            select(w.surface(), w.file(1));
            KeyEvent back = pressWith(w.surface(), KeyCode.LEFT, false, true, false);
            assertEquals(w.readme(), selected(w.surface()), in + "Alt+Left goes back");
            assertTrue(back.isConsumed(), in + "and is consumed, so it cannot reach the native menu");
            KeyEvent forward = pressWith(w.surface(), KeyCode.RIGHT, false, true, false);
            assertEquals(w.file(1), selected(w.surface()), in + "Alt+Right goes forward");
            assertTrue(forward.isConsumed());
            assertFalse(history.isEmpty());

            // Siblings: Ctrl-N / Ctrl-P. New File, Print or Find File outside the Emacs keymap.
            pressWith(w.surface(), KeyCode.N, true, false, false);
            assertEquals(w.file(2), selected(w.surface()), in + "Ctrl-N selects the next sibling");
            pressWith(w.surface(), KeyCode.P, true, false, false);
            assertEquals(w.file(1), selected(w.surface()), in + "Ctrl-P selects the previous sibling");
            assertEquals(tabs, w.tabs().getTabs().size(), in + "no tab was opened");

            // Fit: Shortcut+0, the editor's text-zoom reset in every keymap.
            fx.controller.textZoom(1);
            double textZoom = fx.shared.getSettings().getFontZoom();
            assertNotEquals(1.0, textZoom, 0.001);
            FxTestSupport.call(w.surface(), "zoomBy", new Class<?>[] {double.class}, 1.9);
            FxTestSupport.invoke(w.surface(), "repaint");
            double mapZoom = FxTestSupport.field(w.surface(), "zoom");
            KeyEvent fit = shortcut(w.surface(), KeyCode.DIGIT0);
            assertTrue(fit.isConsumed(), in + "Shortcut+0 belongs to the map");
            assertNotEquals(mapZoom, (double) FxTestSupport.field(w.surface(), "zoom"), 0.0001, in + "it fits the map");
            assertEquals(textZoom, fx.shared.getSettings().getFontZoom(), 0.0001, in + "not the editor's text zoom");
            fx.controller.textZoom(0);

            // The claim is the surface's alone, and only while it has the keyboard.
            assertEquals(
                    ProjectMapView.claimedChords(KeymapManager.isMac()),
                    w.surface().getProperties().get(KeyDispatcher.CLAIMED_KEYS));
            assertFalse(w.view().getProperties().containsKey(KeyDispatcher.CLAIMED_KEYS));
        });
    }

    @Test
    void theMapsOwnChordsReachItUnderTheEmacsKeymap() throws Exception {
        theMapsOwnChordsReachIt("emacs");
    }

    @Test
    void theMapsOwnChordsReachItUnderTheCuaKeymap() throws Exception {
        theMapsOwnChordsReachIt("cua");
    }

    @Test
    void theMapsOwnChordsReachItUnderTheVsCodeKeymap() throws Exception {
        theMapsOwnChordsReachIt("vscode");
    }

    @Test
    void theMapsOwnChordsReachItUnderTheIntelliJAndSublimeKeymaps() throws Exception {
        theMapsOwnChordsReachIt("intellij");
        theMapsOwnChordsReachIt("sublime");
    }

    @Test
    void anAltChordTheMapHasNoUseForIsStillKeptFromTheNativeMenu() throws Exception {
        MapWindow w = window("cua");
        FxTestSupport.runOnFx(() -> {
            // Nothing to go back to: the map still consumes the chord it claimed.
            for (int i = 0; i < ProjectMapView.MAX_SELECTION_HISTORY + 5; i++) {
                pressWith(w.surface(), KeyCode.LEFT, false, true, false);
            }
            KeyEvent back = pressWith(w.surface(), KeyCode.LEFT, false, true, false);
            assertTrue(back.isConsumed());
            // An Alt chord the map did not claim is the dispatcher's to swallow, as everywhere else.
            Path before = selected(w.surface());
            KeyEvent other = pressWith(w.surface(), KeyCode.F9, false, true, false);
            assertTrue(other.isConsumed());
            assertEquals(before, selected(w.surface()), "and it never reached the map");

            // With the keyboard in a column filter the map claims nothing.
            select(w.surface(), w.file(1));
            typeChar(w.scene(), KeyCode.SLASH, false, "/");
            assertSame(filterOf(w.surface(), w.a()), w.scene().getFocusOwner());
            assertFalse(w.surface().getProperties().containsKey(KeyDispatcher.CLAIMED_KEYS));
            w.surface().requestFocus();
            assertTrue(w.surface().getProperties().containsKey(KeyDispatcher.CLAIMED_KEYS));
        });
    }

    @Test
    void f2RenamesTheSelectedMapEntryInsteadOfRunningTheSymbolRename() throws Exception {
        MapWindow w = window("vscode"); // F2 is lsp.rename there
        List<String> prompts = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            w.panel().setPrompt((title, label, initial, onAccept) -> prompts.add(initial));
            select(w.surface(), w.file(2));
            KeyEvent f2 = press(w.surface(), KeyCode.F2);
            assertEquals(List.of("f2.txt"), prompts, "the file rename prompt, pre-filled with the name");
            assertTrue(f2.isConsumed());

            select(w.surface(), w.project());
            press(w.surface(), KeyCode.F2);
            assertEquals(1, prompts.size(), "the project root is never renamed, as in the tree");

            select(w.surface(), w.a());
            assertTrue(
                    press(w.surface(), KeyCode.DELETE).isConsumed(), "Delete on a folder is a no-op, as in the tree");
            assertTrue(Files.isDirectory(w.a()));
        });
    }

    @Test
    void ctrlNAndCtrlPInTheSharedSearchFieldMoveTheMapSelectionInAGuiKeymap() throws Exception {
        MapWindow w = window("cua"); // Ctrl+N is New File, Ctrl+P is Print
        FxTestSupport.runOnFx(() -> {
            TextField search = FxTestSupport.field(w.panel(), "filterField");
            int tabs = w.tabs().getTabs().size();
            select(w.surface(), w.file(1));
            search.requestFocus();
            pressWith(search, KeyCode.N, true, false, false);
            assertEquals(w.file(2), selected(w.surface()));
            pressWith(search, KeyCode.P, true, false, false);
            assertEquals(w.file(1), selected(w.surface()));
            assertEquals(tabs, w.tabs().getTabs().size(), "no new file tab");
        });
    }

    @Test
    void escapeClearsTheSearchThenHandsTheKeyboardBackToTheEditor() throws Exception {
        window("emacs"); // boots the window
        // The tab comes first: selecting a tab re-roots a project-less Project panel.
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("text\n");
            FxTestSupport.call(fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().requestFocus(); // the editor had the keyboard before the map did
            return b;
        });
        MapWindow w = window("emacs");
        TextField search = FxTestSupport.field(w.panel(), "filterField");
        FxTestSupport.runOnFx(() -> {
            search.setText("f1");
            FxTestSupport.<javafx.animation.PauseTransition>field(w.panel(), "filterDebounce")
                    .stop();
            FxTestSupport.invoke(w.panel(), "rebuildBody");
            w.surface().requestFocus();
            press(w.surface(), KeyCode.ESCAPE);
            assertEquals("", search.getText(), "the first Escape clears the search");
            assertSame(w.surface(), w.scene().getFocusOwner());
            FxTestSupport.<javafx.animation.PauseTransition>field(w.panel(), "filterDebounce")
                    .stop();
            FxTestSupport.invoke(w.panel(), "rebuildBody");

            press(w.surface(), KeyCode.ESCAPE);
            Node owner = w.scene().getFocusOwner();
            assertTrue(
                    owner != null && within(owner, buffer.getArea()), "the next one returns to the editor: " + owner);
        });
    }

    // --- helpers (copies of the ones ProjectMapViewFxTest keeps private) ---

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

    private static Object columnLayoutFor(Region surface, Path parent) {
        return keyedColumnValue(surface, "columnLayouts", parent);
    }

    private static TextField filterOf(Region surface, Path parent) {
        return (TextField)
                FxTestSupport.call(keyedColumnValue(surface, "columnControls", parent), "filter", new Class<?>[0]);
    }

    private static Button closeButtonOf(Region surface, Path parent) {
        return (Button)
                FxTestSupport.call(keyedColumnValue(surface, "columnControls", parent), "close", new Class<?>[0]);
    }

    private static double origin(Object box, String coordinate) {
        return (double) FxTestSupport.call(box, coordinate, new Class<?>[0]);
    }

    private static double width(Object box) {
        return origin(box, "width");
    }

    private static double edge(Object box, String origin, String size) {
        return origin(box, origin) + origin(box, size);
    }

    private static double center(Object box, String origin, String size) {
        return origin(box, origin) + origin(box, size) / 2;
    }

    /** Fires a plain KEY_PRESSED at {@code target}: through the scene's filters first, as a real key is. */
    private static KeyEvent press(Node target, KeyCode code) {
        return pressWith(target, code, false, false, false);
    }

    /**
     * Fires a KEY_PRESSED and returns the copy that was dispatched — {@code fireEvent} sends a copy, and it is
     * the copy that gets consumed, so this captures it at the scene on its way back up or, when a filter below
     * consumed it, reports that by its absence.
     */
    private static KeyEvent pressWith(Node target, KeyCode code, boolean ctrl, boolean alt, boolean meta) {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, meta);
        return dispatch(target, event);
    }

    /** Dispatches {@code event} at {@code target} and returns an event whose consumed state is the outcome. */
    private static KeyEvent dispatch(Node target, KeyEvent event) {
        boolean[] reachedEnd = {false};
        javafx.event.EventHandler<KeyEvent> probe = seen -> reachedEnd[0] = true;
        // A handler on the stage (above the scene) sees the event only if nothing consumed it on the way.
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

    /** A key press and the character it types, each sent to whatever has the focus at that moment. */
    private static void typeChar(Scene scene, KeyCode code, boolean shift, String character) {
        Event.fireEvent(
                scene.getFocusOwner(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
        Event.fireEvent(
                scene.getFocusOwner(),
                new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, shift, false, false, false));
    }

    private static void click(Region surface, double x, double y) {
        fire(surface, MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY, true);
        fire(surface, MouseEvent.MOUSE_RELEASED, x, y, MouseButton.PRIMARY, true);
        fire(surface, MouseEvent.MOUSE_CLICKED, x, y, MouseButton.PRIMARY, true);
    }

    /**
     * Fires a mouse event at the point {@code (x, y)} of the surface. An event is created in scene coordinates
     * (its pick result converts them back), so the surface's handlers see exactly {@code (x, y)}.
     */
    private static void fire(
            Region surface, EventType<MouseEvent> type, double x, double y, MouseButton button, boolean still) {
        boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        Point2D scene = surface.localToScene(x, y);
        Event.fireEvent(
                surface,
                new MouseEvent(
                        type,
                        scene.getX(),
                        scene.getY(),
                        scene.getX(),
                        scene.getY(),
                        button,
                        button == MouseButton.NONE ? 0 : 1,
                        false,
                        false,
                        false,
                        false,
                        down && button == MouseButton.PRIMARY,
                        down && button == MouseButton.MIDDLE,
                        false,
                        false,
                        false,
                        still,
                        new PickResult(surface, scene.getX(), scene.getY())));
    }

    /** A scroll event at the surface point {@code (x, y)}; see {@link #fire} for the coordinates. */
    private static ScrollEvent scroll(
            Region surface,
            double x,
            double y,
            double deltaX,
            double deltaY,
            boolean shift,
            boolean alt,
            boolean inertia) {
        Point2D scene = surface.localToScene(x, y);
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                scene.getX(),
                scene.getY(),
                scene.getX(),
                scene.getY(),
                shift,
                false,
                alt,
                false,
                true,
                inertia,
                deltaX,
                deltaY,
                deltaX,
                deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                new PickResult(surface, scene.getX(), scene.getY()));
    }
}
