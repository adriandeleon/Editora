package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.WindowEvent;

import org.jspecify.annotations.Nullable;

/**
 * A context menu whose items are built the first time it is about to show.
 *
 * <p>A menu attached to something that exists once per open file (a tab) is built for every file and opened
 * for almost none: the tab menu is ~25 items, each with an icon node, so building it eagerly was a
 * measurable slice of every tab's construction and a few hundred retained objects per tab.
 *
 * <p>The menu itself can wait too ({@link #install(Control, Supplier)}, {@link #install(Tab, Consumer)}): a
 * {@code ContextMenu} is a popup window of its own, and a new window used to build about forty of them (one per
 * tool-window stripe button, two per tab) before its first paint, for menus almost never opened.
 */
final class LazyContextMenu {

    /** A control's or a tab's menu still to build, under this key in its properties. */
    private static final Object PENDING = new Object();

    /** Set on a control or tab pane once it has the filter that builds a pending menu. */
    private static final Object ARMED = new Object();

    private record Pending(Supplier<? extends @Nullable ContextMenu> build) {}

    private LazyContextMenu() {}

    /**
     * Gives {@code control} the menu {@code build} makes, built when it is first asked for: a right-click, the
     * Menu key or Shift+F10. A filter builds and sets it, so the control's own handler then shows it as before.
     */
    static void install(Control control, Supplier<? extends @Nullable ContextMenu> build) {
        control.setContextMenu(null); // a menu it had is replaced, as setContextMenu would
        control.getProperties().put(PENDING, new Pending(build));
        if (control.getProperties().putIfAbsent(ARMED, Boolean.TRUE) == null) {
            control.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> menuOf(control));
        }
    }

    /**
     * Takes away {@code control}'s menu, built or still to build: a reused cell whose new row has none must not open
     * the menu its previous row would have built.
     */
    static void clear(Control control) {
        control.getProperties().remove(PENDING);
        control.setContextMenu(null);
    }

    /** {@code control}'s context menu, built now if it was still to be built ({@link #install(Control, Supplier)}). */
    static @Nullable ContextMenu menuOf(Control control) {
        if (control.getProperties().remove(PENDING) instanceof Pending pending) {
            control.setContextMenu(pending.build().get());
        }
        return control.getContextMenu();
    }

    /**
     * Gives {@code tab} a {@linkplain #of lazily filled} menu, itself made only when the tab's header is first
     * right-clicked: its tab pane's one filter builds it before the header's own handler looks for it.
     */
    static void install(Tab tab, Consumer<ContextMenu> build) {
        tab.setContextMenu(null); // a menu it had is replaced, as setContextMenu would
        tab.getProperties().put(PENDING, new Pending(() -> of(build)));
        if (tab.getProperties().putIfAbsent(ARMED, Boolean.TRUE) == null) {
            if (tab.getTabPane() != null) {
                arm(tab.getTabPane());
            }
            tab.tabPaneProperty().addListener((o, was, now) -> {
                if (now != null) {
                    arm(now);
                }
            });
        }
    }

    /** {@code tab}'s context menu, built now if it was still to be built ({@link #install(Tab, Consumer)}). */
    static @Nullable ContextMenu menuOf(Tab tab) {
        if (tab.getProperties().remove(PENDING) instanceof Pending pending) {
            tab.setContextMenu(pending.build().get());
        }
        return tab.getContextMenu();
    }

    private static void arm(TabPane pane) {
        if (pane.getProperties().putIfAbsent(ARMED, Boolean.TRUE) == null) {
            pane.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
                // The header (a skin's node) carries its tab under Tab.class in its properties.
                for (Node n = e.getTarget() instanceof Node t ? t : null; n != null && n != pane; n = n.getParent()) {
                    if (n.hasProperties() && n.getProperties().get(Tab.class) instanceof Tab tab) {
                        menuOf(tab);
                        return;
                    }
                }
            });
        }
    }

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

    /**
     * Hide the separators that hidden items have orphaned — leading, trailing, or with nothing visible since
     * the previous one — and show the rest again, so a menu that hides items per showing never draws two
     * rules in a row.
     */
    static void collapseSeparators(List<MenuItem> items) {
        SeparatorMenuItem pending = null; // a separator after a visible item, awaiting a visible one after it
        boolean visibleSince = false; // a visible item since the last kept separator (or the top)
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem sep) {
                sep.setVisible(false);
                if (visibleSince) {
                    pending = sep;
                    visibleSince = false;
                }
            } else if (item.isVisible()) {
                if (pending != null) {
                    pending.setVisible(true);
                    pending = null;
                }
                visibleSince = true;
            }
        }
    }
}
