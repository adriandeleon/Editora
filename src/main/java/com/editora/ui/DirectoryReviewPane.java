package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.diff.DirectoryDiff;
import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/** A lazily loaded, bounded-cache review of the changed files in two directory trees. */
final class DirectoryReviewPane implements TabContent {

    static final class Entry {
        private final String label;
        private final DirectoryDiff.Kind kind;
        private final long leftSize;
        private final long rightSize;
        private int additions = -1;
        private int deletions = -1;

        Entry(String label, DirectoryDiff.Kind kind, long leftSize, long rightSize) {
            this.label = label;
            this.kind = kind;
            this.leftSize = leftSize;
            this.rightSize = rightSize;
        }

        String label() {
            return label;
        }

        DirectoryDiff.Kind kind() {
            return kind;
        }

        void setStats(int additions, int deletions) {
            this.additions = additions;
            this.deletions = deletions;
        }
    }

    record Loaded(DiffViewerPane pane, int additions, int deletions) {}

    @FunctionalInterface
    interface Loader {
        void load(Entry entry, Consumer<Loaded> onReady);
    }

    private static final int MAX_CACHED_PANES = 32;

    private final String title;
    private final List<Entry> entries;
    private final Loader loader;
    private final BorderPane root = new BorderPane();
    private final ListView<Entry> files = new ListView<>();
    private final Label position = new Label();
    private final Button exitDiffUiButton = new Button();
    private final Map<Entry, DiffViewerPane> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<DirectoryReviewPane.Entry, DiffViewerPane> eldest) {
            return size() > MAX_CACHED_PANES;
        }
    };
    private long loadGeneration;
    private DiffViewerPane activePane;

    DirectoryReviewPane(String title, List<Entry> entries, String summary, Loader loader) {
        this.title = title;
        this.entries = List.copyOf(entries);
        this.loader = loader;
        root.getStyleClass().add("patch-review");
        files.getItems().setAll(entries);
        files.getStyleClass().add("patch-file-list");
        files.setCellFactory(v -> new FileCell());
        files.setPrefWidth(275);
        files.setMinWidth(185);
        files.getSelectionModel().selectedIndexProperty().addListener((o, old, next) -> show(next.intValue()));

        Button previous = button(Icons.arrowUp(), tr("diff.previousFile"), () -> selectRelative(-1));
        Button next = button(Icons.arrowDown(), tr("diff.nextFile"), () -> selectRelative(1));
        exitDiffUiButton.setGraphic(Icons.editora());
        exitDiffUiButton.setAccessibleText(tr("tooltip.diffUiExit"));
        exitDiffUiButton.setTooltip(new Tooltip(tr("tooltip.diffUiExit")));
        exitDiffUiButton.getStyleClass().addAll("flat", "diff-toolbar-button", "diff-ui-exit");
        exitDiffUiButton.setFocusTraversable(false);
        exitDiffUiButton.setVisible(false);
        exitDiffUiButton.setManaged(false);
        position.getStyleClass().add("diff-summary");
        Label scanSummary = new Label(summary);
        scanSummary.getStyleClass().add("diff-summary");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox nav = new HBox(3, position, previous, next, spacer, exitDiffUiButton);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.setPadding(new Insets(4, 6, 2, 6));
        VBox header = new VBox(nav, scanSummary);
        header.setPadding(new Insets(0, 6, 4, 6));
        header.getStyleClass().add("diff-toolbar");
        VBox left = new VBox(header, new Separator(), files);
        VBox.setVgrow(files, Priority.ALWAYS);
        left.getStyleClass().add("patch-review-sidebar");
        root.setLeft(left);
        if (!entries.isEmpty()) {
            files.getSelectionModel().select(0);
        } else {
            Label empty = new Label(tr("diff.directory.noDifferences"));
            empty.getStyleClass().add("tool-window-placeholder");
            root.setCenter(new StackPane(empty));
        }
    }

    List<DiffViewerPane> panes() {
        List<DiffViewerPane> panes = new ArrayList<>(cache.values());
        if (activePane != null && !panes.contains(activePane)) {
            panes.add(activePane);
        }
        return panes;
    }

    DiffViewerPane activePane() {
        return activePane;
    }

    void setExitDiffUiAction(Runnable action) {
        boolean visible = action != null;
        exitDiffUiButton.setOnAction(visible ? event -> action.run() : null);
        exitDiffUiButton.setVisible(visible);
        exitDiffUiButton.setManaged(visible);
    }

    private void selectRelative(int delta) {
        if (entries.isEmpty()) {
            return;
        }
        int current = Math.max(0, files.getSelectionModel().getSelectedIndex());
        int next = Math.max(0, Math.min(entries.size() - 1, current + delta));
        files.getSelectionModel().select(next);
        files.scrollTo(next);
    }

    private void show(int index) {
        long requested = ++loadGeneration;
        activePane = null;
        if (index < 0 || index >= entries.size()) {
            root.setCenter(null);
            position.setText("");
            return;
        }
        Entry entry = entries.get(index);
        position.setText(tr("diff.filePosition", index + 1, entries.size()));
        DiffViewerPane cached = cache.get(entry);
        if (cached != null) {
            activePane = cached;
            root.setCenter(cached.node());
            return;
        }
        Label loading = new Label(tr("status.diff.loadingFile", entry.label()));
        loading.getStyleClass().add("tool-window-placeholder");
        root.setCenter(new StackPane(loading));
        loader.load(entry, loaded -> {
            if (loaded == null) {
                if (requested == loadGeneration) {
                    root.setCenter(new StackPane(new Label(tr("status.diff.tooLarge"))));
                }
                return;
            }
            cache.put(entry, loaded.pane());
            entry.setStats(loaded.additions(), loaded.deletions());
            files.refresh();
            if (requested == loadGeneration) {
                activePane = loaded.pane();
                root.setCenter(loaded.pane().node());
            }
        });
    }

    private static Button button(Node icon, String tip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon);
        button.setAccessibleText(tip);
        button.setTooltip(new Tooltip(tip));
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("flat", "diff-toolbar-button");
        button.setOnAction(e -> action.run());
        return button;
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return Icons.diff();
    }

    private static final class FileCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String kind =
                    switch (entry.kind) {
                        case MODIFIED -> "≠";
                        case LEFT_ONLY -> "←";
                        case RIGHT_ONLY -> "→";
                        case UNREADABLE -> "!";
                    };
            Label badge = new Label(kind);
            badge.getStyleClass().add("patch-file-status");
            Label name = new Label(entry.label);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            String detail = entry.additions >= 0
                    ? "+" + entry.additions + "  −" + entry.deletions
                    : sizeSummary(entry.leftSize, entry.rightSize);
            Label stat = new Label(detail);
            stat.getStyleClass().add("diff-summary");
            HBox row = new HBox(8, badge, name, stat);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
            setAccessibleText(entry.label + ", " + accessibleKind(entry.kind) + ", " + detail);
        }

        private static String accessibleKind(DirectoryDiff.Kind kind) {
            return tr(
                    switch (kind) {
                        case MODIFIED -> "diff.directory.modified";
                        case LEFT_ONLY -> "diff.directory.leftOnly";
                        case RIGHT_ONLY -> "diff.directory.rightOnly";
                        case UNREADABLE -> "diff.directory.unreadable";
                    });
        }

        private static String sizeSummary(long left, long right) {
            if (left < 0) {
                return formatBytes(right);
            }
            if (right < 0) {
                return formatBytes(left);
            }
            return formatBytes(left) + " / " + formatBytes(right);
        }

        private static String formatBytes(long bytes) {
            if (bytes < 0) {
                return "—";
            }
            if (bytes < 1024) {
                return bytes + " B";
            }
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
    }
}
