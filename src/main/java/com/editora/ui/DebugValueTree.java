package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TreeItem;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The rows of an evaluated value, loaded as they are opened: an object's fields on first expansion, a large array a
 * page at a time behind a "Show N more…" row. Shared by the hover popup ({@link DebugValuePopup}) and the Evaluate
 * window's result ({@link DebugEvaluateWindow}); the Debug window's own Variables tree has more to it (Set Value,
 * changed values, rows the stop's budget left unloaded) and loads its rows itself.
 */
final class DebugValueTree {

    /** The DAP {@code variables} request: {@code filter} null for all children, {@code count > 0} one page. */
    interface Children {
        void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb);
    }

    private final Children source;

    DebugValueTree(Children source) {
        this.source = source;
    }

    /** A tree item whose children load on first expansion (a page at a time for a large array). */
    TreeItem<DebugPanel.VarRow> lazy(DebugPanel.VarRow row) {
        TreeItem<DebugPanel.VarRow> item = new TreeItem<>(row) {
            @Override
            public boolean isLeaf() {
                return row.ref() <= 0;
            }
        };
        if (row.ref() > 0) {
            boolean[] loaded = {false};
            item.expandedProperty().addListener((o, was, now) -> {
                if (now && !loaded[0]) {
                    loaded[0] = true;
                    load(item, row);
                }
            });
        }
        return item;
    }

    private void load(TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row) {
        if (!DebugValues.fetchedByPage(row.indexed())) {
            source.children(row.ref(), null, 0, 0, all -> page(item, row, all, 0));
            return;
        }
        if (row.named() > 0) {
            source.children(row.ref(), "named", 0, 0, named -> {
                item.getChildren().addAll(rows(row, named));
                requestPage(item, row, 0);
            });
        } else {
            requestPage(item, row, 0);
        }
    }

    private void requestPage(TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row, int start) {
        int count = DebugValues.nextPage(start, row.indexed());
        source.children(row.ref(), "indexed", start, count, got -> {
            dropMore(item);
            if (got.size() > count) {
                page(item, row, got, 0); // the adapter ignored start/count
                return;
            }
            item.getChildren().addAll(rows(row, got));
            int done = start + got.size();
            int next = DebugValues.nextPage(done, row.indexed());
            if (next > 0 && !got.isEmpty()) {
                item.getChildren().add(new More(next, row.indexed() - done, () -> requestPage(item, row, done)));
            }
        });
    }

    private void page(
            TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row, List<DapModels.VariableInfo> all, int start) {
        dropMore(item);
        int end = start + DebugValues.nextPage(start, all.size());
        item.getChildren().addAll(rows(row, all.subList(start, end)));
        int next = DebugValues.nextPage(end, all.size());
        if (next > 0) {
            item.getChildren().add(new More(next, all.size() - end, () -> page(item, row, all, end)));
        }
    }

    private List<TreeItem<DebugPanel.VarRow>> rows(DebugPanel.VarRow parent, List<DapModels.VariableInfo> vars) {
        List<TreeItem<DebugPanel.VarRow>> out = new ArrayList<>(vars.size());
        for (DapModels.VariableInfo v : vars) {
            out.add(lazy(new DebugPanel.VarRow(
                    v.name(),
                    v.value(),
                    v.type() == null ? "" : v.type(),
                    v.variablesReference(),
                    parent.ref(),
                    DebugPanel.Kind.VARIABLE,
                    v.namedVariables(),
                    v.indexedVariables(),
                    false,
                    null,
                    v.evaluateName())));
        }
        return out;
    }

    private static void dropMore(TreeItem<DebugPanel.VarRow> item) {
        var children = item.getChildren();
        if (!children.isEmpty() && children.getLast() instanceof More) {
            children.removeLast();
        }
    }

    /** "Show N more…": Enter or a double-click loads the next page. */
    static final class More extends TreeItem<DebugPanel.VarRow> {
        private final Runnable next;
        private boolean requested;

        More(int next, int remaining, Runnable load) {
            super(new DebugPanel.VarRow(
                    tr("debugpanel.showMore", next, remaining), "", "", 0, 0, DebugPanel.Kind.MORE));
            this.next = load;
        }

        void load() {
            if (!requested) {
                requested = true;
                next.run();
            }
        }
    }
}
