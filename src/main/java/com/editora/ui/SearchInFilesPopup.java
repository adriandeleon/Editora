package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.editora.editor.SearchMatcher;
import com.editora.search.FileResult;
import com.editora.search.LineMatch;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;

import static com.editora.i18n.Messages.tr;

/**
 * An in-scene "Find in Files" popup overlay: a query field + case/regex/whole-word toggles + an editable
 * search-folder field, over a grouped file→match result list. Typing runs a debounced multi-file content
 * search (via the injected {@link Ops#search}); Enter on a match jumps to it (Enter on a file header opens
 * that file), and the popup closes. No replace — it's the keyboard-first counterpart to the Find-in-Files
 * tool window. Modeled on {@link QuickOpen}/{@link CommandPalette}: shown through the shared
 * {@link OverlayHost}, {@code editora.ownsKeys} so its nav chords aren't hijacked, {@code Esc}/{@code C-g}
 * dismiss (handled by the host).
 */
final class SearchInFilesPopup {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** One result row: a file header ({@code match == null}, {@code count} = its match count) or a match. */
    private record Row(Path file, LineMatch match, int count) {
        boolean isHeader() {
            return match == null;
        }
    }

    /** Window hooks the popup needs (the default root, the async search, and open-in-editor). */
    interface Ops {
        /** The folder to seed the search root with on open (project root, else the active file's folder). */
        Path defaultRoot();

        /** Runs the multi-file search off-thread with comma-separated include/exclude globs; {@code onResult}
         *  is delivered on the FX thread. */
        void search(
                SearchQuery query,
                Path root,
                String includeGlobs,
                String excludeGlobs,
                Consumer<SearchService.Outcome> onResult);

        /** Opens {@code file} and jumps to {@code line}/{@code col} (1-based), focusing the editor. */
        void openMatch(Path file, int line, int col);

        /** Records the run query into the persistent search history (shared with the tool window). */
        void recordSearch(String query);
    }

    private static final double CELL_HEIGHT = 24;
    private static final int MAX_VISIBLE = 14;
    private static final double CARD_WIDTH = 660;

    private final OverlayHost overlayHost;
    private final Ops ops;

    private final TextField query = new TextField();
    private final CheckBox caseSensitive = new CheckBox("Aa");
    private final CheckBox regex = new CheckBox(".*");
    private final CheckBox wholeWord = new CheckBox("W");
    private final TextField rootField = new TextField();
    /** Comma-separated include / exclude globs (e.g. {@code *.java, src/**}), mirroring the Find-in-Files panel. */
    private final TextField include = new TextField();

    private final TextField exclude = new TextField();
    /** Shown only when ripgrep is the effective backend (pushed from the coordinator), mirroring the panel. */
    private final Label backendBadge = new Label("ripgrep");

    private final ListView<Row> list = new ListView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label status = new Label();
    private final VBox content;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    private boolean showing;

    SearchInFilesPopup(OverlayHost overlayHost, Ops ops) {
        this.overlayHost = overlayHost;
        this.ops = ops;
        this.content = build();
        debounce.setOnFinished(e -> runSearch());
    }

    private VBox build() {
        query.setPromptText(tr("search.queryPrompt"));
        query.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        query.addEventFilter(KeyEvent.KEY_PRESSED, this::onQueryKey);
        // Emacs caret movement + basic editing (registered after onQueryKey so its list navigation wins).
        com.editora.command.TextInputKeymap.installShared(query);
        if (IS_MAC) {
            // Swallow Option-composed chars from the opening chord / chorded keys (mirrors QuickOpen).
            query.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                if (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown()) {
                    e.consume();
                }
            });
        }

        caseSensitive.setTooltip(new Tooltip(tr("search.caseTip")));
        regex.setTooltip(new Tooltip(tr("search.regexTip")));
        wholeWord.setTooltip(new Tooltip(tr("find.wholeWord")));
        caseSensitive.selectedProperty().addListener((o, a, b) -> runSearch());
        regex.selectedProperty().addListener((o, a, b) -> runSearch());
        wholeWord.selectedProperty().addListener((o, a, b) -> runSearch());
        backendBadge.getStyleClass().add("search-backend-badge");
        backendBadge.setTooltip(new Tooltip(tr("search.backendRipgrepTip")));
        backendBadge.setVisible(false);
        backendBadge.setManaged(false);
        Region toggleSpacer = new Region();
        HBox.setHgrow(toggleSpacer, Priority.ALWAYS);
        HBox toggles = new HBox(12, caseSensitive, regex, wholeWord, toggleSpacer, backendBadge);
        toggles.setAlignment(Pos.CENTER_LEFT);
        toggles.getStyleClass().add("fif-toggles");

        rootField.setPromptText(tr("search.rootPrompt"));
        rootField.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        Label rootLabel = new Label(tr("search.rootLabel"));
        rootLabel.getStyleClass().add("fif-root-label");
        HBox.setHgrow(rootField, Priority.ALWAYS);
        HBox rootRow = new HBox(6, rootLabel, rootField);
        rootRow.setAlignment(Pos.CENTER_LEFT);

        include.setPromptText(tr("search.includePrompt"));
        exclude.setPromptText(tr("search.excludePrompt"));
        include.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        exclude.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        com.editora.command.TextInputKeymap.installShared(rootField);
        com.editora.command.TextInputKeymap.installShared(include);
        com.editora.command.TextInputKeymap.installShared(exclude);
        HBox.setHgrow(include, Priority.ALWAYS);
        HBox.setHgrow(exclude, Priority.ALWAYS);
        HBox globRow = new HBox(6, include, exclude);
        globRow.setAlignment(Pos.CENTER_LEFT);

        list.setItems(rows);
        list.setFixedCellSize(CELL_HEIGHT);
        list.setCellFactory(v -> new ResultCell());
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> resizeList());

        status.getStyleClass().add("fif-status");
        Label title = new Label(tr("search.popupTitle"));
        title.getStyleClass().add("palette-title");
        Label hint = new Label(tr("search.popupHint"));
        hint.getStyleClass().add("palette-hint");

        VBox card = new VBox(6, title, query, toggles, rootRow, globRow, list, status, hint);
        card.getStyleClass().addAll("command-palette", "fif-popup");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxSize(CARD_WIDTH, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        resizeList();
        return card;
    }

    /** Sizes the result list to its row count, capped at {@link #MAX_VISIBLE} (then it scrolls). */
    private void resizeList() {
        int n = Math.max(1, Math.min(rows.size(), MAX_VISIBLE));
        double h = n * CELL_HEIGHT + 2;
        list.setMinHeight(h);
        list.setPrefHeight(h);
        list.setMaxHeight(h);
    }

    /**
     * Shows the popup. Resets the search-root field to {@code Ops.defaultRoot()} (so it tracks the current
     * project / file), seeds the query from {@code selection} when non-null (else keeps the previous query),
     * focuses + selects the query field, and runs the search if a query is present.
     */
    void show(String selection) {
        Path root = ops.defaultRoot();
        rootField.setText(root == null ? "" : root.toString());
        if (selection != null && !selection.isEmpty()) {
            query.setText(selection);
        }
        showing = true;
        overlayHost.show(
                content,
                () -> {
                    query.requestFocus();
                    query.selectAll();
                    runSearch();
                },
                () -> showing = false);
    }

    boolean isShown() {
        return showing;
    }

    /** Shows/hides the "ripgrep" backend badge (pushed by the coordinator, like {@code SearchPanel}). */
    void setBackendActive(boolean ripgrep) {
        backendBadge.setVisible(ripgrep);
        backendBadge.setManaged(ripgrep);
    }

    private void onQueryKey(KeyEvent e) {
        switch (e.getCode()) {
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case ENTER -> {
                openSelected();
                e.consume();
            }
            default -> {}
            // ESCAPE / C-g are handled by the OverlayHost.
        }
    }

    private void move(int delta) {
        int size = rows.size();
        if (size == 0) {
            return;
        }
        int idx = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
    }

    private void openSelected() {
        Row r = list.getSelectionModel().getSelectedItem();
        if (r == null) {
            return;
        }
        overlayHost.hide();
        if (r.isHeader()) {
            ops.openMatch(r.file(), 1, 0);
        } else {
            ops.openMatch(r.file(), r.match().line(), r.match().col());
        }
    }

    private void runSearch() {
        String q = query.getText();
        if (q == null || q.isBlank()) {
            rows.clear();
            status.setText("");
            return;
        }
        if (regex.isSelected()) {
            String err = SearchMatcher.regexError(q);
            if (err != null) {
                rows.clear();
                status.setText(err);
                return;
            }
        }
        Path root = resolveRoot();
        if (root == null && !rootField.getText().isBlank()) {
            rows.clear();
            status.setText(tr("search.rootNotFound"));
            return;
        }
        ops.recordSearch(q);
        status.setText(tr("search.searching"));
        SearchQuery sq = new SearchQuery(q, caseSensitive.isSelected(), regex.isSelected(), wholeWord.isSelected());
        Path scope = root;
        ops.search(sq, scope, include.getText(), exclude.getText(), outcome -> populate(outcome, scope));
    }

    private void populate(SearchService.Outcome outcome, Path root) {
        List<Row> out = new ArrayList<>();
        int firstMatch = -1;
        for (FileResult fr : outcome.files()) {
            out.add(new Row(fr.file(), null, fr.matches().size()));
            for (LineMatch m : fr.matches()) {
                if (firstMatch < 0) {
                    firstMatch = out.size();
                }
                out.add(new Row(fr.file(), m, 0));
            }
        }
        currentRoot = root;
        rows.setAll(out);
        if (!rows.isEmpty()) {
            list.getSelectionModel().select(Math.max(0, firstMatch));
        }
        status.setText(
                outcome.totalMatches() == 0
                        ? tr("search.none")
                        : outcome.truncated()
                                ? tr("search.summaryTruncated", outcome.totalMatches(), outcome.fileCount())
                                : tr("search.summary", outcome.totalMatches(), outcome.fileCount()));
    }

    /** The root the currently-shown results were produced against (for relativizing header paths). */
    private Path currentRoot;

    /** Resolves the root field to a directory: blank ⇒ null (open buffers only); {@code ~} expanded; a
     *  non-directory ⇒ null. */
    private Path resolveRoot() {
        String t = rootField.getText();
        if (t == null || t.isBlank()) {
            return null;
        }
        t = t.trim();
        if (t.startsWith("~")) {
            t = System.getProperty("user.home", "") + t.substring(1);
        }
        try {
            Path p = Path.of(t).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A file's display path: relative to {@code root} when it lives under it, else the absolute path. Pure
     * (unit-tested).
     */
    static String displayPath(Path root, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (root != null) {
            Path r = root.toAbsolutePath().normalize();
            if (abs.startsWith(r)) {
                String rel = r.relativize(abs).toString();
                return rel.isEmpty() ? String.valueOf(abs.getFileName()) : rel;
            }
        }
        return abs.toString();
    }

    private final class ResultCell extends ListCell<Row> {
        private final Label fileName = new Label();
        private final Label count = new Label();
        private final HBox header = new HBox(6, fileName, count);
        private final Label lineNo = new Label();
        private final Label preview = new Label();
        private final HBox matchRow = new HBox(8, lineNo, preview);

        ResultCell() {
            header.setAlignment(Pos.CENTER_LEFT);
            fileName.getStyleClass().add("fif-file");
            count.getStyleClass().add("fif-count");
            matchRow.setAlignment(Pos.CENTER_LEFT);
            matchRow.getStyleClass().add("fif-match-row");
            lineNo.getStyleClass().add("fif-line");
            preview.getStyleClass().add("fif-preview");
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    openSelected();
                }
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            if (item.isHeader()) {
                fileName.setText(displayPath(currentRoot, item.file()));
                fileName.setGraphic(
                        FileIcons.forFileName(String.valueOf(item.file().getFileName())));
                count.setText("(" + item.count() + ")");
                setGraphic(header);
            } else {
                LineMatch m = item.match();
                lineNo.setText(String.valueOf(m.line()));
                String text = m.lineText() == null ? "" : m.lineText().strip();
                preview.setText(text);
                setGraphic(matchRow);
            }
        }
    }
}
