package com.editora.ui;

import java.util.Collections;
import java.util.List;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * The editor area: the region of the window that holds open file tabs, as a single component that
 * {@link MainController} talks to instead of reaching into a {@link TabPane} directly.
 *
 * <p><b>Today this holds exactly one tab group</b> and every method is a thin delegation to that one
 * {@code TabPane}, so behavior is identical to the pre-extraction code. The point of the indirection is
 * that splitting the editor area into several independent groups — different files side by side, as every
 * desktop IDE does (#762) — becomes a change to <em>this</em> class rather than to ~90 call sites spread
 * through a 15k-line controller.
 *
 * <p>The API is therefore written in the vocabulary that survives that change, and each method's javadoc
 * records which group it will mean once there are several. Two rules kept the migration honest:
 *
 * <ul>
 *   <li>{@link #tabs()} returns an <b>unmodifiable</b> view. It is still <em>live</em> (it reflects later
 *       adds and removes, which several callers rely on), but a caller cannot mutate through it — so every
 *       structural change has to go through {@link #add}/{@link #remove} and is visible in this file. With
 *       several groups that list becomes a synthesized concatenation, which could not be mutable anyway.
 *   <li>Nothing here exposes the underlying {@code TabPane}. {@link #node()} hands out the area's root node
 *       for layout purposes only.
 * </ul>
 *
 * <p>Not thread-safe: like the rest of the UI layer this is FX-thread-only.
 */
final class EditorArea {

    /**
     * The one tab group. When the area gains multiple groups this becomes a tree of groups plus a
     * reference to the focused one, and the delegations below resolve against that.
     */
    private final TabPane pane;

    /**
     * Cached read-only view of {@link #pane}'s tabs. {@code unmodifiableList} wraps the live list rather than
     * copying it, so one wrapper built once stays correct — and {@link #tabs()} is called from the tab-switch
     * and window-focus paths, which should not allocate just to be read.
     */
    private final List<Tab> tabsView;

    EditorArea(TabPane pane) {
        this.pane = pane;
        this.tabsView = Collections.unmodifiableList(pane.getTabs());
    }

    /** The area's root node, for layout (handed to {@link ToolWindowManager} as the split-pane centre). */
    Node node() {
        return pane;
    }

    /**
     * Every open tab in visual order, as a live but unmodifiable view — with several groups this is the
     * concatenation across groups, left/top group first. Use {@link #add}/{@link #remove} to mutate.
     */
    List<Tab> tabs() {
        return tabsView;
    }

    /** The selected tab of the focused group, or {@code null} when the area is empty. */
    Tab selectedTab() {
        return pane.getSelectionModel().getSelectedItem();
    }

    /** The index of {@link #selectedTab()} within the focused group, or {@code -1}. */
    int selectedIndex() {
        return pane.getSelectionModel().getSelectedIndex();
    }

    /** Whether the area holds no tabs at all (in any group). */
    boolean isEmpty() {
        return pane.getTabs().isEmpty();
    }

    /** The total number of open tabs (across all groups). */
    int size() {
        return pane.getTabs().size();
    }

    /** Whether {@code tab} is open (in any group). */
    boolean contains(Tab tab) {
        return pane.getTabs().contains(tab);
    }

    /** The index of {@code tab} within its own group, or {@code -1} if it is not open. */
    int indexOf(Tab tab) {
        return pane.getTabs().indexOf(tab);
    }

    /** The tab at {@code index} of the focused group. */
    Tab tabAt(int index) {
        return pane.getTabs().get(index);
    }

    /** Appends {@code tab} to the focused group. */
    void add(Tab tab) {
        pane.getTabs().add(tab);
    }

    /** Inserts {@code tab} at {@code index} of the focused group (used by pin/drag reordering). */
    void add(int index, Tab tab) {
        pane.getTabs().add(index, tab);
    }

    /** Removes {@code tab} from whichever group holds it; a no-op if it is not open. */
    void remove(Tab tab) {
        pane.getTabs().remove(tab);
    }

    /** Selects {@code tab} (and, with several groups, focuses the group that holds it). */
    void select(Tab tab) {
        pane.getSelectionModel().select(tab);
    }

    /** Selects the tab at {@code index} of the focused group. */
    void select(int index) {
        pane.getSelectionModel().select(index);
    }

    /**
     * Collapses or restores the tab header strip. Done with a style class rather than
     * {@code visible}/{@code managed} because the {@code TabPane} skin owns the header node, so a CSS class
     * is the supported way to hide it (see {@code .no-tab-header} in {@code app.css}).
     */
    void setTabHeaderVisible(boolean visible) {
        pane.getStyleClass().remove("no-tab-header");
        if (!visible) {
            pane.getStyleClass().add("no-tab-header");
        }
    }

    /**
     * Observes the effective active tab. With several groups this fires both when the selection changes
     * inside the focused group and when focus moves to a different group, since either changes which buffer
     * the rest of the UI is looking at.
     */
    void addSelectionListener(ChangeListener<? super Tab> listener) {
        pane.getSelectionModel().selectedItemProperty().addListener(listener);
    }

    /** Observes tabs opening and closing anywhere in the area. */
    void addTabsListener(ListChangeListener<? super Tab> listener) {
        pane.getTabs().addListener(listener);
    }

    /** Installs an event filter covering the whole editor area. */
    <T extends Event> void addEventFilter(EventType<T> type, EventHandler<? super T> handler) {
        pane.addEventFilter(type, handler);
    }
}
