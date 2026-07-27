package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventType;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * The editor area: the region of the window that holds open file tabs, as a single component that
 * {@link MainController} talks to instead of reaching into a {@link TabPane} directly.
 *
 * <p>The area holds one or more <b>groups</b>, each an independent strip of tabs with its own selection, so
 * two different files can be on screen at once (#762) — what every desktop IDE calls splitting the editor.
 * Groups sit side by side in a single {@link SplitPane}, so the whole area shares one orientation: "split
 * right" lays them out horizontally, "split down" vertically. That is deliberately flatter than IntelliJ's or
 * Eclipse's arbitrarily nested editor areas; a flat list covers the overwhelmingly common two- or three-group
 * case without a recursive tree to persist, focus and collapse correctly. Nesting can come later behind this
 * same API.
 *
 * <p><b>Exactly one group is focused</b>, and it is the one the rest of the UI means by "the active buffer":
 * {@link #selectedTab()} reads it, {@link #add} appends to it, and {@link #activeTabProperty()} fires both
 * when its selection changes and when focus moves to another group, since either changes which buffer the
 * window is looking at.
 *
 * <p>Two invariants keep callers honest:
 *
 * <ul>
 *   <li>{@link #tabs()} is <b>unmodifiable</b>. Every structural change goes through {@link #add}/
 *       {@link #remove} and so is visible in this file. While there is a single group it is also a live view
 *       of that group's list and costs no allocation, which matters because it is read on the tab-switch and
 *       window-focus paths; once split it is a snapshot, taken in visual order.
 *   <li>Nothing exposes the underlying {@code TabPane}s. {@link #node()} hands out the area's root for layout.
 * </ul>
 *
 * <p>Not thread-safe: like the rest of the UI layer this is FX-thread-only.
 */
final class EditorArea {

    /** Holds the groups side by side. One group means a single item and no visible divider. */
    private final SplitPane root = new SplitPane();

    /** Groups in visual order (left→right, or top→bottom). Never empty. */
    private final List<TabPane> groups = new ArrayList<>();

    /** The group whose selection the rest of the UI means by "the active tab". Always in {@link #groups}. */
    private TabPane focused;

    /**
     * The effective active tab — the focused group's selection. A single property rather than a passthrough
     * of one {@code TabPane}'s selection, because moving focus between groups changes the active buffer
     * without any group's own selection changing.
     */
    private final ObjectProperty<Tab> activeTab = new SimpleObjectProperty<>();

    /**
     * Cached read-only view of group 0's tabs, used while unsplit. {@code unmodifiableList} wraps the live
     * list rather than copying, so one wrapper built once stays correct.
     */
    private final List<Tab> singleGroupView;

    /** Registered by callers; re-attached to every group created later so they observe the whole area. */
    private final List<ListChangeListener<? super Tab>> tabsListeners = new ArrayList<>();

    private final List<FilterRegistration<?>> filters = new ArrayList<>();

    /** True while this class is relocating a tab between groups — see {@link #isRelocating()}. */
    private boolean relocating;

    private boolean tabHeaderVisible = true;

    private record FilterRegistration<T extends Event>(EventType<T> type, EventHandler<? super T> handler) {
        void applyTo(Node node) {
            node.addEventFilter(type, handler);
        }
    }

    EditorArea(TabPane initial) {
        this.singleGroupView = Collections.unmodifiableList(initial.getTabs());
        root.setOrientation(Orientation.HORIZONTAL);
        root.getStyleClass().add("editor-area");
        adopt(initial);
        focused = initial;
        activeTab.set(initial.getSelectionModel().getSelectedItem());
    }

    // --- structure -------------------------------------------------------------------------------------

    /** The area's root node, for layout (handed to {@link ToolWindowManager} as the split-pane centre). */
    Node node() {
        return root;
    }

    /** How many groups the area currently holds (1 when unsplit). */
    int groupCount() {
        return groups.size();
    }

    /** Whether the area is showing more than one group. */
    boolean isSplit() {
        return groups.size() > 1;
    }

    /** Adds {@code group} to the area, wiring the listeners and filters every group must carry. */
    private void adopt(TabPane group) {
        groups.add(group);
        root.getItems().add(group);
        group.getStyleClass().add("editor-group");
        applyTabHeader(group);
        for (ListChangeListener<? super Tab> l : tabsListeners) {
            group.getTabs().addListener(l);
        }
        for (FilterRegistration<?> f : filters) {
            f.applyTo(group);
        }
        // Whichever group holds keyboard focus is the active one. focusWithin covers the deeply nested
        // editor controls, where a plain focused listener on the TabPane never fires.
        //
        // Ignored while relocating, and that is not an optimisation. Adding a tab to a pane makes the skin
        // reparent its content, which fires focusWithin *from inside the list mutation*; publishing a new
        // active tab there runs the controller's selection listener, which calls
        // EditorBuffer.setRenderingActive -> Minimap.renderContent -> Node.snapshot(), and a snapshot forces
        // a full CSS/layout/peer sync of a scene graph that is still half-updated. The pulse then dies on
        // "validation of PGGroup children failed". A relocation sets the focused group explicitly once the
        // mutation has finished, so nothing is lost by sitting these out.
        group.focusWithinProperty().addListener((obs, was, now) -> {
            if (now && !relocating) {
                setFocusedGroup(group);
            }
        });
        group.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (group == focused) {
                activeTab.set(now);
            }
        });
    }

    /** Makes {@code group} the focused one, republishing its selection as the active tab. */
    private void setFocusedGroup(TabPane group) {
        if (group == focused || !groups.contains(group)) {
            return;
        }
        focused = group;
        activeTab.set(group.getSelectionModel().getSelectedItem());
    }

    /** The group holding {@code tab}, or {@code null} if it is not open. */
    private TabPane ownerOf(Tab tab) {
        for (TabPane g : groups) {
            if (g.getTabs().contains(tab)) {
                return g;
            }
        }
        return null;
    }

    // --- querying --------------------------------------------------------------------------------------

    /**
     * Every open tab in visual order — group by group, each group in strip order. Unmodifiable; a live view
     * of the only group while unsplit, a snapshot once split.
     */
    List<Tab> tabs() {
        if (groups.size() == 1) {
            return singleGroupView;
        }
        List<Tab> all = new ArrayList<>();
        for (TabPane g : groups) {
            all.addAll(g.getTabs());
        }
        return Collections.unmodifiableList(all);
    }

    /** The focused group's selected tab, or {@code null} when that group is empty. */
    Tab selectedTab() {
        return focused.getSelectionModel().getSelectedItem();
    }

    /** The index of {@link #selectedTab()} within the focused group, or {@code -1}. */
    int selectedIndex() {
        return focused.getSelectionModel().getSelectedIndex();
    }

    /** Whether the area holds no tabs at all, in any group. */
    boolean isEmpty() {
        for (TabPane g : groups) {
            if (!g.getTabs().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The total number of open tabs across all groups. */
    int size() {
        int n = 0;
        for (TabPane g : groups) {
            n += g.getTabs().size();
        }
        return n;
    }

    /** Whether {@code tab} is open in any group. */
    boolean contains(Tab tab) {
        return ownerOf(tab) != null;
    }

    /** The index of {@code tab} within its own group, or {@code -1} if it is not open. */
    int indexOf(Tab tab) {
        TabPane owner = ownerOf(tab);
        return owner == null ? -1 : owner.getTabs().indexOf(tab);
    }

    /** The tab at {@code index} of the focused group. */
    Tab tabAt(int index) {
        return focused.getTabs().get(index);
    }

    /**
     * True while a tab is being moved between groups. Such a move reaches listeners as a remove followed by
     * an add, which is indistinguishable from a close followed by an open — so the close-cleanup path (buffer
     * disposal, language-server shutdown) must sit this out, exactly as it already does for a pin reorder.
     */
    boolean isRelocating() {
        return relocating;
    }

    // --- mutation --------------------------------------------------------------------------------------

    /** Appends {@code tab} to the focused group. */
    void add(Tab tab) {
        focused.getTabs().add(tab);
    }

    /** Inserts {@code tab} at {@code index} of the focused group (used by pin/drag reordering). */
    void add(int index, Tab tab) {
        focused.getTabs().add(index, tab);
    }

    /**
     * Removes {@code tab} from whichever group holds it; a no-op if it is not open. A group emptied this way
     * collapses, so closing the last file in a split does not leave a dead pane behind.
     */
    void remove(Tab tab) {
        TabPane owner = ownerOf(tab);
        if (owner == null) {
            return;
        }
        owner.getTabs().remove(tab);
        if (owner.getTabs().isEmpty() && groups.size() > 1) {
            discard(owner);
        }
    }

    /** Removes an emptied group, moving focus to a neighbour. */
    private void discard(TabPane group) {
        int idx = groups.indexOf(group);
        groups.remove(group);
        root.getItems().remove(group);
        if (focused == group) {
            focused = groups.get(Math.min(idx, groups.size() - 1));
            activeTab.set(focused.getSelectionModel().getSelectedItem());
        }
    }

    /** Selects {@code tab} and focuses the group holding it. */
    void select(Tab tab) {
        TabPane owner = ownerOf(tab);
        if (owner == null) {
            return;
        }
        setFocusedGroup(owner);
        owner.getSelectionModel().select(tab);
        activeTab.set(owner.getSelectionModel().getSelectedItem());
    }

    /** Selects the tab at {@code index} of the focused group. */
    void select(int index) {
        focused.getSelectionModel().select(index);
    }

    // --- splitting -------------------------------------------------------------------------------------

    /**
     * Moves the focused group's selected tab into a new group beside it, laid out per {@code orientation},
     * and focuses that new group — the IDE "split right" / "split down" gesture.
     *
     * <p>Refuses when the focused group holds only that one tab: the move would empty the group, collapse it,
     * and leave the layout exactly as it started, having merely flickered.
     *
     * @return whether a split happened
     */
    boolean splitActive(Orientation orientation) {
        Tab tab = selectedTab();
        if (tab == null || focused.getTabs().size() < 2) {
            return false;
        }
        root.setOrientation(orientation);
        TabPane group = newGroup();
        relocate(tab, group);
        return true;
    }

    /**
     * Moves the focused group's selected tab to the next group round-robin, creating a second group if the
     * area is not split yet. Returns whether anything moved.
     */
    boolean moveActiveToNextGroup() {
        Tab tab = selectedTab();
        if (tab == null) {
            return false;
        }
        if (groups.size() == 1) {
            return splitActive(root.getOrientation());
        }
        int next = (groups.indexOf(focused) + 1) % groups.size();
        relocate(tab, groups.get(next));
        return true;
    }

    /** Moves {@code tab} into {@code target}, suppressing close-cleanup for the intervening remove. */
    private void relocate(Tab tab, TabPane target) {
        TabPane owner = ownerOf(tab);
        relocating = true;
        try {
            if (owner != null) {
                detach(tab, owner);
            }
            target.getTabs().add(tab);
        } finally {
            relocating = false;
        }
        // Collapse an emptied source only after the move, so the tab is never briefly homeless.
        if (owner != null && owner.getTabs().isEmpty() && groups.size() > 1) {
            discard(owner);
        }
        setFocusedGroup(target);
        target.getSelectionModel().select(tab);
        activeTab.set(tab);
    }

    /**
     * Removes {@code tab} from {@code owner} in a way that is safe to follow immediately with an add to
     * another group.
     *
     * <p>A {@code TabPane}'s skin parents the selected tab's content node, and it only lets go of it when it
     * processes the removal — which happens on the next layout pass, not on the list mutation. Adding the tab
     * to a second pane in the same frame therefore gives one node two parents, and the scene-graph sync
     * fails the next pulse with {@code AssertionError: validation of PGGroup children failed} (an assertion,
     * so it is invisible with assertions disabled and a hard crash with them on — which is how the FX suite
     * runs). Detaching the content first, and restoring it after the move, keeps the node single-parented
     * throughout.
     */
    private void detach(Tab tab, TabPane owner) {
        Node content = tab.getContent();
        tab.setContent(null);
        owner.getTabs().remove(tab);
        tab.setContent(content);
    }

    /** Appends a fresh, empty group to the area. */
    private TabPane newGroup() {
        TabPane group = new TabPane();
        group.setTabClosingPolicy(groups.get(0).getTabClosingPolicy());
        adopt(group);
        return group;
    }

    /**
     * Merges every group back into the first one, preserving visual order, and leaves the area unsplit.
     * Returns whether anything changed.
     */
    boolean unsplit() {
        if (groups.size() == 1) {
            return false;
        }
        Tab active = selectedTab();
        TabPane primary = groups.get(0);
        relocating = true;
        try {
            for (TabPane g : new ArrayList<>(groups.subList(1, groups.size()))) {
                for (Tab tab : new ArrayList<>(g.getTabs())) {
                    detach(tab, g); // see detach: never let a tab's content have two parents in one frame
                    primary.getTabs().add(tab);
                }
                groups.remove(g);
                root.getItems().remove(g);
            }
        } finally {
            relocating = false;
        }
        focused = primary;
        if (active != null) {
            primary.getSelectionModel().select(active);
        }
        activeTab.set(primary.getSelectionModel().getSelectedItem());
        return true;
    }

    /** Moves focus to the next group round-robin. Returns whether the area is split enough to matter. */
    boolean focusNextGroup() {
        if (groups.size() < 2) {
            return false;
        }
        TabPane next = groups.get((groups.indexOf(focused) + 1) % groups.size());
        setFocusedGroup(next);
        Tab tab = next.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getContent() != null) {
            tab.getContent().requestFocus();
        }
        return true;
    }

    // --- chrome + observation --------------------------------------------------------------------------

    /**
     * Collapses or restores the tab header strip on every group. Done with a style class rather than
     * {@code visible}/{@code managed} because the {@code TabPane} skin owns the header node, so a CSS class
     * is the supported way to hide it (see {@code .no-tab-header} in {@code app.css}).
     */
    void setTabHeaderVisible(boolean visible) {
        tabHeaderVisible = visible;
        for (TabPane g : groups) {
            applyTabHeader(g);
        }
    }

    private void applyTabHeader(TabPane group) {
        group.getStyleClass().remove("no-tab-header");
        if (!tabHeaderVisible) {
            group.getStyleClass().add("no-tab-header");
        }
    }

    /** The effective active tab: the focused group's selection, republished when focus moves between groups. */
    ObjectProperty<Tab> activeTabProperty() {
        return activeTab;
    }

    /** Observes the effective active tab — see {@link #activeTabProperty()}. */
    void addSelectionListener(ChangeListener<? super Tab> listener) {
        activeTab.addListener(listener);
    }

    /** Observes tabs opening and closing anywhere in the area, including in groups created later. */
    void addTabsListener(ListChangeListener<? super Tab> listener) {
        tabsListeners.add(listener);
        for (TabPane g : groups) {
            g.getTabs().addListener(listener);
        }
    }

    /** Installs an event filter on every group, including groups created later. */
    <T extends Event> void addEventFilter(EventType<T> type, EventHandler<? super T> handler) {
        FilterRegistration<T> reg = new FilterRegistration<>(type, handler);
        filters.add(reg);
        for (TabPane g : groups) {
            reg.applyTo(g);
        }
    }
}
