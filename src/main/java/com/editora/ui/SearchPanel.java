package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
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
import javafx.scene.layout.VBox;

/**
 * The "Find in Files" results tool window: a query + replace box with case/regex/word toggles, and a
 * tree of matches grouped by file (Enter / double-click jumps to a match). Mirrors {@code GitPanel}
 * ({@link ToolWindowContent}); all work is routed back to the controller via {@link Actions}.
 */
public final class SearchPanel extends VBox implements ToolWindowContent {

    /** Controller callbacks. */
    public interface Actions {
        void search(SearchQuery query);

        void openMatch(Path file, int line, int col);

        void replaceAll(SearchQuery query, String replacement, List<Path> files);
    }

    /** A tree row: a file header ({@code match == null}) or a single match under it. */
    private record Row(Path file, LineMatch match) {}

    private final Actions actions;
    private final TextField queryField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox caseBox = new CheckBox("Aa");
    private final CheckBox regexBox = new CheckBox(".*");
    private final CheckBox wordBox = new CheckBox("W");
    private final Label summary = new Label();
    private final TreeView<Row> tree = new TreeView<>();
    private List<Path> lastFiles = List.of();

    public SearchPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("search-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));
        build();
    }

    private void build() {
        queryField.setPromptText(tr("search.queryPrompt"));
        replaceField.setPromptText(tr("search.replacePrompt"));
        caseBox.setTooltip(new Tooltip(tr("search.caseTip")));
        regexBox.setTooltip(new Tooltip(tr("search.regexTip")));
        wordBox.setTooltip(new Tooltip(tr("find.wholeWord")));
        HBox.setHgrow(queryField, Priority.ALWAYS);
        HBox.setHgrow(replaceField, Priority.ALWAYS);

        Button searchBtn = new Button(tr("search.run"));
        searchBtn.setDefaultButton(false);
        searchBtn.setOnAction(e -> runSearch());
        Button replaceBtn = new Button(tr("search.replaceAll"));
        replaceBtn.setOnAction(e -> runReplace());

        queryField.setOnAction(e -> runSearch());
        HBox queryRow = new HBox(6, queryField, caseBox, regexBox, wordBox, searchBtn);
        queryRow.setAlignment(Pos.CENTER_LEFT);
        HBox replaceRow = new HBox(6, replaceField, replaceBtn);
        replaceRow.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("search-summary");

        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<>());
        tree.setCellFactory(t -> new RowCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        // Standard Emacs-style navigation within the panel (mirrors the other tool windows). Native
        // arrow keys also work in the tree; text fields keep their own keys.
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);

        getChildren().addAll(queryRow, replaceRow, summary, tree);
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

    private SearchQuery currentQuery() {
        return new SearchQuery(queryField.getText(), caseBox.isSelected(), regexBox.isSelected(), wordBox.isSelected());
    }

    private void runSearch() {
        if (!queryField.getText().isEmpty()) {
            actions.search(currentQuery());
        }
    }

    private void runReplace() {
        if (!queryField.getText().isEmpty() && !lastFiles.isEmpty()) {
            actions.replaceAll(currentQuery(), replaceField.getText(), lastFiles);
        }
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
        tree.setRoot(root);
        summary.setText(
                outcome.totalMatches() == 0
                        ? tr("search.none")
                        : tr(
                                outcome.truncated() ? "search.summaryTruncated" : "search.summary",
                                outcome.totalMatches(),
                                outcome.fileCount()));
    }

    /** Re-runs the current search (e.g. after a replace) if there is a query. */
    public void refresh() {
        runSearch();
    }

    @Override
    public void focusFirstItem() {
        queryField.requestFocus();
        queryField.selectAll();
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
