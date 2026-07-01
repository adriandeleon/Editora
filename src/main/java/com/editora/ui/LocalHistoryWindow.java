package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;

import static com.editora.i18n.Messages.tr;

/**
 * IntelliJ-style Local History window: the file's revisions in a list on the left, and a live
 * side-by-side diff of the selected revision (the "before") against the current file on the right,
 * reusing {@link DiffViewerPane}. A small toolbar shows the revision timestamp header, a "N differences"
 * count, ignore-whitespace / highlight-words toggles (both re-diff via {@link DiffEngine.DiffOptions}),
 * and a Revert button; a content-search box filters the revision list by loaded content. Selecting a
 * revision re-diffs (off-thread, via the injected {@link DiffComputer}); the pane's own toolbar keeps the
 * side-by-side/unified toggle and prev/next-change navigation.
 */
final class LocalHistoryWindow {

    /** Off-thread diff compute (delegates to {@code DiffCoordinator}/{@code DiffService}). */
    @FunctionalInterface
    interface DiffComputer {
        void compute(String left, String right, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult);
    }

    /** Async fetch of a revision's snapshot text (delegates to {@code HistoryService}). */
    @FunctionalInterface
    interface ContentFetcher {
        void fetch(HistoryRevision rev, Consumer<String> onText);
    }

    private final Stage stage = new Stage();
    private final ObservableList<HistoryRevision> all = FXCollections.observableArrayList();
    private final FilteredList<HistoryRevision> filtered = new FilteredList<>(all);
    private final ListView<HistoryRevision> list = new ListView<>(filtered);
    private final TextField search = new TextField();
    private final Map<HistoryRevision, String> contentCache = new HashMap<>();

    private final BorderPane rightPane = new BorderPane();
    private final Label headerInfo = new Label();
    private final Label diffCount = new Label();
    private final ToggleButton ignoreWs = new ToggleButton(tr("history.window.ignoreWhitespace"));
    private final ToggleButton wordLevel = new ToggleButton(tr("history.window.highlightWords"));

    private final String fileName;
    private final Path target;
    private final Supplier<String> currentText;
    private final ContentFetcher fetcher;
    private final DiffComputer computer;
    private final Consumer<HistoryRevision> onRevert;
    private final Settings settings;

    private DiffViewerPane pane; // built on the first diff result
    private String snapshotText = "";
    private String baseText = "";
    private int gen; // stale-guard for async re-diffs (selection / toggle can overlap)

    LocalHistoryWindow(
            Window owner,
            String fileName,
            Path target,
            List<HistoryRevision> revisions,
            Supplier<String> currentText,
            ContentFetcher fetcher,
            DiffComputer computer,
            Consumer<HistoryRevision> onRevert,
            Settings settings) {
        this.fileName = fileName;
        this.target = target;
        this.currentText = currentText;
        this.fetcher = fetcher;
        this.computer = computer;
        this.onRevert = onRevert;
        this.settings = settings;
        all.setAll(revisions);
        wordLevel.setSelected(true); // word-level emphasis on by default (matches the editor diff)

        stage.initOwner(owner);
        stage.setTitle(tr("history.window.title", fileName));
        Scene scene = new Scene(build(), 1000, 640);
        applyStylesheets(scene);
        stage.setScene(scene);
    }

    void show(HistoryRevision preselect) {
        stage.show();
        if (preselect != null && all.contains(preselect)) {
            list.getSelectionModel().select(preselect);
            list.scrollTo(preselect);
        } else {
            list.getSelectionModel().selectFirst();
        }
        // Pre-load revision contents so the content-search box can filter by them (bounded by retention).
        for (HistoryRevision r : all) {
            fetcher.fetch(r, text -> {
                contentCache.put(r, text == null ? "" : text);
                if (!search.getText().isBlank()) {
                    reapplyFilter();
                }
            });
        }
    }

    private Region build() {
        // Left: content search + revision list.
        search.setPromptText(tr("history.window.searchPrompt"));
        search.textProperty().addListener((o, a, b) -> reapplyFilter());
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(HistoryRevision r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setText(null);
                    return;
                }
                String tag = r.label() != null && !r.label().isBlank()
                        ? r.label()
                        : FileHistoryPanel.reasonLabel(r.reason());
                setText(FileHistoryPanel.absoluteTime(r.timestamp()) + "  ·  " + tag);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                showRevision(b);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox left = new VBox(6, search, list);
        left.getStyleClass().add("history-window-left");
        left.setMinWidth(240);
        left.setPrefWidth(300);

        // Right: a toolbar (header + diff count + toggles + revert) above the diff pane.
        headerInfo.getStyleClass().add("history-window-header");
        diffCount.getStyleClass().add("history-window-count");
        ignoreWs.setTooltip(new Tooltip(tr("history.window.ignoreWhitespace")));
        wordLevel.setTooltip(new Tooltip(tr("history.window.highlightWords")));
        ignoreWs.setOnAction(e -> recompute());
        wordLevel.setOnAction(e -> recompute());
        Button revert = new Button(tr("history.window.revert"));
        revert.setTooltip(new Tooltip(tr("history.window.revertTip")));
        revert.setOnAction(e -> revertSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, headerInfo, spacer, diffCount, ignoreWs, wordLevel, revert);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("history-window-toolbar");

        rightPane.setTop(toolbar);
        Label empty = new Label(tr("history.window.selectPrompt"));
        empty.getStyleClass().add("tool-window-placeholder");
        rightPane.setCenter(new StackPane(empty));

        SplitPaneShim split = new SplitPaneShim(left, rightPane, 0.3);
        return split.node();
    }

    /** Loads the selected revision's snapshot + the current file text, then re-diffs. */
    private void showRevision(HistoryRevision rev) {
        headerInfo.setText(tr("history.window.header", FileHistoryPanel.absoluteTime(rev.timestamp())));
        baseText = currentText.get();
        String cached = contentCache.get(rev);
        if (cached != null) {
            snapshotText = cached;
            recompute();
        } else {
            fetcher.fetch(rev, text -> {
                contentCache.put(rev, text == null ? "" : text);
                snapshotText = text == null ? "" : text;
                recompute();
            });
        }
    }

    /** Re-diffs {@link #snapshotText} vs {@link #baseText} with the current toggle options (off-thread). */
    private void recompute() {
        int g = ++gen;
        DiffEngine.DiffOptions opts = new DiffEngine.DiffOptions(ignoreWs.isSelected(), wordLevel.isSelected());
        computer.compute(snapshotText, baseText, opts, model -> {
            if (g != gen) {
                return; // a newer selection/toggle superseded this result
            }
            if (model == null) {
                diffCount.setText(tr("history.window.tooLarge"));
                return;
            }
            diffCount.setText(
                    tr("history.window.differences", model.changeBlockStarts().size()));
            if (pane == null) {
                pane = new DiffViewerPane(
                        fileName,
                        tr("history.window.beforeHeader"),
                        tr("history.window.currentHeader"),
                        fileName,
                        fileName,
                        snapshotText,
                        baseText,
                        model,
                        settings.getFontFamily(),
                        settings.getFontSize(),
                        settings.isShowLineNumbers(),
                        target == null ? null : target.toString());
                rightPane.setCenter(pane.node());
            } else {
                pane.updateContent(snapshotText, baseText, model);
            }
        });
    }

    private void revertSelected() {
        HistoryRevision sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        onRevert.accept(sel);
        // The editor buffer changed underneath → re-baseline and re-diff (now identical).
        javafx.application.Platform.runLater(() -> {
            baseText = currentText.get();
            recompute();
        });
    }

    private void reapplyFilter() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(java.util.Locale.ROOT);
        filtered.setPredicate(rev -> {
            if (q.isEmpty()) {
                return true;
            }
            if (FileHistoryPanel.absoluteTime(rev.timestamp())
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains(q)
                    || FileHistoryPanel.reasonLabel(rev.reason())
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains(q)
                    || (rev.label() != null
                            && rev.label().toLowerCase(java.util.Locale.ROOT).contains(q))) {
                return true;
            }
            String c = contentCache.get(rev);
            return c == null || c.toLowerCase(java.util.Locale.ROOT).contains(q); // not-yet-loaded stays visible
        });
    }

    private void applyStylesheets(Scene scene) {
        addSheet(scene, "/com/editora/styles/app.css");
        addSheet(scene, "/com/editora/styles/syntax.css");
        String editorTheme = EditorThemes.stylesheetFor(settings.getEditorTheme());
        if (editorTheme != null) {
            scene.getStylesheets().add(editorTheme);
        }
    }

    private void addSheet(Scene scene, String path) {
        java.net.URL url = getClass().getResource(path);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }

    /** Tiny wrapper so the split ratio is set after the divider exists (avoids a construction-order gotcha). */
    private static final class SplitPaneShim {
        private final javafx.scene.control.SplitPane sp;

        SplitPaneShim(Region left, Region right, double ratio) {
            sp = new javafx.scene.control.SplitPane(left, right);
            sp.getStyleClass().add("history-window-split");
            javafx.application.Platform.runLater(() -> sp.setDividerPositions(ratio));
        }

        Region node() {
            return sp;
        }
    }
}
