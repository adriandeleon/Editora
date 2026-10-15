package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.ContextMenu;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;

/**
 * Closes an open right-click menu on a mouse press anywhere outside it, in any Editora window.
 *
 * <p>JavaFX's own auto-hide deliberately ignores a press inside the menu's <em>owner node</em> (the node
 * passed to {@code ContextMenu.show(node, x, y)}; see {@code PopupWindow.PopupEventRedirector}). That
 * exemption exists so a click on a drop-down's own button can toggle it, but a right-click menu is anchored
 * to the whole tree, list, text area or panel it was opened on, so a click anywhere else in that same
 * control left the menu standing, and a right-click on another row stacked a second menu beside it.
 *
 * <p>Only menus that opened during a context-menu request or a secondary-button gesture are closed here;
 * drop-downs (menu buttons, the menu bar, choice boxes, Side-anchored pickers) keep the stock behaviour. The
 * press is not consumed, so a click in a list still selects the row and a right-click on another row
 * opens that row's menu straight away.
 */
public final class ContextMenuDismissal {

    /** Window property marking a showing {@link ContextMenu} as a right-click menu ({@code Boolean}). */
    static final String RIGHT_CLICK_MENU = "editora.rightClickMenu";

    private static final String HOOKED = "editora.contextMenuDismissal";

    private static boolean installed;
    /** True from a context-menu gesture until the FX thread is next idle: menus shown meanwhile are its. */
    private static boolean gestureActive;

    private static boolean clearQueued;

    private ContextMenuDismissal() {}

    /** Hooks every current and future window. Idempotent; FX thread only. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    hook(w);
                }
            }
        });
        for (Window w : Window.getWindows()) {
            hook(w);
        }
    }

    private static void hook(Window window) {
        if (window instanceof ContextMenu menu) {
            // A window joins the list on every show, so this is re-decided for each showing.
            menu.getProperties().put(RIGHT_CLICK_MENU, gestureActive);
            return; // a press inside a menu is the menu's own business (it activates items on release)
        }
        if (window.getProperties().putIfAbsent(HOOKED, Boolean.TRUE) != null) {
            return;
        }
        window.addEventFilter(MouseEvent.MOUSE_PRESSED, ContextMenuDismissal::onPress);
        window.addEventFilter(MouseEvent.MOUSE_RELEASED, ContextMenuDismissal::onSecondary);
        window.addEventFilter(MouseEvent.MOUSE_CLICKED, ContextMenuDismissal::onSecondary);
        window.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> markGesture());
    }

    private static void onPress(MouseEvent e) {
        // A press outside the owner node was already handled (and consumed) by JavaFX's auto-hide; this sees
        // the presses inside it. Closing first means a right-click's new menu is the only one left open.
        List<ContextMenu> open = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w instanceof ContextMenu menu && isOpenRightClickMenu(menu)) {
                open.add(menu);
            }
        }
        open.forEach(ContextMenu::hide); // hide() edits the window list, hence the copy
        onSecondary(e);
    }

    private static void onSecondary(MouseEvent e) {
        if (e.getButton() == MouseButton.SECONDARY) {
            markGesture();
        }
    }

    /** True for a showing, auto-hiding menu that a right-click (or the Menu key) opened. */
    static boolean isOpenRightClickMenu(ContextMenu menu) {
        return menu.isShowing()
                && menu.isAutoHide()
                && Boolean.TRUE.equals(menu.getProperties().get(RIGHT_CLICK_MENU));
    }

    private static void markGesture() {
        gestureActive = true;
        if (!clearQueued) {
            clearQueued = true;
            Platform.runLater(() -> {
                gestureActive = false;
                clearQueued = false;
            });
        }
    }
}
