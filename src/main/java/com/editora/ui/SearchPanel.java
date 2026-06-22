package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;

import static com.editora.i18n.Messages.tr;

/**
 * The "Find in Files" results tool window: a query + replace box with case/regex/word toggles, and a
 * tree of matches grouped by file (Enter / double-click jumps to a match). Mirrors {@code GitPanel}
 * ({@link ToolWindowContent}); all work is routed back to the controller via {@link Actions}.
 */
public final class SearchPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    public interface Actions {
        void search(SearchQuery query, String includeGlobs, String excludeGlobs);

        /**
         * Opens a match. {@code focusEditor} = true activates it (move focus into the editor — double-click /
         * Enter); false is a preview (jump there but keep focus in the results, so the user can keep
         * arrowing through results — single click / keyboard selection), mirroring the Structure tool window.
         */
        void openMatch(Path file, int line, int col, boolean focusEditor);

        void replaceAll(SearchQuery query, String replacement, List<Path> files);

        /** Records an executed query into the persistent search history. */
        void recordSearch(String query);
    }

    /** A tree row: a file header ({@code match == null}) or a single match under it. */
    private record Row(Path file, LineMatch match) {}

    private final Actions actions;
    private final ComboBox<String> queryCombo = new ComboBox<>();
    private final TextField replaceField = new TextField();
    private final TextField includeField = new TextField();
    private final TextField excludeField = new TextField();
    private final CheckBox caseBox = new CheckBox("Aa");
    private final CheckBox regexBox = new CheckBox(".*");
    private final CheckBox wordBox = new CheckBox("W");
    private final Label summary = new Label();
    /** Shown only when ripgrep is the effective Find-in-Files backend (set by the controller). */
    private final Label backendBadge = new Label("ripgrep");
    /** The folder being searched (project root, or the current file's folder), set by the controller. */
    private final Label scopeLabel = new Label();

    private final TreeView<Row> tree = new TreeView<>();
    private List<Path> lastFiles = List.of();
    /** True while {@link #setResults} rebuilds the tree, so the selection listener doesn't auto-open. */
    private boolean suppressNavigation;

    public SearchPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("search-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        queryCombo.setEditable(true);
        queryCombo.setPromptText(tr("search.queryPrompt"));
        queryCombo.setVisibleRowCount(12);
        replaceField.setPromptText(tr("search.replacePrompt"));
        caseBox.setTooltip(new Tooltip(tr("search.caseTip")));
        regexBox.setTooltip(new Tooltip(tr("search.regexTip")));
        wordBox.setTooltip(new Tooltip(tr("find.wholeWord")));
        HBox.setHgrow(queryCombo, Priority.ALWAYS);
        queryCombo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(replaceField, Priority.ALWAYS);

        Button searchBtn = new Button(tr("search.run"));
        searchBtn.setDefaultButton(false);
        searchBtn.setOnAction(e -> runSearch());
        Button replaceBtn = new Button(tr("search.replaceAll"));
        replaceBtn.setOnAction(e -> runReplace());

        // Disable the search controls (case/regex/word toggles + Search) while the query is empty, and the
        // Replace All button while the replacement field is empty — nothing useful can run in those states.
        javafx.beans.binding.BooleanBinding queryEmpty =
                queryCombo.getEditor().textProperty().isEmpty();
        searchBtn.disableProperty().bind(queryEmpty);
        caseBox.disableProperty().bind(queryEmpty);
        regexBox.disableProperty().bind(queryEmpty);
        wordBox.disableProperty().bind(queryEmpty);
        replaceBtn.disableProperty().bind(replaceField.textProperty().isEmpty());
        // Clearing the query empties the results (nothing to show without a search term).
        queryCombo.getEditor().textProperty().addListener((o, old, now) -> {
            if (now == null || now.isEmpty()) {
                clearResults();
            }
        });

        queryCombo.setOnAction(e -> runSearch());
        HBox queryRow = new HBox(6, queryCombo, caseBox, regexBox, wordBox, searchBtn);
        queryRow.setAlignment(Pos.CENTER_LEFT);
        HBox replaceRow = new HBox(6, replaceField, replaceBtn);
        replaceRow.setAlignment(Pos.CENTER_LEFT);

        includeField.setPromptText(tr("search.includePrompt"));
        excludeField.setPromptText(tr("search.excludePrompt"));
        includeField.setTooltip(new Tooltip(tr("search.globsTip")));
        excludeField.setTooltip(new Tooltip(tr("search.globsTip")));
        includeField.setOnAction(e -> runSearch());
        excludeField.setOnAction(e -> runSearch());
        HBox.setHgrow(includeField, Priority.ALWAYS);
        HBox.setHgrow(excludeField, Priority.ALWAYS);
        HBox globsRow = new HBox(6, includeField, excludeField);
        globsRow.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("search-summary");
        backendBadge.getStyleClass().add("search-backend-badge");
        backendBadge.setTooltip(new Tooltip(tr("search.backendRipgrepTip")));
        backendBadge.setVisible(false);
        backendBadge.setManaged(false);
        Region summarySpacer = new Region();
        HBox.setHgrow(summarySpacer, Priority.ALWAYS);
        HBox summaryRow = new HBox(6, summary, summarySpacer, backendBadge);
        summaryRow.setAlignment(Pos.CENTER_LEFT);

        // The folder being searched (project root, or the current file's folder) — shown explicitly so the
        // user always knows the scope. Set by the controller via setScope; ellipsized with a full-path tooltip.
        Label scopePrefix = new Label(tr("search.scopeLabel"));
        scopePrefix.getStyleClass().add("search-scope-prefix");
        scopeLabel.getStyleClass().add("search-scope-path");
        scopeLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(scopeLabel, Priority.ALWAYS);
        HBox scopeRow = new HBox(6, scopePrefix, scopeLabel);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.getStyleClass().add("search-scope-row");

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell());
        // Open the match as soon as the selection changes (single click, or keyboard nav) — but as a
        // preview that keeps focus in the results so the user can keep arrowing — mirroring the Structure
        // tool window. Suppressed while the tree is rebuilt so a programmatic selection change can't fire.
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!suppressNavigation) {
                openSelected(false);
            }
        });
        // A double-click activates: open and move focus into the editor.
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        // Standard Emacs-style navigation within the panel (mirrors the other tool windows). Native
        // arrow keys also work in the tree; text fields keep their own keys.
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(scopeRow, queryRow, replaceRow, globsRow, summaryRow, tree);
    }

    /** Sets the displayed search-scope folder. {@code display} is a friendly label (e.g. {@code ~/proj});
     *  {@code fullPath} (nullable) is the hover tooltip with the absolute path. */
    public void setScope(String display, String fullPath) {
        scopeLabel.setText(display == null ? "" : display);
        scopeLabel.setTooltip(fullPath == null || fullPath.isBlank() ? null : new Tooltip(fullPath));
    }

    /** Show/hide the "ripgrep" backend badge (the controller calls this from applyRipgrepSupport). */
    public void setBackendActive(boolean ripgrep) {
        backendBadge.setVisible(ripgrep);
        backendBadge.setManaged(ripgrep);
    }

    private void onKey(KeyEvent e) {
        if (e.getTarget() instanceof TextInputControl) {
            return; // typing in the query/replace fields
        }
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
                if (!e.isControlDown()) {
                    return;
                }
                switch (e.getCode()) {
                    case N -> {
                        moveSelection(1);
                        e.consume();
                    }
                    case P -> {
                        moveSelection(-1);
                        e.consume();
                    }
                    case F -> {
                        expandSelection(true);
                        e.consume();
                    }
                    case B -> {
                        expandSelection(false);
                        e.consume();
                    }
                    case M -> {
                        activateSelected();
                        e.consume();
                    }
                    default -> {}
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

    private void expandSelection(boolean expand) {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf()) {
            item.setExpanded(expand);
        }
    }

    /** The current query text (the editable combo's editor — not its committed value). */
    private String queryText() {
        String t = queryCombo.getEditor().getText();
        return t == null ? "" : t;
    }

    private SearchQuery currentQuery() {
        return new SearchQuery(queryText(), caseBox.isSelected(), regexBox.isSelected(), wordBox.isSelected());
    }

    private void runSearch() {
        String text = queryText();
        if (!text.isEmpty()) {
            actions.recordSearch(text);
            actions.search(currentQuery(), includeField.getText(), excludeField.getText());
        }
    }

    private void runReplace() {
        if (!queryText().isEmpty() && !lastFiles.isEmpty()) {
            actions.replaceAll(currentQuery(), replaceField.getText(), lastFiles);
        }
    }

    /** Binds the query combo's dropdown to the persistent search-history list (most-recent first). */
    public void setHistory(ObservableList<String> history) {
        queryCombo.setItems(history);
    }

    /** Double-click / Enter: open the selected match and move focus into the editor (else toggle a header). */
    private void activateSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        Row row = item.getValue();
        if (row.match() != null) {
            actions.openMatch(row.file(), row.match().line(), row.match().col(), true);
        } else {
            item.setExpanded(!item.isExpanded());
        }
    }

    /** Single click / keyboard selection: preview the selected match (jump there, keep focus in the panel). */
    private void openSelected(boolean focusEditor) {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null) {
            return;
        }
        Row row = item.getValue();
        if (row.match() != null) {
            actions.openMatch(row.file(), row.match().line(), row.match().col(), focusEditor);
        }
    }

    /** Populates the tree from a search outcome (called on the FX thread by the controller). */
    public void setResults(SearchService.Outcome outcome) {
        TreeItem<Row> root = new TreeItem<>();
        List<Path> files = new ArrayList<>();
        for (FileResult fr : outcome.files()) {
            files.add(fr.file());
            TreeItem<Row> fileItem = new TreeItem<>(new Row(fr.file(), null));
            fileItem.setExpanded(true);
            for (LineMatch m : fr.matches()) {
                fileItem.getChildren().add(new TreeItem<>(new Row(fr.file(), m)));
            }
            root.getChildren().add(fileItem);
        }
        this.lastFiles = files;
        suppressNavigation = true; // swapping the root clears the selection — don't let that auto-open
        tree.setRoot(root);
        suppressNavigation = false;
        summary.setText(
                outcome.totalMatches() == 0
                        ? tr("search.none")
                        : tr(
                                outcome.truncated() ? "search.summaryTruncated" : "search.summary",
                                outcome.totalMatches(),
                                outcome.fileCount()));
    }

    /** Empties the results tree + summary (e.g. when the query is cleared). */
    private void clearResults() {
        lastFiles = List.of();
        suppressNavigation = true; // the root swap clears the selection — don't let that auto-open
        tree.setRoot(new TreeItem<>());
        suppressNavigation = false;
        summary.setText("");
    }

    /** Re-runs the current search (e.g. after a replace) if there is a query. */
    public void refresh() {
        runSearch();
    }

    /** Pre-fills the search field (e.g. from the editor's single-line selection) and runs the search. */
    public void setQuery(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        queryCombo.getEditor().setText(text);
        runSearch();
    }

    /** Returns focus to the results tree (after a preview-open, so keyboard nav keeps working there). */
    public void focusResults() {
        tree.requestFocus();
    }

    @Override
    public void focusFirstItem() {
        queryCombo.getEditor().requestFocus();
        queryCombo.getEditor().selectAll();
    }

    /** Renders a file header (name + match count) or a match line (line number + trimmed preview). */
    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setText(null);
                getStyleClass().remove("search-file-row");
                return;
            }
            if (row.match() == null) {
                int count =
                        getTreeItem() == null ? 0 : getTreeItem().getChildren().size();
                setText(row.file().getFileName() + "  (" + count + ")");
                if (!getStyleClass().contains("search-file-row")) {
                    getStyleClass().add("search-file-row");
                }
            } else {
                getStyleClass().remove("search-file-row");
                String preview = row.match().lineText().strip();
                if (preview.length() > 200) {
                    preview = preview.substring(0, 200) + "…";
                }
                setText(row.match().line() + ":  " + preview);
            }
        }
    }
}
