package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
import com.editora.diff.DiffText;
import com.editora.diff.PatchWriter;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.TabContent;
import com.editora.editor.TextMateHighlighter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * A diff viewer tab ({@link TabContent}) comparing two texts. The default is **side-by-side** — two
 * read-only {@link CodeArea}s with synchronized scrolling, original-line-number gutters, syntax
 * highlighting, per-line diff backgrounds (added / removed / changed / filler) and intra-line word
 * emphasis on changed lines — with a toolbar toggle to a single-pane **unified** view. Prev/next-change
 * navigation and "export patch" round it out. Purely a view built from a precomputed
 * {@link DiffModel} (see {@code com.editora.diff}); it never diffs or shells out itself.
 */
public final class DiffViewerPane implements TabContent {

    private static final Collection<String> NONE = Collections.emptyList();
    private static final List<String> WORD = List.of("diff-word");
    private static final int CONTEXT_LINES = 3;
    private static volatile boolean rememberedCollapseContext = true;
    private static volatile boolean rememberedWrapLines;
    private static final ExecutorService HIGHLIGHT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diff-highlight");
        t.setDaemon(true);
        return t;
    });

    private final String title;
    private String leftName;
    private String rightName;
    private String headerLeft;
    private String headerRight;
    private String leftText;
    private String rightText;
    private DiffModel model;
    private final IGrammar grammar;
    private String fontStyle; // mutable so text zoom can resize the diff areas live (#533)
    private final boolean showLineNumbers;
    private java.util.function.Consumer<String> onExportPatch = p -> {};
    private java.util.function.Consumer<DiffEngine.DiffOptions> onOptionsChanged = o -> {};
    /** Re-fetches both sides + re-renders if changed (set by the controller); the file-on-disk refresh. */
    private Runnable refresher = () -> {};

    /** Which side is the editable/local file that "apply change" writes into (NONE = read-only diff). */
    public enum EditableSide {
        NONE,
        LEFT,
        RIGHT
    }

    private EditableSide editableSide = EditableSide.NONE;
    /** Receives the editable side's full new text after a hunk is applied (controller writes it back). */
    private java.util.function.Predicate<String> onApply = t -> false;

    private final BorderPane root = new BorderPane();
    private final Label summary = new Label();
    private final Label changeNav = new Label();
    private final Button toggleButton = new Button();
    private final Button swapButton = new Button();
    private Button exportButton;
    private final Button applyAllButton = new Button();
    private final Button undoButton = new Button();
    private final Button saveButton = new Button();
    private final Button whitespaceButton = new Button();
    private final ToggleButton wordButton = new ToggleButton();
    private final MenuButton rulesButton = new MenuButton();
    private final CheckMenuItem ignoreCaseItem = new CheckMenuItem();
    private final CheckMenuItem smartAlignmentItem = new CheckMenuItem();
    private final ToggleButton contextButton = new ToggleButton();
    private final ToggleButton wrapButton = new ToggleButton();
    private final Button stageHunkButton = new Button();
    private final Button unstageHunkButton = new Button();
    private final Button revertHunkButton = new Button();
    private final Button applyEofButton = new Button();
    private final ToggleButton editResultButton = new ToggleButton();
    private final Button applyResultButton = new Button();
    private final Button resetResultButton = new Button();
    private final Button exitDiffUiButton = new Button();
    private Runnable onExitDiffUi = () -> {};
    private Runnable onUndo = () -> {};
    private Runnable onSave = () -> {};
    private java.util.function.BiConsumer<String, String> onSwapRequested = (l, r) -> {};
    private boolean swapEnabled;
    private boolean swapPending;
    private boolean sidesSwapped;
    private java.util.function.Consumer<String> onResultEdited = t -> {};
    private boolean resultEditingEnabled;
    private int unappliedUndoDepth;

    private boolean unified; // false = side-by-side (default)
    private DiffEngine.DiffOptions options = DiffEngine.DiffOptions.DEFAULT;
    private boolean collapseContext = rememberedCollapseContext;
    private boolean wrapLines = rememberedWrapLines;
    private final AtomicLong highlightGeneration = new AtomicLong();
    private final AtomicLong resultHighlightGeneration = new AtomicLong();
    private EnumSet<GitHunkAction> gitHunkActions = EnumSet.noneOf(GitHunkAction.class);
    private java.util.function.Consumer<GitHunkRequest> onGitHunkAction = r -> {};

    public enum GitHunkAction {
        STAGE,
        UNSTAGE,
        REVERT,
        OPEN
    }

    public record GitHunkRequest(
            GitHunkAction action, int startRow, int endRow, String beforeText, String afterText, int targetLine) {}

    /**
     * Width of the side-by-side view's left pane, or 0 in unified view. The toolbar's right-hand cluster
     * (change count + navigation + actions) is capped by this so it begins where the second file begins
     * rather than at the far edge of the window — on a wide window those controls were a screen away from
     * the diff they act on.
     */
    private final javafx.beans.property.DoubleProperty leftPaneWidth =
            new javafx.beans.property.SimpleDoubleProperty(0);

    /** The side-by-side left pane, kept so {@link #showSideBySide()} can re-bind {@link #leftPaneWidth}
     *  after a round trip through the unified view (which unbinds it). */
    private javafx.scene.layout.Region leftPaneBox;

    private int changeCursor = -1; // index into model.changeBlockStarts for prev/next nav

    // Side-by-side areas (built once, lazily).
    private CodeArea leftArea;
    private CodeArea rightArea;
    private Node sideBySideNode;
    private javafx.scene.control.SplitPane sideSplit;
    private DiffConnectorCanvas connectorCanvas;
    private CodeArea unifiedArea;
    private Node unifiedNode;
    private CodeArea resultArea;
    private Node resultNode;
    private SplitPane resultSplit;
    private final PauseTransition resultDiffDelay = new PauseTransition(Duration.millis(250));
    private boolean resultEditing;
    private boolean resultDirty;
    private boolean updatingResult;
    private String resultBaselineText;
    private boolean syncing; // re-entrancy guard for scroll sync
    private int[] sideSourceRows = new int[0];
    private int[] unifiedSourceRows = new int[0];

    private record ViewState(
            double leftScroll,
            double rightScroll,
            double unifiedScroll,
            boolean leftFocused,
            boolean rightFocused,
            int selectedLeftLine,
            int selectedRightLine,
            double divider) {}

    private record DisplayRow(Row row, int sourceStart, int sourceEnd, boolean collapsed) {}

    private record DisplayUnified(UnifiedRow row, int sourceStart, int sourceEnd, boolean collapsed) {}

    public DiffViewerPane(
            String title,
            String headerLeft,
            String headerRight,
            String leftName,
            String rightName,
            String leftText,
            String rightText,
            DiffModel model,
            String fontFamily,
            int fontSize,
            boolean showLineNumbers,
            String grammarPath) {
        this.title = title;
        this.leftName = leftName;
        this.rightName = rightName;
        this.headerLeft = headerLeft == null ? tr("diff.leftHeader") : headerLeft;
        this.headerRight = headerRight == null ? tr("diff.rightHeader") : headerRight;
        this.leftText = leftText;
        this.rightText = rightText;
        this.model = model;
        this.showLineNumbers = showLineNumbers;
        this.fontStyle = "-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;";
        // Syntax grammar: prefer the local file's full path (so location-based types like ~/.ssh/config
        // resolve), else the "new" file's name, falling back to the old name's extension.
        IGrammar g = grammarFor(grammarPath != null && !grammarPath.isBlank() ? grammarPath : rightName);
        this.grammar = g != null ? g : grammarFor(leftName);

        root.getStyleClass().add("diff-viewer");
        root.setAccessibleRole(AccessibleRole.PARENT);
        root.setAccessibleText(tr("diff.accessibleViewer", this.headerLeft, this.headerRight));
        root.setTop(buildToolbar());
        resultDiffDelay.setOnFinished(e -> {
            if (resultEditing && resultArea != null) {
                String text = resultArea.getText();
                onResultEdited.accept(text);
                highlightResult(text);
            }
        });
        showSideBySide();
        installChangeNavKeys();
    }

    /**
     * Single-key change navigation for a <em>read-only</em> diff (e.g. a PR review diff): {@code n} = next
     * change, {@code p} = previous. Gated on {@link EditableSide#NONE} so it never swallows typing in an
     * editable "apply change" diff (where the RIGHT/LEFT side is a live editor). The filter is on {@code root},
     * so it runs before the focused area's own key handling; a modifier held (so {@code C-n}/… still reach the
     * area) is ignored.
     */
    private void installChangeNavKeys() {
        root.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (editableSide != EditableSide.NONE
                    || e.isShortcutDown()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || e.isShiftDown()) {
                return;
            }
            if (e.getCode() == javafx.scene.input.KeyCode.N) {
                nextChange();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.P) {
                prevChange();
                e.consume();
            }
        });
    }

    public void setOnExportPatch(java.util.function.Consumer<String> onExportPatch) {
        this.onExportPatch = onExportPatch == null ? p -> {} : onExportPatch;
    }

    public void setOnOptionsChanged(java.util.function.Consumer<DiffEngine.DiffOptions> listener) {
        this.onOptionsChanged = listener == null ? o -> {} : listener;
    }

    /** Installs asynchronous side swapping. The callback receives the desired new left/right texts and
     *  must finish with {@link #swapSides(DiffModel)} or {@link #cancelSwap()}. */
    public void setOnSwapRequested(java.util.function.BiConsumer<String, String> listener) {
        swapEnabled = listener != null;
        onSwapRequested = listener == null ? (l, r) -> {} : listener;
        swapButton.setVisible(swapEnabled);
        swapButton.setManaged(swapEnabled);
        updateResultControls();
    }

    /** Recomputes the comparison after a pause in the editable Result draft. */
    public void setOnResultEdited(java.util.function.Consumer<String> listener) {
        resultEditingEnabled = listener != null;
        onResultEdited = listener == null ? t -> {} : listener;
        boolean visible = resultEditingEnabled && editableSide != EditableSide.NONE;
        editResultButton.setVisible(visible);
        editResultButton.setManaged(visible);
        if (!visible && resultEditing) {
            closeResultEditor();
        }
    }

    /** Shows a full-UI icon for standalone {@code --diff-ui}; passing {@code null} hides it again. */
    public void setExitDiffUiAction(Runnable action) {
        onExitDiffUi = action == null ? () -> {} : action;
        boolean visible = action != null;
        exitDiffUiButton.setVisible(visible);
        exitDiffUiButton.setManaged(visible);
    }

    public void setOptions(DiffEngine.DiffOptions options) {
        this.options = options == null ? DiffEngine.DiffOptions.DEFAULT : options;
        wordButton.setSelected(this.options.wordLevel());
        ignoreCaseItem.setSelected(this.options.ignoreCase());
        smartAlignmentItem.setSelected(this.options.smartAlignment());
        updateWhitespaceButton();
    }

    public void setGitHunkActions(Set<GitHunkAction> actions, java.util.function.Consumer<GitHunkRequest> handler) {
        gitHunkActions =
                actions == null || actions.isEmpty() ? EnumSet.noneOf(GitHunkAction.class) : EnumSet.copyOf(actions);
        onGitHunkAction = handler == null ? r -> {} : handler;
        stageHunkButton.setVisible(gitHunkActions.contains(GitHunkAction.STAGE));
        stageHunkButton.setManaged(stageHunkButton.isVisible());
        unstageHunkButton.setVisible(gitHunkActions.contains(GitHunkAction.UNSTAGE));
        unstageHunkButton.setManaged(unstageHunkButton.isVisible());
        revertHunkButton.setVisible(gitHunkActions.contains(GitHunkAction.REVERT));
        revertHunkButton.setManaged(revertHunkButton.isVisible());
        rebuildCurrentView();
    }

    /** Hides the general viewer options when an embedding surface supplies its own controls. */
    public void setOptionsControlsVisible(boolean visible) {
        for (Node n : List.of(whitespaceButton, wordButton, rulesButton, contextButton, wrapButton)) {
            n.setVisible(visible);
            n.setManaged(visible);
        }
    }

    /** Resizes the diff areas' font (text zoom): rebuilds the inline font style and re-applies it to whichever
     *  areas are currently built (side-by-side or unified). Newly built areas pick up the latest style (#533). */
    public void setFont(String family, int size) {
        this.fontStyle = "-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;";
        if (leftArea != null) {
            leftArea.setStyle(fontStyle);
        }
        if (rightArea != null) {
            rightArea.setStyle(fontStyle);
        }
        if (unifiedArea != null) {
            unifiedArea.setStyle(fontStyle);
        }
        if (resultArea != null) {
            resultArea.setStyle(fontStyle);
        }
    }

    /** Installs the controller's re-fetch-and-rerender hook (run on focus-regain / after git mutation). */
    public void setRefresher(Runnable refresher) {
        this.refresher = refresher == null ? () -> {} : refresher;
    }

    /**
     * Marks which side is the editable/local file and the callback that writes the applied text back.
     * When set (not {@link EditableSide#NONE}), each change block shows an "apply change" chevron at
     * the center seam (side-by-side view) that replaces the hunk with the other side's content.
     */
    public void setEditable(
            EditableSide side, java.util.function.Consumer<String> onApply, Runnable onUndo, Runnable onSave) {
        setEditable(
                side,
                text -> {
                    if (onApply != null) {
                        onApply.accept(text);
                        return true;
                    }
                    return false;
                },
                onUndo,
                onSave);
    }

    public void setEditable(
            EditableSide side, java.util.function.Predicate<String> onApply, Runnable onUndo, Runnable onSave) {
        this.editableSide = side == null ? EditableSide.NONE : side;
        this.onApply = onApply == null ? t -> false : onApply;
        this.onUndo = onUndo == null ? () -> {} : onUndo;
        this.onSave = onSave == null ? () -> {} : onSave;
        boolean editable = this.editableSide != EditableSide.NONE;
        for (Button b : new Button[] {applyAllButton, undoButton, saveButton}) {
            b.setVisible(editable);
            b.setManaged(editable);
        }
        editResultButton.setVisible(editable && resultEditingEnabled);
        editResultButton.setManaged(editable && resultEditingEnabled);
        if (!editable) {
            closeResultEditor();
        }
        updateEofButton();
        // Called after construction (the view is already built without arrows) — rebuild so the
        // per-line "apply" chevrons appear beside the center seam.
        sideBySideNode = null;
        leftPaneBox = null;
        unifiedNode = null;
        leftArea = null;
        rightArea = null;
        unifiedArea = null;
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
    }

    /** Re-fetches both sides and re-renders if they changed (no-op when content is identical). */
    public void refresh() {
        refresher.run();
    }

    /** Whether the displayed content already equals {@code l}/{@code r} (so a refresh can skip a rebuild,
     *  keeping the current view + scroll position). */
    public boolean matches(String l, String r) {
        return java.util.Objects.equals(leftText, l) && java.util.Objects.equals(rightText, r);
    }

    public boolean matchesEditableText(String text) {
        String baseline = resultEditing && resultBaselineText != null
                ? resultBaselineText
                : editableSide == EditableSide.RIGHT ? rightText : leftText;
        return java.util.Objects.equals(baseline, text);
    }

    public boolean hasDirtyResult() {
        return resultEditing && resultDirty;
    }

    public EditableSide editableSide() {
        return editableSide;
    }

    public boolean hasResultEditor() {
        return resultEditing && resultArea != null;
    }

    public String resultText() {
        return resultArea == null ? null : resultArea.getText();
    }

    /** Replaces the compared content + diff model and re-renders the current view (rebuilds both the
     *  side-by-side and unified nodes lazily). Keeps the toolbar, headers, grammar, and view mode. */
    public void updateContent(String newLeft, String newRight, DiffModel newModel) {
        replaceContent(newLeft, newRight, newModel, true);
    }

    /** Updates only the rendered comparison for a Result draft, without rebasing or replacing the draft. */
    public void updateDraftContent(String newLeft, String newRight, DiffModel newModel) {
        replaceContent(newLeft, newRight, newModel, false);
    }

    /** Completes a requested side swap with its precomputed model. Labels, scroll state, patch names, and
     *  the editable-side marker all follow the content; the local target itself remains unchanged. */
    public void swapSides(DiffModel swappedModel) {
        if (swappedModel == null) {
            cancelSwap();
            return;
        }
        ViewState state = captureViewState();
        ViewState swappedState = new ViewState(
                state.rightScroll(),
                state.leftScroll(),
                state.unifiedScroll(),
                state.rightFocused(),
                state.leftFocused(),
                state.selectedRightLine(),
                state.selectedLeftLine(),
                1.0 - state.divider());
        String oldLeftText = leftText;
        leftText = rightText;
        rightText = oldLeftText;
        String oldLeftName = leftName;
        leftName = rightName;
        rightName = oldLeftName;
        String oldLeftHeader = headerLeft;
        headerLeft = headerRight;
        headerRight = oldLeftHeader;
        editableSide = switch (editableSide) {
            case LEFT -> EditableSide.RIGHT;
            case RIGHT -> EditableSide.LEFT;
            case NONE -> EditableSide.NONE;
        };
        sidesSwapped = !sidesSwapped;
        model = swappedModel;
        root.setAccessibleText(tr("diff.accessibleViewer", headerLeft, headerRight));
        if (resultEditing && !resultDirty) {
            setResultText(editableSide == EditableSide.RIGHT ? rightText : leftText, true);
        }
        highlightGeneration.incrementAndGet();
        sideBySideNode = null;
        leftPaneBox = null;
        unifiedNode = null;
        leftArea = null;
        rightArea = null;
        unifiedArea = null;
        swapPending = false;
        restoreChangeCursor(swappedState);
        updateSummary();
        updateEofButton();
        updateResultControls();
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
        restoreViewState(swappedState);
    }

    /** Re-enables swapping when an asynchronous recomputation cannot produce a model. */
    public void cancelSwap() {
        swapPending = false;
        updateResultControls();
    }

    private void replaceContent(String newLeft, String newRight, DiffModel newModel, boolean syncCleanResult) {
        ViewState state = captureViewState();
        this.leftText = newLeft;
        this.rightText = newRight;
        this.model = newModel;
        if (syncCleanResult && resultEditing && !resultDirty) {
            setResultText(editableSide == EditableSide.RIGHT ? newRight : newLeft, true);
        }
        highlightGeneration.incrementAndGet();
        // Drop cached nodes so the next show* rebuilds from the new model.
        sideBySideNode = null;
        leftPaneBox = null;
        unifiedNode = null;
        leftArea = null;
        rightArea = null;
        unifiedArea = null;
        restoreChangeCursor(state);
        updateSummary();
        updateEofButton();
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
        restoreViewState(state);
    }

    /** The unified-diff text for the "export patch" action. */
    public String patchText(String leftLabel, String rightLabel) {
        return PatchWriter.unifiedDiff(leftLabel, rightLabel, leftText, rightText);
    }

    private static IGrammar grammarFor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return GrammarRegistry.shared().forFileName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private HBox buildToolbar() {
        summary.getStyleClass().add("diff-summary");
        changeNav.getStyleClass().add("diff-summary");
        updateSummary();
        Button next = iconButton(Icons.arrowDown(), tr("diff.nextChange"), this::nextChange);
        Button prev = iconButton(Icons.arrowUp(), tr("diff.prevChange"), this::prevChange);
        exportButton = iconButton(
                Icons.saveAs(),
                tr("diff.tooltip.exportPatch"),
                () -> onExportPatch.accept(patchText("a/" + leftName, "b/" + rightName)));
        exportButton.setAccessibleText(tr("diff.exportPatch"));
        whitespaceButton.getStyleClass().addAll("flat", "diff-option-button");
        whitespaceButton.setFocusTraversable(false);
        whitespaceButton.setOnAction(e -> cycleWhitespace());
        updateWhitespaceButton();
        configureToggle(wordButton, tr("diff.highlightWords"), tr("diff.tooltip.words"), true, selected -> {
            options = options.withWordLevel(selected);
            onOptionsChanged.accept(options);
        });
        rulesButton.setText(tr("diff.rules"));
        rulesButton.setAccessibleText(tr("diff.rules"));
        rulesButton.setTooltip(descriptiveTooltip(tr("diff.tooltip.rules")));
        rulesButton.setFocusTraversable(false);
        rulesButton.getStyleClass().addAll("flat", "diff-option-button");
        ignoreCaseItem.setText(tr("diff.ignoreCase"));
        ignoreCaseItem.setSelected(options.ignoreCase());
        ignoreCaseItem.setOnAction(e -> updateIgnoreCase(ignoreCaseItem.isSelected()));
        smartAlignmentItem.setText(tr("diff.smartAlignment"));
        smartAlignmentItem.setSelected(options.smartAlignment());
        smartAlignmentItem.setOnAction(e -> updateSmartAlignment(smartAlignmentItem.isSelected()));
        rulesButton.getItems().addAll(ignoreCaseItem, smartAlignmentItem);
        configureToggle(
                contextButton, tr("diff.collapseContext"), tr("diff.tooltip.context"), collapseContext, selected -> {
                    collapseContext = selected;
                    rememberedCollapseContext = selected;
                    rebuildCurrentView();
                });
        configureToggle(wrapButton, tr("diff.wrapLines"), tr("diff.tooltip.wrap"), wrapLines, selected -> {
            wrapLines = selected;
            rememberedWrapLines = selected;
            applyWrapMode();
        });
        hunkButton(stageHunkButton, tr("diff.stageHunk"), GitHunkAction.STAGE);
        hunkButton(unstageHunkButton, tr("diff.unstageHunk"), GitHunkAction.UNSTAGE);
        hunkButton(revertHunkButton, tr("diff.revertHunk"), GitHunkAction.REVERT);
        applyEofButton.setText(tr("diff.applyFinalNewline"));
        applyEofButton.setAccessibleText(tr("diff.applyFinalNewline"));
        applyEofButton.setTooltip(new Tooltip(tr("diff.applyFinalNewline")));
        applyEofButton.setFocusTraversable(false);
        applyEofButton.getStyleClass().addAll("flat", "diff-option-button");
        applyEofButton.setOnAction(e -> applyFinalNewline());
        updateEofButton();
        // "Apply all": replace the editable file with the other side entirely. Shown only when a side is
        // editable (set by setEditable, which runs after construction), so it starts hidden.
        applyAllButton.setGraphic(Icons.check());
        applyAllButton.setTooltip(new Tooltip(tr("diff.applyAll")));
        applyAllButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        applyAllButton.setFocusTraversable(false);
        applyAllButton.setOnAction(e -> applyAll());
        applyAllButton.setVisible(false);
        applyAllButton.setManaged(false);
        // Undo / Save the applied changes (shown only when a side is editable).
        editButton(undoButton, Icons.undo(), tr("diff.undo"), () -> {
            if (unappliedUndoDepth > 0) {
                onUndo.run();
                unappliedUndoDepth--;
                updateEditButtons();
            }
        });
        editButton(saveButton, Icons.save(), tr("diff.save"), () -> {
            onSave.run();
            saveButton.setDisable(true);
        });
        updateEditButtons();
        editResultButton.setGraphic(Icons.edit());
        editResultButton.setAccessibleText(tr("diff.editResult"));
        editResultButton.setTooltip(descriptiveTooltip(tr("diff.tooltip.editResult")));
        editResultButton.getStyleClass().addAll("flat", "diff-toolbar-button", "diff-option-button");
        editResultButton.setFocusTraversable(false);
        editResultButton.setOnAction(e -> toggleResultEditor());
        editResultButton.setVisible(false);
        editResultButton.setManaged(false);
        toggleButton.setOnAction(e -> toggleView());
        toggleButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        toggleButton.setFocusTraversable(false);
        updateToggleButton();
        swapButton.setGraphic(Icons.diff());
        swapButton.setAccessibleText(tr("diff.swapSides"));
        swapButton.setTooltip(descriptiveTooltip(tr("diff.tooltip.swapSides")));
        swapButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        swapButton.setFocusTraversable(false);
        swapButton.setOnAction(e -> requestSwap());
        swapButton.setVisible(false);
        swapButton.setManaged(false);
        exitDiffUiButton.setGraphic(Icons.editora());
        exitDiffUiButton.setAccessibleText(tr("tooltip.diffUiExit"));
        exitDiffUiButton.setTooltip(new Tooltip(tr("tooltip.diffUiExit")));
        exitDiffUiButton.getStyleClass().addAll("flat", "diff-toolbar-button", "diff-ui-exit");
        exitDiffUiButton.setFocusTraversable(false);
        exitDiffUiButton.setOnAction(e -> onExitDiffUi.run());
        exitDiffUiButton.setVisible(false);
        exitDiffUiButton.setManaged(false);
        // The gap grows as usual, but stops at the left pane's edge so the cluster lands at the start of
        // the second file. Uncapped in unified view (leftPaneWidth 0), where there is no second pane and
        // right-aligned is still the sensible place for it.
        Region gap = spacer();
        gap.maxWidthProperty()
                .bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> leftPaneWidth.get() <= 0
                                ? Double.MAX_VALUE
                                : Math.max(0, leftPaneWidth.get() - summary.getWidth() - TOOLBAR_H_PADDING),
                        leftPaneWidth,
                        summary.widthProperty()));
        HBox bar = new HBox(
                2,
                summary,
                gap,
                changeNav,
                next,
                prev,
                sep(),
                editResultButton,
                applyAllButton,
                undoButton,
                saveButton,
                sep(),
                stageHunkButton,
                unstageHunkButton,
                revertHunkButton,
                applyEofButton,
                sep(),
                whitespaceButton,
                wordButton,
                rulesButton,
                contextButton,
                wrapButton,
                sep(),
                toggleButton,
                swapButton,
                exportButton,
                exitDiffUiButton);
        bar.getStyleClass().add("diff-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, TOOLBAR_H_PADDING, 3, TOOLBAR_H_PADDING));
        return bar;
    }

    private void updateEofButton() {
        boolean visible = editableSide != EditableSide.NONE && model.finalNewlineDiffers();
        applyEofButton.setVisible(visible);
        applyEofButton.setManaged(visible);
        applyEofButton.setDisable(resultEditing);
    }

    private void applyFinalNewline() {
        String editable = editableSide == EditableSide.RIGHT ? rightText : leftText;
        DiffText parsed = DiffText.parse(editable);
        boolean desired = editableSide == EditableSide.RIGHT ? model.leftFinalNewline() : model.rightFinalNewline();
        deliverApply(new DiffText(parsed.lines(), parsed.lineSeparator(), desired).compose(parsed.lines()));
    }

    private void hunkButton(Button button, String label, GitHunkAction action) {
        button.setText(label);
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("flat", "diff-option-button");
        button.setOnAction(e -> performGitAction(action, currentBlockStart(), false));
        button.setVisible(false);
        button.setManaged(false);
    }

    private void configureToggle(
            ToggleButton button,
            String text,
            String description,
            boolean selected,
            java.util.function.Consumer<Boolean> changed) {
        button.setText(text);
        button.setAccessibleText(text);
        button.setTooltip(descriptiveTooltip(description));
        button.setSelected(selected);
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("flat", "diff-option-button");
        button.setOnAction(e -> changed.accept(button.isSelected()));
    }

    private void cycleWhitespace() {
        DiffEngine.WhitespaceMode next =
                switch (options.whitespace()) {
                    case NONE -> DiffEngine.WhitespaceMode.TRIM;
                    case TRIM -> DiffEngine.WhitespaceMode.ALL;
                    case ALL -> DiffEngine.WhitespaceMode.NONE;
                };
        options = options.withWhitespace(next);
        updateWhitespaceButton();
        onOptionsChanged.accept(options);
    }

    private void updateIgnoreCase(boolean selected) {
        ignoreCaseItem.setSelected(selected);
        options = options.withIgnoreCase(selected);
        onOptionsChanged.accept(options);
    }

    private void updateSmartAlignment(boolean selected) {
        smartAlignmentItem.setSelected(selected);
        options = options.withSmartAlignment(selected);
        onOptionsChanged.accept(options);
    }

    private void updateWhitespaceButton() {
        whitespaceButton.setText(
                switch (options.whitespace()) {
                    case NONE -> tr("diff.whitespace.none");
                    case TRIM -> tr("diff.whitespace.trim");
                    case ALL -> tr("diff.whitespace.all");
                });
        whitespaceButton.setAccessibleText(whitespaceButton.getText());
        whitespaceButton.setTooltip(descriptiveTooltip(
                switch (options.whitespace()) {
                    case NONE -> tr("diff.tooltip.whitespace.none");
                    case TRIM -> tr("diff.tooltip.whitespace.trim");
                    case ALL -> tr("diff.tooltip.whitespace.all");
                }));
    }

    private void rebuildCurrentView() {
        ViewState state = captureViewState();
        highlightGeneration.incrementAndGet();
        sideBySideNode = null;
        unifiedNode = null;
        leftArea = null;
        rightArea = null;
        unifiedArea = null;
        leftPaneBox = null;
        sideSplit = null;
        connectorCanvas = null;
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
        restoreViewState(state);
    }

    private ViewState captureViewState() {
        int leftLine = -1;
        int rightLine = -1;
        if (changeCursor >= 0 && changeCursor < model.changeBlockStarts().size()) {
            Row row = model.rows().get(model.changeBlockStarts().get(changeCursor));
            leftLine = row.leftLine();
            rightLine = row.rightLine();
        }
        double divider = 0.5;
        if (sideSplit != null && sideSplit.getDividerPositions().length > 0) {
            divider = sideSplit.getDividerPositions()[0];
        }
        return new ViewState(
                leftArea == null ? 0 : leftArea.getEstimatedScrollY(),
                rightArea == null ? 0 : rightArea.getEstimatedScrollY(),
                unifiedArea == null ? 0 : unifiedArea.getEstimatedScrollY(),
                leftArea != null && leftArea.isFocused(),
                rightArea != null && rightArea.isFocused(),
                leftLine,
                rightLine,
                divider);
    }

    private void restoreChangeCursor(ViewState state) {
        if (state.selectedLeftLine() < 0 && state.selectedRightLine() < 0) {
            changeCursor = -1;
            return;
        }
        int best = -1;
        int distance = Integer.MAX_VALUE;
        List<Integer> starts = model.changeBlockStarts();
        for (int i = 0; i < starts.size(); i++) {
            Row row = model.rows().get(starts.get(i));
            int d = row.leftLine() >= 0 && state.selectedLeftLine() >= 0
                    ? Math.abs(row.leftLine() - state.selectedLeftLine())
                    : row.rightLine() >= 0 && state.selectedRightLine() >= 0
                            ? Math.abs(row.rightLine() - state.selectedRightLine())
                            : Integer.MAX_VALUE;
            if (d < distance) {
                best = i;
                distance = d;
            }
        }
        changeCursor = best;
    }

    private void restoreViewState(ViewState state) {
        Platform.runLater(() -> {
            syncing = true;
            try {
                if (sideSplit != null) {
                    sideSplit.setDividerPositions(state.divider());
                }
                if (unified && unifiedArea != null) {
                    unifiedArea.estimatedScrollYProperty().setValue(state.unifiedScroll());
                } else {
                    if (leftArea != null) {
                        leftArea.estimatedScrollYProperty().setValue(state.leftScroll());
                    }
                    if (rightArea != null) {
                        rightArea.estimatedScrollYProperty().setValue(state.rightScroll());
                    }
                }
            } finally {
                syncing = false;
            }
            if (state.leftFocused() && leftArea != null) {
                leftArea.requestFocus();
            } else if (state.rightFocused() && rightArea != null) {
                rightArea.requestFocus();
            }
            if (changeCursor >= 0 && changeCursor < model.changeBlockStarts().size()) {
                scrollToRow(model.changeBlockStarts().get(changeCursor));
            }
        });
    }

    private void applyWrapMode() {
        if (leftArea != null) {
            leftArea.setWrapText(wrapLines);
        }
        if (rightArea != null) {
            rightArea.setWrapText(wrapLines);
        }
        if (unifiedArea != null) {
            unifiedArea.setWrapText(wrapLines);
        }
        if (resultArea != null) {
            resultArea.setWrapText(wrapLines);
        }
    }

    /** Configures an editable-only toolbar button (hidden until {@link #setEditable} shows it). */
    private void editButton(Button b, Node icon, String tip, Runnable action) {
        b.setGraphic(icon);
        b.setAccessibleText(tip);
        b.setTooltip(new Tooltip(tip));
        b.getStyleClass().addAll("flat", "diff-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        b.setVisible(false);
        b.setManaged(false);
    }

    private void updateToggleButton() {
        toggleButton.setGraphic(unified ? Icons.splitVertical() : Icons.previewOnly());
        toggleButton.setAccessibleText(unified ? tr("diff.viewSideBySide") : tr("diff.viewUnified"));
        toggleButton.setTooltip(
                descriptiveTooltip(unified ? tr("diff.tooltip.viewSideBySide") : tr("diff.tooltip.viewUnified")));
    }

    private Button iconButton(Node icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "diff-toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(descriptiveTooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Tooltip descriptiveTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(360);
        tooltip.setShowDelay(javafx.util.Duration.millis(350));
        return tooltip;
    }

    /** Horizontal inset of the diff toolbar; also what the cluster cap has to allow for. */
    private static final double TOOLBAR_H_PADDING = 6;

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static Separator sep() {
        Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
        s.getStyleClass().add("diff-toolbar-separator");
        return s;
    }

    /** Palette-command entry points mirroring the diff toolbar (act on the active diff tab). */
    public void toggleViewMode() {
        toggleView();
    }

    public void applyAllChanges() {
        if (editableSide != EditableSide.NONE) {
            applyAll(); // no-op on a read-only diff (no editable side / onApply)
        }
    }

    public void toggleResultEditing() {
        if (resultEditingEnabled && editableSide != EditableSide.NONE) {
            editResultButton.fire();
        }
    }

    public void swapComparisonSides() {
        if (swapEnabled) {
            swapButton.fire();
        }
    }

    public void toggleIgnoreCase() {
        updateIgnoreCase(!options.ignoreCase());
    }

    public void toggleSmartAlignment() {
        updateSmartAlignment(!options.smartAlignment());
    }

    public void goNextChange() {
        nextChange();
    }

    public void goPreviousChange() {
        prevChange();
    }

    public void stageCurrentHunk() {
        if (gitHunkActions.contains(GitHunkAction.STAGE))
            performGitAction(GitHunkAction.STAGE, currentBlockStart(), false);
    }

    public void unstageCurrentHunk() {
        if (gitHunkActions.contains(GitHunkAction.UNSTAGE))
            performGitAction(GitHunkAction.UNSTAGE, currentBlockStart(), false);
    }

    public void revertCurrentHunk() {
        if (gitHunkActions.contains(GitHunkAction.REVERT))
            performGitAction(GitHunkAction.REVERT, currentBlockStart(), false);
    }

    public void copyCurrentHunk() {
        int row = currentBlockStart();
        if (row >= 0) copyHunk(row);
    }

    public void openCurrentChange() {
        if (gitHunkActions.contains(GitHunkAction.OPEN))
            performGitAction(GitHunkAction.OPEN, currentBlockStart(), true);
    }

    private void toggleResultEditor() {
        if (editResultButton.isSelected()) {
            openResultEditor();
        } else if (resultDirty) {
            // Applying or resetting is explicit; a toolbar click must not silently discard a draft.
            editResultButton.setSelected(true);
            if (resultArea != null) {
                resultArea.requestFocus();
            }
        } else {
            closeResultEditor();
        }
    }

    private void openResultEditor() {
        if (!resultEditingEnabled || editableSide == EditableSide.NONE) {
            editResultButton.setSelected(false);
            return;
        }
        resultEditing = true;
        resultBaselineText = editableSide == EditableSide.RIGHT ? rightText : leftText;
        if (resultArea == null) {
            buildResultEditor();
        }
        setResultText(resultBaselineText, true);
        editResultButton.setSelected(true);
        updateResultControls();
        rebuildCurrentView();
        Platform.runLater(resultArea::requestFocus);
    }

    private void closeResultEditor() {
        resultDiffDelay.stop();
        resultHighlightGeneration.incrementAndGet();
        resultEditing = false;
        resultDirty = false;
        resultBaselineText = null;
        editResultButton.setSelected(false);
        resultSplit = null;
        updateResultControls();
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
    }

    private void buildResultEditor() {
        resultArea = new CodeArea();
        resultArea.setId("diff-editable-result");
        resultArea.getStyleClass().addAll("editor-area", "diff-result");
        resultArea.setAccessibleText(tr("diff.resultDescription"));
        resultArea.setWrapText(wrapLines);
        resultArea.setStyle(fontStyle);
        resultArea.textProperty().addListener((o, oldText, newText) -> {
            if (updatingResult) {
                return;
            }
            resultDirty = !java.util.Objects.equals(resultBaselineText, newText);
            updateResultControls();
            resultDiffDelay.playFromStart();
        });

        Label label = new Label(tr("diff.result"));
        label.getStyleClass().add("diff-result-label");
        applyResultButton.setText(tr("diff.applyResult"));
        applyResultButton.setAccessibleText(tr("diff.applyResult"));
        applyResultButton.setTooltip(descriptiveTooltip(tr("diff.tooltip.applyResult")));
        applyResultButton.setOnAction(e -> applyResult());
        resetResultButton.setText(tr("diff.resetResult"));
        resetResultButton.setAccessibleText(tr("diff.resetResult"));
        resetResultButton.setTooltip(descriptiveTooltip(tr("diff.tooltip.resetResult")));
        resetResultButton.setOnAction(e -> resetResult());
        HBox header = new HBox(6, label, spacer(), resetResultButton, applyResultButton);
        header.setAlignment(Pos.CENTER_LEFT);
        var scroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(resultArea);
        VBox box = new VBox(4, header, scroll);
        box.getStyleClass().add("diff-result-box");
        box.setPadding(new Insets(6, 8, 8, 8));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        resultNode = box;
    }

    private void setResultText(String text, boolean baseline) {
        if (resultArea == null) {
            return;
        }
        updatingResult = true;
        try {
            resultArea.replaceText(text == null ? "" : text);
            resultArea.getUndoManager().forgetHistory();
        } finally {
            updatingResult = false;
        }
        if (baseline) {
            resultBaselineText = resultArea.getText();
            resultDirty = false;
        }
        updateResultControls();
        highlightResult(resultArea.getText());
    }

    private void applyResult() {
        if (!resultEditing || !resultDirty || resultArea == null) {
            return;
        }
        String text = resultArea.getText();
        if (onApply.test(text)) {
            resultBaselineText = text;
            resultDirty = false;
            unappliedUndoDepth++;
            updateResultControls();
            updateEditButtons();
            resultDiffDelay.stop();
            onResultEdited.accept(text);
        }
    }

    private void resetResult() {
        if (!resultEditing || resultArea == null) {
            return;
        }
        setResultText(resultBaselineText, true);
        onResultEdited.accept(resultBaselineText);
    }

    private void updateResultControls() {
        boolean active = resultEditing;
        applyResultButton.setDisable(!active || !resultDirty);
        resetResultButton.setDisable(!active || !resultDirty);
        applyAllButton.setDisable(active);
        applyEofButton.setDisable(active);
        stageHunkButton.setDisable(active);
        unstageHunkButton.setDisable(active);
        revertHunkButton.setDisable(active);
        swapButton.setDisable(!swapEnabled || swapPending || resultDirty);
    }

    private void requestSwap() {
        if (!swapEnabled || swapPending || resultDirty) {
            return;
        }
        swapPending = true;
        updateResultControls();
        onSwapRequested.accept(rightText, leftText);
    }

    private void highlightResult(String text) {
        if (resultArea == null || text == null || text.isEmpty() || grammar == null) {
            return;
        }
        long generation = resultHighlightGeneration.incrementAndGet();
        HIGHLIGHT_EXECUTOR.submit(() -> {
            StyleSpans<Collection<String>> styles;
            try {
                styles = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                return;
            }
            Platform.runLater(() -> {
                if (generation == resultHighlightGeneration.get()
                        && resultArea != null
                        && text.equals(resultArea.getText())) {
                    resultArea.setStyleSpans(0, styles);
                }
            });
        });
    }

    private void toggleView() {
        unified = !unified;
        updateToggleButton();
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
    }

    // --- side-by-side ---------------------------------------------------------------------------

    private void showSideBySide() {
        if (sideBySideNode == null) {
            buildSideBySide();
        }
        // (Re-)bind every time, not once at build: showUnified unbinds it, so a toggle out and back would
        // otherwise leave the toolbar cluster stuck at the window edge. Tracks the divider as it is dragged.
        if (leftPaneBox != null) {
            leftPaneWidth.bind(leftPaneBox.widthProperty());
        }
        showComparison(sideBySideNode);
    }

    private void buildSideBySide() {
        List<DisplayRow> display = sideDisplayRows();
        List<Row> rows = display.stream().map(DisplayRow::row).toList();
        sideSourceRows = display.stream().mapToInt(DisplayRow::sourceStart).toArray();
        leftArea = readOnlyArea("diff-left");
        rightArea = readOnlyArea("diff-right");

        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        int[] leftNos = new int[rows.size()];
        int[] rightNos = new int[rows.size()];
        List<int[]> leftWordAbs = new ArrayList<>();
        List<int[]> rightWordAbs = new ArrayList<>();
        int leftOffset = 0;
        int rightOffset = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String l = r.left() == null ? "" : r.left();
            String rt = r.right() == null ? "" : r.right();
            if (i > 0) {
                left.append('\n');
                right.append('\n');
                leftOffset++;
                rightOffset++;
            }
            leftNos[i] = r.leftLine();
            rightNos[i] = r.rightLine();
            if (!display.get(i).collapsed() && r.type() == RowType.MODIFIED) {
                addAbs(leftWordAbs, leftOffset, r.leftWordRanges());
                addAbs(rightWordAbs, rightOffset, r.rightWordRanges());
            }
            left.append(l);
            right.append(rt);
            leftOffset += l.length();
            rightOffset += rt.length();
        }

        leftArea.replaceText(left.toString());
        rightArea.replaceText(right.toString());
        applyStyle(leftArea, left.toString(), leftWordAbs);
        applyStyle(rightArea, right.toString(), rightWordAbs);
        for (int i = 0; i < rows.size(); i++) {
            if (display.get(i).collapsed()) {
                leftArea.setParagraphStyle(i, List.of("diff-collapsed"));
                rightArea.setParagraphStyle(i, List.of("diff-collapsed"));
            } else {
                leftArea.setParagraphStyle(i, leftLineClasses(rows.get(i).type()));
                rightArea.setParagraphStyle(i, rightLineClasses(rows.get(i).type()));
            }
        }
        // Keep transfer actions at the center seam. RichTextFX paragraph graphics are leading-edge
        // gutters, so the right pane owns the action column even when the editable/local side is LEFT;
        // the chevron direction still indicates which side receives the change. Putting LEFT actions in
        // the left pane's gutter stranded them at the window's outer edge, far away from the comparison.
        installGutter(leftArea, leftNos, sideSourceRows, false);
        installGutter(rightArea, rightNos, sideSourceRows, editableSide != EditableSide.NONE && !resultEditing);
        installContextMenu(leftArea, sideSourceRows);
        installContextMenu(rightArea, sideSourceRows);
        installScrollFocus(leftArea);
        installScrollFocus(rightArea);
        syncScroll(leftArea, rightArea);
        syncScroll(rightArea, leftArea);

        var leftScroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(leftArea);
        var rightScroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(rightArea);
        Label leftHeader = paneHeader(headerLeft);
        Label rightHeader = paneHeader(headerRight);
        javafx.scene.layout.VBox leftBox = new javafx.scene.layout.VBox(leftHeader, leftScroll);
        javafx.scene.layout.VBox rightBox = new javafx.scene.layout.VBox(rightHeader, rightScroll);
        // Reserve a narrow center track without changing SplitPane behavior. The transparent overlay below
        // paints through this gap and slightly into each side, producing JetBrains-style hunk ribbons.
        leftBox.setPadding(new Insets(0, 14, 0, 0));
        rightBox.setPadding(new Insets(0, 0, 0, 14));
        javafx.scene.layout.VBox.setVgrow(leftScroll, Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(rightScroll, Priority.ALWAYS);
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.5);
        sideSplit = split;
        StackPane layered = new StackPane(split);
        connectorCanvas = new DiffConnectorCanvas(leftArea, rightArea, rows);
        connectorCanvas.widthProperty().bind(layered.widthProperty());
        connectorCanvas.heightProperty().bind(layered.heightProperty());
        layered.getChildren().add(connectorCanvas);
        leftPaneBox = leftBox;
        sideBySideNode = layered;
    }

    /**
     * Transparent, mouse-pass-through overlay for side-by-side change ribbons and right-edge overview
     * markers. Geometry is derived only for visible RichTextFX paragraphs, so scrolling remains bounded by
     * the number of change blocks on screen rather than document size.
     */
    private final class DiffConnectorCanvas extends Canvas {

        private final CodeArea left;
        private final CodeArea right;
        private final List<DiffConnectorModel.Band> bands;
        private boolean drawQueued;
        private int selectedBand = -1;

        DiffConnectorCanvas(CodeArea left, CodeArea right, List<Row> rows) {
            this.left = left;
            this.right = right;
            bands = DiffConnectorModel.bands(rows);
            setManaged(false);
            setMouseTransparent(true);
            setFocusTraversable(false);

            widthProperty().addListener((o, oldValue, newValue) -> requestDraw());
            heightProperty().addListener((o, oldValue, newValue) -> requestDraw());
            left.estimatedScrollYProperty().addListener((o, oldValue, newValue) -> requestDraw());
            right.estimatedScrollYProperty().addListener((o, oldValue, newValue) -> requestDraw());
            left.widthProperty().addListener((o, oldValue, newValue) -> requestDraw());
            right.widthProperty().addListener((o, oldValue, newValue) -> requestDraw());
            left.layoutBoundsProperty().addListener((o, oldValue, newValue) -> requestDraw());
            right.layoutBoundsProperty().addListener((o, oldValue, newValue) -> requestDraw());
            sceneProperty().addListener((o, oldValue, newValue) -> requestDraw());
            requestDraw();
        }

        void setSelectedBand(int selectedBand) {
            this.selectedBand = selectedBand;
            requestDraw();
        }

        private void requestDraw() {
            if (drawQueued) {
                return;
            }
            drawQueued = true;
            Platform.runLater(() -> {
                drawQueued = false;
                draw();
            });
        }

        private void draw() {
            GraphicsContext graphics = getGraphicsContext2D();
            graphics.clearRect(0, 0, getWidth(), getHeight());
            if (getScene() == null || bands.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            Bounds leftViewport = left.localToScreen(left.getBoundsInLocal());
            Bounds rightViewport = right.localToScreen(right.getBoundsInLocal());
            if (leftViewport == null || rightViewport == null) {
                return;
            }
            double leftX = screenToLocal(leftViewport.getMaxX(), leftViewport.getMinY())
                    .getX();
            double rightX = screenToLocal(rightViewport.getMinX(), rightViewport.getMinY())
                    .getX();
            if (rightX <= leftX) {
                return;
            }

            for (int i = 0; i < bands.size(); i++) {
                DiffConnectorModel.Band band = bands.get(i);
                VisibleBand leftBand = visibleBand(left, band, leftViewport);
                VisibleBand rightBand = visibleBand(right, band, rightViewport);
                if (leftBand == null || rightBand == null) {
                    continue;
                }
                boolean selected = i == selectedBand;
                Color color = bandColor(band.kind(), selected);
                graphics.setFill(color);
                graphics.setStroke(color.deriveColor(0, 1, 0.82, Math.min(0.9, color.getOpacity() + 0.22)));
                graphics.setLineWidth(selected ? 1.5 : 1.0);
                drawRibbon(graphics, leftX, rightX, leftBand, rightBand);
            }
            drawOverview(graphics, rightViewport);
        }

        private VisibleBand visibleBand(CodeArea area, DiffConnectorModel.Band band, Bounds viewport) {
            int visibleCount = area.getVisibleParagraphs().size();
            if (visibleCount == 0) {
                return null;
            }
            int first = area.visibleParToAllParIndex(0);
            int last = area.visibleParToAllParIndex(visibleCount - 1);
            if (band.endRow() <= first || band.startRow() > last) {
                return null;
            }
            double top = band.startRow() <= first
                    ? viewport.getMinY()
                    : area.getParagraphBoundsOnScreen(band.startRow())
                            .map(Bounds::getMinY)
                            .orElse(viewport.getMinY());
            int finalRow = band.endRow() - 1;
            double bottom = finalRow >= last
                    ? viewport.getMaxY()
                    : area.getParagraphBoundsOnScreen(finalRow)
                            .map(Bounds::getMaxY)
                            .orElse(viewport.getMaxY());
            double localTop = screenToLocal(viewport.getMinX(), Math.max(viewport.getMinY(), top))
                    .getY();
            double localBottom = screenToLocal(viewport.getMinX(), Math.min(viewport.getMaxY(), bottom))
                    .getY();
            return localBottom > localTop ? new VisibleBand(localTop, localBottom) : null;
        }

        private void drawRibbon(
                GraphicsContext graphics, double leftX, double rightX, VisibleBand leftBand, VisibleBand rightBand) {
            double controlLeft = leftX + (rightX - leftX) * 0.45;
            double controlRight = leftX + (rightX - leftX) * 0.55;
            graphics.beginPath();
            graphics.moveTo(leftX, leftBand.top());
            graphics.bezierCurveTo(controlLeft, leftBand.top(), controlRight, rightBand.top(), rightX, rightBand.top());
            graphics.lineTo(rightX, rightBand.bottom());
            graphics.bezierCurveTo(
                    controlRight, rightBand.bottom(), controlLeft, leftBand.bottom(), leftX, leftBand.bottom());
            graphics.closePath();
            graphics.fill();
            graphics.stroke();
        }

        private void drawOverview(GraphicsContext graphics, Bounds rightViewport) {
            double top = screenToLocal(rightViewport.getMinX(), rightViewport.getMinY())
                    .getY();
            double bottom = screenToLocal(rightViewport.getMinX(), rightViewport.getMaxY())
                    .getY();
            double trackHeight = Math.max(1, bottom - top);
            double x = getWidth() - 5;
            for (int i = 0; i < bands.size(); i++) {
                DiffConnectorModel.Band band = bands.get(i);
                double y = top
                        + trackHeight
                                * band.startRow()
                                / Math.max(1, right.getParagraphs().size());
                double height = Math.max(
                        3,
                        trackHeight
                                * (band.endRow() - band.startRow())
                                / Math.max(1, right.getParagraphs().size()));
                graphics.setFill(bandColor(band.kind(), i == selectedBand).deriveColor(0, 1, 1, 0.88));
                graphics.fillRoundRect(x, y, i == selectedBand ? 4 : 3, height, 2, 2);
            }
        }

        private Color bandColor(DiffConnectorModel.Kind kind, boolean selected) {
            double opacity = selected ? 0.52 : 0.30;
            return switch (kind) {
                case ADDED -> Color.rgb(63, 185, 80, opacity);
                case REMOVED -> Color.rgb(248, 81, 73, opacity);
                case MODIFIED -> Color.rgb(210, 153, 34, opacity);
            };
        }

        private record VisibleBand(double top, double bottom) {}
    }

    private void installContextMenu(CodeArea area, int[] sourceRows) {
        area.setOnContextMenuRequested(e -> {
            int clicked;
            try {
                int offset = area.hit(e.getX(), e.getY()).getInsertionIndex();
                clicked = area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor();
            } catch (RuntimeException ignored) {
                clicked = area.getCurrentParagraph();
            }
            int displayRow = Math.max(0, Math.min(clicked, sourceRows.length - 1));
            int sourceRow = sourceRows.length == 0 ? -1 : sourceRows[displayRow];
            if (sourceRow < 0
                    || sourceRow >= model.rows().size()
                    || model.rows().get(sourceRow).type() == RowType.EQUAL) {
                return;
            }
            changeCursor = changeBlockIndexContaining(sourceRow);
            updateChangeNav();
            ContextMenu menu = new ContextMenu();
            addGitMenuItem(menu, GitHunkAction.STAGE, tr("diff.stageHunk"), sourceRow, false);
            addGitMenuItem(menu, GitHunkAction.STAGE, tr("diff.stageLine"), sourceRow, true);
            addGitMenuItem(menu, GitHunkAction.UNSTAGE, tr("diff.unstageHunk"), sourceRow, false);
            addGitMenuItem(menu, GitHunkAction.UNSTAGE, tr("diff.unstageLine"), sourceRow, true);
            addGitMenuItem(menu, GitHunkAction.REVERT, tr("diff.revertHunk"), sourceRow, false);
            addGitMenuItem(menu, GitHunkAction.REVERT, tr("diff.revertLine"), sourceRow, true);
            MenuItem copy = new MenuItem(tr("diff.copyHunk"));
            copy.setOnAction(a -> copyHunk(sourceRow));
            menu.getItems().add(copy);
            if (gitHunkActions.contains(GitHunkAction.OPEN)) {
                MenuItem open = new MenuItem(tr("diff.openInEditor"));
                open.setOnAction(a -> performGitAction(GitHunkAction.OPEN, sourceRow, true));
                menu.getItems().add(open);
            }
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void addGitMenuItem(ContextMenu menu, GitHunkAction action, String label, int sourceRow, boolean lineOnly) {
        if (!gitHunkActions.contains(action)) {
            return;
        }
        MenuItem item = new MenuItem(label);
        item.setOnAction(e -> performGitAction(action, sourceRow, lineOnly));
        menu.getItems().add(item);
    }

    private int currentBlockStart() {
        List<Integer> starts = model.changeBlockStarts();
        if (starts.isEmpty()) {
            return -1;
        }
        return changeCursor >= 0 && changeCursor < starts.size() ? starts.get(changeCursor) : starts.get(0);
    }

    private int changeBlockIndexContaining(int row) {
        List<Integer> starts = model.changeBlockStarts();
        for (int i = starts.size() - 1; i >= 0; i--) {
            if (starts.get(i) <= row) {
                return i;
            }
        }
        return -1;
    }

    private void performGitAction(GitHunkAction action, int row, boolean lineOnly) {
        if (row < 0 || model.quality() == com.editora.diff.DiffModels.Quality.METADATA_ONLY) {
            return;
        }
        int start = lineOnly ? row : model.changeBlockStarts().get(changeBlockIndexContaining(row));
        int end = lineOnly ? row + 1 : blockEndFrom(start);
        boolean leftTarget = action == GitHunkAction.STAGE;
        if (sidesSwapped) {
            leftTarget = !leftTarget;
        }
        String before = leftTarget ? leftText : rightText;
        String after = action == GitHunkAction.OPEN
                ? before
                : computeAppliedFor(leftTarget ? EditableSide.LEFT : EditableSide.RIGHT, start, end);
        Row target = model.rows().get(row);
        int preferredLine = leftTarget ? target.leftLine() : target.rightLine();
        int fallbackLine = leftTarget ? target.rightLine() : target.leftLine();
        int line = preferredLine >= 0 ? preferredLine : fallbackLine;
        onGitHunkAction.accept(new GitHunkRequest(action, start, end, before, after, Math.max(1, line)));
    }

    private void copyHunk(int row) {
        int start = model.changeBlockStarts().get(changeBlockIndexContaining(row));
        int end = blockEndFrom(start);
        StringBuilder text = new StringBuilder();
        for (int i = start; i < end; i++) {
            Row r = model.rows().get(i);
            if (r.left() != null) {
                text.append('-').append(r.left()).append('\n');
            }
            if (r.right() != null) {
                text.append('+').append(r.right()).append('\n');
            }
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text.toString());
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private List<DisplayRow> sideDisplayRows() {
        List<Row> rows = model.rows();
        if (!collapseContext || rows.size() <= CONTEXT_LINES * 2 + 2) {
            List<DisplayRow> out = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                out.add(new DisplayRow(rows.get(i), i, i + 1, false));
            }
            return out;
        }
        List<DisplayRow> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            if (rows.get(i).type() != RowType.EQUAL) {
                out.add(new DisplayRow(rows.get(i), i, i + 1, false));
                i++;
                continue;
            }
            int end = i + 1;
            while (end < rows.size() && rows.get(end).type() == RowType.EQUAL) {
                end++;
            }
            int leftKeep = i == 0 ? 0 : Math.min(CONTEXT_LINES, end - i);
            int rightKeep = end == rows.size() ? 0 : Math.min(CONTEXT_LINES, end - i - leftKeep);
            if (end - i <= leftKeep + rightKeep + 1) {
                for (int j = i; j < end; j++) {
                    out.add(new DisplayRow(rows.get(j), j, j + 1, false));
                }
            } else {
                for (int j = i; j < i + leftKeep; j++) {
                    out.add(new DisplayRow(rows.get(j), j, j + 1, false));
                }
                int hiddenStart = i + leftKeep;
                int hiddenEnd = end - rightKeep;
                String marker = tr("diff.unchangedLines", hiddenEnd - hiddenStart);
                Row first = rows.get(hiddenStart);
                Row collapsed = Row.equal(marker, first.leftLine(), first.rightLine());
                out.add(new DisplayRow(collapsed, hiddenStart, hiddenEnd, true));
                for (int j = hiddenEnd; j < end; j++) {
                    out.add(new DisplayRow(rows.get(j), j, j + 1, false));
                }
            }
            i = end;
        }
        return out;
    }

    private Label paneHeader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("diff-pane-header");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /**
     * Keeps the two side-by-side panes aligned: copies the scroll position of the <b>focused</b> pane to
     * the other. The rows are 1:1 aligned (filler lines), so the absolute scroll offsets match.
     *
     * <p>Only the focused pane drives, which makes the sync strictly one-directional at any moment and so
     * <b>cannot oscillate</b>. (A naïve bidirectional copy fed back: RichTextFX refines {@code estimatedScrollY}
     * as paragraphs are measured, so the follower settled to a slightly different value and pushed the leader
     * back — a feedback loop, worst on a navigation jump into an unmeasured region.) A scroll gesture focuses
     * its pane (see {@code installScrollFocus}), so the other pane follows it. This governs only interactive
     * scrolling — next/prev navigation pins both panes explicitly (see {@link #scrollToRow(int)}).
     */
    private void syncScroll(CodeArea from, CodeArea to) {
        from.estimatedScrollYProperty().addListener((o, ov, nv) -> {
            if (syncing || nv == null || !from.isFocused()) {
                return; // only the focused (actively scrolled) pane drives the other — no feedback loop
            }
            syncing = true;
            try {
                to.estimatedScrollYProperty().setValue(nv);
            } finally {
                syncing = false;
            }
        });
    }

    /** A scroll gesture on a pane focuses it, so it becomes the one that drives the other (see syncScroll). */
    private static void installScrollFocus(CodeArea area) {
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!area.isFocused()) {
                area.requestFocus();
            }
        });
    }

    // --- unified --------------------------------------------------------------------------------

    private void showUnified() {
        if (unifiedNode == null) {
            buildUnified();
        }
        leftPaneWidth.unbind(); // no second pane to align to; the cluster goes back to the right edge
        leftPaneWidth.set(0);
        showComparison(unifiedNode);
    }

    private void showComparison(Node comparison) {
        if (!resultEditing || resultNode == null) {
            root.setCenter(comparison);
            return;
        }
        if (resultSplit == null) {
            resultSplit = new SplitPane(comparison, resultNode);
            resultSplit.setOrientation(Orientation.VERTICAL);
            resultSplit.setDividerPositions(0.62);
            resultSplit.getStyleClass().add("diff-result-split");
        } else {
            double divider = resultSplit.getDividerPositions().length == 0
                    ? 0.62
                    : resultSplit.getDividerPositions()[0];
            resultSplit.getItems().setAll(comparison, resultNode);
            resultSplit.setDividerPositions(divider);
        }
        root.setCenter(resultSplit);
    }

    private void buildUnified() {
        List<DisplayUnified> display = unifiedDisplayRows();
        List<UnifiedRow> rows = display.stream().map(DisplayUnified::row).toList();
        unifiedSourceRows =
                display.stream().mapToInt(DisplayUnified::sourceStart).toArray();
        unifiedArea = readOnlyArea("diff-unified");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(rows.get(i).text());
        }
        String text = sb.toString();
        unifiedArea.replaceText(text);
        List<int[]> wordAbs = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                offset++;
            }
            addAbs(wordAbs, offset, rows.get(i).wordRanges());
            offset += rows.get(i).text().length();
        }
        applyStyle(unifiedArea, text, wordAbs);
        int[] nos = new int[rows.size()];
        String[] signs = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            UnifiedRow r = rows.get(i);
            if (display.get(i).collapsed()) {
                unifiedArea.setParagraphStyle(i, List.of("diff-collapsed"));
                nos[i] = r.rightLine();
                signs[i] = "⋯";
                continue;
            }
            switch (r.type()) {
                case ADD -> {
                    unifiedArea.setParagraphStyle(i, List.of("diff-added"));
                    nos[i] = r.rightLine();
                    signs[i] = "+";
                }
                case REMOVE -> {
                    unifiedArea.setParagraphStyle(i, List.of("diff-removed"));
                    nos[i] = r.leftLine();
                    signs[i] = "-";
                }
                default -> {
                    nos[i] = r.rightLine();
                    signs[i] = " ";
                }
            }
        }
        installUnifiedGutter(unifiedArea, nos, signs);
        unifiedNode = new org.fxmisc.flowless.VirtualizedScrollPane<>(unifiedArea);
    }

    private List<DisplayUnified> unifiedDisplayRows() {
        List<UnifiedRow> rows = model.unified();
        if (!collapseContext || rows.size() <= CONTEXT_LINES * 2 + 2) {
            List<DisplayUnified> out = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                out.add(new DisplayUnified(rows.get(i), i, i + 1, false));
            }
            return out;
        }
        List<DisplayUnified> out = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            if (rows.get(i).type() != com.editora.diff.DiffModels.UnifiedType.CONTEXT) {
                out.add(new DisplayUnified(rows.get(i), i, i + 1, false));
                i++;
                continue;
            }
            int end = i + 1;
            while (end < rows.size() && rows.get(end).type() == com.editora.diff.DiffModels.UnifiedType.CONTEXT) {
                end++;
            }
            int leftKeep = i == 0 ? 0 : Math.min(CONTEXT_LINES, end - i);
            int rightKeep = end == rows.size() ? 0 : Math.min(CONTEXT_LINES, end - i - leftKeep);
            if (end - i <= leftKeep + rightKeep + 1) {
                for (int j = i; j < end; j++) {
                    out.add(new DisplayUnified(rows.get(j), j, j + 1, false));
                }
            } else {
                for (int j = i; j < i + leftKeep; j++) {
                    out.add(new DisplayUnified(rows.get(j), j, j + 1, false));
                }
                int hiddenStart = i + leftKeep;
                int hiddenEnd = end - rightKeep;
                UnifiedRow first = rows.get(hiddenStart);
                out.add(new DisplayUnified(
                        new UnifiedRow(
                                com.editora.diff.DiffModels.UnifiedType.CONTEXT,
                                tr("diff.unchangedLines", hiddenEnd - hiddenStart),
                                first.leftLine(),
                                first.rightLine(),
                                null),
                        hiddenStart,
                        hiddenEnd,
                        true));
                for (int j = hiddenEnd; j < end; j++) {
                    out.add(new DisplayUnified(rows.get(j), j, j + 1, false));
                }
            }
            i = end;
        }
        return out;
    }

    // --- shared rendering -----------------------------------------------------------------------

    private CodeArea readOnlyArea(String extraClass) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("editor-area", "diff-area", extraClass);
        area.setAccessibleText(
                extraClass.equals("diff-left")
                        ? tr("diff.accessibleLeft", headerLeft)
                        : extraClass.equals("diff-right")
                                ? tr("diff.accessibleRight", headerRight)
                                : tr("diff.accessibleUnified"));
        area.setEditable(false);
        area.setFocusTraversable(true);
        area.setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.OFF);
        area.setWrapText(wrapLines);
        area.setStyle(fontStyle);
        return area;
    }

    /** Applies syntax highlighting (if a grammar is known) plus intra-line word emphasis. */
    private void applyStyle(CodeArea area, String text, List<int[]> wordRanges) {
        if (text.isEmpty()) {
            return; // RichTextFX rejects zero-length spans
        }
        StyleSpans<Collection<String>> words = wordRanges.isEmpty() ? null : buildWordSpans(text.length(), wordRanges);
        if (words != null) {
            area.setStyleSpans(0, words);
        }
        if (grammar == null) {
            return;
        }
        long generation = highlightGeneration.get();
        HIGHLIGHT_EXECUTOR.submit(() -> {
            StyleSpans<Collection<String>> base;
            try {
                base = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                return;
            }
            StyleSpans<Collection<String>> combined = words == null ? base : base.overlay(words, DiffViewerPane::union);
            Platform.runLater(() -> {
                if (generation == highlightGeneration.get() && text.equals(area.getText())) {
                    area.setStyleSpans(0, combined);
                }
            });
        });
    }

    private static Collection<String> union(Collection<String> a, Collection<String> b) {
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        Set<String> s = new HashSet<>(a);
        s.addAll(b);
        return s;
    }

    /** A full-length {@link StyleSpans} marking {@code ranges} (sorted, non-overlapping) with "diff-word". */
    private static StyleSpans<Collection<String>> buildWordSpans(int len, List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort((x, y) -> Integer.compare(x[0], y[0]));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int pos = 0;
        for (int[] r : sorted) {
            int start = Math.max(pos, Math.min(r[0], len));
            int end = Math.min(r[1], len);
            if (start > pos) {
                b.add(NONE, start - pos);
            }
            if (end > start) {
                b.add(WORD, end - start);
                pos = end;
            }
        }
        if (pos < len) {
            b.add(NONE, len - pos);
        }
        return b.create();
    }

    /** Converts a row's per-line word ranges to absolute offsets within the assembled document. */
    private static void addAbs(List<int[]> out, int lineOffset, int[][] ranges) {
        if (ranges == null) {
            return;
        }
        for (int[] r : ranges) {
            out.add(new int[] {lineOffset + r[0], lineOffset + r[1]});
        }
    }

    private static List<String> leftLineClasses(RowType t) {
        return switch (t) {
            case REMOVED -> List.of("diff-removed");
            case MODIFIED -> List.of("diff-modified");
            case ADDED -> List.of("diff-filler"); // left side is filler for an added line
            default -> List.of();
        };
    }

    private static List<String> rightLineClasses(RowType t) {
        return switch (t) {
            case ADDED -> List.of("diff-added");
            case MODIFIED -> List.of("diff-modified");
            case REMOVED -> List.of("diff-filler");
            default -> List.of();
        };
    }

    /** A right-aligned original-line-number gutter; filler lines (-1) show blank. When {@code showApply}
     *  is true, each change block's first row also gets an "apply change" chevron that copies that hunk
     *  into the editable side. The right pane owns this action gutter so it remains at the center seam. */
    private void installGutter(CodeArea area, int[] lineNos, int[] sourceRows, boolean showApply) {
        boolean apply = showApply && editableSide != EditableSide.NONE;
        List<Row> rows = model.rows();
        boolean right = editableSide == EditableSide.RIGHT;
        Set<Integer> blockStarts = apply ? new HashSet<>(model.changeBlockStarts()) : Set.of();
        // Always render a gutter when apply arrows are needed, even if line numbers are off.
        if (!showLineNumbers && !apply) {
            return;
        }
        int width = Math.max(2, String.valueOf(maxOf(lineNos)).length());
        double numW = width * 9.0 + 12;
        IntFunction<Node> factory = i -> {
            Label num = new Label();
            num.getStyleClass().add("diff-lineno");
            if (showLineNumbers) {
                int no = i < lineNos.length ? lineNos[i] : -1;
                num.setText(no < 0 ? "" : String.valueOf(no));
                num.setMinWidth(numW);
                num.setPrefWidth(numW);
            }
            num.setAlignment(Pos.CENTER_RIGHT);
            int sourceRow = i < sourceRows.length ? sourceRows[i] : -1;
            Row source = sourceRow >= 0 && sourceRow < rows.size() ? rows.get(sourceRow) : null;
            String symbol = source == null || source.type() == RowType.EQUAL
                    ? ""
                    : area == leftArea ? (source.left() == null ? "" : "−") : (source.right() == null ? "" : "+");
            Label changeSign = new Label(symbol);
            changeSign.getStyleClass().add("diff-change-sign");
            changeSign.setMinWidth(12);
            changeSign.setAccessibleText(
                    symbol.isEmpty() ? "" : tr(area == leftArea ? "diff.accessibleRemoved" : "diff.accessibleAdded"));
            HBox gutter;
            if (!apply) {
                gutter = new HBox(changeSign, num);
            } else {
                // Hunk apply (double chevron, at each change block's first row) + per-line apply (single
                // chevron, on every changed row). Both copy the other side's content into the local file.
                HBox hunkSlot = arrowSlot(
                        blockStarts.contains(sourceRow)
                                ? (right ? Icons.doubleChevronRight() : Icons.doubleChevronLeft())
                                : null,
                        tr("diff.applyChange"),
                        () -> applyBlock(sourceRow));
                HBox lineSlot = arrowSlot(
                        sourceRow >= 0
                                        && sourceRow < rows.size()
                                        && rows.get(sourceRow).type() != RowType.EQUAL
                                ? (right ? Icons.chevronRight() : Icons.chevronLeft())
                                : null,
                        tr("diff.applyLine"),
                        () -> applyRow(sourceRow));
                gutter = new HBox(hunkSlot, lineSlot, changeSign, num);
            }
            // The "lineno" class gives the gutter the editor's opaque (theme-aware) background, so text
            // scrolled horizontally never bleeds under the line numbers.
            gutter.getStyleClass().add("lineno");
            gutter.setAlignment(Pos.CENTER_LEFT);
            gutter.setMaxHeight(Double.MAX_VALUE);
            return gutter;
        };
        area.setParagraphGraphicFactory(factory);
    }

    /** A fixed-width gutter cell holding {@code icon} (clickable, with {@code tip}) or empty for alignment. */
    private HBox arrowSlot(Node icon, String tip, Runnable onClick) {
        HBox slot = new HBox();
        slot.setMinWidth(16);
        slot.setPrefWidth(16);
        slot.setMaxWidth(16);
        slot.setAlignment(Pos.CENTER);
        if (icon != null) {
            slot.getStyleClass().add("diff-apply");
            slot.setAccessibleRole(AccessibleRole.BUTTON);
            slot.setAccessibleText(tip);
            slot.setFocusTraversable(true);
            Tooltip.install(slot, new Tooltip(tip));
            slot.setOnMouseClicked(e -> {
                onClick.run();
                e.consume();
            });
            slot.setOnKeyPressed(e -> {
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                        || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    onClick.run();
                    e.consume();
                }
            });
            slot.getChildren().add(icon);
        }
        return slot;
    }

    /** Whole-hunk apply: replaces the editable side's contiguous change block at {@code start} with the
     *  other side's content. */
    private void applyBlock(int start) {
        deliverApply(computeApplied(start, blockEndFrom(start)));
    }

    /** The exclusive end of the contiguous non-equal run starting at {@code start}. */
    private int blockEndFrom(int start) {
        List<Row> rows = model.rows();
        int e = start;
        while (e < rows.size() && rows.get(e).type() != RowType.EQUAL) {
            e++;
        }
        return e;
    }

    /** Per-line apply: replaces the editable side's row {@code i} with the other side's content (insert /
     *  delete / swap), then hands the editable side's new full text to {@link #onApply}. */
    private void applyRow(int i) {
        deliverApply(computeApplied(i, i + 1));
    }

    /** "Apply all": makes the editable file identical to the other side (its exact fetched text). Since
     *  this replaces the whole local file at once, it asks for confirmation first. */
    private void applyAll() {
        String otherText = editableSide == EditableSide.RIGHT ? leftText : rightText;
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("diff.applyAll.confirm"),
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        confirm.setTitle(tr("diff.applyAll.confirmTitle"));
        confirm.setHeaderText(null);
        if (root.getScene() != null && root.getScene().getWindow() != null) {
            confirm.initOwner(root.getScene().getWindow());
        }
        if (confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                == javafx.scene.control.ButtonType.OK) {
            deliverApply(otherText);
        }
    }

    private void deliverApply(String text) {
        if (onApply.test(text)) {
            unappliedUndoDepth++;
            updateEditButtons();
        }
    }

    private void updateEditButtons() {
        undoButton.setDisable(unappliedUndoDepth <= 0);
        saveButton.setDisable(unappliedUndoDepth <= 0);
        applyAllButton.setDisable(resultEditing);
    }

    /** The editable side's full text after taking the other side's content for rows in {@code [start,end)}
     *  and keeping the editable side's content elsewhere (filler = no line). */
    private String computeApplied(int start, int end) {
        return computeAppliedFor(editableSide, start, end);
    }

    private String computeAppliedFor(EditableSide side, int start, int end) {
        boolean rightEditable = side == EditableSide.RIGHT;
        List<Row> rows = model.rows();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean inBlock = i >= start && i < end;
            String text = inBlock == rightEditable ? r.left() : r.right();
            // inBlock → other side; outside → editable side. With rightEditable:
            //   inBlock  → left (other);  outside → right (editable)
            //   !rightEditable: inBlock → right (other); outside → left (editable)
            if (text != null) {
                out.add(text);
            }
        }
        String editableText = rightEditable ? rightText : leftText;
        return DiffText.parse(editableText).compose(out);
    }

    private void installUnifiedGutter(CodeArea area, int[] lineNos, String[] signs) {
        int width = Math.max(2, String.valueOf(maxOf(lineNos)).length());
        IntFunction<Node> factory = i -> {
            int no = i < lineNos.length ? lineNos[i] : -1;
            Label num = new Label(no < 0 ? "" : String.valueOf(no));
            num.getStyleClass().add("diff-lineno");
            num.setMinWidth(width * 9.0 + 8);
            num.setPrefWidth(width * 9.0 + 8);
            num.setAlignment(Pos.CENTER_RIGHT);
            Label sign = new Label(i < signs.length ? signs[i] : " ");
            sign.getStyleClass().add("diff-sign");
            sign.setMinWidth(14);
            sign.setAlignment(Pos.CENTER);
            HBox box = new HBox(num, sign);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        };
        area.setParagraphGraphicFactory(factory);
    }

    private static int maxOf(int[] arr) {
        int m = 1;
        for (int v : arr) {
            m = Math.max(m, v);
        }
        return m;
    }

    // --- navigation -----------------------------------------------------------------------------

    private void nextChange() {
        List<Integer> starts = model.changeBlockStarts();
        if (starts.isEmpty()) {
            return;
        }
        changeCursor = Math.min(changeCursor + 1, starts.size() - 1);
        scrollToRow(starts.get(changeCursor));
        updateChangeNav();
    }

    private void prevChange() {
        List<Integer> starts = model.changeBlockStarts();
        if (starts.isEmpty()) {
            return;
        }
        changeCursor = changeCursor <= 0 ? 0 : changeCursor - 1;
        scrollToRow(starts.get(changeCursor));
        updateChangeNav();
    }

    /** The change-count indicator before the prev/next arrows: the total at rest, "{n} of {total}"
     *  once the user starts stepping through changes. */
    private void updateChangeNav() {
        int total = model.changeBlockStarts().size();
        if (changeCursor >= 0) {
            changeNav.setText(tr("diff.changePos", changeCursor + 1, total));
        } else {
            changeNav.setText(tr(total == 1 ? "diff.changeCount.one" : "diff.changeCount", total));
        }
        if (connectorCanvas != null) {
            connectorCanvas.setSelectedBand(changeCursor);
        }
    }

    /** Refreshes the toolbar's +added/−removed summary and the change-count indicator from the model. */
    private void updateSummary() {
        String text = tr("diff.summary", model.added(), model.removed());
        if (model.finalNewlineDiffers()) {
            text += "  ·  " + tr("diff.finalNewlineDiffers");
        }
        if (model.quality() != com.editora.diff.DiffModels.Quality.FULL) {
            text += "  ·  "
                    + tr(
                            model.quality() == com.editora.diff.DiffModels.Quality.LINE_ONLY
                                    ? "diff.simplified"
                                    : "diff.metadataOnly");
        }
        summary.setText(text);
        updateChangeNav();
    }

    /** Scrolls to, and selects, the change block starting at side-by-side row {@code sideRow}, so the
     *  user sees which change the nav advanced to. */
    private void scrollToRow(int sideRow) {
        int sideEnd = blockEndFrom(sideRow);
        if (unified && unifiedArea != null) {
            int u = displayUnifiedRowForSource(unifiedRowFor(sideRow));
            int displayEnd = displayUnifiedRowForSource(unifiedRowFor(sideEnd));
            int top = Math.max(0, u);
            selectLines(unifiedArea, u, displayEnd);
            // Pin the row to the top AFTER the selection (selectRange schedules a caret-follow scroll that
            // would otherwise leave the block bottom-aligned). One pulse later runs after that follow.
            Platform.runLater(() -> unifiedArea.showParagraphAtTop(top));
        } else if (leftArea != null && rightArea != null) {
            int displayStart = displaySideRowForSource(sideRow);
            int displayEnd = displaySideRowForSource(sideEnd);
            int top = Math.max(0, displayStart);
            // Highlight the block on both panes (caret at the block top — see selectLines). The rows are 1:1
            // aligned (filler lines), so navigation pins BOTH panes to the same top row explicitly, with the
            // scroll sync suppressed. Relying on the focus-gated sync listener to align the follower fails on a
            // backward jump: selectRange's caret-follow may already have left the driven pane at `top`, so its
            // estimatedScrollY never changes, the listener never fires, and the follower is stranded at its own
            // caret-follow position. Setting both deterministically can't desync.
            selectLines(leftArea, displayStart, displayEnd);
            selectLines(rightArea, displayStart, displayEnd);
            Platform.runLater(() -> {
                syncing = true;
                try {
                    leftArea.showParagraphAtTop(top);
                    rightArea.showParagraphAtTop(top);
                } finally {
                    syncing = false;
                }
            });
        }
    }

    private int displaySideRowForSource(int source) {
        for (int i = 0; i < sideSourceRows.length; i++) {
            if (sideSourceRows[i] >= source) {
                return i;
            }
        }
        return Math.max(0, sideSourceRows.length - 1);
    }

    private int displayUnifiedRowForSource(int source) {
        for (int i = 0; i < unifiedSourceRows.length; i++) {
            if (unifiedSourceRows[i] >= source) {
                return i;
            }
        }
        return Math.max(0, unifiedSourceRows.length - 1);
    }

    /** Selects whole lines {@code [start, end)} in {@code area} (clamped), as a visible block highlight. */
    private static void selectLines(CodeArea area, int start, int end) {
        int pars = area.getParagraphs().size();
        if (pars == 0) {
            return;
        }
        int s = Math.max(0, Math.min(start, pars - 1));
        int e = Math.max(s + 1, Math.min(end, pars));
        // Anchor at the block end, caret at the block START, so RichTextFX's caret-follow scroll targets the
        // top of the block — agreeing with the explicit showParagraphAtTop instead of pulling to the bottom.
        area.selectRange(e - 1, area.getParagraph(e - 1).length(), s, 0);
    }

    /** Maps a side-by-side row index to the first unified row at/after it (unified expands MODIFIED). */
    private int unifiedRowFor(int sideRow) {
        List<Row> rows = model.rows();
        int u = 0;
        for (int i = 0; i < sideRow && i < rows.size(); i++) {
            u += rows.get(i).type() == RowType.MODIFIED ? 2 : 1;
        }
        return u;
    }

    // --- TabContent -----------------------------------------------------------------------------

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
}
