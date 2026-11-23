package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.jspecify.annotations.Nullable;

/**
 * What the user had in hand in a {@link TreeView} whose rows are about to be rebuilt from fresh data: the
 * selected rows, the focused row, and where to land when the selection itself is gone.
 *
 * <p>Rows are matched by a caller-supplied key, not by position or item identity — the rebuilt tree holds new
 * items. When none of the selected rows survives (the file was just staged and left its group), the selection
 * moves to the row that followed it, so acting on a list from the keyboard continues with the next row
 * instead of starting again at the top.
 */
final class TreeViewState {

    private final Set<String> selected;
    private final @Nullable String focused;
    /** The sibling after the last selected row, then the one before the first: where to go if all are gone. */
    private final List<String> fallbacks;

    private TreeViewState(Set<String> selected, @Nullable String focused, List<String> fallbacks) {
        this.selected = selected;
        this.focused = focused;
        this.fallbacks = fallbacks;
    }

    static <T> TreeViewState capture(TreeView<T> tree, Function<T, String> key) {
        Set<String> selected = new LinkedHashSet<>();
        List<String> fallbacks = new ArrayList<>(2);
        for (TreeItem<T> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                selected.add(key.apply(item.getValue()));
            }
        }
        // The first and last selected rows from the selection's own indices: asking the tree for each item's row
        // costs a walk of its siblings per item, which after Ctrl+A on a long list is quadratic.
        int firstRow = Integer.MAX_VALUE;
        int lastRow = -1;
        for (Integer row : tree.getSelectionModel().getSelectedIndices()) {
            if (row != null && row >= 0) {
                firstRow = Math.min(firstRow, row);
                lastRow = Math.max(lastRow, row);
            }
        }
        TreeItem<T> first = lastRow < 0 ? null : tree.getTreeItem(firstRow);
        TreeItem<T> last = lastRow < 0 ? null : tree.getTreeItem(lastRow);
        addSibling(fallbacks, last, 1, key);
        addSibling(fallbacks, first, -1, key);
        TreeItem<T> focusedItem = tree.getFocusModel().getFocusedItem();
        String focused =
                focusedItem == null || focusedItem.getValue() == null ? null : key.apply(focusedItem.getValue());
        return new TreeViewState(selected, focused, fallbacks);
    }

    private static <T> void addSibling(
            List<String> out, @Nullable TreeItem<T> item, int step, Function<T, String> key) {
        if (item == null || item.getParent() == null) {
            return;
        }
        List<TreeItem<T>> siblings = item.getParent().getChildren();
        int at = siblings.indexOf(item) + step;
        if (at >= 0 && at < siblings.size() && siblings.get(at).getValue() != null) {
            out.add(key.apply(siblings.get(at).getValue()));
        }
    }

    /** Re-selects and re-focuses the captured rows in the rebuilt {@code tree}. */
    <T> void restore(TreeView<T> tree, Function<T, String> key) {
        var selection = tree.getSelectionModel();
        selection.clearSelection();
        if (selected.isEmpty()) {
            return;
        }
        List<Integer> rows = new ArrayList<>();
        int focusRow = -1;
        int fallbackRow = -1;
        int fallbackRank = Integer.MAX_VALUE;
        // One walk of the visible rows in order, counting them, instead of asking the tree for each row: right after
        // a rebuild its row cache is empty and each getTreeItem(row) steps through the siblings before it (O(n²)).
        List<TreeItem<T>> visible = visibleRows(tree);
        for (int row = 0, n = visible.size(); row < n; row++) {
            TreeItem<T> item = visible.get(row);
            if (item.getValue() == null) {
                continue;
            }
            String k = key.apply(item.getValue());
            if (selected.contains(k)) {
                rows.add(row);
            }
            if (k.equals(focused)) {
                focusRow = row;
            }
            int rank = fallbacks.indexOf(k);
            if (rank >= 0 && rank < fallbackRank) {
                fallbackRank = rank;
                fallbackRow = row;
            }
        }
        if (rows.isEmpty() && fallbackRow >= 0) {
            rows.add(fallbackRow);
            focusRow = fallbackRow;
        }
        if (rows.isEmpty()) {
            return;
        }
        int[] rest = rows.stream().skip(1).mapToInt(Integer::intValue).toArray();
        selection.selectIndices(rows.get(0), rest);
        tree.getFocusModel().focus(focusRow >= 0 ? focusRow : rows.get(rows.size() - 1));
    }

    /** The rows {@code tree} shows, in row order: the root when it is shown, then every child of an expanded item. */
    static <T> List<TreeItem<T>> visibleRows(TreeView<T> tree) {
        List<TreeItem<T>> rows = new ArrayList<>();
        TreeItem<T> root = tree.getRoot();
        if (root == null) {
            return rows;
        }
        java.util.ArrayDeque<TreeItem<T>> pending = new java.util.ArrayDeque<>();
        if (tree.isShowRoot()) {
            pending.push(root);
        } else if (root.isExpanded()) {
            pushChildren(pending, root);
        }
        while (!pending.isEmpty()) {
            TreeItem<T> item = pending.pop();
            rows.add(item);
            if (item.isExpanded()) {
                pushChildren(pending, item);
            }
        }
        return rows;
    }

    private static <T> void pushChildren(java.util.ArrayDeque<TreeItem<T>> pending, TreeItem<T> item) {
        List<TreeItem<T>> children = item.getChildren();
        for (int i = children.size() - 1; i >= 0; i--) {
            pending.push(children.get(i));
        }
    }
}
