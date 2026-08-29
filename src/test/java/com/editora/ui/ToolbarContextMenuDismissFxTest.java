package com.editora.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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
 * <p>Two of these menus were seen standing open side by side, which can only happen if the press that
 * opened the second one never dismissed the first — a {@link javafx.scene.control.ContextMenu} auto-hides,
 * but that hide is driven by the platform's popup grab. Two things now guarantee it regardless: the
 * coordinator reuses one menu instance (so showing it again moves it rather than stacking a second one),
 * and while it is open a mouse press anywhere in the window closes it.
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

    private static void rightClickTheToolbar(double screenX, double screenY) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                coordinator, "showContextMenu", new Class<?>[] {double.class, double.class}, screenX, screenY));
        // A second hop through the FX thread, so the runnable the show queues (which arms the dismissal)
        // has run by the time the caller looks.
        FxTestSupport.runOnFx(() -> {});
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
        rightClickTheToolbar(100, 100);
        assertEquals(1, openMenuCount(), "precondition: right-clicking the toolbar opens its menu");

        rightClickTheToolbar(400, 100);
        assertEquals(
                1,
                openMenuCount(),
                "a second right-click left the first menu standing — two toolbar menus are on screen at once");
    }

    /**
     * The menu must not depend on the platform's auto-hide, which is the mechanism that failed in the
     * report. Auto-hide is switched off here to stand in for a platform where the popup grab is missed:
     * JavaFX's own hide answers an owner-scene press, so with it left on this test would pass whether or
     * not the coordinator does anything of its own.
     */
    @Test
    void aMousePressClosesTheMenuEvenWhenAutoHideDoesNotFire() throws Exception {
        javafx.scene.control.ContextMenu menu = FxTestSupport.field(coordinator, "contextMenu");
        FxTestSupport.runOnFx(() -> menu.setAutoHide(false));
        try {
            rightClickTheToolbar(100, 100);
            assertEquals(1, openMenuCount(), "precondition: right-clicking the toolbar opens its menu");

            FxTestSupport.runOnFx(() -> Event.fireEvent(scene().getRoot(), press(700, 400)));

            assertEquals(
                    0,
                    openMenuCount(),
                    "a click elsewhere in the window left the toolbar's menu open — it is relying on the"
                            + " platform's auto-hide, which is exactly what failed in the report");
        } finally {
            FxTestSupport.runOnFx(() -> menu.setAutoHide(true));
        }
    }

    private static MouseEvent press(double x, double y) {
        return new MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
    }

    @Test
    void theMenuIsStillUsableAfterBeingDismissed() throws Exception {
        rightClickTheToolbar(100, 100);
        FxTestSupport.runOnFx(() -> {
            for (Window w : Window.getWindows().stream().toList()) {
                if (w.isShowing() && w instanceof PopupWindow p) {
                    p.hide();
                }
            }
        });
        assertEquals(0, openMenuCount(), "precondition: the menu is closed");

        rightClickTheToolbar(200, 100);
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
