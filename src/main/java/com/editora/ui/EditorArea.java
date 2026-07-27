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
import javafx.scene.layout.StackPane;

/**
 * The editor area: the region of the window that holds open file tabs, as a single component that
 * {@link MainController} talks to instead of reaching into a {@link TabPane} directly.
 *
 * <p>The area holds one or more <b>groups</b>, each an independent strip of tabs with its own selection, so
 * two different files can be on screen at once (#762). Groups form a <b>tree</b>: a leaf is a group, and a
 * branch is a {@link SplitPane} with an orientation and two or more children, each itself a leaf or a branch.
 * So a horizontal split can contain a vertical one, as in IntelliJ, Eclipse and Visual Studio.
 *
 * <p>Two rules keep that tree from degenerating, and both matter as much as the nesting itself:
 *
 * <ul>
 *   <li>Splitting a group <b>along the orientation its parent already uses</b> adds a sibling rather than a
 *       nested branch, so splitting right three times yields three columns instead of a right-leaning chain
 *       of two-way splits.
 *   <li>A branch left with a single child is <b>replaced by that child</b>. Without this, closing files
 *       leaves invisible one-item {@code SplitPane}s that still take part in layout and that every later
 *       traversal has to step through.
 * </ul>
 *
 * <p><b>Exactly one group is focused</b>, and it is the one the rest of the UI means by "the active buffer":
 * {@link #selectedTab()} reads it, {@link #add} appends to it, and {@link #activeTabProperty()} fires both
 * when its selection changes and when focus moves to another group.
 *
 * <p>Two invariants keep callers honest:
 *
 * <ul>
 *   <li>{@link #tabs()} is <b>unmodifiable</b>. Every structural change goes through {@link #add}/
 *       {@link #remove} and so is visible in this file. While there is a single group it is also a live view
 *       of that group's list and costs no allocation, which matters because it is read on the tab-switch and
 *       window-focus paths; once split it is a snapshot in visual order.
 *   <li>Nothing exposes the underlying panes. {@link #node()} hands out a stable container for layout, whose
 *       identity never changes even as the tree beneath it is rebuilt.
 * </ul>
 *
 * <p>Not thread-safe: like the rest of the UI layer this is FX-thread-only.
 */
final class EditorArea {

    /** Stable wrapper handed to the layout, so the tree beneath can be rebuilt without re-parenting the area. */
    private final StackPane container = new StackPane();

    /** Root of the group tree: a {@link TabPane} while unsplit, otherwise a {@link SplitPane}. */
    private Node tree;

    /** Group 0 — the FXML-injected pane. Kept identifiable so {@link #unsplit()} always merges back into it. */
    private final TabPane primary;

    /** The group whose selection the rest of the UI means by "the active tab". Always a leaf of the tree. */
    private TabPane focused;

    /**
     * The effective active tab — the focused group's selection. A single property rather than a passthrough
     * of one {@code TabPane}'s selection, because moving focus between groups changes the active buffer
     * without any group's own selection changing.
     */
    private final ObjectProperty<Tab> activeTab = new SimpleObjectProperty<>();

    /**
     * Cached read-only view of the primary group's tabs, used while unsplit. {@code unmodifiableList} wraps
     * the live list rather than copying, so one wrapper built once stays correct.
     */
    private final List<Tab> singleGroupView;

    /** Registered by callers; re-attached to every group created later so they observe the whole area. */
    private final List<ListChangeListener<? super Tab>> tabsListeners = new ArrayList<>();

    private final List<FilterRegistration<?>> filters = new ArrayList<>();

    /** True while this class is restructuring the tree — see {@link #isRelocating()}. */
    private boolean relocating;

    private boolean tabHeaderVisible = true;

    private record FilterRegistration<T extends Event>(EventType<T> type, EventHandler<? super T> handler) {
        void applyTo(Node node) {
            node.addEventFilter(type, handler);
        }
    }

    EditorArea(TabPane initial) {
        this.primary = initial;
        this.singleGroupView = Collections.unmodifiableList(initial.getTabs());
        container.getStyleClass().add("editor-area");
        wire(initial);
        tree = initial;
        container.getChildren().setAll(initial);
        focused = initial;
        activeTab.set(initial.getSelectionModel().getSelectedItem());
    }

    // --- the group tree --------------------------------------------------------------------------------

    /** The area's root node, for layout. Stable for the lifetime of the area. */
    Node node() {
        return container;
    }

    /** Every group in visual order: depth-first, so left-to-right and top-to-bottom as drawn. */
    private List<TabPane> orderedGroups() {
        List<TabPane> out = new ArrayList<>();
        collect(tree, out);
        return out;
    }

    private static void collect(Node node, List<TabPane> out) {
        if (node instanceof TabPane leaf) {
            out.add(leaf);
        } else if (node instanceof SplitPane branch) {
            for (Node child : branch.getItems()) {
                collect(child, out);
            }
        }
    }

    /** The branch directly holding {@code child}, or {@code null} when {@code child} is the tree root. */
    private SplitPane parentOf(Node child) {
        return findParent(tree, child);
    }

    private static SplitPane findParent(Node node, Node child) {
        if (!(node instanceof SplitPane branch)) {
            return null;
        }
        if (branch.getItems().contains(child)) {
            return branch;
        }
        for (Node sub : branch.getItems()) {
            SplitPane found = findParent(sub, child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** How many groups the area currently holds (1 when unsplit). */
    int groupCount() {
        return orderedGroups().size();
    }

    /** Whether the area is showing more than one group. */
    boolean isSplit() {
        return groupCount() > 1;
    }

    /** How deeply the group tree nests: 1 while unsplit, 2 after one split, more once splits nest. */
    int depth() {
        return depthOf(tree);
    }

    private static int depthOf(Node node) {
        if (!(node instanceof SplitPane branch)) {
            return 1;
        }
        int deepest = 0;
        for (Node child : branch.getItems()) {
            deepest = Math.max(deepest, depthOf(child));
        }
        return deepest + 1;
    }

    /** Wires the listeners and filters every group must carry. Does not place it in the tree. */
    private void wire(TabPane group) {
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
        // Ignored while restructuring, and that is not an optimisation. Adding a tab to a pane makes the skin
        // reparent its content, which fires focusWithin *from inside the list mutation*; publishing a new
        // active tab there runs the controller's selection listener into EditorBuffer.setRenderingActive ->
        // Minimap.renderContent -> Node.snapshot(), and a snapshot forces a full CSS/layout/peer sync of a
        // scene graph that is still half-updated, killing the pulse with "validation of PGGroup children
        // failed". Restructuring sets the focused group explicitly once it has finished.
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
        if (group == focused) {
            return;
        }
        focused = group;
        activeTab.set(group.getSelectionModel().getSelectedItem());
    }

    /** The group holding {@code tab}, or {@code null} if it is not open. */
    private TabPane ownerOf(Tab tab) {
        for (TabPane g : orderedGroups()) {
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
        List<TabPane> groups = orderedGroups();
        if (groups.size() == 1 && groups.get(0) == primary) {
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
        for (TabPane g : orderedGroups()) {
            if (!g.getTabs().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The total number of open tabs across all groups. */
    int size() {
        int n = 0;
        for (TabPane g : orderedGroups()) {
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
     * True while this class is moving a tab between groups or restructuring the tree. Such a move reaches
     * listeners as a remove followed by an add, which is indistinguishable from a close followed by an open —
     * so the close-cleanup path (buffer disposal, language-server shutdown) must sit it out, exactly as it
     * already does for a pin reorder.
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
        if (owner.getTabs().isEmpty() && groupCount() > 1) {
            discard(owner);
        }
    }

    /** Removes an emptied group from the tree, collapsing any branch it leaves with a single child. */
    private void discard(TabPane group) {
        int idx = orderedGroups().indexOf(group);
        SplitPane parent = parentOf(group);
        if (parent == null) {
            return; // the only group: never remove the last one
        }
        relocating = true;
        try {
            parent.getItems().remove(group);
            collapseIfRedundant(parent);
        } finally {
            relocating = false;
        }
        if (focused == group) {
            List<TabPane> after = orderedGroups();
            focused = after.get(Math.min(idx, after.size() - 1));
            activeTab.set(focused.getSelectionModel().getSelectedItem());
        }
    }

    /**
     * Replaces a branch reduced to one child with that child. A one-item {@code SplitPane} is invisible but
     * not inert: it still takes part in layout, and every traversal has to step through it. Substituting
     * keeps the grandparent's child count unchanged, so this can never cascade.
     */
    private void collapseIfRedundant(SplitPane branch) {
        if (branch.getItems().size() != 1) {
            return;
        }
        Node only = branch.getItems().get(0);
        SplitPane grandparent = parentOf(branch);
        branch.getItems().remove(only);
        if (grandparent == null) {
            tree = only;
            container.getChildren().setAll(only);
        } else {
            int at = grandparent.getItems().indexOf(branch);
            grandparent.getItems().remove(branch);
            grandparent.getItems().add(at, only);
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
     * and focuses that new group — the IDE "split right" / "split down" gesture. The new group takes the
     * focused one's place in the tree, so a horizontal split can contain a vertical one.
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
        TabPane fresh = new TabPane();
        fresh.setTabClosingPolicy(primary.getTabClosingPolicy());
        wire(fresh);
        insertBeside(focused, fresh, orientation);
        relocate(tab, fresh);
        return true;
    }

    /**
     * Places {@code fresh} immediately after {@code leaf} in the tree, splitting along {@code orientation}.
     *
     * <p>When the leaf's own parent already splits that way, {@code fresh} becomes a plain sibling instead of
     * a nested branch — so splitting right three times gives three columns rather than a right-leaning chain
     * of two-way splits, which is both what users expect and far cheaper to traverse and to persist.
     */
    private void insertBeside(TabPane leaf, TabPane fresh, Orientation orientation) {
        SplitPane parent = parentOf(leaf);
        relocating = true;
        try {
            if (parent != null && parent.getOrientation() == orientation) {
                parent.getItems().add(parent.getItems().indexOf(leaf) + 1, fresh);
                return;
            }
            SplitPane branch = new SplitPane();
            branch.setOrientation(orientation);
            if (parent == null) {
                container.getChildren().remove(leaf); // single-parent the leaf before the branch adopts it
                branch.getItems().addAll(leaf, fresh);
                tree = branch;
                container.getChildren().setAll(branch);
            } else {
                int at = parent.getItems().indexOf(leaf);
                parent.getItems().remove(leaf);
                branch.getItems().addAll(leaf, fresh);
                parent.getItems().add(at, branch);
            }
        } finally {
            relocating = false;
        }
    }

    /**
     * Moves the focused group's selected tab to the next group in visual order, wrapping round, and creating
     * a second group if the area is not split yet. Returns whether anything moved.
     */
    boolean moveActiveToNextGroup() {
        Tab tab = selectedTab();
        if (tab == null) {
            return false;
        }
        List<TabPane> groups = orderedGroups();
        if (groups.size() == 1) {
            return splitActive(Orientation.HORIZONTAL);
        }
        relocate(tab, groups.get((groups.indexOf(focused) + 1) % groups.size()));
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
        if (owner != null && owner.getTabs().isEmpty() && groupCount() > 1) {
            discard(owner);
        }
        setFocusedGroup(target);
        target.getSelectionModel().select(tab);
        activeTab.set(tab);
    }

    /**
     * Removes {@code tab} from {@code owner} in a way that is safe to follow immediately with an add to
     * another group. A {@code TabPane}'s skin parents the selected tab's content node and only lets go on the
     * next layout pass, so adding to a second pane in the same frame would briefly give one node two parents.
     * Detaching the content first, and restoring it after, keeps it single-parented throughout.
     */
    private void detach(Tab tab, TabPane owner) {
        Node content = tab.getContent();
        tab.setContent(null);
        owner.getTabs().remove(tab);
        tab.setContent(content);
    }

    /**
     * Merges every group back into the primary one, preserving visual order, and leaves the area unsplit.
     * Returns whether anything changed.
     */
    boolean unsplit() {
        List<TabPane> groups = orderedGroups();
        if (groups.size() == 1) {
            return false;
        }
        Tab active = selectedTab();
        relocating = true;
        try {
            for (TabPane g : groups) {
                if (g == primary) {
                    continue;
                }
                for (Tab tab : new ArrayList<>(g.getTabs())) {
                    detach(tab, g);
                    primary.getTabs().add(tab);
                }
            }
            SplitPane parent = parentOf(primary);
            if (parent != null) {
                parent.getItems().remove(primary);
            }
            tree = primary;
            container.getChildren().setAll(primary);
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

    /** Moves focus to the next group in visual order. Returns whether the area is split enough to matter. */
    boolean focusNextGroup() {
        List<TabPane> groups = orderedGroups();
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
        for (TabPane g : orderedGroups()) {
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
        for (TabPane g : orderedGroups()) {
            g.getTabs().addListener(listener);
        }
    }

    /** Installs an event filter on every group, including groups created later. */
    <T extends Event> void addEventFilter(EventType<T> type, EventHandler<? super T> handler) {
        FilterRegistration<T> reg = new FilterRegistration<>(type, handler);
        filters.add(reg);
        for (TabPane g : orderedGroups()) {
            reg.applyTo(g);
        }
    }
}
