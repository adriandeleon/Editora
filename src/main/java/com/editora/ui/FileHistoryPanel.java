package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.editor.ControlNames;
import com.editora.editor.MenuChords;
import com.editora.editor.UiZoom;
import com.editora.git.RelativeTime;
import com.editora.history.HistoryBlobStore;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Local History tool window: a newest-first list of recorded versions of the active file on the left,
 * and a <b>live side-by-side diff</b> of the selected revision against the current file on the right —
 * reusing {@link DiffViewerPane}, with ignore-whitespace / highlight-words toggles, per-hunk apply chevrons
 * (IntelliJ-style selective restore), and a whole-file Restore. Everything lives in this one docked tool
 * window (there is no separate history window). Like {@link GitLogPanel} it is purely a view — the controller
 * (via {@link Actions}/{@link DiffSupport}) owns the history service and the buffers.
 *
 * <p>The right side always describes {@link #shown}: the toolbar is dead while nothing is shown, and every
 * path that takes the shown revision away (another file, a filter that hides it, a folder row that is not a
 * revision) clears the diff with it, so a diff can never outlive the row it belongs to.
 */
public final class FileHistoryPanel extends VBox implements ToolWindowContent {

    /** Operations the panel asks the controller to perform. */
    public interface Actions {
        void refresh();

        void restore(HistoryRevision revision);

        /** Folder-history restore: write a revision's content back to its own path (recreating a deleted file). */
        void restoreToDisk(HistoryRevision revision);

        /** Prompt for and set (or clear) a label on this existing revision, so it can be found later by name. */
        void editLabel(HistoryRevision revision);

        /**
         * Asked before a whole-file restore replaces text that was never saved; {@code false} keeps the file
         * as it is. A restore over a saved file is not asked about (it is one undo away).
         */
        default boolean confirmRestoreOverUnsavedEdits(Path file) {
            return true;
        }

        /** Escape in the panel: hand the keyboard back to the editor. */
        default void focusEditor() {}

        /** Folder view: delete every revision of the file at {@code path} (which may no longer exist), after asking. */
        default void purgeFile(String path) {}
    }

    /**
     * The diff machinery the right pane needs, injected once by the coordinator (delegates to
     * {@code DiffCoordinator}/{@code HistoryService}). Kept separate from {@link Actions} so the panel stays
     * a view. {@code applyToLocalIfUnchanged}/{@code undoLocal}/{@code saveLocal} back the per-hunk chevrons.
     */
    public interface DiffSupport {
        void fetchContent(HistoryRevision revision, Consumer<Optional<String>> onText);

        void computeDiff(String left, String right, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult);

        String currentText(Path target);

        /**
         * {@link #currentText}, for a file that may be closed: a closed file is read off the FX thread (PERF-01,
         * Pauta 6.2.0); {@code then} gets the text on the FX thread.
         */
        default void currentTextLater(Path target, Consumer<String> then) {
            then.accept(currentText(target));
        }

        /** Restores {@code revision} into the file; {@code done} runs on the FX thread once that has finished. */
        void revert(HistoryRevision revision, Runnable done);

        /**
         * Applies {@code newText} only while {@code target} still holds {@code expectedText} (the text the
         * hunk was computed from) and reports whether it did.
         */
        void applyToLocalIfUnchanged(
                Path target, @Nullable String expectedText, String newText, Consumer<Boolean> done);

        void undoLocal(Path target);

        void saveLocal(Path target);

        Settings settings();

        /** Whether {@code target} is open with edits that were never saved. */
        default boolean hasUnsavedEdits(Path target) {
            return false;
        }
    }

    /** One file in the folder-history view: its absolute path, a display name, deleted flag, and revisions. */
    public record FileGroup(String path, String display, boolean deleted, List<HistoryRevision> revisions) {}

    /** Above this many characters the editor text is not hashed to find the "Current" row. */
    private static final int MAX_HASHED_CHARS = 4_000_000;

    private static final double MIN_ROW_HEIGHT = 24;

    /** How many files' last-selected revisions are remembered for the way back to their tab. */
    private static final int REMEMBERED_FILES = 32;

    private final Actions actions;
    private @Nullable DiffSupport support; // injected after construction

    // --- Left: revision list (single-file) / folder tree ---
    private final Label fileLabel = new Label(tr("history.noFile"));
    private final Tooltip fileTip = ControlNames.tooltip();
    private final Button backToFile;
    private final TextField filter = new TextField();
    private final ListView<HistoryRevision> revisions = new ListView<>();
    private final Label placeholder = new Label(tr("history.noRevisions"));
    private final List<HistoryRevision> allRevisions = new ArrayList<>();
    private final TreeView<Object> folderTree = new TreeView<>();
    private final List<FileGroup> allGroups = new ArrayList<>(); // the folder view's files, before the filter
    private final Label folderEmpty = new Label(tr("history.noMatch")); // the tree has no placeholder of its own

    // --- Right: the live diff of the shown revision vs the current file ---
    private final BorderPane rightPane = new BorderPane();
    private final HBox diffToolbar;
    private final Label headerInfo = new Label();
    private final Tooltip headerTip = ControlNames.tooltip();
    private final ToggleButton ignoreWs = new ToggleButton(tr("history.window.ignoreWhitespace"));
    private final ToggleButton wordLevel = new ToggleButton(tr("history.window.highlightWords"));
    private final Button restore = new Button(tr("history.window.revert"));
    private final Label diffPlaceholder = new Label(tr("history.window.selectPrompt"));
    private final PauseTransition editorSettled = new PauseTransition(Duration.millis(250));

    private @Nullable Path target; // the active file the revisions belong to (null in folder mode / no file)
    private @Nullable Path folderShown; // the folder whose history is listed (null in single-file mode)
    private @Nullable HistoryRevision shown; // the revision the right side describes (null = the prompt)
    private @Nullable Path shownPath; // the file `shown` is compared with: `target`, or a folder row's own file
    private boolean shownMissing; // `shown`'s content could not be read
    private boolean shownTooLarge; // `shown` could not be diffed
    private @Nullable DiffViewerPane pane; // built on the first diff result for `shownPath`
    private @Nullable Path panePath; // the file `pane` was built for
    private String snapshotText = "";
    private String baseText = "";
    private String editorSha = ""; // hash of the editor text, to find the row it equals ("" = not known)
    private @Nullable HistoryRevision pendingSelection; // asked for before its list arrived (Recent Changes)
    private @Nullable HistoryRevision pendingFolderSelection; // asked for before its folder's tree arrived
    private boolean keepingSelection; // the list is being rebuilt; selection changes are not the user's
    private int gen; // stale-guard for async re-diffs (selection / toggle can overlap)

    /** Each file's last selected revision, so coming back to its tab comes back to its diff. */
    private final Map<String, HistoryRevision> remembered = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HistoryRevision> eldest) {
            return size() > REMEMBERED_FILES;
        }
    };

    public FileHistoryPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("history-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(0);
        setPadding(Insets.EMPTY);

        // Left side --------------------------------------------------------------------------------------
        fileLabel.getStyleClass().add("git-branch-label");
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        fileLabel.setMinWidth(0);
        HBox.setHgrow(fileLabel, Priority.ALWAYS);
        backToFile = CommandTips.name(
                iconButton(Icons.chevronLeft(), CommandTips.title("history.backToFile"), this::showFileView),
                "history.backToFile");
        backToFile.setVisible(false);
        backToFile.setManaged(false);
        Button refresh = iconButton(Icons.refresh(), tr("history.refreshTip"), actions::refresh);
        FocusCommands.press(this, "history.refresh", refresh); // the window's buttons' commands
        FocusCommands.press(this, "history.toggleIgnoreWhitespace", ignoreWs);
        FocusCommands.press(this, "history.toggleHighlightWords", wordLevel);
        HBox toolbar = UiZoom.spacing(new HBox(backToFile, fileLabel, refresh), 2);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        filter.setPromptText(tr("history.filterPrompt"));
        filter.setAccessibleText(tr("history.filterPrompt"));
        filter.getStyleClass().add("history-filter");
        filter.setMaxWidth(Double.MAX_VALUE);
        filter.textProperty().addListener((o, a, b) -> {
            if (keepingSelection) {
                return;
            }
            if (folderTree.isManaged()) {
                fillFolderTree(shown, null); // the shown revision stays while the filter still lists it
            } else {
                applyFilter();
            }
        });

        revisions.getStyleClass().addAll("git-tree", "history-list");
        revisions.setCellFactory(v -> new RevisionCell());
        revisions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && !keepingSelection) {
                if (target != null) {
                    remembered.put(target.toString(), b);
                }
                showRevision(b, target);
            }
        });
        revisions.addEventHandler(KeyEvent.KEY_PRESSED, this::onListKey);
        RowContextMenu.install(revisions); // Menu key / Shift+F10 open the selected row's menu
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        revisions.setPlaceholder(placeholder);
        VBox.setVgrow(revisions, Priority.ALWAYS);

        folderTree.getStyleClass().add("git-tree");
        folderTree.setShowRoot(false);
        folderTree.setCellFactory(t -> new FolderCell());
        folderTree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (keepingSelection) {
                return;
            }
            pendingFolderSelection = null; // the user chose: an earlier Recent Changes pick no longer applies
            if (b != null && b.getValue() instanceof HistoryRevision r) {
                showRevision(r, Path.of(r.path()));
            } else {
                resetDiff(); // a file row, or nothing: no one revision to describe
            }
        });
        folderTree.addEventHandler(KeyEvent.KEY_PRESSED, this::onFolderKey);
        RowContextMenu.install(folderTree);
        VBox.setVgrow(folderTree, Priority.ALWAYS);
        folderTree.setVisible(false);
        folderTree.setManaged(false);
        folderEmpty.getStyleClass().add("tool-window-placeholder");
        folderEmpty.setWrapText(true);
        folderEmpty.setVisible(false);
        folderEmpty.setManaged(false);

        VBox left = UiZoom.spacing(new VBox(toolbar, filter, revisions, folderTree, folderEmpty), 4);
        UiZoom.padding(left, 4);
        left.getStyleClass().add("history-window-left");
        UiZoom.minWidth(left, 200);

        // Right side (diff) ------------------------------------------------------------------------------
        // The header gives way first when the pane is narrow (its full text is in the tooltip); the
        // buttons never shrink, so "Restore" is always readable.
        headerInfo.getStyleClass().add("history-window-header");
        headerInfo.setMinWidth(0);
        headerInfo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(headerInfo, Priority.ALWAYS);
        ControlNames.tip(ignoreWs, tr("history.window.ignoreWhitespace"));
        ControlNames.tip(wordLevel, tr("history.window.highlightWords"));
        wordLevel.setSelected(true); // word-level emphasis on by default (matches the editor diff)
        ignoreWs.setOnAction(e -> recompute());
        wordLevel.setOnAction(e -> recompute());
        ControlNames.tip(restore, tr("history.window.revertTip"));
        restore.setOnAction(e -> restoreShown());
        restore.setMinWidth(Region.USE_PREF_SIZE);
        diffToolbar = UiZoom.spacing(new HBox(headerInfo, ignoreWs, wordLevel, restore), 8);
        diffToolbar.setAlignment(Pos.CENTER_LEFT);
        diffToolbar.getStyleClass().add("history-window-toolbar");
        // The pane's width, not the toolbar's own: a toolbar that cannot shrink is laid out at its minimum,
        // wider than the pane, and would never learn that it has to.
        rightPane.widthProperty().addListener((o, was, now) -> fitToolbar(now.doubleValue()));
        fitToolbar(Double.MAX_VALUE);
        rightPane.setTop(diffToolbar);
        rightPane.setMinWidth(0);
        diffPlaceholder.getStyleClass().add("tool-window-placeholder");
        diffPlaceholder.setWrapText(true);
        rightPane.setCenter(new StackPane(diffPlaceholder));
        // Tab inside a read-only diff area went nowhere, so the keyboard was stuck in the diff.
        rightPane.addEventFilter(KeyEvent.KEY_PRESSED, this::leaveDiffOnTab);
        updateToolbar();

        editorSettled.setOnFinished(e -> rebaseline());
        addEventHandler(KeyEvent.KEY_PRESSED, this::onPanelKey);

        SplitPane split = new SplitPane(left, rightPane);
        split.getStyleClass().add("history-window-split");
        Platform.runLater(() -> split.setDividerPositions(0.28));
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().setAll(split);
    }

    /**
     * Who gives way as the diff pane narrows: the header first (its text is in its tooltip), then the two
     * option toggles (theirs too) — never Restore, which has to stay readable at any width.
     */
    private void fitToolbar(double width) {
        double buttons = ignoreWs.prefWidth(-1) + wordLevel.prefWidth(-1) + restore.prefWidth(-1);
        double needed = buttons
                + 3 * diffToolbar.getSpacing()
                + diffToolbar.snappedLeftInset()
                + diffToolbar.snappedRightInset();
        double togglesMin = width < needed ? 0 : Region.USE_PREF_SIZE;
        ignoreWs.setMinWidth(togglesMin);
        wordLevel.setMinWidth(togglesMin);
    }

    /** Injects the diff machinery (the coordinator calls this once, after construction). */
    public void setDiffSupport(DiffSupport support) {
        this.support = support;
    }

    /** The folder whose history the panel is listing, or {@code null} while it shows a single file. */
    public @Nullable Path folderShown() {
        return folderShown;
    }

    /** Leaves the folder view for the active file's history (the back button; also for the coordinator). */
    public void showFileView() {
        folderShown = null;
        actions.refresh();
    }

    /** Toggles between single-file list mode and folder-tree mode. */
    private void setFolderMode(boolean folder) {
        String prompt = tr(folder ? "history.folder.filterPrompt" : "history.filterPrompt");
        filter.setPromptText(prompt);
        filter.setAccessibleText(prompt);
        if (!folder) {
            folderEmpty.setVisible(false);
            folderEmpty.setManaged(false);
        }
        revisions.setVisible(!folder);
        revisions.setManaged(!folder);
        folderTree.setVisible(folder);
        folderTree.setManaged(folder);
        backToFile.setVisible(folder);
        backToFile.setManaged(folder);
        ControlNames.tip(restore, tr(folder ? "history.folder.revertTip" : "history.window.revertTip"));
    }

    private static Button iconButton(javafx.scene.Node icon, String tip, Runnable action) {
        return Icons.toolbarButton(icon, tip, action, "flat", "git-toolbar-button"); // tooltip + accessible name
    }

    /** Replaces the revision list (single-file mode). {@code fileName} = null/blank ⇒ "no file". */
    public void setRevisions(List<HistoryRevision> list, @Nullable String fileName, @Nullable Path target) {
        boolean wasFolder = folderTree.isManaged();
        folderShown = null;
        setFolderMode(false);
        boolean sameFile = !wasFolder && target != null && target.equals(this.target);
        // The list is reloaded after every recorded revision of the active file (each save and autosave).
        // While the shown revision of the same file is still listed its diff stays: clearing it threw the
        // user back to "select a revision" in the middle of a hunk-by-hunk restore, on the diff's own Save.
        // Coming back to a file's tab comes back to the revision that was selected there.
        // Matched as the same recorded revision, not by equals: a relabelled revision is a new record (U1).
        HistoryRevision wanted = sameFile ? shown : target == null ? null : remembered.get(target.toString());
        if (pendingSelection != null && HistoryRowText.find(list, pendingSelection) != null) {
            wanted = pendingSelection;
            pendingSelection = null;
        }
        wanted = HistoryRowText.find(list, wanted);
        pendingFolderSelection = null;
        this.target = target;
        boolean named = fileName != null && !fileName.isBlank();
        setHeading(named ? tr("history.forFile", fileName) : tr("history.noFile"), target);
        allRevisions.clear();
        allRevisions.addAll(list);
        rehashEditorText(); // the "Current" tag moves when the hash lands
        keepingSelection = true;
        try {
            if (!sameFile) {
                filter.clear(); // a filter typed for one file says nothing about the next
            }
            fillList();
            if (wanted != null && revisions.getItems().contains(wanted)) {
                revisions.getSelectionModel().select(wanted);
            } else {
                revisions.getSelectionModel().clearSelection();
                wanted = null; // not listed, or filtered out
            }
        } finally {
            keepingSelection = false;
        }
        if (wanted == null) {
            resetDiff(); // the previously-shown diff was for the old file (or old list) — clear it
        } else if (sameFile && HistoryRowText.sameRevision(wanted, shown) && pane != null) {
            shown = wanted;
            describe(wanted); // a new label is in the header too
            if (target != null) {
                remembered.put(target.toString(), wanted);
            }
            if (support != null && target != null) {
                int asked = gen;
                support.currentTextLater(target, now -> {
                    if (asked == gen && !now.equals(baseText)) {
                        reDiffAfterEdit(); // same revision, but the file has moved on
                    }
                });
            }
        } else if (target != null) { // a remembered or pending revision is always one of a file's
            remembered.put(target.toString(), wanted);
            showRevision(wanted, target);
        }
    }

    private void setHeading(String text, @Nullable Path path) {
        fileLabel.setText(text);
        revisions.setAccessibleText(text);
        folderTree.setAccessibleText(text);
        if (path == null) {
            fileLabel.setTooltip(null);
        } else {
            fileTip.setText(path.toString()); // a long name is cut short in the label; two files can share one
            fileLabel.setTooltip(fileTip);
        }
    }

    /**
     * In the folder view, selects {@code revision}'s row and brings it into view, which shows its diff; a filter
     * that hides it is cleared. Asked for before the folder tree lists it (Recent Changes on a deleted file shows
     * its folder off the FX thread), it is selected when a tree listing it arrives — unless the user picks a row
     * first.
     */
    public void selectInFolder(HistoryRevision revision) {
        if (revision == null) {
            return;
        }
        TreeItem<Object> row = folderTree.isManaged() ? folderItemOf(revision) : null;
        if (row == null && folderTree.isManaged() && !filter.getText().isEmpty()) {
            pendingFolderSelection = revision;
            filter.clear(); // rebuilds the tree, which selects it if this folder has it
            row = folderItemOf(revision);
        }
        if (row != null) {
            folderTree.getSelectionModel().select(row);
            reveal(row);
        }
        // Kept after a select too: the folder that lists it may still be on its way (another folder shows now).
        pendingFolderSelection = revision;
    }

    /**
     * Selects {@code revision} and brings it into view, which shows its diff on the right; a filter that
     * hides it is cleared. Asked for before the revision's file is the one listed (Recent Changes opens the
     * file first), it is selected as soon as a list containing it arrives.
     */
    public void selectRevision(HistoryRevision revision) {
        if (revision == null) {
            return;
        }
        HistoryRevision listed = HistoryRowText.find(allRevisions, revision);
        if (folderTree.isManaged() || listed == null) {
            pendingSelection = revision;
            return;
        }
        pendingSelection = null;
        if (!revisions.getItems().contains(listed)) {
            filter.clear();
        }
        revisions.getSelectionModel().select(listed);
        revisions.scrollTo(listed);
    }

    /** Clears the right diff pane back to the "select a revision" prompt (a fresh pane is built on next diff). */
    private void resetDiff() {
        gen++; // drop any in-flight async diff
        shown = null;
        shownPath = null;
        shownMissing = false;
        shownTooLarge = false;
        pane = null;
        panePath = null;
        snapshotText = "";
        baseText = "";
        headerInfo.setText("");
        headerInfo.setTooltip(null);
        showPlaceholder(diffPlaceholder);
        updateToolbar();
    }

    private void showPlaceholder(Label label) {
        rightPane.setCenter(new StackPane(label));
    }

    /** The toolbar acts on {@link #shown}: nothing shown (or unreadable) ⇒ nothing to press. */
    private void updateToolbar() {
        diffToolbar.setDisable(shown == null || shownMissing);
        ignoreWs.setDisable(shownTooLarge);
        wordLevel.setDisable(shownTooLarge);
    }

    /**
     * Shows folder-history mode: a file → revisions tree (deleted files badged), restorable from each row. The same
     * folder listed again (a save, a restore, a relabel in it) keeps its selected revision, its diff and its scroll.
     */
    public void setFolderHistory(Path folder, List<FileGroup> groups) {
        Path name = folder == null ? null : folder.getFileName();
        boolean sameFolder = folder != null && folder.equals(folderShown) && folderTree.isManaged();
        showFolder(name == null ? "" : name.toString(), groups, folder, sameFolder);
        if (folder != null) {
            fileTip.setText(folder.toString());
            fileLabel.setTooltip(fileTip);
        }
    }

    /** As {@link #setFolderHistory(Path, List)}, for a caller that only has the folder's name. */
    public void setFolderHistory(String folderName, List<FileGroup> groups) {
        showFolder(folderName == null ? "" : folderName, groups, null, false);
    }

    private void showFolder(String folderName, List<FileGroup> groups, @Nullable Path folder, boolean sameFolder) {
        HistoryRevision keep = sameFolder ? shown : null;
        @Nullable Object firstVisible = sameFolder ? firstVisibleFolderRow() : null;
        if (!sameFolder) {
            resetDiff(); // the file view's diff, or another folder's, does not carry over
        }
        setFolderMode(true);
        this.target = null;
        folderShown = folder;
        setHeading(tr("history.forFolder", folderName), null);
        allGroups.clear();
        allGroups.addAll(groups);
        if (!sameFolder) {
            keepingSelection = true;
            try {
                filter.clear(); // a filter typed for one listing says nothing about the next
            } finally {
                keepingSelection = false;
            }
        }
        fillFolderTree(keep, firstVisible);
    }

    /**
     * Builds the folder tree from {@link #allGroups} through the filter. A pending Recent Changes pick is selected
     * first; otherwise {@code keep} is, while it is still listed, with its diff kept (and re-read, as its file may
     * just have been restored); otherwise the diff is cleared. The row holding {@code firstVisible} (a file or a
     * revision; null: none) is scrolled back to the top, so the rows on screen stay put though rows were added above.
     */
    private void fillFolderTree(@Nullable HistoryRevision keep, @Nullable Object firstVisible) {
        String q = filter.getText();
        boolean filtering = q != null && !q.isBlank();
        TreeItem<Object> root = new TreeItem<>();
        TreeItem<Object> kept = null;
        TreeItem<Object> picked = null;
        for (FileGroup g : allGroups) {
            List<HistoryRevision> rows = filtering ? folderRowsMatching(g, q) : g.revisions();
            if (rows.isEmpty()) {
                continue;
            }
            TreeItem<Object> fileNode = new TreeItem<>(g);
            fileNode.setExpanded(true); // the revisions are what the view is for
            for (HistoryRevision r : rows) {
                TreeItem<Object> item = new TreeItem<>(r);
                if (HistoryRowText.sameRevision(r, pendingFolderSelection)) {
                    picked = item;
                } else if (HistoryRowText.sameRevision(r, keep)) {
                    kept = item;
                }
                fileNode.getChildren().add(item);
            }
            root.getChildren().add(fileNode);
        }
        if (picked != null) {
            pendingFolderSelection = null;
        }
        TreeItem<Object> select = picked != null ? picked : kept;
        keepingSelection = true;
        try {
            folderTree.setRoot(root);
            if (select == null) {
                folderTree.getSelectionModel().clearSelection();
            } else {
                folderTree.getSelectionModel().select(select);
            }
        } finally {
            keepingSelection = false;
        }
        boolean empty = root.getChildren().isEmpty();
        folderEmpty.setText(tr(filtering && !allGroups.isEmpty() ? "history.noMatch" : "history.noRevisions"));
        folderEmpty.setVisible(empty);
        folderEmpty.setManaged(empty);
        if (select == null) {
            resetDiff();
        } else if (select.getValue() instanceof HistoryRevision chosen) {
            if (select == kept && HistoryRowText.sameRevision(chosen, shown) && pane != null) {
                shown = chosen;
                describe(chosen);
                rebaseline(); // the file may have changed under it (a restore brings a deleted one back)
            } else {
                showRevision(chosen, Path.of(chosen.path()));
            }
        }
        TreeItem<Object> top = firstVisible == null ? null : folderItemOf(firstVisible);
        if (select != null && (select == picked || top == null)) {
            reveal(select);
        } else if (top != null) {
            reveal(top);
        }
    }

    /**
     * The folder filter: a file whose name or path matches keeps every revision; otherwise only its revisions that
     * match as a row of the file's own list would (label, reason, time).
     */
    private List<HistoryRevision> folderRowsMatching(FileGroup g, String q) {
        String needle = q.strip().toLowerCase(Locale.ROOT);
        if (g.display().toLowerCase(Locale.ROOT).contains(needle)
                || g.path().toLowerCase(Locale.ROOT).contains(needle)) {
            return g.revisions();
        }
        List<HistoryRevision> out = new ArrayList<>();
        for (HistoryRevision r : g.revisions()) {
            if (HistoryRowText.matches(
                    r,
                    q,
                    reasonLabel(r.reason()),
                    HistoryRowText.dateTimeText(r.timestamp(), ZoneId.systemDefault(), locale()),
                    fullTime(r.timestamp()),
                    dayCaption(r.timestamp()))) {
                out.add(r);
            }
        }
        return out;
    }

    /** What the folder tree's first row on screen holds (a file group or a revision), or null when not known. */
    private @Nullable Object firstVisibleFolderRow() {
        if (folderTree.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            javafx.scene.control.IndexedCell<?> cell = flow.getFirstVisibleCell();
            TreeItem<Object> item = cell == null ? null : folderTree.getTreeItem(cell.getIndex());
            return item == null ? null : item.getValue();
        }
        return null;
    }

    /** Scrolls the folder tree so {@code item}'s row is on screen. */
    private void reveal(TreeItem<Object> item) {
        int row = folderTree.getRow(item);
        if (row >= 0) {
            folderTree.scrollTo(row);
        }
    }

    /**
     * The folder-tree row of {@code value}: the same revision ({@link HistoryRowText#sameRevision}), or the file
     * group of the same path; null when the tree does not list it.
     */
    private @Nullable TreeItem<Object> folderItemOf(Object value) {
        TreeItem<Object> root = folderTree.getRoot();
        if (root == null) {
            return null;
        }
        for (TreeItem<Object> file : root.getChildren()) {
            if (value instanceof FileGroup wanted
                    && file.getValue() instanceof FileGroup g
                    && g.path().equals(wanted.path())) {
                return file;
            }
            for (TreeItem<Object> row : file.getChildren()) {
                if (value instanceof HistoryRevision wanted
                        && row.getValue() instanceof HistoryRevision r
                        && HistoryRowText.sameRevision(r, wanted)) {
                    return row;
                }
            }
        }
        return null;
    }

    // --- Diff (right pane) ------------------------------------------------------------------------------

    /** The diff's header: when {@code rev} was recorded, and its label or reason. */
    private void describe(HistoryRevision rev) {
        String what = rev.label().isBlank() ? reasonLabel(rev.reason()) : rev.label();
        String header = tr("history.window.header", fullTime(rev.timestamp()), what);
        headerInfo.setText(header);
        headerTip.setText(header);
        headerInfo.setTooltip(headerTip);
    }

    /**
     * Loads {@code rev}'s snapshot and the current text of {@code path}, then diffs them. {@code path} is the
     * active file in the single-file view, and the revision's own file in the folder view (a deleted file
     * compares with nothing).
     */
    private void showRevision(HistoryRevision rev, @Nullable Path path) {
        if (support == null || path == null) {
            return;
        }
        shown = rev;
        shownPath = path;
        shownMissing = false;
        shownTooLarge = false;
        describe(rev);
        int request = ++gen;
        updateToolbar();
        DiffSupport diffs = support;
        diffs.currentTextLater(path, base -> {
            if (request == gen) {
                baseText = base;
                fetchShown(diffs, rev, request);
            }
        });
    }

    /** The second half of {@link #showRevision}: the revision's snapshot, once the current text is known. */
    private void fetchShown(DiffSupport diffs, HistoryRevision rev, int request) {
        diffs.fetchContent(rev, content -> {
            if (request != gen) {
                return;
            }
            if (content.isEmpty()) {
                pane = null;
                panePath = null;
                shownMissing = true;
                Label unavailable = new Label(tr("history.window.unavailable"));
                unavailable.getStyleClass().add("tool-window-placeholder");
                showPlaceholder(unavailable);
                updateToolbar();
                return;
            }
            snapshotText = content.orElseThrow();
            recompute();
        });
    }

    /** Re-diffs {@link #snapshotText} vs {@link #baseText} with the current toggle options (off-thread). */
    private void recompute() {
        DiffSupport diffs = support;
        Path path = shownPath; // set with shown
        if (diffs == null || shown == null || path == null || shownMissing) {
            return; // nothing shown: the texts below belong to no revision
        }
        int g = ++gen;
        boolean editable = !folderTree.isManaged(); // no per-hunk apply in a folder view
        DiffEngine.DiffOptions opts = new DiffEngine.DiffOptions(ignoreWs.isSelected(), wordLevel.isSelected());
        diffs.computeDiff(snapshotText, baseText, opts, model -> {
            if (g != gen) {
                return; // a newer selection/toggle superseded this result
            }
            if (model == null) {
                // No diff to draw — and the one on screen is some other revision's: take it down.
                pane = null;
                panePath = null;
                shownTooLarge = true;
                Label tooLarge = new Label(tr("history.window.tooLarge"));
                tooLarge.getStyleClass().add("tool-window-placeholder");
                showPlaceholder(tooLarge);
                updateToolbar();
                return;
            }
            shownTooLarge = false;
            updateToolbar();
            Settings s = diffs.settings();
            String fileName =
                    path.getFileName() == null ? "" : path.getFileName().toString();
            if (pane == null || !path.equals(panePath)) {
                pane = new DiffViewerPane(
                        fileName,
                        tr("history.window.beforeHeader"),
                        tr("history.window.currentHeader"),
                        fileName,
                        fileName,
                        snapshotText,
                        baseText,
                        model,
                        s.getFontFamily(),
                        s.getFontSize(),
                        s.isShowLineNumbers(),
                        path.toString());
                panePath = path;
                pane.setOptionsControlsVisible(false);
                if (editable) {
                    installHunkApply(pane, path, diffs);
                }
                rightPane.setCenter(pane.node());
            } else {
                pane.updateContent(snapshotText, baseText, model);
            }
        });
    }

    /**
     * Per-hunk "apply change" chevrons on the current-file (right) side — IntelliJ-style selective restore.
     * Each apply writes the whole-file result through the undoable buffer, then we re-diff so the remaining
     * changes (and chevrons) update. The whole-file result is "the file as this pane shows it, plus that
     * hunk", so it is applied only while the file still equals the pane's own current-side text: anything
     * typed since the revision was selected is kept, and the refused apply re-baselines and re-diffs instead.
     */
    private void installHunkApply(DiffViewerPane built, Path t, DiffSupport diffs) {
        built.setEditableAsync(
                DiffViewerPane.EditableSide.RIGHT,
                (newText, done) -> diffs.applyToLocalIfUnchanged(t, built.editableBaselineText(), newText, applied -> {
                    done.accept(applied);
                    reDiffAfterEdit();
                }),
                () -> {
                    diffs.undoLocal(t);
                    reDiffAfterEdit();
                },
                () -> diffs.saveLocal(t));
    }

    /** After a per-hunk apply/undo changed the current file, re-baseline + re-diff (the editable side
     *  persists, so the remaining chevrons update). */
    private void reDiffAfterEdit() {
        Platform.runLater(this::rebaseline);
    }

    /**
     * The file's text was edited in the editor (typing, undo, a restore). The diff's "current" side and the
     * "Current" row are both statements about that text, so they follow it — once the typing pauses.
     */
    public void editorTextChanged() {
        if (target == null || (shown == null && allRevisions.isEmpty())) {
            return;
        }
        editorSettled.playFromStart();
    }

    /** Re-reads the compared file: moves the "Current" tag and re-diffs when the text is not what was diffed. */
    private void rebaseline() {
        if (support == null) {
            return;
        }
        if (target != null) {
            rehashEditorText();
        }
        if (shown != null && shownPath != null && !shownMissing) {
            int asked = gen;
            support.currentTextLater(shownPath, now -> {
                if (asked == gen && (!now.equals(baseText) || pane == null)) {
                    baseText = now;
                    recompute();
                }
            });
        }
    }

    /**
     * Hashes the editor's text off the FX thread (PERF-01, Pauta 6.2.0) and, when it lands and is still the latest,
     * moves the "Current" tag to the row it equals.
     */
    private void rehashEditorText() {
        long mine = ++hashGen;
        if (support == null || target == null || allRevisions.isEmpty()) {
            editorSha = "";
            return;
        }
        support.currentTextLater(target, text -> {
            if (mine == hashGen) {
                hashLater(text, mine);
            }
        });
    }

    private void hashLater(String text, long mine) {
        if (text.length() > MAX_HASHED_CHARS) {
            editorSha = "";
            return;
        }
        com.editora.perf.OffFx.then("history-hash", () -> HistoryBlobStore.sha256(text), hashed -> {
            String sha = hashed.or("");
            if (mine == hashGen && !sha.equals(editorSha)) {
                editorSha = sha;
                revisions.refresh();
            }
        });
    }

    /** Which {@link #rehashEditorText} is the latest: an older hash that lands later is dropped. */
    private long hashGen;

    /** The Restore button: the shown revision, into the file (single-file) or back to its own path (folder). */
    private void restoreShown() {
        if (shown != null) {
            restoreRevision(shown);
        }
    }

    /** The selected revision of the view that shows (the folder's tree, or the file's list); null when none is. */
    private @Nullable HistoryRevision selectedRevision() {
        if (folderTree.isManaged()) {
            TreeItem<Object> sel = folderTree.getSelectionModel().getSelectedItem();
            return sel != null && sel.getValue() instanceof HistoryRevision r ? r : null;
        }
        return revisions.getSelectionModel().getSelectedItem();
    }

    /** Restore This Version on the selected revision ({@code history.restoreSelected}); false when none is. */
    boolean restoreSelected() {
        HistoryRevision r = selectedRevision();
        if (r == null) {
            return false;
        }
        restoreRevision(r);
        return true;
    }

    /** Set / Edit Label… on the selected revision, in a file's or a folder's history ({@code history.labelSelected}). */
    boolean labelSelected() {
        HistoryRevision r = selectedRevision();
        if (r == null) {
            return false;
        }
        actions.editLabel(r);
        return true;
    }

    /** Delete History… on the file selected in a folder's history ({@code localHistory.purgeSelectedFile}). */
    boolean purgeSelectedFile() {
        TreeItem<Object> sel =
                folderTree.isManaged() ? folderTree.getSelectionModel().getSelectedItem() : null;
        if (sel == null || !(sel.getValue() instanceof FileGroup g)) {
            return false;
        }
        actions.purgeFile(g.path());
        return true;
    }

    /** Restore from the button, a row's menu or Enter — one path, so they ask and behave alike. */
    private void restoreRevision(HistoryRevision revision) {
        if (revision == null) {
            return;
        }
        if (folderShown != null || target == null) {
            actions.restoreToDisk(revision);
            return;
        }
        if (support != null && support.hasUnsavedEdits(target) && !actions.confirmRestoreOverUnsavedEdits(target)) {
            return;
        }
        if (!HistoryRowText.sameRevision(revision, shown)) {
            selectRevision(revision); // the diff then says what was restored
        }
        if (HistoryRowText.sameRevision(revision, shown)) {
            revertSelected();
        } else {
            actions.restore(revision); // no diff support: nothing to re-baseline
        }
    }

    private void revertSelected() {
        HistoryRevision sel = shown;
        if (sel == null || support == null) {
            return;
        }
        // The restore reads the revision off-thread and applies it later, so re-baseline when it reports
        // back: a runLater queued here ran first and left the panel diffing the pre-revert text. It reports
        // back whether or not it restored, so a refused restore leaves the diff as it was.
        support.revert(sel, this::rebaseline);
    }

    // --- Keys -------------------------------------------------------------------------------------------

    private void onListKey(KeyEvent e) {
        HistoryRevision sel = revisions.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        if (e.getCode() == KeyCode.ENTER) {
            focusDiff();
            e.consume();
        } else if (e.getCode() == KeyCode.F2) {
            actions.editLabel(sel);
            e.consume();
        }
    }

    private void onFolderKey(KeyEvent e) {
        TreeItem<Object> sel = folderTree.getSelectionModel().getSelectedItem();
        Object value = sel == null ? null : sel.getValue();
        if (e.getCode() == KeyCode.ENTER && value instanceof HistoryRevision r) {
            restoreRevision(r); // the folder view exists to bring a file back
            e.consume();
        } else if (e.getCode() == KeyCode.F2 && value instanceof HistoryRevision r) {
            actions.editLabel(r);
            e.consume();
        } else if (e.getCode() == KeyCode.DELETE && !e.isShortcutDown() && value instanceof FileGroup) {
            purgeSelectedFile(); // asks first, as the row's Delete History… does
            e.consume();
        }
    }

    /** Panel-wide keys: Escape steps back (first a typed filter, then to the editor), F5 reloads. */
    private void onPanelKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ESCAPE) {
            if (filter.isFocused() && !filter.getText().isEmpty()) {
                filter.clear();
            } else {
                actions.focusEditor();
            }
            e.consume();
        } else if (e.getCode() == KeyCode.F5) {
            actions.refresh();
            e.consume();
        }
    }

    /**
     * Tab in a read-only diff area moves on: forward to the top of the panel (the filter, then the list),
     * backward to Restore. An editable area keeps its Tab.
     */
    private void leaveDiffOnTab(KeyEvent e) {
        if (e.getCode() != KeyCode.TAB || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return;
        }
        if (!(e.getTarget() instanceof javafx.scene.control.TextInputControl
                        || e.getTarget() instanceof org.fxmisc.richtext.GenericStyledArea<?, ?, ?>)
                || isEditable(e.getTarget())) {
            return;
        }
        e.consume();
        if (e.isShiftDown()) {
            restore.requestFocus();
        } else {
            filter.requestFocus(); // the filter heads the file's list and the folder's tree alike
        }
    }

    private static boolean isEditable(Object area) {
        return area instanceof org.fxmisc.richtext.GenericStyledArea<?, ?, ?> a && a.isEditable();
    }

    /** Enter on a revision: into its diff, on the current-file side. */
    private void focusDiff() {
        if (pane == null) {
            return;
        }
        javafx.scene.Node area = pane.node().lookup(".diff-right");
        if (area == null) {
            area = pane.node().lookup(".diff-area");
        }
        if (area != null) {
            area.requestFocus();
        }
    }

    // --- Filtering + focus ------------------------------------------------------------------------------

    /** Re-applies the filter field; a filter that hides the shown revision takes its diff down with it. */
    private void applyFilter() {
        HistoryRevision keep = HistoryRowText.find(allRevisions, shown);
        keepingSelection = true;
        try {
            fillList();
            if (keep != null && revisions.getItems().contains(keep)) {
                revisions.getSelectionModel().select(keep);
            } else {
                revisions.getSelectionModel().clearSelection();
            }
        } finally {
            keepingSelection = false;
        }
        if (keep != null && revisions.getSelectionModel().getSelectedItem() == null) {
            resetDiff();
        }
    }

    /** Fills the list from {@link #allRevisions} through the filter, and words the empty list to match. */
    private void fillList() {
        String q = filter.getText();
        boolean filtering = q != null && !q.isBlank();
        if (!filtering) {
            revisions.getItems().setAll(allRevisions);
        } else {
            List<HistoryRevision> matching = new ArrayList<>();
            for (HistoryRevision r : allRevisions) {
                if (HistoryRowText.matches(
                        r,
                        q,
                        reasonLabel(r.reason()),
                        rowTime(r.timestamp()),
                        fullTime(r.timestamp()),
                        dayCaption(r.timestamp()))) {
                    matching.add(r);
                }
            }
            revisions.getItems().setAll(matching);
        }
        placeholder.setText(tr(filtering && !allRevisions.isEmpty() ? "history.noMatch" : "history.noRevisions"));
    }

    @Override
    public void focusFirstItem() {
        if (folderTree.isManaged()) {
            folderTree.requestFocus();
            return;
        }
        if (!revisions.getItems().isEmpty() && revisions.getSelectionModel().isEmpty()) {
            revisions.getSelectionModel().select(0);
            revisions.scrollTo(0);
        }
        revisions.requestFocus();
    }

    // --- Cells ------------------------------------------------------------------------------------------

    /** Tree cell for folder mode: a file row (deleted = struck through + badge) or a revision row. */
    private final class FolderCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            setText(null);
            setTooltip(null);
            setAccessibleText(null);
            if (empty || value == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (value instanceof FileGroup g) {
                Label name = new Label(g.display());
                HBox box = UiZoom.spacing(
                        new HBox(
                                FileIcons.forFileName(
                                        Path.of(g.path()).getFileName().toString()),
                                name),
                        6);
                box.setAlignment(Pos.CENTER_LEFT);
                if (g.deleted()) {
                    // The strike-through goes on the name's own text: set on the cell it never reached it.
                    name.getStyleClass().add("history-deleted-file");
                    Label badge = new Label(tr("history.deleted"));
                    badge.getStyleClass().add("history-row-tag");
                    box.getChildren().add(badge);
                    setAccessibleText(g.display() + ", " + tr("history.deleted"));
                } else {
                    setAccessibleText(g.display());
                }
                setGraphic(box);
                setTooltip(ControlNames.tooltip(g.path()));
                MenuItem purge = MenuChords.tag(
                        item(tr("history.menu.purge"), Icons.trash(), () -> actions.purgeFile(g.path())),
                        "localHistory.purgeSelectedFile");
                purge.getStyleClass().add("danger"); // a destructive row reads red
                setContextMenu(new ContextMenu(purge));
            } else if (value instanceof HistoryRevision r) {
                String what = r.label().isBlank() ? reasonLabel(r.reason()) : r.label();
                Label when = new Label(HistoryRowText.dateTimeText(r.timestamp(), ZoneId.systemDefault(), locale()));
                Label tag = new Label(what);
                tag.getStyleClass().add(r.label().isBlank() ? "history-row-reason" : "history-label");
                HBox box = UiZoom.spacing(new HBox(Icons.history(), when, tag), 8);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setTooltip(ControlNames.tooltip(tooltipText(r)));
                setAccessibleText(fullTime(r.timestamp()) + ", " + what);
                MenuItem restoreItem = MenuChords.tag(
                        item(tr("history.menu.restore"), Icons.undo(), () -> restoreRevision(r)),
                        "history.restoreSelected");
                String labelText = r.label().isBlank() ? tr("history.menu.setLabel") : tr("history.menu.editLabel");
                MenuItem editLabel = MenuChords.tag(
                        item(labelText, Icons.edit(), () -> actions.editLabel(r)), "history.labelSelected");
                setContextMenu(new ContextMenu(restoreItem, editLabel));
            }
        }
    }

    /**
     * A revision row: label (if any) first, then the time of day and the reason, with a tag on the row the
     * editor's text equals. The first row of each day carries the day as a caption above it — part of the
     * row, so the arrow keys never land on a caption. The size and the full date are in the tooltip.
     */
    private final class RevisionCell extends ListCell<HistoryRevision> {
        RevisionCell() {
            setPrefWidth(0); // as wide as the list, never wider: the label is cut short, not scrolled sideways
            UiZoom.follow(this, this::requestLayout);
        }

        /** The row is as high as what it holds — a day caption makes it taller — not the theme's fixed 3em. */
        @Override
        protected double computePrefHeight(double width) {
            javafx.scene.Node content = getGraphic();
            double inner = content == null ? 0 : content.prefHeight(-1);
            return Math.max(UiZoom.px(MIN_ROW_HEIGHT), snappedTopInset() + inner + snappedBottomInset());
        }

        @Override
        protected void updateItem(HistoryRevision r, boolean empty) {
            super.updateItem(r, empty);
            setText(null);
            if (empty || r == null) {
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                setAccessibleText(null);
                return;
            }
            boolean labelled = !r.label().isBlank();
            String tag = tagFor(r);
            HBox line = UiZoom.spacing(new HBox(), 6);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getChildren().add(Icons.history());
            if (labelled) {
                Label lbl = new Label(r.label());
                lbl.getStyleClass().add("history-label");
                UiZoom.minWidth(lbl, 40);
                line.getChildren().add(lbl);
            }
            Label when = new Label(rowTime(r.timestamp()));
            when.setMinWidth(Region.USE_PREF_SIZE);
            line.getChildren().add(when);
            if (!labelled) { // a labelled row's reason is "Label", which the label already says
                Label reason = new Label(reasonLabel(r.reason()));
                reason.getStyleClass().add("history-row-reason");
                reason.setMinWidth(0);
                line.getChildren().add(reason);
            }
            if (tag != null) {
                Label t = new Label(tag);
                t.getStyleClass().add("history-row-tag");
                t.setMinWidth(Region.USE_PREF_SIZE);
                line.getChildren().add(t);
            }
            if (HistoryRowText.startsDay(revisions.getItems(), getIndex(), ZoneId.systemDefault())) {
                Label day = new Label(dayCaption(r.timestamp()));
                day.getStyleClass().add("history-day");
                setGraphic(UiZoom.spacing(new VBox(day, line), 2));
            } else {
                setGraphic(line);
            }
            setTooltip(ControlNames.tooltip(tooltipText(r)));
            setAccessibleText(accessibleText(r, tag));
            MenuItem restoreItem = MenuChords.tag(
                    item(tr("history.menu.restore"), Icons.undo(), () -> restoreRevision(r)),
                    "history.restoreSelected");
            String labelText = labelled ? tr("history.menu.editLabel") : tr("history.menu.setLabel");
            MenuItem editLabel =
                    MenuChords.tag(item(labelText, Icons.edit(), () -> actions.editLabel(r)), "history.labelSelected");
            setContextMenu(new ContextMenu(restoreItem, editLabel));
        }
    }

    /**
     * "Current" on the newest revision the editor's text equals; "Latest" on the newest row when that is not
     * it (unsaved edits, a restore of an older row, an undo). Null for an untagged row.
     */
    private @Nullable String tagFor(HistoryRevision r) {
        if (allRevisions.isEmpty()) {
            return null;
        }
        int current = HistoryRowText.currentIndex(allRevisions, editorSha);
        if (current >= 0 && allRevisions.get(current) == r) {
            return tr("history.current");
        }
        return allRevisions.get(0) == r ? tr("history.latest") : null;
    }

    private String tooltipText(HistoryRevision r) {
        StringBuilder sb = new StringBuilder();
        if (!r.label().isBlank()) {
            sb.append(r.label()).append('\n');
        }
        sb.append(fullTime(r.timestamp()))
                .append('\n')
                .append(timeLabel(r.timestamp()))
                .append('\n')
                .append(reasonLabel(r.reason()))
                .append('\n')
                .append(HistoryRowText.sizeText(r.sizeBytes(), locale()));
        return sb.toString();
    }

    private String accessibleText(HistoryRevision r, @Nullable String tag) {
        StringBuilder sb = new StringBuilder();
        if (tag != null) {
            sb.append(tag).append(", ");
        }
        if (!r.label().isBlank()) {
            sb.append(r.label()).append(", ");
        }
        return sb.append(fullTime(r.timestamp()))
                .append(", ")
                .append(reasonLabel(r.reason()))
                .toString();
    }

    private static MenuItem item(String label, javafx.scene.Node icon, Runnable run) {
        MenuItem m = new MenuItem(label);
        if (icon != null) {
            m.setGraphic(icon);
        }
        m.setOnAction(e -> run.run());
        return m;
    }

    // --- Text -------------------------------------------------------------------------------------------

    /**
     * The locale dates and numbers are written in: the JVM's default for formats, which start-up sets once
     * ({@code Messages.locale}) and which keeps the system's region, so a British machine keeps British dates.
     */
    static Locale locale() {
        return Locale.getDefault(Locale.Category.FORMAT);
    }

    private static String rowTime(long epochMillis) {
        return HistoryRowText.timeText(epochMillis, ZoneId.systemDefault(), locale());
    }

    private static String fullTime(long epochMillis) {
        return HistoryRowText.fullText(epochMillis, ZoneId.systemDefault(), locale());
    }

    private static String dayCaption(long epochMillis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = HistoryRowText.dayOf(epochMillis, zone);
        return switch (HistoryRowText.dayKind(day, LocalDate.now(zone))) {
            case TODAY -> tr("history.day.today");
            case YESTERDAY -> tr("history.day.yesterday");
            case DATED -> HistoryRowText.dayText(day, locale());
        };
    }

    /** Localized "N minutes ago"-style label (reuses the blame relative-time bucketing + i18n keys). */
    private static String timeLabel(long epochMillis) {
        RelativeTime.Span span = RelativeTime.of(epochMillis / 1000, System.currentTimeMillis() / 1000);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("blame.now");
            case MINUTES -> tr("blame.minutesAgo", v);
            case HOURS -> tr("blame.hoursAgo", v);
            case DAYS -> tr("blame.daysAgo", v);
            case WEEKS -> tr("blame.weeksAgo", v);
            case MONTHS -> tr("blame.monthsAgo", v);
            case YEARS -> tr("blame.yearsAgo", v);
        };
    }

    static String reasonLabel(String reason) {
        return switch (reason == null ? "" : reason) {
            case HistoryRevision.REASON_AUTOSAVE -> tr("history.reason.autosave");
            case HistoryRevision.REASON_EXTERNAL -> tr("history.reason.external");
            case HistoryRevision.REASON_LABEL -> tr("history.reason.label");
            case HistoryRevision.REASON_DELETE -> tr("history.reason.delete");
            case HistoryRevision.REASON_AGENT -> tr("history.reason.agent");
            case HistoryRevision.REASON_HTTP -> tr("history.reason.http");
            case HistoryRevision.REASON_RESTORE -> tr("history.reason.restore");
            case HistoryRevision.REASON_REVERT -> tr("history.reason.revert");
            case HistoryRevision.REASON_LINK_UPDATE -> tr("history.reason.linkUpdate");
            case HistoryCoordinator.REASON_BASELINE -> tr("history.reason.baseline");
            // "replace-in-files" is what the same capture was stored as before it had a reason of its own
            case HistoryCoordinator.REASON_BEFORE_REPLACE, "replace-in-files" -> tr("history.reason.beforeReplace");
            default -> tr("history.reason.save");
        };
    }
}
