package com.editora.ui;

import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;

/**
 * Keeps the icon column in every menu the app draws, the way the menu bar keeps it on every row.
 *
 * <p>A right-click or drop-down row is a stock {@link MenuItem}, laid out by the stylesheet ({@code .context-menu
 * .menu-item} in {@code app.css}): a 22 px column for the glyph with 8 px to the title, and the chord 18 px or more
 * after the title column. The skin gives that column to every row of a menu as soon as one row has a glyph (or a
 * tick), so a row without one still lines up. A menu with none at all would have no column, and its titles would
 * start 30 px left of every other menu's. As each menu shows, this marks such a menu {@value #BLANK_COLUMN}, and the
 * stylesheet gives its titles the column as padding.
 *
 * <p>The skin also keeps a tick and a glyph in two columns, one after the other, so a menu with both kinds of row
 * would have 60 px before its titles. Such a menu is marked {@value #SHARED_COLUMN}, and the stylesheet draws its
 * ticks in the glyphs' column.
 */
public final class MenuRowLayout {

    /** The style class of a showing menu where no row has a glyph or a tick of its own. */
    static final String BLANK_COLUMN = "menu-blank-column";

    /** The style class of a showing menu that has both tick rows and glyph rows: the two share one column. */
    static final String SHARED_COLUMN = "menu-shared-column";

    private static boolean installed;

    private MenuRowLayout() {}

    /** Watches every menu as it shows, in every window, now and later. Idempotent; FX thread only. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // A popup joins the window list on every show, after its on-showing handlers have set the rows'
        // visibility and graphics and before it is sized: the column is decided for this showing.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w instanceof ContextMenu menu) {
                        apply(menu);
                    }
                }
            }
        });
    }

    /**
     * Marks {@code menu} {@value #BLANK_COLUMN} when its shown rows have no glyph or tick, and
     * {@value #SHARED_COLUMN} when they have both; unmarks it otherwise.
     */
    static void apply(ContextMenu menu) {
        mark(menu.getStyleClass(), BLANK_COLUMN, needsBlankColumn(menu.getItems()));
        mark(menu.getStyleClass(), SHARED_COLUMN, needsSharedColumn(menu.getItems()));
    }

    private static void mark(ObservableList<String> classes, String styleClass, boolean on) {
        if (on && !classes.contains(styleClass)) {
            classes.add(styleClass);
        } else if (!on) {
            classes.remove(styleClass);
        }
    }

    /**
     * Whether a menu of {@code items} has both a visible tick row and a visible glyph row, so that the two columns
     * the skin gives them are drawn as one. A tick row with a glyph of its own needs both columns: such a menu
     * keeps them. Pure.
     */
    static boolean needsSharedColumn(List<MenuItem> items) {
        boolean tick = false;
        boolean glyph = false;
        for (MenuItem item : items) {
            if (!item.isVisible() || item instanceof SeparatorMenuItem || item instanceof CustomMenuItem) {
                continue;
            }
            boolean ticks = item instanceof CheckMenuItem || item instanceof RadioMenuItem;
            if (ticks && item.getGraphic() != null) {
                return false;
            }
            tick |= ticks;
            glyph |= !ticks && item.getGraphic() != null;
        }
        return tick && glyph;
    }

    /**
     * Whether a menu of {@code items} needs the icon column drawn as padding: it has a visible row and none of
     * its visible rows has a glyph or a tick. A row laid out by its own content (the menu bar's) decides nothing
     * here: such a menu is left as it is. Pure.
     */
    static boolean needsBlankColumn(List<MenuItem> items) {
        boolean anyRow = false;
        for (MenuItem item : items) {
            if (!item.isVisible() || item instanceof SeparatorMenuItem) {
                continue;
            }
            if (item instanceof CustomMenuItem
                    || item instanceof CheckMenuItem
                    || item instanceof RadioMenuItem
                    || item.getGraphic() != null) {
                return false;
            }
            anyRow = true;
        }
        return anyRow;
    }
}
