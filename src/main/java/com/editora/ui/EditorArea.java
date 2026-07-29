package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;
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
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import com.editora.config.EditorGroupLayout;

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

    /** What the controller is currently dragging, if anything. See {@link #setDraggedTabSource}. */
    private Supplier<Tab> draggedTab;

    /** While >= 0, {@link #add(Tab)} routes to this group index instead of the focused one (session restore). */
    private int restoreTargetGroup = -1;

    /**
     * Translucent overlay showing where a dragged tab would land. Unmanaged and mouse-transparent so it can
     * be positioned freely over any group without taking part in layout or swallowing the drag events it is
     * drawn in response to.
     */
    private final Region dropIndicator = new Region();

    private boolean tabHeaderVisible = true;

    private record FilterRegistration<T extends Event>(EventType<T> type, EventHandler<? super T> handler) {
        void applyTo(Node node) {
            node.addEventFilter(type, handler);
        }
    }

    EditorArea(TabPane initial) {
        this.primary = initial;
        this.singleGroupView = Collections.unmodifiableList(initial.getTabs());
        // NOT "editor-area": that class is the editor *text surface*, and it pins JetBrains Mono at 14px
        // (see app.css). Putting it on the container made every tab header inside inherit the editor's
        // monospace font instead of the UI font — the tab titles rendered wrong app-wide.
        container.getStyleClass().add("editor-area-root");
        dropIndicator.getStyleClass().add("editor-drop-indicator");
        dropIndicator.setManaged(false);
        dropIndicator.setMouseTransparent(true);
        dropIndicator.setVisible(false);
        wire(initial);
        tree = initial;
        container.getChildren().setAll(initial, dropIndicator);
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
        // Catch-all collapse. remove() discards an emptied group directly, but not every close goes through
        // it: clicking a tab's close button fires Tab.onCloseRequest and then *JavaFX itself* removes the tab
        // from the pane, so the group empties without this class being told. Watching the list covers every
        // path — button, command, or programmatic — instead of only the ones routed through remove().
        //
        // Deferred, because this fires *during* the list change: discarding here would restructure the scene
        // graph from inside a mutation, the hazard documented on the focusWithin listener above. By the next
        // pulse the change has settled; the group is re-checked then, so a discard that already happened
        // (remove()'s own synchronous one) is a no-op rather than a double removal.
        group.getTabs().addListener((ListChangeListener<Tab>) c -> {
            if (relocating || !group.getTabs().isEmpty() || groupCount() < 2) {
                return;
            }
            Platform.runLater(() -> {
                if (!relocating
                        && group.getTabs().isEmpty()
                        && groupCount() > 1
                        && orderedGroups().contains(group)) {
                    discard(group);
                }
            });
        });
        // Dropping a tab onto the group body: middle moves it here, an edge splits this group that way.
        group.setOnDragOver(e -> onDragOverGroup(group, e));
        group.setOnDragExited(e -> hideDropIndicator());
        group.setOnDragDropped(e -> {
            boolean moved = onDropOnGroup(group, e);
            e.setDropCompleted(moved);
            e.consume();
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

    /**
     * Appends {@code tab} to the focused group — or, during a session restore, to the group named by
     * {@link #setRestoreTargetGroup}. Routing at insert time rather than moving the tab afterwards matters:
     * every move is a remove-then-add that the rest of the UI has to be told to ignore, and doing that once
     * per restored file is both slower and more to get wrong.
     */
    void add(Tab tab) {
        if (restoreTargetGroup >= 0) {
            addToGroup(restoreTargetGroup, tab);
            return;
        }
        focused.getTabs().add(tab);
    }

    /**
     * Directs subsequent {@link #add(Tab)} calls to the group at this depth-first index; {@code -1} restores
     * the normal "append to the focused group" behaviour. Only used while restoring a session.
     */
    void setRestoreTargetGroup(int index) {
        this.restoreTargetGroup = index;
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
            container.getChildren().setAll(only, dropIndicator);
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
        insertBeside(leaf, fresh, orientation, true);
    }

    private void insertBeside(TabPane leaf, TabPane fresh, Orientation orientation, boolean after) {
        SplitPane parent = parentOf(leaf);
        relocating = true;
        try {
            if (parent != null && parent.getOrientation() == orientation) {
                parent.getItems().add(parent.getItems().indexOf(leaf) + (after ? 1 : 0), fresh);
                return;
            }
            SplitPane branch = new SplitPane();
            branch.setOrientation(orientation);
            if (parent == null) {
                container.getChildren().remove(leaf); // single-parent the leaf before the branch adopts it
                branch.getItems().setAll(after ? List.of(leaf, fresh) : List.of(fresh, leaf));
                tree = branch;
                container.getChildren().setAll(branch, dropIndicator);
            } else {
                int at = parent.getItems().indexOf(leaf);
                parent.getItems().remove(leaf);
                branch.getItems().setAll(after ? List.of(leaf, fresh) : List.of(fresh, leaf));
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
            container.getChildren().setAll(primary, dropIndicator);
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

    // --- saved layout ----------------------------------------------------------------------------------

    /** The index of the group holding {@code tab} in depth-first order, or {@code -1} if it is not open. */
    int groupIndexOf(Tab tab) {
        TabPane owner = ownerOf(tab);
        return owner == null ? -1 : orderedGroups().indexOf(owner);
    }

    /**
     * The current split shape, for the session file — {@code null} while unsplit, so an unsplit window writes
     * nothing and an older reader is unaffected. Leaves carry only their selected-tab index; which files sit
     * in which group is recorded per file, so the two halves cannot disagree (see {@link EditorGroupLayout}).
     */
    EditorGroupLayout snapshotLayout(Predicate<Tab> persisted) {
        return isSplit() ? describe(tree, persisted) : null;
    }

    private static EditorGroupLayout describe(Node node, Predicate<Tab> persisted) {
        if (node instanceof TabPane leaf) {
            return EditorGroupLayout.leaf(persistedIndexOfSelection(leaf, persisted));
        }
        SplitPane branch = (SplitPane) node;
        List<EditorGroupLayout> children = new ArrayList<>();
        for (Node child : branch.getItems()) {
            children.add(describe(child, persisted));
        }
        return EditorGroupLayout.branch(branch.getOrientation().name(), children);
    }

    /**
     * The selected tab's index <em>among the tabs that will actually be written to the session</em>, which is
     * not the same as its index in the group.
     *
     * <p>Not every open tab is persisted — the Welcome tab and an unsaved buffer have no path, so the session
     * skips them — but they still occupy a slot in the group. Recording the live index would therefore save a
     * number in one coordinate system and restore it in another: a group of {@code [Welcome, a.c, b.c]} with
     * {@code b.c} selected would save index 2 and, on restore into a two-tab group, select {@code a.c}. The
     * clamp hides it whenever the number lands out of range, which is why the symptom is intermittent and
     * looks like "sometimes the wrong file is focused" rather than an obvious break.
     *
     * <p>Counting only persisted tabs puts the index in the same space restore fills the group in. A selected
     * tab that is not itself persisted lands on the next one that is, which is the closest thing to right.
     */
    private static int persistedIndexOfSelection(TabPane leaf, Predicate<Tab> persisted) {
        Tab selected = leaf.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return 0;
        }
        int index = 0;
        for (Tab tab : leaf.getTabs()) {
            if (tab == selected) {
                break;
            }
            if (persisted.test(tab)) {
                index++;
            }
        }
        return index;
    }

    /**
     * Rebuilds the split shape to match {@code layout}, leaving every group empty and ready for
     * {@link #addToGroup}. A null or single-leaf layout collapses the area to one group.
     *
     * <p>Restoring the shape <em>before</em> any file is opened is what lets the session fill straight into
     * the right groups; building it afterwards would mean moving every tab a second time, and each move is a
     * remove-then-add that the rest of the UI has to be told to ignore.
     */
    void restoreLayout(EditorGroupLayout layout) {
        unsplit();
        if (layout == null || layout.leafCount() < 2) {
            return;
        }
        relocating = true;
        try {
            container.getChildren().remove(primary);
            Node rebuilt = rebuild(layout, new java.util.ArrayDeque<>(List.of(primary)));
            tree = rebuilt;
            container.getChildren().setAll(rebuilt, dropIndicator);
        } finally {
            relocating = false;
        }
        focused = primary;
    }

    /** Materialises {@code node}, reusing the primary pane for the first leaf so its identity is preserved. */
    private Node rebuild(EditorGroupLayout node, java.util.Deque<TabPane> reusable) {
        if (node.isLeaf()) {
            TabPane leaf = reusable.poll();
            if (leaf == null) {
                leaf = new TabPane();
                leaf.setTabClosingPolicy(primary.getTabClosingPolicy());
                wire(leaf);
            }
            return leaf;
        }
        SplitPane branch = new SplitPane();
        branch.setOrientation("VERTICAL".equals(node.getOrientation()) ? Orientation.VERTICAL : Orientation.HORIZONTAL);
        for (EditorGroupLayout child : node.getChildren()) {
            branch.getItems().add(rebuild(child, reusable));
        }
        return branch;
    }

    /** Appends {@code tab} to the group at depth-first index {@code index}, clamped into range. */
    void addToGroup(int index, Tab tab) {
        List<TabPane> groups = orderedGroups();
        groups.get(Math.max(0, Math.min(index, groups.size() - 1))).getTabs().add(tab);
    }

    /**
     * Applies each leaf's saved selected-tab index. Called once the session's files are in place; indices
     * are clamped, since a file in the layout may no longer exist on disk. Deliberately leaves focus alone —
     * the caller re-selects the session's active file afterwards, and that is what decides the focused group.
     */
    void applyRestoredSelection(EditorGroupLayout layout) {
        List<TabPane> groups = orderedGroups();
        List<EditorGroupLayout> leaves = new ArrayList<>();
        if (layout != null) {
            collectLeaves(layout, leaves);
        }
        for (int i = 0; i < groups.size() && i < leaves.size(); i++) {
            TabPane group = groups.get(i);
            if (!group.getTabs().isEmpty()) {
                int at = Math.max(
                        0, Math.min(leaves.get(i).getSelected(), group.getTabs().size() - 1));
                group.getSelectionModel().select(at);
            }
        }
    }

    private static void collectLeaves(EditorGroupLayout node, List<EditorGroupLayout> out) {
        if (node.isLeaf()) {
            out.add(node);
            return;
        }
        for (EditorGroupLayout child : node.getChildren()) {
            collectLeaves(child, out);
        }
    }

    /**
     * Drops any group left empty by a restore. A saved file can be gone from disk, and a group whose every
     * file vanished would otherwise come back as a permanently blank pane the user has to close by hand.
     */
    void pruneEmptyGroups() {
        for (TabPane group : orderedGroups()) {
            if (group.getTabs().isEmpty() && groupCount() > 1) {
                discard(group);
            }
        }
    }

    // --- drag and drop ---------------------------------------------------------------------------------

    /**
     * Supplies the tab currently being dragged, if any. Owned by the controller, which starts the drag from
     * the tab header; the area only needs to know what is in flight when a drop lands on a group.
     */
    void setDraggedTabSource(Supplier<Tab> source) {
        this.draggedTab = source;
    }

    /**
     * Handles a drag hovering over {@code group}: shows where the tab would land and accepts the transfer.
     * Dropping into the middle moves the tab into that group; dropping near an edge splits the group and puts
     * the tab on that side.
     */
    private void onDragOverGroup(TabPane group, DragEvent e) {
        Tab dragged = draggedTab == null ? null : draggedTab.get();
        if (dragged == null) {
            return;
        }
        DropZone zone = DropZone.of(e.getX(), e.getY(), group.getWidth(), group.getHeight());
        if (!isMeaningful(dragged, group, zone)) {
            hideDropIndicator();
            return;
        }
        e.acceptTransferModes(TransferMode.MOVE);
        showDropIndicator(group, zone);
        e.consume();
    }

    /**
     * Whether the drop would actually change anything. Dropping a tab into the middle of the group it already
     * lives in is a no-op, and splitting a group off its only tab would empty that group, collapse it, and
     * land back where it started — so neither should light up a target.
     */
    private boolean isMeaningful(Tab dragged, TabPane group, DropZone zone) {
        boolean sameGroup = group.getTabs().contains(dragged);
        if (!zone.isSplit()) {
            return !sameGroup;
        }
        return !(sameGroup && group.getTabs().size() < 2);
    }

    /** Performs a drop on {@code group}. Returns whether anything moved. */
    private boolean onDropOnGroup(TabPane group, DragEvent e) {
        hideDropIndicator();
        Tab dragged = draggedTab == null ? null : draggedTab.get();
        if (dragged == null) {
            return false;
        }
        DropZone zone = DropZone.of(e.getX(), e.getY(), group.getWidth(), group.getHeight());
        if (!isMeaningful(dragged, group, zone)) {
            return false;
        }
        if (!zone.isSplit()) {
            relocate(dragged, group);
            return true;
        }
        TabPane fresh = new TabPane();
        fresh.setTabClosingPolicy(primary.getTabClosingPolicy());
        wire(fresh);
        boolean horizontal = zone == DropZone.LEFT || zone == DropZone.RIGHT;
        boolean after = zone == DropZone.RIGHT || zone == DropZone.BOTTOM;
        insertBeside(group, fresh, horizontal ? Orientation.HORIZONTAL : Orientation.VERTICAL, after);
        relocate(dragged, fresh);
        return true;
    }

    /** Highlights the region the dragged tab would occupy: the whole group, or the half it would split off. */
    private void showDropIndicator(TabPane group, DropZone zone) {
        javafx.geometry.Bounds b = container.sceneToLocal(group.localToScene(group.getBoundsInLocal()));
        double x = b.getMinX();
        double y = b.getMinY();
        double w = b.getWidth();
        double h = b.getHeight();
        switch (zone) {
            case LEFT -> w /= 2;
            case RIGHT -> {
                x += w / 2;
                w /= 2;
            }
            case TOP -> h /= 2;
            case BOTTOM -> {
                y += h / 2;
                h /= 2;
            }
            case CENTER -> {
                /* the whole group */
            }
        }
        dropIndicator.setVisible(true);
        dropIndicator.resizeRelocate(x, y, w, h);
        dropIndicator.toFront();
    }

    private void hideDropIndicator() {
        dropIndicator.setVisible(false);
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
