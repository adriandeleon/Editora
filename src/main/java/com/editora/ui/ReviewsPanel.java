package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.config.ReviewThread;
import com.editora.editor.UiZoom;
import com.editora.review.ReviewText;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Review Comments tool window: the review threads of the active file's repository, by file, open ones first
 * shown, resolved and dismissed ones on request. Enter (or a double click) opens a thread at its lines with its
 * card; Delete deletes it; each row's menu resolves, reopens or deletes, and is a command's
 * ({@code review.openSelected}, {@code review.toggleSelectedResolved}, {@code review.deleteSelected}).
 */
final class ReviewsPanel extends VBox implements ToolWindowContent {

    /** What the panel shows: a repository's threads, or none when the active file is in none. */
    record Scope(ReviewCoordinator.@Nullable Place place, List<ReviewThread> threads) {}

    /** What the rows do. */
    interface Actions {
        void open(ReviewCoordinator.Place place, ReviewThread t);

        void resolve(ReviewCoordinator.Place place, ReviewThread t, boolean resolved);

        void delete(ReviewCoordinator.Place place, ReviewThread t);
    }

    private sealed interface Row permits FileRow, ThreadRow {}

    private record FileRow(String path, int open) implements Row {}

    private record ThreadRow(ReviewCoordinator.Place place, ReviewThread thread) implements Row {}

    private final Supplier<Scope> source;
    private final Actions actions;
    private final TextField filterField = new TextField();
    private final ToggleButton showClosed = new ToggleButton(tr("review.panel.showClosed"));
    private final TreeView<Row> tree = new TreeView<>();
    private final Label placeholder = new Label();
    private final StackPane placeholderPane;

    ReviewsPanel(Supplier<Scope> source, Actions actions) {
        this.source = source;
        this.actions = actions;
        getStyleClass().add("notes-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        UiZoom.spacing(this, 4);

        filterField.setPromptText(tr("review.panel.filterPrompt"));
        filterField.setAccessibleText(tr("review.panel.filterPrompt"));
        filterField.getStyleClass().add("notes-filter");
        filterField.textProperty().addListener((o, w, n) -> refresh());
        FilterFieldNav.install(filterField, tree, this::activateSelected);
        Button clear = new Button("✕");
        clear.getStyleClass().add("project-filter-clear");
        clear.setFocusTraversable(false);
        Icons.name(clear, tr("project.filterClear"));
        clear.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clear.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        ClearableField.command(filterField, clear);
        clear.managedProperty().bind(clear.visibleProperty());
        HBox.setHgrow(filterField, Priority.ALWAYS);
        showClosed.getStyleClass().addAll("flat", "scope-toggle");
        showClosed.setFocusTraversable(false);
        CommandTips.name(showClosed, "review.toggleShowClosed");
        showClosed.selectedProperty().addListener((o, w, n) -> refresh());
        HBox header = UiZoom.spacing(new HBox(filterField, clear, showClosed), 6);
        header.getStyleClass().add("project-filter-bar");
        header.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("notes-tree");
        tree.setAccessibleText(tr("toolwindow.reviews"));
        tree.setCellFactory(t -> new Cell());
        RowContextMenu.install(tree);
        tree.getProperties().put(com.editora.command.KeyDispatcher.CLAIMED_KEYS, java.util.Set.of("delete"));
        VBox.setVgrow(tree, Priority.ALWAYS);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                activateSelected();
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE && selectedThread() != null) {
                ThreadRow r = selectedThread();
                actions.delete(r.place(), r.thread());
                e.consume();
            }
        });

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        placeholderPane = new StackPane(placeholder);
        placeholderPane.setMouseTransparent(true);
        StackPane treePane = new StackPane(tree, placeholderPane);
        VBox.setVgrow(treePane, Priority.ALWAYS);
        getChildren().addAll(header, treePane);
        refresh();
    }

    @Override
    public void focusFirstItem() {
        filterField.requestFocus();
    }

    /** Show Resolved and Dismissed — as a click on the toggle. */
    void toggleShowClosed() {
        showClosed.fire();
    }

    /** Rebuilds the tree from the active repository's threads. */
    void refresh() {
        Scope scope = source.get();
        TreeItem<Row> root = new TreeItem<>();
        String filter = filterField.getText() == null
                ? ""
                : filterField.getText().strip().toLowerCase(Locale.ROOT);
        if (scope.place() != null) {
            Map<String, List<ReviewThread>> byFile = new LinkedHashMap<>();
            for (ReviewThread t : scope.threads()) {
                if ((showClosed.isSelected() || t.open()) && matches(t, filter)) {
                    byFile.computeIfAbsent(t.path(), k -> new ArrayList<>()).add(t);
                }
            }
            byFile.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                int open =
                        (int) e.getValue().stream().filter(ReviewThread::open).count();
                TreeItem<Row> file = new TreeItem<>(new FileRow(e.getKey(), open));
                file.setExpanded(true);
                e.getValue().stream()
                        .sorted(java.util.Comparator.comparingInt(
                                t -> t.anchor().line()))
                        .forEach(t -> file.getChildren().add(new TreeItem<>(new ThreadRow(scope.place(), t))));
                root.getChildren().add(file);
            });
        }
        tree.setRoot(root);
        boolean empty = root.getChildren().isEmpty();
        placeholder.setText(tr(scope.place() == null ? "review.panel.noRepository" : "review.panel.empty"));
        placeholderPane.setVisible(empty);
    }

    /** Whether {@code t} matches the filter text: its path, or any comment's text or author. Pure. */
    static boolean matches(ReviewThread t, String filter) {
        if (filter.isEmpty() || t.path().toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        return t.comments().stream()
                .anyMatch(c -> c.body().toLowerCase(Locale.ROOT).contains(filter)
                        || c.author().name().toLowerCase(Locale.ROOT).contains(filter));
    }

    private @Nullable ThreadRow selectedThread() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        return item != null && item.getValue() instanceof ThreadRow r ? r : null;
    }

    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null && !tree.getRoot().getChildren().isEmpty()) {
            item = tree.getRoot().getChildren().get(0).getChildren().isEmpty()
                    ? null
                    : tree.getRoot().getChildren().get(0).getChildren().get(0);
        }
        if (item != null && item.getValue() instanceof ThreadRow r) {
            actions.open(r.place(), r.thread());
        }
    }

    /** A thread's menu: Open, Resolve / Reopen, Delete, each a command's on the selected thread. */
    private List<MenuItem> threadMenuRows(ThreadRow r) {
        MenuItem open = new MenuItem(tr("review.panel.open"));
        open.setOnAction(e -> actions.open(r.place(), r.thread()));
        boolean isOpen = r.thread().open();
        MenuItem toggle = new MenuItem(tr(isOpen ? "review.card.resolve" : "review.card.reopen"));
        toggle.setGraphic(isOpen ? Icons.check() : Icons.refresh());
        toggle.setOnAction(e -> actions.resolve(r.place(), r.thread(), isOpen));
        MenuItem delete = new MenuItem(tr("review.card.delete"));
        delete.setGraphic(Icons.trash());
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> actions.delete(r.place(), r.thread()));
        return List.of(
                com.editora.editor.MenuChords.tag(open, "review.openSelected"),
                com.editora.editor.MenuChords.tag(toggle, "review.toggleSelectedResolved"),
                new SeparatorMenuItem(),
                com.editora.editor.MenuChords.tag(delete, "review.deleteSelected"));
    }

    /** Runs the row of the selected thread's menu that {@code commandId} stands for; false with no thread selected. */
    boolean runMenuRow(String commandId) {
        ThreadRow r = selectedThread();
        return r != null && MenuRows.fire(threadMenuRows(r), commandId);
    }

    /** The tree, for tests. */
    TreeView<?> tree() {
        return tree;
    }

    private final class Cell extends TreeCell<Row> {
        @Override
        protected void updateItem(@Nullable Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                setContextMenu(null);
                return;
            }
            if (row instanceof FileRow f) {
                setText(f.path() + (f.open() > 0 ? "  (" + f.open() + ")" : ""));
                setContextMenu(null);
            } else if (row instanceof ThreadRow r) {
                ReviewThread t = r.thread();
                String state = t.open()
                        ? ""
                        : "  · " + tr("review.state." + t.state().name().toLowerCase(Locale.ROOT));
                setText(tr("review.line", t.anchor().line() + 1) + "  " + ReviewText.label(t) + state);
                setContextMenu(new ContextMenu(threadMenuRows(r).toArray(MenuItem[]::new)));
            }
        }
    }
}
