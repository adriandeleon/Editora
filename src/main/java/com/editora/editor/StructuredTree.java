package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.structured.StructuredNode;

/**
 * Renders a parsed {@link StructuredNode} tree (JSON / YAML / TOML) into a self-scrolling JavaFX
 * {@code TreeView} for the 3-mode preview — a collapsible, type-colored structure view. Kept in
 * {@code editor} (no {@code ui} dependency), like {@code CompletionPopup}/{@code MarkwhenTimeline}. The
 * {@code TreeView} manages its own scrolling + row virtualization, so it's hosted directly as the
 * Split/Preview side rather than inside the preview {@code ScrollPane} (the CSV-grid pattern).
 */
public final class StructuredTree {

    /** Root plus this many child levels are auto-expanded; deeper levels start collapsed. */
    private static final int AUTO_EXPAND_DEPTH = 1;

    private StructuredTree() {}

    public static Node build(StructuredNode root) {
        TreeItem<StructuredNode> item = toItem(root);
        expand(item, AUTO_EXPAND_DEPTH);
        TreeView<StructuredNode> tree = new TreeView<>(item);
        tree.getStyleClass().add("structured-tree");
        tree.setShowRoot(true);
        tree.setCellFactory(tv -> new StructuredCell());
        return tree;
    }

    /**
     * A flat, fully-expanded, depth-indented list of every node's rendered row — the whole tree as
     * standalone {@code TextFlow}s (not a virtualized {@code TreeView}), for snapshot-to-PDF export.
     */
    public static java.util.List<Node> printableRows(StructuredNode root) {
        java.util.List<Node> rows = new java.util.ArrayList<>();
        appendRows(root, 0, rows);
        return rows;
    }

    private static void appendRows(StructuredNode n, int depth, java.util.List<Node> rows) {
        TextFlow row = render(n);
        if (depth > 0) {
            row.getChildren().add(0, styled("  ".repeat(depth), "structured-punct")); // monospace indent
        }
        rows.add(row);
        for (StructuredNode c : n.children()) {
            appendRows(c, depth + 1, rows);
        }
    }

    private static TreeItem<StructuredNode> toItem(StructuredNode n) {
        TreeItem<StructuredNode> item = new TreeItem<>(n);
        for (StructuredNode c : n.children()) {
            item.getChildren().add(toItem(c));
        }
        return item;
    }

    private static void expand(TreeItem<?> item, int depth) {
        if (depth < 0 || item.isLeaf()) {
            return;
        }
        item.setExpanded(true);
        for (TreeItem<?> c : item.getChildren()) {
            expand(c, depth - 1);
        }
    }

    /** A cell rendering {@code key: value} with the value colored by type (or {@code key {n}}/{@code [n]} for a container). */
    private static final class StructuredCell extends TreeCell<StructuredNode> {
        @Override
        protected void updateItem(StructuredNode n, boolean empty) {
            super.updateItem(n, empty);
            if (empty || n == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setGraphic(render(n));
        }
    }

    private static TextFlow render(StructuredNode n) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("structured-row");
        if (n.key() != null) {
            flow.getChildren().add(styled(n.key(), "structured-key"));
            flow.getChildren().add(styled(": ", "structured-punct"));
        }
        if (n.isContainer()) {
            String badge = n.kind() == StructuredNode.Kind.OBJECT ? "{" + n.size() + "}" : "[" + n.size() + "]";
            flow.getChildren().add(styled(badge, "structured-count"));
        } else {
            String text = n.kind() == StructuredNode.Kind.STRING ? '"' + oneLine(n.value()) + '"' : n.value();
            flow.getChildren().add(styled(text, valueClass(n.kind())));
        }
        return flow;
    }

    private static Text styled(String text, String styleClass) {
        Text t = new Text(text);
        t.getStyleClass().add(styleClass);
        return t;
    }

    /** Collapses newlines/tabs so a multi-line string value stays on one tree row. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private static String valueClass(StructuredNode.Kind kind) {
        return switch (kind) {
            case STRING -> "structured-string";
            case NUMBER -> "structured-number";
            case BOOLEAN -> "structured-boolean";
            default -> "structured-null";
        };
    }
}
