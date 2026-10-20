package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownLinkIndex;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Backlinks tool window: what links to the active file — every Markdown link, raw HTML reference and
 * wiki link in the project that points at it — grouped by the file it is in, each with its line.
 *
 * <p>It asks the project's link index ({@link MarkdownLinkGraph}), so nothing is searched: the answer is a
 * lookup on the index's own thread, shown when it comes back. The list follows the active tab and the
 * index while the window is open, and does nothing while it is closed. Links are the ones in saved files;
 * an edit shows up when its document is saved.
 */
final class MarkdownBacklinks {

    static final String ID = "backlinks";

    /** One row: a file that links here ({@code line < 0}, with how many links), or one of its links. */
    record Row(@Nullable Path file, String label, int line, int count) {
        boolean isFile() {
            return line < 0;
        }
    }

    private final CoordinatorHost host;
    private final MarkdownLinkGraph graph;
    private final IndexCoordinator.Ops ops;

    private @Nullable ToolWindow window;
    private @Nullable ToolWindowManager manager;
    private @Nullable Panel panel;
    private @Nullable Path shownFor;
    private long request;

    /** Completes with the rows of the last refresh once they are on show (tests wait on it). */
    CompletableFuture<List<Row>> lastRefresh = CompletableFuture.completedFuture(List.of());

    MarkdownBacklinks(CoordinatorHost host, MarkdownLinkGraph graph, IndexCoordinator.Ops ops) {
        this.host = host;
        this.graph = graph;
        this.ops = ops;
        graph.addChangeListener(() -> {
            if (isOpen()) {
                refresh();
            }
        });
    }

    /** The tool window, made on first request; {@code manager} is the window's, used to open it. */
    ToolWindow toolWindow(ToolWindowManager manager) {
        this.manager = manager;
        if (window == null) {
            window = new ToolWindow(
                    ID, tr("toolwindow.backlinks"), ToolWindow.Side.BOTTOM, Icons::find, this::panel, "tool.backlinks");
        }
        return window;
    }

    Panel panel() {
        if (panel == null) {
            panel = new Panel();
        }
        return panel;
    }

    private boolean isOpen() {
        return manager != null && window != null && manager.isOpen(window);
    }

    /** {@code tool.backlinks}: shows or hides the window. */
    void toggle() {
        if (manager == null || window == null) {
            return;
        }
        manager.toggle(window);
        if (isOpen()) {
            refresh();
        }
    }

    /** {@code markdown.showBacklinks}: opens the window on the active file's backlinks. */
    void show() {
        if (manager != null && window != null && !manager.isOpen(window)) {
            manager.open(window, true);
        }
        refresh();
    }

    /** The active tab changed; a closed window does not care. */
    void activeBufferChanged(@Nullable EditorBuffer buffer) {
        if (isOpen()) {
            refresh();
        }
    }

    /** Asks the index what links to the active file and shows the answer when it arrives. */
    void refresh() {
        EditorBuffer active = host.activeBuffer();
        Path path = active == null ? null : active.getPath();
        Path file = path == null || !Vfs.isLocal(path)
                ? null
                : path.toAbsolutePath().normalize();
        Path root = graph.rootOrNull();
        long mine = ++request;
        CompletableFuture<List<Row>> done = new CompletableFuture<>();
        lastRefresh = done;
        if (file == null || root == null || !file.startsWith(root)) {
            shownFor = file;
            panel().show(file, root, List.of(), root == null ? "backlinks.noProject" : "backlinks.noFile");
            done.complete(List.of());
            return;
        }
        graph.submit(index -> {
                    List<Row> rows = index == null ? List.of() : rows(index, file, root);
                    Platform.runLater(() -> {
                        if (mine == request) {
                            shownFor = file;
                            panel().show(file, root, rows, "backlinks.none");
                        }
                        done.complete(rows);
                    });
                })
                .whenComplete((v, failure) -> Platform.runLater(() -> done.complete(List.of())));
    }

    /** Worker: the rows for {@code file} — each linking file, then its links in line order. */
    static List<Row> rows(MarkdownLinkIndex index, Path file, Path root) {
        List<Row> rows = new ArrayList<>();
        Path source = null;
        int header = -1;
        List<MarkdownLinkIndex.Backlink> links = new ArrayList<>(index.incoming(file));
        links.sort(java.util.Comparator.comparing((MarkdownLinkIndex.Backlink b) -> rel(root, b.source()))
                .thenComparingInt(MarkdownLinkIndex.Backlink::line));
        for (MarkdownLinkIndex.Backlink link : links) {
            if (!link.source().equals(source)) {
                source = link.source();
                header = rows.size();
                rows.add(new Row(source, rel(root, source), -1, 0));
            }
            Row head = rows.get(header);
            rows.set(header, new Row(head.file(), head.label(), -1, head.count() + 1));
            rows.add(new Row(source, link.lineText(), link.line(), 0));
        }
        return rows;
    }

    private static String rel(Path root, Path file) {
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    @Nullable
    Path shownFor() {
        return shownFor;
    }

    /** The window's content: a title saying whose backlinks these are, and the list. */
    final class Panel extends VBox implements ToolWindowContent {

        private final Label title = new Label();
        private final ListView<Row> list = new ListView<>();
        private final Label empty = new Label();

        Panel() {
            getStyleClass().add("backlinks-panel");
            title.getStyleClass().add("tool-window-subtitle");
            title.setPadding(new javafx.geometry.Insets(4, 8, 4, 8));
            empty.getStyleClass().add("settings-hint");
            list.setPlaceholder(empty);
            list.setCellFactory(view -> new ListCell<>() {
                @Override
                protected void updateItem(Row row, boolean none) {
                    super.updateItem(row, none);
                    getStyleClass().removeAll("backlinks-file", "backlinks-link");
                    if (none || row == null) {
                        setText(null);
                        return;
                    }
                    getStyleClass().add(row.isFile() ? "backlinks-file" : "backlinks-link");
                    setText(
                            row.isFile()
                                    ? tr("backlinks.file", row.label(), row.count())
                                    : tr("backlinks.link", row.line() + 1, row.label()));
                }
            });
            list.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    activate();
                }
            });
            list.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    activate();
                    e.consume();
                }
            });
            VBox.setVgrow(list, Priority.ALWAYS);
            getChildren().addAll(title, list);
        }

        void show(@Nullable Path file, @Nullable Path root, List<Row> rows, String emptyKey) {
            long links = rows.stream().filter(r -> !r.isFile()).count();
            title.setText(
                    file == null
                            ? tr("toolwindow.backlinks")
                            : tr("backlinks.title", rel(root == null ? file : root, file), links));
            empty.setText(tr(emptyKey));
            list.getItems().setAll(rows);
        }

        /** Opens the selected row: a link at its line, a file at its first link. */
        void activate() {
            Row row = list.getSelectionModel().getSelectedItem();
            if (row == null) {
                return;
            }
            int line = row.line();
            if (row.isFile()) {
                int at = list.getItems().indexOf(row);
                line = at + 1 < list.getItems().size()
                        ? Math.max(0, list.getItems().get(at + 1).line())
                        : 0;
            }
            ops.openAndGoto(row.file(), line, 0);
        }

        ListView<Row> list() {
            return list;
        }

        String titleText() {
            return title.getText();
        }

        @Override
        public void focusFirstItem() {
            list.requestFocus();
            if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
                list.getSelectionModel().select(0);
            }
        }
    }
}
