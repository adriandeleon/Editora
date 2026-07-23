package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;

import com.editora.todo.TodoColors;
import com.editora.todo.TodoGrouping;
import com.editora.todo.TodoMatch;
import com.editora.todo.TodoService;

import static com.editora.i18n.Messages.tr;

/**
 * The "TODO" results tool window, an IntelliJ-style Comment Manager list: every configured-keyword match,
 * groupable by <b>file / priority / tag / keyword</b> (a top selector), each match row rendered with its
 * structured parts colored ({@code KEYWORD [tag] (priority) description}) + a muted {@code file:line}. Enter /
 * double-click jumps to it. Auto-scoped by the controller — the open project's tree when one is open, else the
 * open buffers. Mirrors {@link SearchPanel} ({@link ToolWindowContent}); work is routed back via {@link Actions}.
 */
public final class TodoPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    /** The keywords offered when reopening a DONE item — the configured patterns, minus DONE itself. */
    private List<String> keywords = List.of("TODO");

    /** Pushed by the coordinator whenever the patterns are (re)compiled. */
    void setKeywords(List<String> patternNames) {
        List<String> out = new ArrayList<>();
        for (String n : patternNames) {
            if (n != null && !n.isBlank() && !com.editora.todo.TodoPatterns.DONE_KEYWORD.equals(n)) {
                out.add(n);
            }
        }
        this.keywords = out.isEmpty() ? List.of("TODO") : List.copyOf(out);
    }

    private List<String> reopenKeywords() {
        return keywords;
    }

    public interface Actions {
        void openMatch(Path file, int line, int col);

        void refresh();

        /** Sets (or clears, when {@code priority} is null) the match's {@code (priority)} in the source. */
        void setPriority(Path file, TodoMatch match, String priority);

        /** Rewrites the match's keyword to {@code DONE} (mark done) or back to a live keyword (reopen). */
        void setKeyword(Path file, TodoMatch match, String keyword);

        /** Prompts for and replaces the match's description text in the source. */
        void editDescription(Path file, TodoMatch match);
    }

    /** A tree row: a group header ({@code match == null}) or a single match under it. */
    private record Row(String header, Path file, TodoMatch match) {}

    private final Actions actions;
    private final Label summary = new Label();
    private final Label scopeLabel = new Label();
    private final ComboBox<TodoGrouping.GroupBy> groupBy = new ComboBox<>();
    private final TextField filterField = new TextField();
    private final TreeView<Row> tree = new TreeView<>();

    private TodoService.Outcome lastOutcome;
    private Path activeFile;
    /** Notified when the user changes the "Group by" dimension, so the coordinator can persist it. */
    private java.util.function.Consumer<TodoGrouping.GroupBy> onGroupByChanged;

    public TodoPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("todo-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        Label scopePrefix = new Label(tr("todo.scopeLabel"));
        scopePrefix.getStyleClass().add("search-scope-prefix");
        scopeLabel.getStyleClass().add("search-scope-path");
        scopeLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(scopeLabel, Priority.ALWAYS);
        HBox scopeRow = new HBox(6, scopePrefix, scopeLabel);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.getStyleClass().add("search-scope-row");

        Label groupLabel = new Label(tr("todo.groupBy"));
        groupBy.getItems().setAll(TodoGrouping.GroupBy.values());
        groupBy.getSelectionModel().select(TodoGrouping.GroupBy.FILE);
        groupBy.setConverter(new StringConverter<>() {
            @Override
            public String toString(TodoGrouping.GroupBy g) {
                return g == null ? "" : tr("todo.groupBy." + g.name().toLowerCase(java.util.Locale.ROOT));
            }

            @Override
            public TodoGrouping.GroupBy fromString(String s) {
                return null;
            }
        });
        groupBy.valueProperty().addListener((o, a, b) -> {
            rebuild();
            if (onGroupByChanged != null && b != null) {
                onGroupByChanged.accept(b);
            }
        });

        Button refreshBtn = new Button();
        refreshBtn.setGraphic(Icons.refresh());
        refreshBtn.getStyleClass().add("todo-refresh");
        refreshBtn.setTooltip(new Tooltip(tr("todo.refresh")));
        refreshBtn.setOnAction(e -> actions.refresh());
        summary.getStyleClass().add("todo-summary");
        HBox.setHgrow(summary, Priority.ALWAYS);
        HBox toolbar = new HBox(6, groupLabel, groupBy, summary, refreshBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Filter row: narrows the visible TODO rows as you type (keyword / tag / description / file),
        // with a trailing clear ("✕") button. Down/Enter move focus into / open the results.
        filterField.setPromptText(tr("todo.filterPrompt"));
        filterField.getStyleClass().add("todo-filter");
        filterField.textProperty().addListener((o, w, n) -> rebuild());
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = ClearableField.clearButton(filterField);
        HBox filterRow = new HBox(6, filterField, clearFilter);
        filterRow.getStyleClass().add("project-filter-bar");
        filterRow.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell(actions, this::reopenKeywords));
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(scopeRow, toolbar, filterRow, tree);
    }

    /** Selects the "Group by" dimension. Call before {@link #setGroupByChangeHandler} to restore a saved value
     *  without re-persisting it (the handler is still null when this fires the listener). */
    public void setGroupBy(TodoGrouping.GroupBy g) {
        if (g != null && g != groupBy.getValue()) {
            groupBy.getSelectionModel().select(g);
        }
    }

    /** Registers the callback fired when the user changes the "Group by" dimension (coordinator persists it). */
    public void setGroupByChangeHandler(java.util.function.Consumer<TodoGrouping.GroupBy> handler) {
        this.onGroupByChanged = handler;
    }

    /** Sets the displayed scan-scope folder. */
    public void setScope(String display, String fullPath) {
        scopeLabel.setText(display == null ? "" : display);
        scopeLabel.setTooltip(fullPath == null || fullPath.isBlank() ? null : new Tooltip(fullPath));
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ENTER -> {
                activateSelected();
                e.consume();
            }
            case DOWN -> {
                moveSelection(1);
                e.consume();
            }
            case UP -> {
                moveSelection(-1);
                e.consume();
            }
            default -> {
                if (e.isControlDown()) {
                    // Emacs navigation: C-n / C-p move, C-m activates.
                    switch (e.getCode()) {
                        case N -> {
                            moveSelection(1);
                            e.consume();
                        }
                        case P -> {
                            moveSelection(-1);
                            e.consume();
                        }
                        case M -> {
                            activateSelected();
                            e.consume();
                        }
                        default -> {}
                    }
                    return;
                }
                // Plain n / p also move — but only when the filter field isn't focused, so typing there works.
                if (!filterField.isFocused()) {
                    switch (e.getCode()) {
                        case N -> {
                            moveSelection(1);
                            e.consume();
                        }
                        case P -> {
                            moveSelection(-1);
                            e.consume();
                        }
                        default -> {}
                    }
                }
            }
        }
    }

    private void moveSelection(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int i = tree.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(rows - 1, (i < 0 ? 0 : i) + delta));
        tree.getSelectionModel().select(next);
        tree.scrollTo(next);
    }

    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Row row = item.getValue();
        if (row.match() != null) {
            actions.openMatch(row.file(), row.match().line(), row.match().col());
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Populates the tree from a scan outcome (called on the FX thread by the controller). */
    public void setResults(TodoService.Outcome outcome) {
        lastOutcome = outcome;
        rebuild();
    }

    /** Sets the active file so its group sorts to the top under "group by file". Re-sorts without re-scanning. */
    public void setActiveFile(Path normalizedActive) {
        if (java.util.Objects.equals(activeFile, normalizedActive)) {
            return;
        }
        activeFile = normalizedActive;
        if (lastOutcome != null) {
            rebuild();
        }
    }

    private TodoGrouping.GroupBy currentGroupBy() {
        TodoGrouping.GroupBy g = groupBy.getValue();
        return g == null ? TodoGrouping.GroupBy.FILE : g;
    }

    /** Case-insensitive substring match over the row's keyword, line text and file path. Empty query = keep. */
    private static boolean matchesFilter(Path file, TodoMatch m, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (m.patternName() != null
                && m.patternName().toLowerCase(java.util.Locale.ROOT).contains(query)) {
            return true;
        }
        if (m.lineText() != null
                && m.lineText().toLowerCase(java.util.Locale.ROOT).contains(query)) {
            return true;
        }
        return file != null
                && file.toString().toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private void rebuild() {
        TodoService.Outcome outcome = lastOutcome;
        if (outcome == null) {
            return;
        }
        String query = filterField.getText() == null
                ? ""
                : filterField.getText().strip().toLowerCase(java.util.Locale.ROOT);
        List<TodoGrouping.Entry> entries = new ArrayList<>();
        for (TodoService.FileTodos fr : outcome.files()) {
            for (TodoMatch m : fr.matches()) {
                if (matchesFilter(fr.file(), m, query)) {
                    entries.add(new TodoGrouping.Entry(fr.file(), m));
                }
            }
        }
        TodoGrouping.GroupBy by = currentGroupBy();
        List<TodoGrouping.Group> groups = TodoGrouping.group(entries, by, activeFile);

        TreeItem<Row> root = new TreeItem<>();
        for (TodoGrouping.Group g : groups) {
            String header = g.label().isEmpty() ? emptyGroupLabel(by) : g.label();
            TreeItem<Row> groupItem = new TreeItem<>(new Row(header, g.file(), null));
            groupItem.setExpanded(true);
            for (TodoGrouping.Entry e : g.entries()) {
                groupItem.getChildren().add(new TreeItem<>(new Row(null, e.file(), e.match())));
            }
            root.getChildren().add(groupItem);
        }
        tree.setRoot(root);
        summary.setText(
                outcome.totalMatches() == 0
                        ? tr("todo.none")
                        : tr(
                                outcome.truncated() ? "todo.summaryTruncated" : "todo.summary",
                                outcome.totalMatches(),
                                outcome.fileCount()));
    }

    /** The localized "no tag / no priority" bucket header for the current grouping. */
    private static String emptyGroupLabel(TodoGrouping.GroupBy by) {
        return switch (by) {
            case PRIORITY -> tr("todo.noPriority");
            case TAG -> tr("todo.noTag");
            default -> tr("todo.noKeyword"); // keyword/file groups always have a label; a safe fallback
        };
    }

    @Override
    public void focusFirstItem() {
        actions.refresh();
        // Land on the filter field so the user can type to filter immediately; Down/Enter move into the
        // results and n/p navigate once focus is in the tree (mirrors the other filterable tool windows).
        filterField.requestFocus();
        if (tree.getExpandedItemCount() > 0) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a group header (label + count, file icon under "by file") or a structured match row. */
    private static final class RowCell extends TreeCell<Row> {
        private final Actions actions;
        /** Live view of the configured keywords (the panel re-pushes them on every pattern change). */
        private final java.util.function.Supplier<List<String>> reopenKeywords;

        RowCell(Actions actions, java.util.function.Supplier<List<String>> reopenKeywords) {
            this.actions = actions;
            this.reopenKeywords = reopenKeywords;
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().remove("todo-file-row");
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (row.match() == null) {
                setContextMenu(null);
                renderHeader(row);
            } else {
                renderMatch(row);
                setContextMenu(row.match().parsed() == null ? null : buildMatchMenu(row));
            }
        }

        /** Edit menu for a structured match: Mark Done / Reopen, set priority, edit description. */
        private javafx.scene.control.ContextMenu buildMatchMenu(Row row) {
            TodoMatch m = row.match();
            Path file = row.file();
            boolean done =
                    com.editora.todo.TodoPatterns.DONE_KEYWORD.equals(m.parsed().keyword());
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();

            // Mark Done overwrites the keyword, and nothing records what it was — so reopening cannot know
            // whether this line started life as a TODO or a FIXME. It used to assume "TODO", quietly
            // downgrading every FIXME/HACK/XXX that was ever marked done and reopened. Ask instead.
            javafx.scene.control.MenuItem doneItem = null;
            javafx.scene.control.Menu reopenMenu = null;
            if (done) {
                reopenMenu = new javafx.scene.control.Menu(tr("todo.menu.reopen"));
                for (String k : reopenKeywords.get()) {
                    javafx.scene.control.MenuItem it = new javafx.scene.control.MenuItem(k);
                    it.setOnAction(e -> actions.setKeyword(file, m, k));
                    reopenMenu.getItems().add(it);
                }
            } else {
                doneItem = new javafx.scene.control.MenuItem(tr("todo.menu.markDone"));
                doneItem.setOnAction(e -> actions.setKeyword(file, m, com.editora.todo.TodoPatterns.DONE_KEYWORD));
            }

            javafx.scene.control.Menu priorityMenu = new javafx.scene.control.Menu(tr("todo.menu.priority"));
            for (String p : com.editora.todo.TodoComment.PRIORITY_ORDER) {
                javafx.scene.control.MenuItem it = new javafx.scene.control.MenuItem(tr("todo.priority." + p));
                it.setOnAction(e -> actions.setPriority(file, m, p));
                priorityMenu.getItems().add(it);
            }
            javafx.scene.control.MenuItem none = new javafx.scene.control.MenuItem(tr("todo.priority.none"));
            none.setOnAction(e -> actions.setPriority(file, m, null));
            priorityMenu.getItems().add(none);

            javafx.scene.control.MenuItem editDesc = new javafx.scene.control.MenuItem(tr("todo.menu.editDescription"));
            editDesc.setOnAction(e -> actions.editDescription(file, m));

            menu.getItems().add(done ? reopenMenu : doneItem);
            menu.getItems().addAll(priorityMenu, editDesc);
            return menu;
        }

        private void renderHeader(Row row) {
            int count = getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
            setText(row.header() + "  (" + count + ")");
            setGraphic(row.file() == null ? null : FileIcons.forFileName(fileLabel(row.file())));
            if (!getStyleClass().contains("todo-file-row")) {
                getStyleClass().add("todo-file-row");
            }
        }

        private void renderMatch(Row row) {
            TodoMatch m = row.match();
            TextFlow flow = new TextFlow();
            flow.getStyleClass().add("todo-row-flow");
            var parsed = m.parsed();
            if (parsed != null) {
                addRun(flow, parsed.keyword(), m.color(), true, false);
                if (parsed.hasTag()) {
                    addRun(flow, " [" + parsed.tag() + "]", TodoColors.TAG_COLOR, false, true);
                }
                if (parsed.hasPriority()) {
                    addRun(
                            flow,
                            " (" + parsed.priority() + ")",
                            TodoColors.priorityColor(parsed.priority()),
                            false,
                            false);
                }
                if (!parsed.description().isEmpty()) {
                    addRun(flow, " " + parsed.description(), null, false, false);
                }
            } else {
                addRun(flow, m.lineText().strip(), m.color(), false, false);
            }
            Text loc = addRun(flow, "  — " + fileLabel(row.file()) + ":" + m.line(), null, false, false);
            loc.getStyleClass().add("todo-row-location");
            setText(null);
            setGraphic(flow);
        }

        private static Text addRun(TextFlow flow, String s, String colorWeb, boolean bold, boolean underline) {
            Text t = new Text(s);
            t.getStyleClass().add("todo-run");
            String style = "";
            if (colorWeb != null && !colorWeb.isBlank()) {
                style += "-fx-fill: " + colorWeb + ";";
            }
            if (bold) {
                style += "-fx-font-weight: bold;";
            }
            if (!style.isEmpty()) {
                t.setStyle(style);
            }
            t.setUnderline(underline);
            flow.getChildren().add(t);
            return t;
        }

        private static String fileLabel(Path p) {
            if (p == null) {
                return "";
            }
            return p.getFileName() == null ? p.toString() : p.getFileName().toString();
        }
    }
}
