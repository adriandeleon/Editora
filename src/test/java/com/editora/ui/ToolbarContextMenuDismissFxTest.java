package com.editora.ui;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ToolBar;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar's right-click menu closes instead of accumulating.
 *
 * <p>Two of these menus were seen standing open side by side. The cause: JavaFX's auto-hide ignores a press
 * inside the menu's owner node, here the whole toolbar, so a second right-click on the bar never dismissed the
 * first. The coordinator reuses one menu instance (showing it again moves it), and {@link ContextMenuDismissal}
 * closes it on a press anywhere, the toolbar included.
 */
@Tag("fx")
class ToolbarContextMenuDismissFxTest {

    private static FxWindowFixture fx;
    private static Object coordinator;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        coordinator = FxTestSupport.field(fx.controller, "toolbarCoordinator");
        FxTestSupport.runOnFx(() -> {
            Scene scene = scene();
            scene.getRoot().resize(1500, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
            fx = null; // a static field would otherwise keep the closed window reachable for the whole run
        }
    }

    @BeforeEach
    void closeAnyOpenMenu() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (Window w : Window.getWindows().stream().toList()) {
                if (w.isShowing() && w instanceof PopupWindow p) {
                    p.hide();
                }
            }
        });
    }

    private static Scene scene() {
        return FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
    }

    private static ToolBar toolBar() {
        return FxTestSupport.field(fx.controller, "toolBar");
    }

    /** A right-click on the toolbar at {@code x} (in its own coordinates), delivered through the window. */
    private static void rightClickTheToolbar(double x) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ToolBar bar = toolBar();
            double y = bar.getHeight() / 2;
            Point2D scene = bar.localToScene(x, y);
            Point2D screen = bar.localToScreen(x, y);
            Event.fireEvent(bar, press(bar, x, y, MouseButton.SECONDARY));
            Event.fireEvent(
                    bar,
                    new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            scene.getX(),
                            scene.getY(),
                            screen.getX(),
                            screen.getY(),
                            false,
                            null));
        });
        FxTestSupport.runOnFx(() -> {}); // let the gesture end
    }

    /** How many popup windows are currently showing a menu of ours. */
    private static int openMenuCount() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            int n = 0;
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w instanceof PopupWindow && w.getScene() != null) {
                    for (Node label : w.getScene().getRoot().lookupAll(".menu-item .label")) {
                        if (label instanceof Label l
                                && l.getText() != null
                                && !l.getText().isBlank()) {
                            n++;
                            break;
                        }
                    }
                }
            }
            return n;
        });
    }

    @Test
    void asecondRightClickMovesTheMenuRatherThanOpeningASecondOne() throws Exception {
        rightClickTheToolbar(100);
        assertEquals(1, openMenuCount(), "precondition: right-clicking the toolbar opens its menu");

        rightClickTheToolbar(400);
        assertEquals(
                1,
                openMenuCount(),
                "a second right-click left the first menu standing — two toolbar menus are on screen at once");
    }

    /** The press lands inside the menu's owner node (the toolbar), which JavaFX's own auto-hide ignores. */
    @Test
    void aClickOnTheToolbarItselfClosesTheMenu() throws Exception {
        rightClickTheToolbar(100);
        assertEquals(1, openMenuCount(), "precondition: right-clicking the toolbar opens its menu");

        FxTestSupport.runOnFx(() -> {
            ToolBar bar = toolBar();
            Event.fireEvent(bar, press(bar, bar.getWidth() - 40, bar.getHeight() / 2, MouseButton.PRIMARY));
        });

        assertEquals(0, openMenuCount(), "a click on the toolbar left its right-click menu open");
    }

    @Test
    void aClickElsewhereInTheWindowClosesTheMenu() throws Exception {
        rightClickTheToolbar(100);
        assertEquals(1, openMenuCount(), "precondition: right-clicking the toolbar opens its menu");

        FxTestSupport.runOnFx(() -> {
            Node root = scene().getRoot();
            Event.fireEvent(root, press(root, 700, 400, MouseButton.PRIMARY));
        });

        assertEquals(0, openMenuCount(), "a click elsewhere in the window left the toolbar's menu open");
    }

    private static MouseEvent press(Node node, double x, double y, MouseButton button) {
        Point2D scene = node.localToScene(x, y);
        Point2D screen = node.localToScreen(x, y);
        return new MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                scene.getX(),
                scene.getY(),
                screen.getX(),
                screen.getY(),
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                true,
                button == MouseButton.SECONDARY,
                false,
                null);
    }

    @Test
    void theMenuIsStillUsableAfterBeingDismissed() throws Exception {
        rightClickTheToolbar(100);
        FxTestSupport.runOnFx(() -> {
            for (Window w : Window.getWindows().stream().toList()) {
                if (w.isShowing() && w instanceof PopupWindow p) {
                    p.hide();
                }
            }
        });
        assertEquals(0, openMenuCount(), "precondition: the menu is closed");

        rightClickTheToolbar(200);
        assertEquals(1, openMenuCount(), "the menu should reopen after having been dismissed");
        boolean labelled = FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                .anyMatch(w -> w.isShowing()
                        && w instanceof PopupWindow
                        && w.getScene() != null
                        && !w.getScene().getRoot().lookupAll(".menu-item").isEmpty()));
        assertTrue(labelled, "the reopened menu should still carry its items");
        assertFalse(openMenuCount() > 1, "and only one of them");
    }
}
