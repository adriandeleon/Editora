package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.lsp.LspManager;

import static com.editora.i18n.Messages.tr;

/**
 * The "Hierarchy" tool window (#682): who calls / is called by a method ({@code callHierarchy}) or a
 * type's super/subtypes ({@code typeHierarchy}), as a lazily-expanded tree — each expansion is one
 * server request for that node's children. A two-state direction toggle (Callers|Callees, resp.
 * Supertypes|Subtypes) rebuilds the tree from the same anchor. Enter / double-click jumps to the node
 * (when it lives in a file — a JDK type has no navigable path). Mirrors {@link ReferencesPanel}
 * ({@link ToolWindowContent}); requests route back through {@link Loader}.
 */
public final class HierarchyPanel extends VBox implements ToolWindowContent {

    /** Coordinator callbacks: expand a node's children in the current mode/direction, and navigate. */
    public interface Loader {
        /** Children of {@code node} — callers/callees or supertypes/subtypes per the panel state. */
        void children(LspManager.HierarchyNode node, boolean primary, Consumer<List<LspManager.HierarchyNode>> cb);

        /** Opens {@code file} at a 0-based position. */
        void open(java.nio.file.Path file, int line, int col);
    }

    /** The two hierarchy modes; the toggle labels follow. */
    public enum Mode {
        CALLS,
        TYPES
    }

    private final Loader loader;
    private final Label summary = new Label();
    private final ToggleButton primaryToggle = new ToggleButton();
    private final ToggleButton secondaryToggle = new ToggleButton();
    private final TreeView<LspManager.HierarchyNode> tree = new TreeView<>();

    private Mode mode = Mode.CALLS;
    private List<LspManager.HierarchyNode> roots = List.of();

    /** A child marker meaning "not fetched yet" — replaced by the real children on first expand. */
    private static final LspManager.HierarchyNode PENDING = new LspManager.HierarchyNode("…", "", "", null, 0, 0, null);

    public HierarchyPanel(Loader loader) {
        this.loader = loader;
        getStyleClass().add("hierarchy-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        summary.getStyleClass().add("search-summary");
        ToggleGroup group = new ToggleGroup();
        primaryToggle.setToggleGroup(group);
        secondaryToggle.setToggleGroup(group);
        primaryToggle.setSelected(true);
        // A direction flip re-roots the tree — children reload lazily under the new direction.
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                (was == primaryToggle ? primaryToggle : secondaryToggle).setSelected(true); // one always on
            } else {
                rebuild();
            }
        });
        HBox toolbar = new HBox(6, primaryToggle, secondaryToggle, summary);
        toolbar.getStyleClass().add("hierarchy-toolbar");

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new NodeCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        tree.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected();
                e.consume();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().addAll(toolbar, tree);
        relabel();
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (tree.getSelectionModel().getSelectedIndex() < 0
                && !tree.getRoot().getChildren().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Shows a fresh hierarchy: the anchor item(s) at the caret, in {@code mode}, primary direction. */
    public void setRoots(Mode mode, List<LspManager.HierarchyNode> roots) {
        this.mode = mode;
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        primaryToggle.setSelected(true);
        relabel();
        rebuild();
    }

    /** Whether the tree currently expands toward the primary direction (callers / supertypes). */
    private boolean primary() {
        return primaryToggle.isSelected();
    }

    private void relabel() {
        primaryToggle.setText(tr(mode == Mode.CALLS ? "hierarchy.callers" : "hierarchy.supertypes"));
        secondaryToggle.setText(tr(mode == Mode.CALLS ? "hierarchy.callees" : "hierarchy.subtypes"));
    }

    private void rebuild() {
        TreeItem<LspManager.HierarchyNode> root = new TreeItem<>();
        for (LspManager.HierarchyNode n : roots) {
            root.getChildren().add(lazyItem(n));
        }
        tree.setRoot(root);
        summary.setText(tr("hierarchy.summary", roots.size()));
        if (!root.getChildren().isEmpty()) {
            root.getChildren().get(0).setExpanded(true); // fetch the anchor's children right away
            tree.getSelectionModel().select(0);
        }
    }

    /** A tree item that fetches its children on first expansion (one request per node). */
    private TreeItem<LspManager.HierarchyNode> lazyItem(LspManager.HierarchyNode node) {
        TreeItem<LspManager.HierarchyNode> item = new TreeItem<>(node);
        item.setGraphic(StructureIcons.forKind(node.kindName()));
        item.getChildren().add(new TreeItem<>(PENDING));
        item.expandedProperty().addListener((o, was, expanded) -> {
            if (!expanded
                    || item.getChildren().size() != 1
                    || item.getChildren().get(0).getValue() != PENDING) {
                return; // already loaded (or loading)
            }
            boolean dir = primary();
            loader.children(node, dir, children -> {
                if (dir != primary() || tree.getRoot() == null) {
                    return; // direction flipped / re-rooted while the request was in flight — stale
                }
                item.getChildren().clear();
                for (LspManager.HierarchyNode c : children) {
                    item.getChildren().add(lazyItem(c));
                }
            });
        });
        return item;
    }

    private void openSelected() {
        TreeItem<LspManager.HierarchyNode> sel = tree.getSelectionModel().getSelectedItem();
        LspManager.HierarchyNode n = sel == null ? null : sel.getValue();
        if (n != null && n != PENDING && n.file() != null) {
            loader.open(n.file(), n.line(), n.col());
        }
    }

    private static final class NodeCell extends TreeCell<LspManager.HierarchyNode> {
        @Override
        protected void updateItem(LspManager.HierarchyNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setGraphic(getTreeItem() == null ? null : getTreeItem().getGraphic());
            String detail = n.detail().isBlank() ? "" : "  —  " + n.detail();
            String where = n.file() == null ? "" : "  ·  " + n.file().getFileName() + ":" + (n.line() + 1);
            setText(n.name() + detail + where);
        }
    }
}
