package com.editora.ui;

import java.util.List;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TreeViewState}: the rows it walks are the rows the tree shows, in the tree's own order, so the selection it
 * puts back after a rebuild lands on the same rows without asking the tree for each row (F1, perf review 2026-11-23).
 */
@Tag("fx")
class TreeViewStateFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static TreeItem<String> item(String name, boolean expanded, List<TreeItem<String>> children) {
        TreeItem<String> item = new TreeItem<>(name);
        item.getChildren().setAll(children);
        item.setExpanded(expanded);
        return item;
    }

    @Test
    void theVisibleRowsAreTheTreesRowsInOrder() throws Exception {
        for (boolean showRoot : new boolean[] {true, false}) {
            List<String> mismatches = FxTestSupport.callOnFx(() -> {
                TreeItem<String> root = item(
                        "root",
                        true,
                        List.of(
                                item("a", true, List.of(new TreeItem<>("a1"), new TreeItem<>("a2"))),
                                item("b", false, List.of(new TreeItem<>("b1"))),
                                item("c", true, List.of(item("c1", true, List.of(new TreeItem<>("c1x"))))),
                                new TreeItem<>("d")));
                TreeView<String> tree = new TreeView<>(root);
                tree.setShowRoot(showRoot);
                List<TreeItem<String>> rows = TreeViewState.visibleRows(tree);
                List<String> out = new java.util.ArrayList<>();
                if (rows.size() != tree.getExpandedItemCount()) {
                    out.add("count " + rows.size() + " vs " + tree.getExpandedItemCount());
                }
                for (int i = 0; i < Math.min(rows.size(), tree.getExpandedItemCount()); i++) {
                    if (rows.get(i) != tree.getTreeItem(i)) {
                        out.add("row " + i);
                    }
                }
                return out;
            });
            assertEquals(List.of(), mismatches, "showRoot=" + showRoot);
        }
    }

    @Test
    void theSelectionIsPutBackByKeyOnTheRebuiltRows() throws Exception {
        List<String> selected = FxTestSupport.callOnFx(() -> {
            TreeItem<String> root =
                    item("", true, List.of(item("g", true, List.of(new TreeItem<>("x"), new TreeItem<>("y")))));
            TreeView<String> tree = new TreeView<>(root);
            tree.setShowRoot(false);
            tree.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
            tree.getSelectionModel().selectIndices(1, 2);
            TreeViewState kept = TreeViewState.capture(tree, s -> s);
            root.getChildren()
                    .setAll(item("g", true, List.of(new TreeItem<>("w"), new TreeItem<>("x"), new TreeItem<>("y"))));
            kept.restore(tree, s -> s);
            return tree.getSelectionModel().getSelectedItems().stream()
                    .map(TreeItem::getValue)
                    .toList();
        });
        assertEquals(List.of("x", "y"), selected);
    }
}
