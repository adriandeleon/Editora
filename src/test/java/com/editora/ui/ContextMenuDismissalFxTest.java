package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A right-click menu closes on a press anywhere outside it — including inside the control it was opened on,
 * which JavaFX's own auto-hide exempts — while drop-downs keep the stock behaviour.
 */
@Tag("fx")
class ContextMenuDismissalFxTest {

    private Stage stage;
    private ListView<String> list;
    /** Every menu the list's right-click handler opened, newest last: like most panels, a fresh one per click. */
    private final List<ContextMenu> opened = new ArrayList<>();
    /** Mouse presses that reached the list's own handlers. */
    private final List<MouseButton> pressesSeen = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit(); // installs ContextMenuDismissal, as App.start does
    }

    @BeforeEach
    void showWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            list = new ListView<>();
            list.getItems().setAll("alpha", "beta", "gamma");
            list.setOnContextMenuRequested(e -> {
                ContextMenu menu = new ContextMenu(new MenuItem("Open"), new MenuItem("Delete"));
                menu.show(list, e.getScreenX(), e.getScreenY()); // anchored to the whole list
                opened.add(menu);
                e.consume();
            });
            list.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> pressesSeen.add(e.getButton()));
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(list), 400, 300));
            stage.show();
        });
        settle();
    }

    @AfterEach
    void closeWindows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (Window w : Window.getWindows().stream().toList()) {
                if (w instanceof PopupWindow p) {
                    p.hide();
                }
            }
            stage.close();
        });
    }

    @Test
    void aClickInsideTheControlTheMenuWasOpenedOnClosesIt() throws Exception {
        FxTestSupport.runOnFx(() -> rightClick(list, 50, 20));
        settle();
        assertEquals(1, openCount(), "precondition: the right-click opened the menu");

        FxTestSupport.runOnFx(() -> Event.fireEvent(list, press(list, 200, 150, MouseButton.PRIMARY)));

        assertEquals(0, openCount(), "a click elsewhere in the list left its right-click menu open");
        assertEquals(List.of(MouseButton.SECONDARY, MouseButton.PRIMARY), pressesSeen, "the click is not swallowed");
    }

    @Test
    void aRightClickOnAnotherRowReplacesTheMenuInsteadOfStackingASecond() throws Exception {
        FxTestSupport.runOnFx(() -> rightClick(list, 50, 20));
        settle();
        FxTestSupport.runOnFx(() -> rightClick(list, 50, 60));
        settle();

        assertEquals(2, opened.size(), "precondition: both right-clicks opened a menu");
        assertEquals(1, openCount(), "the first menu is still standing beside the second");
        assertTrue(FxTestSupport.callOnFx(() -> opened.get(1).isShowing()), "the newest one is the one left open");
    }

    @Test
    void aClickInAnotherWindowClosesIt() throws Exception {
        Stage other = FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(), 200, 200));
            s.show();
            return s;
        });
        try {
            FxTestSupport.runOnFx(() -> rightClick(list, 50, 20));
            settle();
            FxTestSupport.runOnFx(() -> {
                Node root = other.getScene().getRoot();
                Event.fireEvent(root, press(root, 20, 20, MouseButton.PRIMARY));
            });
            assertEquals(0, openCount(), "a click in another Editora window left the menu open");
        } finally {
            FxTestSupport.runOnFx(other::close);
        }
    }

    @Test
    void aDropDownOpenedByAPlainClickKeepsTheStockBehaviour() throws Exception {
        // A menu button's own popup is anchored to the button so that a click on the button toggles it.
        ContextMenu dropDown = FxTestSupport.callOnFx(() -> {
            ContextMenu menu = new ContextMenu(new MenuItem("One"));
            menu.show(list, javafx.geometry.Side.BOTTOM, 0, 0);
            return menu;
        });
        settle();

        FxTestSupport.runOnFx(() -> Event.fireEvent(list, press(list, 200, 150, MouseButton.PRIMARY)));

        assertTrue(FxTestSupport.callOnFx(dropDown::isShowing), "a press on its owner closed a drop-down");
        FxTestSupport.runOnFx(dropDown::hide);
    }

    @Test
    void aMenuThatTurnedAutoHideOffIsLeftAlone() throws Exception {
        FxTestSupport.runOnFx(() -> rightClick(list, 50, 20));
        settle();
        ContextMenu menu = opened.get(0);
        FxTestSupport.runOnFx(() -> menu.setAutoHide(false));

        FxTestSupport.runOnFx(() -> Event.fireEvent(list, press(list, 200, 150, MouseButton.PRIMARY)));

        assertTrue(FxTestSupport.callOnFx(menu::isShowing));
    }

    @Test
    void aMenuOpenedByAPlainClickAfterARightClickIsNotTakenForOne() throws Exception {
        FxTestSupport.runOnFx(() -> rightClick(list, 50, 20));
        settle(); // the right-click gesture is over
        ContextMenu dropDown = FxTestSupport.callOnFx(() -> {
            opened.get(0).hide();
            ContextMenu menu = new ContextMenu(new MenuItem("One"));
            menu.show(list, javafx.geometry.Side.BOTTOM, 0, 0);
            return menu;
        });
        assertFalse(FxTestSupport.callOnFx(() -> ContextMenuDismissal.isOpenRightClickMenu(dropDown)));
        FxTestSupport.runOnFx(dropDown::hide);
    }

    /** The press and the context-menu request a right-click delivers, in the Linux/macOS order. */
    private static void rightClick(Node node, double x, double y) {
        Event.fireEvent(node, press(node, x, y, MouseButton.SECONDARY));
        Point2D scene = node.localToScene(x, y);
        Point2D screen = node.localToScreen(x, y);
        Event.fireEvent(
                node,
                new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        scene.getX(),
                        scene.getY(),
                        screen.getX(),
                        screen.getY(),
                        false,
                        null));
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

    /** One hop through the FX thread, so a gesture's queued clear has run. */
    private static void settle() throws Exception {
        FxTestSupport.runOnFx(() -> {});
    }

    private int openCount() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (int) opened.stream().filter(ContextMenu::isShowing).count());
    }
}
