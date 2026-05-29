package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.fxmisc.richtext.CodeArea;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;

import javafx.application.Platform;

import javafx.scene.Node;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Tool window content showing the active editor's foldable-region hierarchy as a collapsible tree,
 * mirroring the code/text folding model ({@link com.editora.editor.FoldRegions}). A search box at the
 * top fuzzy-filters the tree, and selecting an entry navigates the editor caret to that line.
 *
 * <p>The panel marks itself with the {@code editora.ownsKeys} property so the scene-level key
 * dispatcher yields, letting it implement Emacs-style navigation locally: {@code C-n}/{@code C-p}
 * move, {@code C-f}/{@code C-b} expand/collapse (or descend/ascend), {@code Enter} jumps to the line,
 * and {@code C-g}/{@code Esc} clears the filter or returns focus to the editor.
 */
public class StructurePanel extends VBox {

    private final TextField filterField = new TextField();
    private final TreeView<StructureNode> tree = new TreeView<>();

    private EditorBuffer buffer;
    private List<StructureNode> roots = List.of();
    /** Suppresses selection-driven navigation while the tree is rebuilt programmatically. */
    private boolean suppressNavigation;

    public StructurePanel() {
        getStyleClass().add("structure-panel");
        // Opt out of the global key dispatcher so chords like C-n reach our local handler.
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        build();
    }

    private void build() {
        filterField.setPromptText("Search structure…");
        filterField.getStyleClass().add("structure-filter");
        filterField.textProperty().addListener((o, w, n) -> applyFilter(n));

        tree.setShowRoot(false);
        tree.getStyleClass().add("structure-tree");
        tree.setCellFactory(t -> new StructureCell());
        VBox.setVgrow(tree, Priority.ALWAYS);

        // Navigate as soon as the selection changes (keyboard, search, or single click), but only
        // for user-driven changes — not while the tree is rebuilt — and without stealing focus.
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressNavigation) {
                navigateTo(now, false);
            }
        });
        // A double-click activates: navigate and move focus into the editor.
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                navigateSelected();
            }
        });

        setSpacing(4);
        getChildren().addAll(filterField, tree);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    /** Attaches to a buffer (or {@code null}), rebuilding the tree and watching it for region changes. */
    public void attach(EditorBuffer buffer) {
        if (this.buffer != null) {
            this.buffer.getFoldManager().setOnRegionsChanged(null);
        }
        this.buffer = buffer;
        if (buffer == null) {
            rebuild();
            return;
        }
        buffer.getFoldManager().setOnRegionsChanged(this::rebuild);
        // Force a recompute so the tree reflects the current text immediately; the callback above
        // then drives live updates as the document changes.
        buffer.getFoldManager().recompute();
    }

    /** Moves keyboard focus into the panel (the search field), for window-switching. */
    public void focusContent() {
        filterField.requestFocus();
    }

    // --- Keyboard handling (Emacs defaults) ---

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                focusEditor();
                e.consume();
            }
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            default -> {
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        move(1);
                        e.consume();
                    }
                    case P -> {
                        move(-1);
                        e.consume();
                    }
                    case F -> {
                        expandOrDescend();
                        e.consume();
                    }
                    case B -> {
                        collapseOrAscend();
                        e.consume();
                    }
                    case M -> {
                        activateSelected();
                        e.consume();
                    }
                    case G -> {
                        clearOrFocusEditor();
                        e.consume();
                    }
                    default -> {
                    }
                }
            }
        }
    }

    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().select(next);
        ensureVisible(next);
    }

    /** Scrolls the tree only when {@code index} is outside the currently visible rows. */
    private void ensureVisible(int index) {
        VirtualFlow<?> flow = virtualFlow();
        if (flow == null) {
            tree.scrollTo(index);
            return;
        }
        IndexedCell<?> first = flow.getFirstVisibleCell();
        IndexedCell<?> last = flow.getLastVisibleCell();
        if (first == null || last == null || index < first.getIndex() || index > last.getIndex()) {
            tree.scrollTo(index);
        }
    }

    private VirtualFlow<?> virtualFlow() {
        Node node = tree.lookup(".virtual-flow");
        return node instanceof VirtualFlow<?> flow ? flow : null;
    }

    private void expandOrDescend() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().select(item.getParent());
            ensureVisible(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    private void clearOrFocusEditor() {
        if (!filterField.getText().isEmpty()) {
            filterField.clear();
            filterField.requestFocus();
        } else {
            focusEditor();
        }
    }

    private void focusEditor() {
        if (buffer != null) {
            buffer.getArea().requestFocus();
        }
    }

    /**
     * Handles Enter: expands the selected node if it is collapsed (so navigation can reveal its
     * children); otherwise activates it, navigating and moving focus into the editor.
     */
    private void activateSelected() {
        TreeItem<StructureNode> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
            return;
        }
        navigateSelected();
    }

    /** Activates the current selection: navigates and moves focus into the editor. */
    private void navigateSelected() {
        navigateTo(tree.getSelectionModel().getSelectedItem(), true);
    }

    /** Moves the editor caret to {@code item}'s region, optionally focusing the editor. */
    private void navigateTo(TreeItem<StructureNode> item, boolean focusEditor) {
        if (item == null || item.getValue() == null || item.getValue().region() == null || buffer == null) {
            return;
        }
        CodeArea area = buffer.getArea();
        int line = item.getValue().region().startLine();
        if (line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        area.moveTo(line, 0);
        // Anchor the target line at the top of the viewport (deferred until layout is ready).
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(line);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        if (focusEditor) {
            area.requestFocus();
        }
    }

    // --- Tree construction ---

    private void rebuild() {
        roots = buffer == null ? List.of() : buildNodes();
        // A rebuild reselects the first row; that must not move the editor caret.
        suppressNavigation = true;
        try {
            applyFilter(filterField.getText());
        } finally {
            suppressNavigation = false;
        }
    }

    private List<StructureNode> buildNodes() {
        CodeArea area = buffer.getArea();
        int paras = area.getParagraphs().size();
        List<Region> regions = new ArrayList<>(new LinkedHashSet<>(buffer.getFoldManager().regions()));
        // Sort so each region precedes the regions it contains: by start asc, then by end desc.
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));

        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        for (Region r : regions) {
            if (r.startLine() < 0 || r.startLine() >= paras) {
                continue;
            }
            String text = area.getParagraph(r.startLine()).getText().trim();
            StructureNode node = new StructureNode(r, text.isEmpty() ? "line " + (r.startLine() + 1) : text);
            while (!stack.isEmpty() && !contains(stack.peek().region(), r)) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
        }
        return rootNodes;
    }

    private static boolean contains(Region outer, Region inner) {
        return outer.startLine() <= inner.startLine() && outer.endLine() >= inner.endLine();
    }

    private void applyFilter(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        TreeItem<StructureNode> root = new TreeItem<>(null);
        for (StructureNode n : roots) {
            TreeItem<StructureNode> item = toItem(n, q);
            if (item != null) {
                root.getChildren().add(item);
            }
        }
        if (root.getChildren().isEmpty()) {
            String message = buffer == null ? "No file open" : (q.isEmpty() ? "No structure" : "No matches");
            root.getChildren().add(new TreeItem<>(new StructureNode(null, message)));
            tree.setRoot(root);
        } else {
            tree.setRoot(root);
            tree.getSelectionModel().select(0);
        }
    }

    /** Builds a tree item for {@code node}, keeping it if it matches the query or has a matching descendant. */
    private TreeItem<StructureNode> toItem(StructureNode node, String q) {
        List<TreeItem<StructureNode>> childItems = new ArrayList<>();
        for (StructureNode child : node.children()) {
            TreeItem<StructureNode> ci = toItem(child, q);
            if (ci != null) {
                childItems.add(ci);
            }
        }
        boolean self = q.isEmpty()
                || CommandPalette.isSubsequence(q, node.label().toLowerCase(Locale.ROOT));
        if (!self && childItems.isEmpty()) {
            return null;
        }
        TreeItem<StructureNode> item = new TreeItem<>(node);
        item.getChildren().setAll(childItems);
        // Collapsed by default; expand only while filtering so matches stay visible.
        item.setExpanded(!q.isEmpty());
        return item;
    }

    private static final class StructureCell extends TreeCell<StructureNode> {
        @Override
        protected void updateItem(StructureNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.label());
        }
    }

    /** A node in the structure tree: a foldable region plus its header-line label and children. */
    private static final class StructureNode {
        private final Region region;
        private final String label;
        private final List<StructureNode> children = new ArrayList<>();

        StructureNode(Region region, String label) {
            this.region = region;
            this.label = label;
        }

        Region region() {
            return region;
        }

        String label() {
            return label;
        }

        List<StructureNode> children() {
            return children;
        }
    }
}
