package com.editora.ui;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map window's results strip, under its search row (Project Map review 3 B1, positions 1 and 6):
 * "3 of 49" (and "partial" when the search did not reach the whole project), previous / next buttons, and a
 * drop-down list of every ranked match — relative paths, folders and declarations marked. Stepping or choosing a
 * match lands on it in the Map ({@link ProjectMapView#resultsGoTo}); the Map tells the strip when its answer or
 * its current match changes ({@link ProjectMapView#setOnResultsChanged}). Shown only while a query searches.
 */
final class ProjectMapResultsStrip extends HBox {

    private final ProjectMapView view;
    private final Supplier<String> query;
    private final Button previous;
    private final Button next;
    private final Label count = new Label();
    private final Label partial = new Label(tr("project.map.results.partial"));
    private final ComboBox<ProjectMapSearchResult.Match> list = new ComboBox<>();
    /** The answer the list holds, so a refresh that only moves the cursor does not rebuild 2,000 rows. */
    private @Nullable ProjectMapSearchResult listed;
    /** Set while the strip itself moves the list's selection, which must not land on a match again. */
    private boolean updating;

    ProjectMapResultsStrip(ProjectMapView view, Supplier<String> query) {
        this.view = view;
        this.query = query;
        getStyleClass().add("project-map-results");
        setAlignment(Pos.CENTER_LEFT);
        previous = Icons.button(
                Icons.findPrevious(),
                tr("project.map.results.previous"),
                () -> view.resultsStep(-1),
                "project-map-results-step");
        next = Icons.button(
                Icons.findNext(),
                tr("project.map.results.next"),
                () -> view.resultsStep(1),
                "project-map-results-step");
        // Reached from the search field by its keys; Tab goes from the field straight to the map.
        previous.setFocusTraversable(false);
        next.setFocusTraversable(false);
        count.getStyleClass().add("project-map-results-count");
        count.setMinWidth(Label.USE_PREF_SIZE);
        count.setAccessibleRole(AccessibleRole.TEXT);
        partial.getStyleClass().add("project-map-results-partial");
        partial.setMinWidth(Label.USE_PREF_SIZE);
        partial.setTooltip(new Tooltip(tr("project.map.results.partialTooltip")));
        list.getStyleClass().add("project-map-results-list");
        list.setPromptText(tr("project.map.results.list"));
        list.setAccessibleText(tr("project.map.results.list"));
        list.setTooltip(new Tooltip(tr("project.map.results.list")));
        list.setVisibleRowCount(16);
        list.setMaxWidth(Double.MAX_VALUE);
        list.setMinWidth(0);
        list.setFocusTraversable(false);
        list.setCellFactory(unused -> new MatchCell());
        list.setButtonCell(new MatchCell());
        list.setOnAction(event -> {
            int index = list.getSelectionModel().getSelectedIndex();
            if (!updating && index >= 0) {
                view.resultsGoTo(index, false);
            }
        });
        HBox.setHgrow(list, Priority.ALWAYS);
        getChildren().setAll(previous, next, count, partial, list);
        refresh();
    }

    /** Brings the strip up to date with the Map's answer and current match. */
    void refresh() {
        String typed = query.get();
        String q = typed == null ? "" : typed.trim(); // as the search reads the field
        boolean searchable = !q.isEmpty() && searchable(q);
        setVisible(searchable);
        setManaged(searchable);
        if (!searchable) {
            setList(null);
            return;
        }
        ProjectMapSearchResult answer = view.searchResult();
        // Until the debounced search runs, the Map still holds the previous query's answer.
        ProjectMapSearchResult result = view.resultsAnswered() && answer.query().equals(q) ? answer : null;
        int size = result == null ? 0 : result.matches().size();
        int cursor = result == null ? -1 : view.resultsCursor();
        String text;
        if (result == null) {
            text = tr("project.map.results.searching");
        } else if (size == 0) {
            text = tr("project.map.results.none");
        } else if (result.total() > size) {
            text = tr("project.map.results.countOfTotal", format(cursor + 1), format(size), format(result.total()));
        } else {
            text = tr("project.map.results.count", format(cursor + 1), format(size));
        }
        boolean cut = result != null && result.partial();
        partial.setVisible(cut);
        partial.setManaged(cut);
        previous.setDisable(size == 0);
        next.setDisable(size == 0);
        setList(size == 0 ? null : result);
        list.setDisable(size == 0);
        updating = true;
        try {
            if (cursor >= 0 && list.getSelectionModel().getSelectedIndex() != cursor) {
                list.getSelectionModel().select(cursor);
            } else if (cursor < 0) {
                list.getSelectionModel().clearSelection();
            }
        } finally {
            updating = false;
        }
        String spoken = cut ? text + ", " + tr("project.map.results.partialTooltip") : text;
        if (!text.equals(count.getText()) || !spoken.equals(count.getAccessibleText())) {
            count.setText(text);
            count.setAccessibleText(spoken);
            // The outcome of a search is read out, not only drawn (B11).
            count.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
        }
    }

    /** The count as shown ("3 of 49"; tests). */
    String countText() {
        return count.getText();
    }

    /** Whether the strip shows the "partial" mark (tests). */
    boolean showsPartial() {
        return partial.isVisible();
    }

    /** The drop-down list of matches (tests). */
    ComboBox<ProjectMapSearchResult.Match> list() {
        return list;
    }

    private void setList(@Nullable ProjectMapSearchResult result) {
        if (result == listed) {
            return;
        }
        listed = result;
        updating = true;
        try {
            List<ProjectMapSearchResult.Match> matches = result == null ? List.of() : result.matches();
            list.setItems(FXCollections.observableArrayList(matches));
        } finally {
            updating = false;
        }
    }

    /** A query the Map searches the whole project for (a shorter one only filters the rows on the map). */
    private static boolean searchable(String q) {
        String text = ProjectMapSearch.Query.parse(q).text();
        return text.codePointCount(0, text.length()) >= ProjectPanel.MIN_MAP_SEARCH_LENGTH;
    }

    private static String format(int value) {
        return NumberFormat.getIntegerInstance(FileHistoryPanel.locale()).format(value);
    }

    /** What a list row (and the closed list) reads: name, a folder or declaration mark, and where it is. */
    static String rowText(ProjectMapSearchResult.Match match) {
        String where = parentOf(match.relativePath());
        return switch (match.kind()) {
            case FOLDER ->
                where.isEmpty()
                        ? tr("project.map.results.folderRow", match.label())
                        : tr("project.map.results.folderRowIn", match.label(), where);
            case SYMBOL ->
                tr(
                        "project.map.results.symbolRow",
                        match.label(),
                        match.detail().isBlank() ? tr("project.map.results.declaration") : match.detail(),
                        match.relativePath(),
                        match.line() + 1);
            case FILE -> where.isEmpty() ? match.label() : tr("project.map.results.fileRowIn", match.label(), where);
            case TEXT -> tr("project.map.results.textRow", match.label(), match.line() + 1, match.detail());
        };
    }

    private static String parentOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash <= 0 ? "" : relativePath.substring(0, slash);
    }

    private static final class MatchCell extends ListCell<ProjectMapSearchResult.Match> {
        MatchCell() {
            setTextOverrun(OverrunStyle.LEADING_ELLIPSIS); // a long path keeps its end: the name
            getStyleClass().add("project-map-results-row");
        }

        @Override
        protected void updateItem(ProjectMapSearchResult.@Nullable Match match, boolean empty) {
            super.updateItem(match, empty);
            getStyleClass()
                    .removeAll("project-map-results-folder", "project-map-results-symbol", "project-map-results-text");
            if (empty || match == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(rowText(match));
            switch (match.kind()) {
                case FOLDER -> {
                    getStyleClass().add("project-map-results-folder");
                    setGraphic(null);
                }
                case SYMBOL -> {
                    getStyleClass().add("project-map-results-symbol");
                    setGraphic(null);
                }
                case FILE, TEXT -> {
                    if (match.kind() == ProjectMapSearchResult.Kind.TEXT) {
                        getStyleClass().add("project-map-results-text");
                    }
                    Path name = match.path().getFileName();
                    setGraphic(FileIcons.forFileName(name == null ? match.label() : name.toString()));
                }
            }
        }
    }
}
