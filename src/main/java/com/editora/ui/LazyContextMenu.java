package com.editora.ui;

import java.util.function.Consumer;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.stage.WindowEvent;

/**
 * A context menu whose items are built the first time it is about to show.
 *
 * <p>A menu attached to something that exists once per open file (a tab) is built for every file and opened
 * for almost none: the tab menu is ~25 items, each with an icon node, so building it eagerly was a
 * measurable slice of every tab's construction and a few hundred retained objects per tab.
 */
final class LazyContextMenu {

    private LazyContextMenu() {}

    /**
     * A menu that calls {@code build} once, immediately before its first showing. {@code build} fills the
     * items and may install its own {@code onShowing} handler, which then also runs for that first showing.
     */
    static ContextMenu of(Consumer<ContextMenu> build) {
        // ContextMenu.show() returns before firing ON_SHOWING when there are no items, so a placeholder
        // stands in until the real ones replace it.
        ContextMenu menu = new ContextMenu(new MenuItem());
        menu.setOnShowing(e -> {
            menu.setOnShowing(null);
            menu.getItems().clear();
            build.accept(menu);
            EventHandler<WindowEvent> refresh = menu.getOnShowing();
            if (refresh != null) {
                refresh.handle(e);
            }
        });
        return menu;
    }

    /** True until {@code menu}'s first showing has built its items (for tests). */
    static boolean isUnbuilt(ContextMenu menu) {
        return menu.getItems().size() == 1 && menu.getItems().get(0).getText() == null;
    }

    static MenuItem item(String label, Node icon, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.setGraphic(icon);
        item.setOnAction(e -> action.run());
        return item;
    }
}
