package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * A {@link ProjectPanel} in a shown stage over a small project on disk, with everything the panel reports
 * recorded: opened, renamed and deleted paths, status messages and rename/new-folder prompts. Deletes go
 * through {@link Ops}, which deletes for good inside the temp dir and never offers the trash.
 *
 * <pre>
 * root/
 *   docs/guide.md
 *   src/Main.java
 *   src/util/Helper.java
 *   .hidden
 *   alpha.txt
 *   beta.txt
 * </pre>
 */
final class ProjectPanelRig implements AutoCloseable {

    /** Deletes inside the temp dir; counts what it was asked, and fails on demand. */
    static class Ops implements ProjectPanel.DeleteOperations {
        final List<Path> deleted = new ArrayList<>();
        final Set<Path> undeletable = new HashSet<>();

        @Override
        public byte[] readAllBytes(Path file) throws IOException {
            return Files.readAllBytes(file);
        }

        @Override
        public void delete(Path file) throws IOException {
            if (undeletable.contains(file)) {
                throw new IOException("locked by another program");
            }
            deleted.add(file);
            Files.delete(file);
        }
    }

    final AsyncTestScope async = new AsyncTestScope();
    final Path root;
    final Path docs;
    final Path guide;
    final Path src;
    final Path main;
    final Path util;
    final Path helper;
    final Path alpha;
    final Path beta;
    final List<Path> opened = new ArrayList<>();
    final List<List<Path>> renamed = new ArrayList<>();
    final List<Path> deletedCallbacks = new ArrayList<>();
    final List<String> status = new ArrayList<>();
    final List<String> prompts = new ArrayList<>();
    final Set<Path> modified = new HashSet<>();
    final Set<Path> open = new HashSet<>();
    final Ops ops = new Ops();
    /** The text of every alert answered by {@link #answerDialogs}. */
    final List<String> dialogs = new CopyOnWriteArrayList<>();

    Consumer<String> promptAccept;
    ProjectPanel panel;
    TreeView<Path> tree;
    TextField filter;
    Stage stage;

    ProjectPanelRig(Path dir) throws Exception {
        root = dir;
        docs = Files.createDirectory(dir.resolve("docs"));
        guide = Files.writeString(docs.resolve("guide.md"), "# guide\n");
        src = Files.createDirectory(dir.resolve("src"));
        main = Files.writeString(src.resolve("Main.java"), "class Main {}\n");
        util = Files.createDirectory(src.resolve("util"));
        helper = Files.writeString(util.resolve("Helper.java"), "class Helper {}\n");
        Files.writeString(dir.resolve(".hidden"), "");
        alpha = Files.writeString(dir.resolve("alpha.txt"), "alpha\n");
        beta = Files.writeString(dir.resolve("beta.txt"), "beta\n");
        FxTestSupport.runOnFx(() -> {
            panel = new ProjectPanel(
                    opened::add,
                    (from, to) -> renamed.add(List.of(from, to)),
                    deletedCallbacks::add,
                    modified::contains,
                    open::contains);
            panel.setDeleteOperations(ops);
            panel.setOnStatus(status::add);
            panel.setPrompt((title, label, initial, onAccept) -> {
                prompts.add(title + "|" + initial);
                promptAccept = onAccept;
            });
            tree = FxTestSupport.field(panel, "tree");
            filter = FxTestSupport.field(panel, "filterField");
            stage = new Stage();
            stage.setScene(new Scene(panel, 420, 600));
            stage.show();
            panel.setRoot(root);
        });
        async.onClose(() -> FxTestSupport.runOnFx(() -> {
            panel.dispose();
            stage.close();
        }));
        awaitChildren(root, 4);
    }

    void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    /** Waits until the row for {@code dir} shows exactly {@code count} children. */
    void awaitChildren(Path dir, int count) throws Exception {
        await(count + " rows under " + dir.getFileName(), () -> {
            TreeItem<Path> item = item(dir);
            return item != null && item.getChildren().size() == count;
        });
    }

    /** The visible tree row for {@code path}, or null. FX thread. */
    TreeItem<Path> item(Path path) {
        return find(tree.getRoot(), path);
    }

    private static TreeItem<Path> find(TreeItem<Path> item, Path path) {
        if (item == null) {
            return null;
        }
        if (path.equals(item.getValue())) {
            return item;
        }
        if (!item.isExpanded() && !item.isLeaf()) {
            return null;
        }
        for (TreeItem<Path> child : item.getChildren()) {
            TreeItem<Path> found = find(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The names of the rows the tree shows, top to bottom. FX thread. */
    List<String> rows() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            names.add(tree.getTreeItem(i).getValue().getFileName().toString());
        }
        return names;
    }

    Path selected() {
        TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    void select(Path path) {
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(item(path));
    }

    /** Delivers a key press to the panel's key handler as if {@code target} had the focus. FX thread. */
    KeyEvent press(Node target, KeyCode code, boolean control) {
        KeyEvent e =
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false).copyFor(target, target);
        FxTestSupport.invokeWith(panel, "onKey", KeyEvent.class, e);
        return e;
    }

    /** A fresh cell of the tree rendering the row for {@code path}. FX thread. */
    TreeCell<Path> cell(Path path) {
        TreeCell<Path> cell = tree.getCellFactory().call(tree);
        cell.updateTreeView(tree);
        cell.updateIndex(tree.getRow(item(path)));
        return cell;
    }

    /** The texts of the labels in a rendered row: its name first, then any open marker. */
    static List<String> labels(TreeCell<Path> cell) {
        return ((HBox) cell.getGraphic())
                .getChildren().stream()
                        .filter(n -> n instanceof Label)
                        .map(n -> ((Label) n).getText())
                        .toList();
    }

    /** The context menu the panel builds for {@code item}. FX thread. */
    ContextMenu menu(TreeItem<Path> item, boolean directory, boolean isRoot) {
        return (ContextMenu) FxTestSupport.call(
                panel,
                "contextMenuFor",
                new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                item,
                directory,
                isRoot);
    }

    /** The entry of {@code items} (searching submenus) labelled with the message {@code key}, or null. */
    static MenuItem entry(List<MenuItem> items, String key) {
        String text = com.editora.i18n.Messages.tr(key);
        for (MenuItem item : items) {
            if (text.equals(item.getText())) {
                return item;
            }
            if (item instanceof Menu sub) {
                MenuItem found = entry(sub.getItems(), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * From now on presses the {@code answer} button of every alert that opens and records its text in
     * {@link #dialogs}. A modal alert blocks its caller, so this must be armed before the action that opens it.
     */
    void answerDialogs(ButtonBar.ButtonData answer) throws Exception {
        Set<Window> answered = new HashSet<>();
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.getScene() == null
                            || !(window.getScene().getRoot() instanceof DialogPane pane)
                            || !answered.add(window)) {
                        continue;
                    }
                    dialogs.add(pane.getContentText());
                    // An alert with no such button (an error has only OK) is dismissed with the one it has,
                    // so a dialog the test did not expect fails an assertion instead of blocking the toolkit.
                    var button = pane.getButtonTypes().stream()
                            .filter(type -> type.getButtonData() == answer)
                            .findFirst()
                            .orElse(pane.getButtonTypes().get(0));
                    ((Button) pane.lookupButton(button)).fire();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
    }

    @Override
    public void close() throws Exception {
        async.close();
    }
}
