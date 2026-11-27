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
import javafx.scene.control.SeparatorMenuItem;
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
import com.editora.editor.ControlNames;
import com.editora.editor.LazyTooltip;
import com.editora.editor.MenuChords;
import com.editora.editor.PressableBox;
import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.jspecify.annotations.Nullable;

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
    private static volatile boolean rememberedCollapseContext = true;
    private static volatile boolean rememberedWrapLines;
    private static final ExecutorService HIGHLIGHT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diff-highlight");
        t.setDaemon(true);
        return t;
    });

    private final String title;
    private @Nullable String leftName;
    private @Nullable String rightName;
    private String headerLeft;
    private String headerRight;
    private String leftText;
    private String rightText;
    private DiffModel model;
    /**
     * The name the syntax grammar is looked up by, when one is bundled for it. The grammar itself is loaded on the
     * highlight executor (PERF-01, Pauta 6.2.0: a cold grammar is read, parsed and compiled), never here.
     */
    private final @Nullable String grammarName;

    private String fontStyle; // mutable so text zoom can resize the diff areas live (#533)
    private final boolean showLineNumbers;
    private java.util.function.Consumer<PatchRequest> onExportPatch = p -> {};
    private SideFormat leftFormat = SideFormat.DEFAULT;
    private SideFormat rightFormat = SideFormat.DEFAULT;

    /**
     * How a side's source bytes spell what the viewer holds as bare-{@code \n} text: its line ending (a
     * {@link com.editora.editops.LineEndings} label) and charset name. Only an exported patch needs them — it
     * has to match the file's bytes to apply.
     */
    public record SideFormat(String lineEnding, String charset) {
        public static final SideFormat DEFAULT =
                new SideFormat(com.editora.editops.LineEndings.LF, com.editora.editorconfig.EditorConfigCharset.UTF_8);

        public SideFormat {
            lineEnding = lineEnding == null ? com.editora.editops.LineEndings.LF : lineEnding;
            charset = charset == null ? com.editora.editorconfig.EditorConfigCharset.UTF_8 : charset;
        }
    }

    /** What "export patch" asks for: both displayed sides with their labels and source formats. */
    public record PatchRequest(
            String leftLabel,
            String rightLabel,
            String leftText,
            String rightText,
            SideFormat leftFormat,
            SideFormat rightFormat) {}

    private java.util.function.Consumer<DiffEngine.DiffOptions> onOptionsChanged = o -> {};
    /** Re-fetches both sides + re-renders if changed (set by the controller); the file-on-disk refresh. */
    private Runnable refresher = () -> {};

    private boolean refreshPending;
    private boolean awaitingShown;

    /** Which side is the editable/local file that "apply change" writes into (NONE = read-only diff). */
    public enum EditableSide {
        NONE,
        LEFT,
        RIGHT
    }

    private EditableSide editableSide = EditableSide.NONE;

    /**
     * What a diff's local file side offers for review comments ({@code ReviewCoordinator}): which of the file's lines
     * carry a thread, marked in that side's gutter, and what a click on a mark does.
     */
    public interface ReviewHook {
        /** The 0-based lines of the local file after which an open review thread ends. */
        java.util.Set<Integer> commentedLines();

        /** A click on the mark of 0-based line {@code line}. */
        void open(int line);
    }

    private @Nullable ReviewHook reviewHook;

    /** The side-by-side panes' source line numbers (1-based, -1 for a filler row), by displayed row. */
    private int[] leftLineNos = new int[0];

    private int[] rightLineNos = new int[0];
    /** Receives the editable side's full new text and reports after the controller has applied it. */
    private java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> onApply =
            (text, done) -> done.accept(false);

    private final BorderPane root = new BorderPane();
    private final Label summary = new Label();
    private final Label changeNav = new Label();
    private final Button toggleButton = new Button();
    /** What {@link #toggleButton} says now ("Unified view" / "Side-by-side view"); see {@link #buildToolbar}. */
    private final String[] toggleName = {""};

    private final Button swapButton = new Button();
    private final Button exportButton;
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
    private boolean applyPending;
    private int unappliedUndoDepth;
    /** Whether the buffer holds changes made from this pane (an apply, or an undo of one) since its last Save. */
    private boolean unsavedApplies;

    private java.util.function.BooleanSupplier undoAvailable = () -> true;

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
            GitHunkAction action,
            int startRow,
            int endRow,
            String beforeText,
            String afterText,
            int targetLine,
            boolean wholeFile) {}

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
    private javafx.scene.layout.@Nullable Region leftPaneBox;

    private int changeCursor = -1; // index into model.changeBlockStarts for prev/next nav

    // Side-by-side areas (built once, lazily).
    private @Nullable CodeArea leftArea;
    private @Nullable CodeArea rightArea;
    private @Nullable Node sideBySideNode;
    private javafx.scene.control.@Nullable SplitPane sideSplit;
    private @Nullable DiffConnectorCanvas connectorCanvas;
    private @Nullable CodeArea unifiedArea;
    private @Nullable Node unifiedNode;
    private @Nullable CodeArea resultArea;
    private @Nullable Node resultNode;
    private @Nullable SplitPane resultSplit;
    private final PauseTransition resultDiffDelay = new PauseTransition(Duration.millis(250));
    private boolean resultEditing;
    private boolean resultDirty;
    /** False when either displayed side is a binary/summary surrogate rather than exact source text. */
    private boolean mutationAllowed = true;

    private boolean updatingResult;
    private @Nullable String resultBaselineText;

    /** Counts the Result editor's edits (FX thread). */
    private long resultEdits;

    private boolean syncing; // re-entrancy guard for scroll sync
    /** The side-by-side pane the user is scrolling; only it drives the other (see {@link #syncScroll}). */
    private @Nullable CodeArea scrollLeader;

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

    /** The layouts of {@link #layoutsModel}, the model they were taken for. */
    private com.editora.diff.DiffLayout.@Nullable Layouts layouts;

    private @Nullable DiffModel layoutsModel;

    public DiffViewerPane(
            String title,
            @Nullable String headerLeft,
            @Nullable String headerRight,
            @Nullable String leftName,
            @Nullable String rightName,
            String leftText,
            String rightText,
            DiffModel model,
            String fontFamily,
            int fontSize,
            boolean showLineNumbers,
            @Nullable String grammarPath) {
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
        String g = grammarFor(grammarPath != null && !grammarPath.isBlank() ? grammarPath : rightName);
        this.grammarName = g != null ? g : grammarFor(leftName);

        root.getStyleClass().add("diff-viewer");
        root.setAccessibleRole(AccessibleRole.PARENT);
        root.setAccessibleText(tr("diff.accessibleViewer", this.headerLeft, this.headerRight));
        exportButton = CommandTips.name(
                iconButton(Icons.saveAs(), CommandTips.title("diff.exportPatch"), this::exportPatch),
                "diff.exportPatch",
                () -> CommandTips.title("diff.exportPatch"),
                () -> tr("diff.tooltip.exportPatch"));
        root.setTop(buildToolbar());
        resultDiffDelay.setOnFinished(e -> {
            if (resultEditing && resultArea != null) {
                String text = resultArea.getText();
                resultDirty = !java.util.Objects.equals(resultBaselineText, text);
                updateResultControls();
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

    public void setOnExportPatch(java.util.function.Consumer<PatchRequest> onExportPatch) {
        this.onExportPatch = onExportPatch == null ? p -> {} : onExportPatch;
    }

    /** Records the source format of the sides as displayed now (after any swap); see {@link SideFormat}. */
    public void setSideFormats(SideFormat left, SideFormat right) {
        leftFormat = left == null ? SideFormat.DEFAULT : left;
        rightFormat = right == null ? SideFormat.DEFAULT : right;
    }

    /** The current export request: the displayed sides, their {@code a/}/{@code b/} labels and formats. */
    public PatchRequest patchRequest() {
        return new PatchRequest("a/" + leftName, "b/" + rightName, leftText, rightText, leftFormat, rightFormat);
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
    public void setExitDiffUiAction(@Nullable Runnable action) {
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
        setEditableAsync(side, (text, done) -> done.accept(onApply != null && onApply.test(text)), onUndo, onSave);
    }

    public void setEditableAsync(
            EditableSide side,
            java.util.function.BiConsumer<String, java.util.function.Consumer<Boolean>> onApply,
            Runnable onUndo,
            Runnable onSave) {
        this.editableSide = side == null ? EditableSide.NONE : side;
        this.onApply = onApply == null ? (text, done) -> done.accept(false) : onApply;
        this.onUndo = onUndo == null ? () -> {} : onUndo;
        this.onSave = onSave == null ? () -> {} : onSave;
        boolean editable = canMutate();
        updateApplyAllButton();
        for (Button b : new Button[] {applyAllButton, undoButton, saveButton}) {
            b.setVisible(editable);
            b.setManaged(editable);
        }
        editResultButton.setVisible(editable && resultEditingEnabled);
        editResultButton.setManaged(editable && resultEditingEnabled);
        if (!editable && !hasDirtyResult()) {
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

    /** Lets the Undo button check that the target still has something to undo before it counts one. */
    public void setUndoAvailable(java.util.function.BooleanSupplier available) {
        undoAvailable = available == null ? () -> true : available;
    }

    /** Enables document/index mutation only while both fetched sides are exact source text. */
    public void setMutationAllowed(boolean allowed) {
        if (mutationAllowed == allowed) {
            return;
        }
        mutationAllowed = allowed;
        // A binary or oversized side is shown as a one-line description; a patch of that is not a patch.
        exportButton.setDisable(!allowed);
        boolean editable = canMutate();
        for (Button b : new Button[] {applyAllButton, undoButton, saveButton}) {
            b.setVisible(editable);
            b.setManaged(editable);
        }
        editResultButton.setVisible(editable && resultEditingEnabled);
        editResultButton.setManaged(editable && resultEditingEnabled);
        if (!editable && !hasDirtyResult()) {
            closeResultEditor();
        }
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

    private boolean canMutate() {
        return mutationAllowed
                && editableSide != EditableSide.NONE
                && model.quality() != com.editora.diff.DiffModels.Quality.METADATA_ONLY;
    }

    /**
     * Re-fetches both sides and re-renders if they changed (no-op when content is identical). A pane nobody
     * can see — an unselected tab, a file of a review that is not the one shown — only notes that it is out
     * of date and catches up when it is shown: every focus regain, file-watcher batch and apply refreshes
     * every open diff, and a review of 200 files used to answer each with 200 blob reads.
     */
    public void refresh() {
        if (!isShown()) {
            refreshPending = true;
            awaitShown();
            return;
        }
        refreshPending = false;
        refresher.run();
    }

    /** Whether a refresh was skipped while the pane was hidden and has not run yet. */
    public boolean isRefreshPending() {
        return refreshPending;
    }

    private boolean isShown() {
        if (root.getScene() == null) {
            return false;
        }
        for (Node node = root; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Runs the pending refresh once whatever hides the pane (no scene, an invisible ancestor) changes. */
    private void awaitShown() {
        if (awaitingShown) {
            return;
        }
        javafx.beans.value.ObservableValue<?> gate = root.sceneProperty();
        if (root.getScene() != null) {
            for (Node node = root; node != null; node = node.getParent()) {
                if (!node.isVisible()) {
                    gate = node.visibleProperty();
                    break;
                }
            }
        }
        awaitingShown = true;
        javafx.beans.value.ObservableValue<?> watched = gate;
        watched.addListener(new javafx.beans.InvalidationListener() {
            @Override
            public void invalidated(javafx.beans.Observable changed) {
                watched.removeListener(this);
                // Later: a node is attached to its parent before the scene reaches it, and a tab's content
                // turns visible before the selection change has finished.
                Platform.runLater(() -> {
                    awaitingShown = false;
                    if (refreshPending) {
                        refresh();
                    }
                });
            }
        });
    }

    /** Whether the displayed content already equals {@code l}/{@code r} (so a refresh can skip a rebuild,
     *  keeping the current view + scroll position). */
    public boolean matches(String l, String r) {
        return java.util.Objects.equals(leftText, l) && java.util.Objects.equals(rightText, r);
    }

    public boolean matchesEditableText(String text) {
        return java.util.Objects.equals(editableBaselineText(), text);
    }

    /**
     * The exact editable-side text every apply this pane delivers was computed from: the displayed side's
     * text, or the Result draft's baseline while the Result editor is open. A hunk or line apply rebuilds the
     * whole document from the displayed rows, so it is only valid while the local file still equals this —
     * which is what the controller must compare against, not its own record of the last text it wrote (that
     * runs ahead of the pane until the re-diff lands).
     */
    public @Nullable String editableBaselineText() {
        return resultEditing && resultBaselineText != null
                ? resultBaselineText
                : editableSide == EditableSide.RIGHT ? rightText : leftText;
    }

    public boolean hasDirtyResult() {
        return resultEditing && resultDirty && draftDiffers();
    }

    public EditableSide editableSide() {
        return editableSide;
    }

    public boolean hasResultEditor() {
        return resultEditing && resultArea != null;
    }

    public @Nullable String resultText() {
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
        SideFormat oldLeftFormat = leftFormat;
        leftFormat = rightFormat;
        rightFormat = oldLeftFormat;
        String oldLeftHeader = headerLeft;
        headerLeft = headerRight;
        headerRight = oldLeftHeader;
        editableSide = switch (editableSide) {
            case LEFT -> EditableSide.RIGHT;
            case RIGHT -> EditableSide.LEFT;
            case NONE -> EditableSide.NONE;
        };
        updateApplyAllButton();
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

    /** {@code name} when a grammar is bundled for it (a name-table lookup: nothing is loaded), else null. */
    private static @Nullable String grammarFor(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return GrammarRegistry.shared().hasGrammarFor(name) ? name : null;
    }

    /** The grammar for {@link #grammarName}, loaded the first time: on the highlight executor only. */
    private @Nullable IGrammar loadGrammar() {
        String name = grammarName;
        if (name == null) {
            return null;
        }
        try {
            return GrammarRegistry.shared().forFileName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The commands behind the bar's option buttons, the result editor's buttons and the gutter's apply chevrons
     * (KBD-01 in Pauta 6.2.0): each presses its button, or applies the current change or the caret's line as a
     * chevron applies the change or line it sits on, in the diff the keyboard is in or the active tab shows.
     */
    private void bindCommands() {
        FocusCommands.press(root, "diff.cycleWhitespace", whitespaceButton);
        FocusCommands.press(root, "diff.toggleWordHighlight", wordButton);
        FocusCommands.press(root, "diff.toggleCollapseContext", contextButton);
        FocusCommands.press(root, "diff.toggleWrapLines", wrapButton);
        FocusCommands.press(root, "diff.applyFinalNewline", applyEofButton);
        // The result editor's buttons, while it is open (its buttons stay visible inside it when it is closed).
        FocusCommands.bindIf(root, "diff.applyResult", whileEditing(applyResultButton));
        FocusCommands.bindIf(root, "diff.resetResult", whileEditing(resetResultButton));
        FocusCommands.bindIf(root, "diff.applyChange", () -> {
            int start = currentBlockStart();
            if (!canMutate() || resultEditing || start < 0) {
                return false;
            }
            applyBlock(start);
            return true;
        });
        FocusCommands.bindIf(root, "diff.applyLine", () -> {
            int row = caretSourceRow(); // a changed row, or -1
            if (!canMutate() || resultEditing || row < 0) {
                return false;
            }
            applyRow(row);
            return true;
        });
    }

    /**
     * Fires {@code button} of the result editor as a click would, and answers true; false, doing nothing, while the
     * editor is closed or the button disabled.
     */
    private java.util.function.BooleanSupplier whileEditing(javafx.scene.control.ButtonBase button) {
        return () -> {
            if (!resultEditing || button.isDisabled()) {
                return false;
            }
            button.fire();
            return true;
        };
    }

    private HBox buildToolbar() {
        bindCommands();
        summary.getStyleClass().add("diff-summary");
        changeNav.getStyleClass().add("diff-summary");
        updateSummary();
        // The buttons that run a diff.* command are named after it (title and live chord).
        Button next = commandButton(Icons.arrowDown(), "diff.nextChange", this::nextChange);
        Button prev = commandButton(Icons.arrowUp(), "diff.previousChange", this::prevChange);
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
        MenuChords.tag(ignoreCaseItem, "diff.toggleIgnoreCase");
        smartAlignmentItem.setText(tr("diff.smartAlignment"));
        smartAlignmentItem.setSelected(options.smartAlignment());
        smartAlignmentItem.setOnAction(e -> updateSmartAlignment(smartAlignmentItem.isSelected()));
        MenuChords.tag(smartAlignmentItem, "diff.toggleSmartAlignment");
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
        ControlNames.tip(applyEofButton, tr("diff.applyFinalNewline"));
        applyEofButton.setFocusTraversable(false);
        applyEofButton.getStyleClass().addAll("flat", "diff-option-button");
        applyEofButton.setOnAction(e -> applyFinalNewline());
        updateEofButton();
        // "Apply all": replace the editable file with the other side entirely. Shown only when a side is
        // editable (set by setEditable, which runs after construction), so it starts hidden.
        updateApplyAllButton();
        CommandTips.name(applyAllButton, "diff.applyAll");
        applyAllButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        applyAllButton.setFocusTraversable(false);
        applyAllButton.setOnAction(e -> applyAll());
        applyAllButton.setVisible(false);
        applyAllButton.setManaged(false);
        // Undo / Save the applied changes (shown only when a side is editable).
        editButton(undoButton, Icons.undo(), "diff.undoApplied", this::undoApplied);
        editButton(saveButton, Icons.save(), "diff.saveResult", this::saveResult);
        saveButton.getStyleClass().add("success");
        updateEditButtons();
        editResultButton.setGraphic(Icons.edit());
        CommandTips.name(
                editResultButton,
                "diff.editResult",
                () -> CommandTips.title("diff.editResult"),
                () -> tr("diff.tooltip.editResult"));
        editResultButton.getStyleClass().addAll("flat", "diff-toolbar-button", "diff-option-button");
        editResultButton.setFocusTraversable(false);
        editResultButton.setOnAction(e -> toggleResultEditor());
        editResultButton.setVisible(false);
        editResultButton.setManaged(false);
        toggleButton.setOnAction(e -> toggleView());
        toggleButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        toggleButton.setFocusTraversable(false);
        // Says which view a click switches to, with the chord of the command that switches. The supplier
        // reads a holder, not this pane: CommandTips keeps it for as long as the button lives, and a
        // supplier that reached the pane (and through it the button) would keep every closed diff alive.
        String[] name = toggleName;
        name[0] = toggleName();
        CommandTips.name(toggleButton, "diff.toggleView", () -> CommandTips.title("diff.toggleView"), () -> name[0]);
        updateToggleButton();
        swapButton.setGraphic(Icons.diff());
        CommandTips.name(
                swapButton,
                "diff.swapSides",
                () -> CommandTips.title("diff.swapSides"),
                () -> tr("diff.tooltip.swapSides"));
        swapButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        swapButton.setFocusTraversable(false);
        swapButton.setOnAction(e -> requestSwap());
        swapButton.setVisible(false);
        swapButton.setManaged(false);
        exitDiffUiButton.setGraphic(Icons.editora());
        CommandTips.name(exitDiffUiButton, "diff.exitDiffMode");
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
                                : Math.max(0, leftPaneWidth.get() - summary.getWidth() - UiZoom.px(TOOLBAR_H_PADDING)),
                        leftPaneWidth,
                        summary.widthProperty()));
        HBox bar = UiZoom.spacing(
                new HBox(
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
                        exitDiffUiButton),
                2);
        bar.getStyleClass().add("diff-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(bar, 3, TOOLBAR_H_PADDING, 3, TOOLBAR_H_PADDING);
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
        boolean desired = editableSide == EditableSide.RIGHT ? model.leftFinalNewline() : model.rightFinalNewline();
        applyWorkedOut(
                () -> {
                    DiffText parsed = DiffText.parse(editable);
                    return new DiffText(parsed.lines(), parsed.lineSeparator(), desired).compose(parsed.lines());
                },
                this::deliverApply);
    }

    /** Where an apply's new text is worked out from a side's text: heard off the FX thread (PERF-01). */
    public static final String APPLY_SITE = "DiffViewerPane.applyText";

    /**
     * Works out an apply's text off the FX thread (PERF-01, Pauta 6.2.0), the apply buttons busy meanwhile (a second
     * press does nothing), then hands it to {@code then} unless the diff was computed again meanwhile.
     */
    private void applyWorkedOut(java.util.concurrent.Callable<String> work, java.util.function.Consumer<String> then) {
        if (applyPending) {
            return;
        }
        DiffModel asked = model;
        applyPending = true;
        updateEditButtons();
        com.editora.perf.OffFx.then(APPLY_SITE, work, a -> {
            applyPending = false;
            updateEditButtons();
            String text = a.value();
            if (text != null && model == asked) {
                then.accept(text);
            }
        });
    }

    private void hunkButton(Button button, String label, GitHunkAction action) {
        button.setText(label);
        button.setAccessibleText(label);
        ControlNames.tip(button, label);
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
        DiffEngine.WhitespaceMode next = switch (options.whitespace()) {
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
        if (layingOut) {
            restoreWhenShown = state; // the view is shown once its layout lands: restored then
            return;
        }
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

    /** Configures an editable-only button, named after its command (hidden until {@link #setEditable} shows it). */
    private void editButton(Button b, Node icon, String commandId, Runnable action) {
        b.setGraphic(icon);
        CommandTips.name(b, commandId);
        b.getStyleClass().addAll("flat", "diff-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        b.setVisible(false);
        b.setManaged(false);
    }

    /** Keeps the whole-file transfer action visually consistent with the per-line and per-hunk arrows. */
    private void updateApplyAllButton() {
        applyAllButton.setGraphic(
                editableSide == EditableSide.LEFT ? Icons.doubleChevronLeft() : Icons.doubleChevronRight());
    }

    private void updateToggleButton() {
        toggleButton.setGraphic(unified ? Icons.splitVertical() : Icons.previewOnly());
        toggleName[0] = toggleName();
        CommandTips.refresh(toggleButton); // the name follows the view (a no-op until the button is named)
    }

    private String toggleName() {
        return unified ? tr("diff.tooltip.viewSideBySide") : tr("diff.tooltip.viewUnified");
    }

    /** An icon button of the diff bar: one name for its tooltip and for a screen reader. */
    private Button iconButton(Node icon, String tip, Runnable action) {
        return Icons.toolbarButton(icon, tip, action, "flat", "diff-toolbar-button");
    }

    /** An icon button of the diff bar that runs {@code commandId}: named after it, with its live chord. */
    private Button commandButton(Node icon, String commandId, Runnable action) {
        return CommandTips.name(iconButton(icon, CommandTips.title(commandId), action), commandId);
    }

    private static Tooltip descriptiveTooltip(String text) {
        Tooltip tooltip = ControlNames.tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(UiZoom.px(360));
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
        if (canMutate() && !resultEditing) {
            applyAll(); // no-op on a read-only diff (no editable side / onApply)
        }
    }

    public void toggleResultEditing() {
        if (resultEditingEnabled && canMutate()) {
            editResultButton.fire();
        }
    }

    public void swapComparisonSides() {
        if (swapEnabled) {
            swapButton.fire();
        }
    }

    /** Takes back the last change applied here (the Undo button, {@code diff.undoApplied}), while it is offered. */
    void undoApplied() {
        if (!undoButton.isVisible() || unappliedUndoDepth <= 0) {
            return;
        }
        if (!undoAvailable.getAsBoolean()) {
            // The buffer was closed or its history is gone: there is nothing left to take back.
            unappliedUndoDepth = 0;
            updateEditButtons();
            return;
        }
        onUndo.run();
        unappliedUndoDepth--;
        // Undoing changes the buffer again: after a Save it is unsaved once more.
        unsavedApplies = true;
        updateEditButtons();
    }

    /** Saves the changes applied here (the Save button, {@code diff.saveResult}), while some are unsaved. */
    void saveResult() {
        if (saveButton.isVisible() && !saveButton.isDisabled()) {
            onSave.run();
            unsavedApplies = false;
            updateEditButtons();
        }
    }

    /** Exports the comparison as a .patch (the Export button, {@code diff.exportPatch}), while it is offered. */
    void exportPatch() {
        if (!exportButton.isDisabled()) {
            onExportPatch.accept(patchRequest());
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

    /**
     * Whether the draft differs from its baseline, looked at now for a command (an edit only marks it dirty: typing
     * back to the baseline is noticed when typing pauses, or here); the flag is set to the answer.
     */
    private boolean draftDiffers() {
        resultDirty = resultArea != null && !java.util.Objects.equals(resultBaselineText, resultArea.getText());
        updateResultControls();
        return resultDirty;
    }

    private void toggleResultEditor() {
        if (editResultButton.isSelected()) {
            openResultEditor();
        } else if (resultDirty && draftDiffers()) {
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
        CodeArea area = resultArea != null ? resultArea : buildResultEditor();
        setResultText(resultBaselineText, true);
        editResultButton.setSelected(true);
        updateResultControls();
        rebuildCurrentView();
        Platform.runLater(area::requestFocus);
    }

    private void closeResultEditor() {
        boolean wasEditing = resultEditing;
        String baseline = resultBaselineText;
        resultDiffDelay.stop();
        resultHighlightGeneration.incrementAndGet();
        resultEditing = false;
        resultDirty = false;
        resultBaselineText = null;
        editResultButton.setSelected(false);
        resultSplit = null;
        updateResultControls();
        if (!wasEditing) {
            if (unified) {
                showUnified();
            } else {
                showSideBySide();
            }
            return;
        }
        // The comparison was built for edit mode, without apply chevrons: build it again for the closed state.
        rebuildCurrentView();
        // A draft comparison may still be installed — the re-diff that would have put the real text back was
        // debounced or in flight and has just been cancelled. Showing it as the working side made the next
        // apply stale and exported a change that does not exist, so fetch the real sides again.
        if (baseline != null && !baseline.equals(editableSide == EditableSide.RIGHT ? rightText : leftText)) {
            refresh();
        }
    }

    /**
     * Releases the Result editor when the pane's tab or window goes. A focused editable RichTextFX area
     * runs a caret blink timer — a GC root — that only {@code dispose()} stops, so a window closed with the
     * Result editor focused stayed reachable (see docs/gotchas.md). The pane must not be shown again.
     */
    public void dispose() {
        resultDiffDelay.stop();
        highlightGeneration.incrementAndGet();
        resultHighlightGeneration.incrementAndGet();
        if (resultArea != null) {
            resultArea.dispose();
        }
    }

    private CodeArea buildResultEditor() {
        resultArea = AreaUndo.bounded(new CodeArea());
        resultArea.setId("diff-editable-result");
        resultArea.getStyleClass().addAll("editor-area", "diff-result");
        resultArea.setAccessibleText(tr("diff.resultDescription"));
        resultArea.setWrapText(wrapLines);
        resultArea.setStyle(fontStyle);
        // Each edit is counted, not read: a listener on the text joined the whole draft per keystroke (PERF-01). An
        // edit makes the draft dirty at once; whether it is back at the baseline is looked at when typing pauses.
        resultArea.plainTextChanges().subscribe(change -> {
            resultEdits++;
            if (updatingResult) {
                return;
            }
            resultDirty = true;
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
        HBox header = UiZoom.spacing(new HBox(label, spacer(), resetResultButton, applyResultButton), 6);
        header.setAlignment(Pos.CENTER_LEFT);
        var scroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(resultArea);
        VBox box = UiZoom.spacing(new VBox(header, scroll), 4);
        box.getStyleClass().add("diff-result-box");
        UiZoom.padding(box, 6, 8, 8, 8);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        resultNode = box;
        return resultArea;
    }

    private void setResultText(@Nullable String text, boolean baseline) {
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
        if (!resultEditing || !resultDirty || resultArea == null || applyPending || !canMutate()) {
            return;
        }
        String text = resultArea.getText();
        applyPending = true;
        updateResultControls();
        updateEditButtons();
        onApply.accept(text, applied -> {
            applyPending = false;
            if (Boolean.TRUE.equals(applied)) {
                resultBaselineText = text;
                String current = resultArea == null ? text : resultArea.getText();
                resultDirty = !java.util.Objects.equals(current, text);
                unappliedUndoDepth++;
                unsavedApplies = true;
                resultDiffDelay.stop();
                onResultEdited.accept(current);
            }
            updateResultControls();
            updateEditButtons();
        });
    }

    /**
     * Reset draft: the result's text goes back to what it was before the edits. The edits cannot be brought
     * back (the draft's undo history is cleared), so it asks first; a draft with no edits is left as it is.
     */
    private void resetResult() {
        if (!resultEditing || resultArea == null) {
            return;
        }
        if (java.util.Objects.equals(resultArea.getText(), resultBaselineText)) {
            return; // nothing would be lost
        }
        javafx.stage.Window owner =
                root.getScene() == null ? null : root.getScene().getWindow();
        if (!Confirm.ask(owner, resetResultQuestion())) {
            return;
        }
        setResultText(resultBaselineText, true);
        onResultEdited.accept(resultBaselineText);
    }

    /** The question before the draft's edits are discarded. Pure. */
    static Confirm.Question resetResultQuestion() {
        return new Confirm.Question(
                tr("diff.resetResult.confirmTitle"), null, tr("diff.resetResult.confirm"), tr("confirm.action.reset"));
    }

    private void updateResultControls() {
        boolean active = resultEditing;
        applyResultButton.setDisable(!active || !resultDirty || applyPending);
        resetResultButton.setDisable(!active || !resultDirty || applyPending);
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
        if (resultArea == null || text == null || text.isEmpty() || grammarName == null) {
            return;
        }
        long generation = resultHighlightGeneration.incrementAndGet();
        long edits = resultEdits; // the draft as it is now: styles for it are dropped once it is edited
        HIGHLIGHT_EXECUTOR.submit(() -> {
            StyleSpans<Collection<String>> styles;
            IGrammar grammar = loadGrammar();
            if (grammar == null) {
                return;
            }
            try {
                styles = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                return;
            }
            Platform.runLater(() -> {
                if (generation == resultHighlightGeneration.get() && resultArea != null && edits == resultEdits) {
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
            if (!layoutReady(false)) {
                layOutThen(false, this::showSideBySide);
                return;
            }
            buildSideBySide();
        }
        layoutShown();
        // (Re-)bind every time, not once at build: showUnified unbinds it, so a toggle out and back would
        // otherwise leave the toolbar cluster stuck at the window edge. Tracks the divider as it is dragged.
        if (leftPaneBox != null) {
            leftPaneWidth.bind(leftPaneBox.widthProperty());
        }
        showComparison(sideBySideNode);
    }

    private void buildSideBySide() {
        // The rows shown and the panes' texts were worked out where the model was made (PERF-01): only put in here.
        com.editora.diff.DiffLayout.Side layout = layouts().side(collapseContext);
        List<Row> rows = layout.rows();
        sideSourceRows = layout.sourceStarts();
        leftArea = readOnlyArea("diff-left");
        rightArea = readOnlyArea("diff-right");
        String left = layout.left();
        String right = layout.right();
        int[] leftNos = layout.leftNos();
        int[] rightNos = layout.rightNos();
        leftLineNos = leftNos;
        rightLineNos = rightNos;
        List<int[]> leftWordAbs = layout.leftWords();
        List<int[]> rightWordAbs = layout.rightWords();

        leftArea.replaceText(left);
        rightArea.replaceText(right);
        applyStyle(leftArea, left, leftWordAbs);
        applyStyle(rightArea, right, rightWordAbs);
        for (int i = 0; i < rows.size(); i++) {
            if (layout.collapsed()[i]) {
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
        installGutter(rightArea, rightNos, sideSourceRows, canMutate() && !resultEditing);
        installContextMenu(leftArea, sideSourceRows);
        installContextMenu(rightArea, sideSourceRows);
        scrollLeader = null;
        syncScroll(leftArea, rightArea);
        syncScroll(rightArea, leftArea);

        var leftScroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(leftArea);
        var rightScroll = new org.fxmisc.flowless.VirtualizedScrollPane<>(rightArea);
        installScrollLeader(leftArea, leftScroll);
        installScrollLeader(rightArea, rightScroll);
        Label leftHeader = paneHeader(headerLeft);
        Label rightHeader = paneHeader(headerRight);
        javafx.scene.layout.VBox leftBox = new javafx.scene.layout.VBox(leftHeader, leftScroll);
        javafx.scene.layout.VBox rightBox = new javafx.scene.layout.VBox(rightHeader, rightScroll);
        // Reserve a narrow center track without changing SplitPane behavior. The transparent overlay below
        // paints through this gap and slightly into each side, producing JetBrains-style hunk ribbons.
        UiZoom.padding(leftBox, 0, 14, 0, 0);
        UiZoom.padding(rightBox, 0, 0, 0, 14);
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
        connectorCanvas.readThemeFrom(layered.getChildren());
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
            UiZoom.follow(this, this::requestDraw);
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
                graphics.setLineWidth(UiZoom.px(selected ? 1.5 : 1.0));
                drawRibbon(graphics, leftX, rightX, leftBand, rightBand);
            }
            drawOverview(graphics, rightViewport);
        }

        private @Nullable VisibleBand visibleBand(CodeArea area, DiffConnectorModel.Band band, Bounds viewport) {
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
            double x = getWidth() - UiZoom.px(5);
            for (int i = 0; i < bands.size(); i++) {
                DiffConnectorModel.Band band = bands.get(i);
                double y = top
                        + trackHeight
                                * band.startRow()
                                / Math.max(1, right.getParagraphs().size());
                double height = Math.max(
                        UiZoom.px(3),
                        trackHeight
                                * (band.endRow() - band.startRow())
                                / Math.max(1, right.getParagraphs().size()));
                graphics.setFill(bandColor(band.kind(), i == selectedBand).deriveColor(0, 1, 1, 0.88));
                graphics.fillRoundRect(x, y, UiZoom.px(i == selectedBand ? 4 : 3), height, UiZoom.px(2), UiZoom.px(2));
            }
        }

        private Color bandColor(DiffConnectorModel.Kind kind, boolean selected) {
            double opacity = selected ? 0.52 : 0.30;
            // The theme's green for added lines and its accent for changed ones, through probes; removed lines are
            // the neutral, as the diff's rows and the gutter draw them: red is for errors (ICON-18, Pauta 6.2.0).
            com.editora.editor.ThemeProbes theme = probes;
            Color base = switch (kind) {
                case ADDED -> theme == null ? NEUTRAL : theme.get(com.editora.editor.ThemeProbes.GREEN, NEUTRAL);
                case REMOVED -> NEUTRAL;
                case MODIFIED -> theme == null ? NEUTRAL : theme.get(com.editora.editor.ThemeProbes.ACCENT_FG, NEUTRAL);
            };
            return Color.color(base.getRed(), base.getGreen(), base.getBlue(), opacity);
        }

        /** A mid grey that reads on light and dark grounds alike: removed lines, and any colour no theme has given. */
        private static final Color NEUTRAL = Color.gray(0.55);
        /** The theme's colours, once the canvas's container hosts their probes. */
        private com.editora.editor.@Nullable ThemeProbes probes;

        void readThemeFrom(javafx.collections.ObservableList<javafx.scene.Node> host) {
            probes = new com.editora.editor.ThemeProbes(
                    host,
                    this::requestDraw,
                    com.editora.editor.ThemeProbes.GREEN,
                    com.editora.editor.ThemeProbes.ACCENT_FG);
        }

        private record VisibleBand(double top, double bottom) {}
    }

    /**
     * The right-click menu of a side of the comparison: on a changed line, the rows for its change and for the line
     * (the caret goes to the line clicked, as in the editor, so the line commands act where the rows do); then, on
     * any line, Copy and Select All of the side's text, which {@code edit.copy} and {@code edit.selectAll} also do
     * while the keyboard is in it.
     */
    private void installContextMenu(CodeArea area, int[] sourceRows) {
        ConsoleContextMenu.mark(area, () -> ConsoleContextMenu.copySelectionOrAll(area), () -> area.selectAll());
        area.setOnContextMenuRequested(e -> {
            int clicked;
            int offset = -1;
            try {
                offset = area.hit(e.getX(), e.getY()).getInsertionIndex();
                clicked = area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor();
            } catch (RuntimeException ignored) {
                clicked = area.getCurrentParagraph();
            }
            javafx.scene.control.IndexRange selection = area.getSelection();
            if (offset >= 0 && (offset < selection.getStart() || offset > selection.getEnd())) {
                area.moveTo(offset); // a right-click outside the selection puts the caret there
            }
            int displayRow = Math.max(0, Math.min(clicked, sourceRows.length - 1));
            int sourceRow = sourceRows.length == 0 ? -1 : sourceRows[displayRow];
            ContextMenu menu = new ContextMenu();
            if (sourceRow >= 0
                    && sourceRow < model.rows().size()
                    && model.rows().get(sourceRow).type() != RowType.EQUAL) {
                changeCursor = changeBlockIndexContaining(sourceRow);
                updateChangeNav();
                addHunkRows(menu, sourceRow);
                menu.getItems().add(new SeparatorMenuItem());
            }
            menu.getItems().addAll(ConsoleContextMenu.copyRow(area), ConsoleContextMenu.selectAllRow(area));
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** The rows for the change at {@code sourceRow}: stage, unstage and revert it or its line, copy it, open it. */
    private void addHunkRows(ContextMenu menu, int sourceRow) {
        addGitMenuItem(menu, GitHunkAction.STAGE, tr("diff.stageHunk"), "diff.stageHunk", sourceRow, false);
        addGitMenuItem(menu, GitHunkAction.STAGE, tr("diff.stageLine"), "diff.stageLine", sourceRow, true);
        addGitMenuItem(menu, GitHunkAction.UNSTAGE, tr("diff.unstageHunk"), "diff.unstageHunk", sourceRow, false);
        addGitMenuItem(menu, GitHunkAction.UNSTAGE, tr("diff.unstageLine"), "diff.unstageLine", sourceRow, true);
        addGitMenuItem(menu, GitHunkAction.REVERT, tr("diff.revertHunk"), "diff.revertHunk", sourceRow, false);
        addGitMenuItem(menu, GitHunkAction.REVERT, tr("diff.revertLine"), "diff.revertLine", sourceRow, true);
        MenuItem copy = MenuChords.tag(new MenuItem(tr("diff.copyHunk")), "diff.copyHunk");
        copy.setOnAction(a -> copyHunk(sourceRow));
        menu.getItems().add(copy);
        if (gitHunkActions.contains(GitHunkAction.OPEN)) {
            MenuItem open = MenuChords.tag(new MenuItem(tr("diff.openInEditor")), "diff.openLineInEditor");
            open.setOnAction(a -> performGitAction(GitHunkAction.OPEN, sourceRow, true));
            menu.getItems().add(open);
        }
    }

    /**
     * The diff row of the caret's line in the side of the comparison the keyboard is in; -1 when the keyboard is in
     * neither side, or the line is unchanged.
     */
    private int caretSourceRow() {
        CodeArea area = leftArea != null && leftArea.isFocused()
                ? leftArea
                : rightArea != null && rightArea.isFocused() ? rightArea : null;
        if (area == null || sideSourceRows.length == 0) {
            return -1;
        }
        int row = sideSourceRows[Math.max(0, Math.min(area.getCurrentParagraph(), sideSourceRows.length - 1))];
        return row >= 0 && row < model.rows().size() && model.rows().get(row).type() != RowType.EQUAL ? row : -1;
    }

    /**
     * Runs {@code action} on the line at the caret alone, as the Stage / Unstage / Revert Line and Open in Editor
     * rows do on the line clicked ({@code diff.stageLine}…); false when that is not offered here or the caret is on
     * no changed line.
     */
    boolean caretLineAction(GitHunkAction action) {
        int row = caretSourceRow();
        if (row < 0 || !gitHunkActions.contains(action)) {
            return false;
        }
        changeCursor = changeBlockIndexContaining(row);
        updateChangeNav();
        performGitAction(action, row, true);
        return true;
    }

    /**
     * {@code commandId}: the command that does the same to the change the menu was opened on (the whole-hunk
     * rows have one, and show its chord; the single-line rows have none).
     */
    private void addGitMenuItem(
            ContextMenu menu,
            GitHunkAction action,
            String label,
            @Nullable String commandId,
            int sourceRow,
            boolean lineOnly) {
        if (!gitHunkActions.contains(action)) {
            return;
        }
        MenuItem item = new MenuItem(label);
        if (action == GitHunkAction.REVERT) {
            item.getStyleClass().add("danger"); // discards the change: a destructive row reads red
        }
        item.setOnAction(e -> performGitAction(action, sourceRow, lineOnly));
        if (commandId != null) {
            MenuChords.tag(item, commandId);
        }
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
        if (row < 0
                || resultEditing
                || !mutationAllowed
                || model.quality() == com.editora.diff.DiffModels.Quality.METADATA_ONLY) {
            return;
        }
        int start = lineOnly ? row : model.changeBlockStarts().get(changeBlockIndexContaining(row));
        int end = lineOnly ? row + 1 : blockEndFrom(start);
        boolean leftTarget = action == GitHunkAction.STAGE;
        if (sidesSwapped) {
            leftTarget = !leftTarget;
        }
        String before = leftTarget ? leftText : rightText;
        String other = leftTarget ? rightText : leftText;
        // Stage and Unstage rewrite the index side. When the rows are everything that differs, the result is
        // the other side as it stands (its end of file included), which lets the handler stage the file's
        // own bytes or drop the entry; otherwise the end of the result follows the side supplying its last
        // line. Revert edits the local file and keeps the documented local-apply rule.
        boolean indexAction = action == GitHunkAction.STAGE || action == GitHunkAction.UNSTAGE;
        boolean wholeFile = indexAction && com.editora.diff.HunkText.coversEveryChange(model.rows(), start, end);
        if (action == GitHunkAction.OPEN || wholeFile) {
            gitAction(
                    action,
                    row,
                    start,
                    end,
                    leftTarget,
                    wholeFile,
                    before,
                    action == GitHunkAction.OPEN ? before : other);
            return;
        }
        List<Row> rows = model.rows();
        boolean target = leftTarget;
        applyWorkedOut( // the side's text parsed and the hunk applied off the FX thread (PERF-01)
                () -> indexAction
                        ? com.editora.diff.HunkText.apply(
                                rows, start, end, !target, DiffText.parse(before), DiffText.parse(other))
                        : appliedText(rows, !target, before, start, end),
                after -> gitAction(action, row, start, end, target, false, before, after));
    }

    private void gitAction(
            GitHunkAction action,
            int row,
            int start,
            int end,
            boolean leftTarget,
            boolean wholeFile,
            String before,
            String after) {
        Row target = model.rows().get(row);
        int preferredLine = leftTarget ? target.leftLine() : target.rightLine();
        int fallbackLine = leftTarget ? target.rightLine() : target.leftLine();
        int line = preferredLine >= 0 ? preferredLine : fallbackLine;
        if (action == GitHunkAction.REVERT && (leftTarget ? EditableSide.LEFT : EditableSide.RIGHT) == editableSide) {
            // Revert edits the editable local file, so it is an apply like the chevrons': going through the
            // same path counts it for Undo and Save. Handed to the Git handler it changed an (often
            // background) buffer while both buttons stayed disabled and the file on disk kept the hunk.
            deliverApply(after);
            return;
        }
        onGitHunkAction.accept(new GitHunkRequest(action, start, end, before, after, Math.max(1, line), wholeFile));
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

    /** {@link com.editora.perf.BlockingSites} name of a view laid out here because its kept layouts were dropped. */
    static final String LAYOUT_SITE = "DiffViewerPane.layout";

    /** Counts layout asks: a layout landing for an earlier ask (or after a view was shown since) is dropped. */
    private long layoutAsk;
    /** Whether a view waits for its layout, worked out off the FX thread. */
    private boolean layingOut;
    /** The view state to restore once the view waiting for its layout is shown. */
    private @Nullable ViewState restoreWhenShown;

    /** Whether the layout of that view of the model shown is worked out (it normally is, where the model was made). */
    private boolean layoutReady(boolean unifiedView) {
        return layouts().has(unifiedView, collapseContext);
    }

    /**
     * Lays the view out off the FX thread (PERF-01, Pauta 6.2.0) when the model's kept layouts were dropped before
     * this viewer asked (more diffs made since, or a model made elsewhere), then shows it with {@code show}. The view
     * shown until then stays; an answer for another model, view or collapse is dropped.
     */
    private void layOutThen(boolean unifiedView, Runnable show) {
        long ask = ++layoutAsk;
        layingOut = true;
        DiffModel m = model;
        boolean collapse = collapseContext;
        com.editora.diff.DiffLayout.Layouts l = layouts();
        com.editora.perf.OffFx.then(
                LAYOUT_SITE,
                () -> {
                    if (unifiedView) {
                        l.unified(collapse);
                    } else {
                        l.side(collapse);
                    }
                    return Boolean.TRUE;
                },
                a -> {
                    if (ask != layoutAsk) {
                        return; // a later ask, or a view shown since
                    }
                    layingOut = false;
                    ViewState restore = restoreWhenShown;
                    restoreWhenShown = null;
                    if (model == m && collapseContext == collapse && unified == unifiedView) {
                        show.run();
                        if (restore != null) {
                            restoreViewState(restore);
                        }
                    }
                });
    }

    /** A view was shown: a layout still landing for an earlier ask is dropped. */
    private void layoutShown() {
        layoutAsk++;
        layingOut = false;
    }

    /** The layouts of the model shown: worked out where it was made, or (a model made elsewhere) as asked for. */
    private com.editora.diff.DiffLayout.Layouts layouts() {
        com.editora.diff.DiffLayout.Layouts l = layouts;
        if (l == null || layoutsModel != model) {
            l = com.editora.diff.DiffLayout.of(model, n -> tr("diff.unchangedLines", n));
            layouts = l;
            layoutsModel = model;
        }
        return l;
    }

    private Label paneHeader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("diff-pane-header");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /**
     * Keeps the two side-by-side panes aligned: copies the scroll position of the pane the user is
     * scrolling (the <b>leader</b>) to the other. The rows are 1:1 aligned (filler lines), so the absolute
     * scroll offsets match.
     *
     * <p>Only the leader drives, which makes the sync strictly one-directional at any moment and so
     * <b>cannot oscillate</b>. (A naïve bidirectional copy fed back: RichTextFX refines {@code estimatedScrollY}
     * as paragraphs are measured, so the follower settled to a slightly different value and pushed the leader
     * back — a feedback loop, worst on a navigation jump into an unmeasured region.) The leader is chosen by
     * the gesture itself (see {@link #installScrollLeader}), not by keyboard focus: a scroll-bar drag moves
     * no focus, so with a focus rule it scrolled one side alone. This governs only interactive scrolling —
     * next/prev navigation pins both panes explicitly (see {@link #scrollToRow(int)}).
     */
    private void syncScroll(CodeArea from, CodeArea to) {
        from.estimatedScrollYProperty().addListener((o, ov, nv) -> {
            if (syncing || nv == null || scrollLeader != from) {
                return; // only the actively scrolled pane drives the other — no feedback loop
            }
            syncing = true;
            try {
                to.estimatedScrollYProperty().setValue(nv);
            } finally {
                syncing = false;
            }
        });
    }

    /**
     * Makes {@code area} the scroll leader whenever the user starts scrolling it: a wheel/touch gesture over
     * it (which also focuses it), a press anywhere in its scroll pane — the scroll-bar thumb and track
     * included — or it gaining focus, which is where key scrolling goes.
     */
    private void installScrollLeader(CodeArea area, Node scrollPane) {
        area.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            scrollLeader = area;
            if (!area.isFocused()) {
                area.requestFocus();
            }
        });
        scrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> scrollLeader = area);
        area.focusedProperty().addListener((o, was, focused) -> {
            if (focused) {
                scrollLeader = area;
            }
        });
    }

    // --- unified --------------------------------------------------------------------------------

    private void showUnified() {
        if (unifiedNode == null) {
            if (!layoutReady(true)) {
                layOutThen(true, this::showUnified);
                return;
            }
            buildUnified();
        }
        layoutShown();
        leftPaneWidth.unbind(); // no second pane to align to; the cluster goes back to the right edge
        leftPaneWidth.set(0);
        showComparison(unifiedNode);
    }

    private void showComparison(@Nullable Node comparison) {
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
        // Worked out where the model was made (PERF-01): only put in here.
        com.editora.diff.DiffLayout.Unified layout = layouts().unified(collapseContext);
        List<UnifiedRow> rows = layout.rows();
        unifiedSourceRows = layout.sourceStarts();
        unifiedArea = readOnlyArea("diff-unified");
        String text = layout.text();
        unifiedArea.replaceText(text);
        applyStyle(unifiedArea, text, layout.words());
        int[] nos = layout.nos();
        String[] signs = layout.signs();
        for (int i = 0; i < rows.size(); i++) {
            if (layout.collapsed()[i]) {
                unifiedArea.setParagraphStyle(i, List.of("diff-collapsed"));
            } else if (rows.get(i).type() == com.editora.diff.DiffModels.UnifiedType.ADD) {
                unifiedArea.setParagraphStyle(i, List.of("diff-added"));
            } else if (rows.get(i).type() == com.editora.diff.DiffModels.UnifiedType.REMOVE) {
                unifiedArea.setParagraphStyle(i, List.of("diff-removed"));
            }
        }
        installUnifiedGutter(unifiedArea, nos, signs);
        installTextMenu(unifiedArea);
        unifiedNode = new org.fxmisc.flowless.VirtualizedScrollPane<>(unifiedArea);
    }

    /**
     * The unified view's right-click menu: Copy and Select All of its text, which {@code edit.copy} and
     * {@code edit.selectAll} also do while the keyboard is in it. Its rows have no hunk actions: the unified view
     * offers none.
     */
    private static void installTextMenu(CodeArea area) {
        ConsoleContextMenu.mark(area, () -> ConsoleContextMenu.copySelectionOrAll(area), () -> area.selectAll());
        ContextMenu menu = new ContextMenu(ConsoleContextMenu.copyRow(area), ConsoleContextMenu.selectAllRow(area));
        area.setContextMenu(menu);
    }

    // --- shared rendering -----------------------------------------------------------------------

    private CodeArea readOnlyArea(String extraClass) {
        CodeArea area = AreaUndo.none(new CodeArea());
        area.getStyleClass().addAll("editor-area", "diff-area", extraClass);
        area.setAccessibleText(
                extraClass.equals("diff-left")
                        ? tr("diff.accessibleLeft", headerLeft)
                        : extraClass.equals("diff-right")
                                ? tr("diff.accessibleRight", headerRight)
                                : tr("diff.accessibleUnified"));
        area.setEditable(false);
        area.setFocusTraversable(true);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
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
        if (grammarName == null) {
            return;
        }
        long generation = highlightGeneration.get();
        HIGHLIGHT_EXECUTOR.submit(() -> {
            StyleSpans<Collection<String>> base;
            IGrammar grammar = loadGrammar();
            if (grammar == null) {
                return;
            }
            try {
                base = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                return;
            }
            if (base == null) {
                return;
            }
            StyleSpans<Collection<String>> combined = words == null ? base : base.overlay(words, DiffViewerPane::union);
            Platform.runLater(() -> {
                // A read-only pane's text changes only by being built again, as a new area: no need to compare it.
                if (generation == highlightGeneration.get()
                        && (area == leftArea || area == rightArea || area == unifiedArea)) {
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

    /** Marks the local file's review threads in its side's gutter, or (null) no longer. */
    public void setReviewHook(@Nullable ReviewHook hook) {
        this.reviewHook = hook;
        refreshReviewMarks();
    }

    /** Draws the review marks again: a thread was added, answered or resolved. */
    public void refreshReviewMarks() {
        if (!unified && leftArea != null && rightArea != null) {
            installGutter(leftArea, leftLineNos, sideSourceRows, false);
            installGutter(rightArea, rightLineNos, sideSourceRows, canMutate() && !resultEditing);
        }
    }

    /** The side-by-side area showing the local file, or null (a read-only diff, or the unified view). */
    private @Nullable CodeArea localArea() {
        if (unified) {
            return null;
        }
        return editableSide == EditableSide.RIGHT ? rightArea : editableSide == EditableSide.LEFT ? leftArea : null;
    }

    /**
     * The local file's 0-based lines {@code {first, last}} the selection (or the caret) covers on its side, or null
     * when the focus is not on that side or only on filler rows. Pure over the pane's state.
     */
    public int @Nullable [] localSelectionLines() {
        CodeArea area = localArea();
        if (area == null || !area.isFocused()) {
            return null;
        }
        int[] nos = area == rightArea ? rightLineNos : leftLineNos;
        int from = area.offsetToPosition(
                        area.getSelection().getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        int end = area.getSelection().getLength() == 0
                ? area.getSelection().getEnd()
                : Math.max(area.getSelection().getStart(), area.getSelection().getEnd() - 1);
        int to = area.offsetToPosition(end, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                .getMajor();
        return sourceLines(nos, from, to);
    }

    /** The first and last 0-based source lines of displayed rows {@code from..to}, skipping fillers; null if none. */
    static int @Nullable [] sourceLines(int[] nos, int from, int to) {
        int first = -1;
        int last = -1;
        for (int i = Math.max(0, from); i <= to && i < nos.length; i++) {
            if (nos[i] > 0) {
                first = first < 0 ? nos[i] - 1 : first;
                last = nos[i] - 1;
            }
        }
        return first < 0 ? null : new int[] {first, last};
    }

    /** A right-aligned original-line-number gutter; filler lines (-1) show blank. When {@code showApply}
     *  is true, each change block's first row also gets an "apply change" chevron that copies that hunk
     *  into the editable side. The right pane owns this action gutter so it remains at the center seam. */
    private void installGutter(CodeArea area, int[] lineNos, int[] sourceRows, boolean showApply) {
        boolean apply = showApply && editableSide != EditableSide.NONE;
        List<Row> rows = model.rows();
        boolean right = editableSide == EditableSide.RIGHT;
        Set<Integer> blockStarts = apply ? new HashSet<>(model.changeBlockStarts()) : Set.of();
        ReviewHook hook = area == localArea() ? reviewHook : null;
        Set<Integer> commented = hook == null ? Set.of() : hook.commentedLines();
        // Always render a gutter when apply arrows are needed, even if line numbers are off.
        if (!showLineNumbers && !apply && hook == null) {
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
                UiZoom.minWidth(num, numW);
                UiZoom.prefWidth(num, numW);
            }
            num.setAlignment(Pos.CENTER_RIGHT);
            int sourceRow = i < sourceRows.length ? sourceRows[i] : -1;
            Row source = sourceRow >= 0 && sourceRow < rows.size() ? rows.get(sourceRow) : null;
            String symbol = source == null || source.type() == RowType.EQUAL
                    ? ""
                    : area == leftArea ? (source.left() == null ? "" : "−") : (source.right() == null ? "" : "+");
            Label changeSign = new Label(symbol);
            changeSign.getStyleClass().add("diff-change-sign");
            UiZoom.minWidth(changeSign, 12);
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
            if (hook != null) {
                int line = i < lineNos.length ? lineNos[i] - 1 : -1;
                gutter.getChildren()
                        .add(
                                0,
                                arrowSlot(
                                        line >= 0 && commented.contains(line) ? Icons.review() : null,
                                        tr("review.diff.open"),
                                        () -> hook.open(line)));
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
    private HBox arrowSlot(@Nullable Node icon, String tip, Runnable onClick) {
        if (icon == null) {
            HBox empty = new HBox();
            fixSlotWidth(empty);
            return empty;
        }
        PressableBox slot = new PressableBox(onClick); // a click, Enter or Space, or a screen reader's press
        fixSlotWidth(slot);
        slot.getStyleClass().add("diff-apply");
        slot.setAccessibleText(tip);
        LazyTooltip.install(slot, () -> tip);
        slot.getChildren().add(icon);
        return slot;
    }

    private static void fixSlotWidth(HBox slot) {
        UiZoom.minWidth(slot, 16);
        UiZoom.prefWidth(slot, 16);
        UiZoom.maxWidth(slot, 16);
        slot.setAlignment(Pos.CENTER);
    }

    /** Whole-hunk apply: replaces the editable side's contiguous change block at {@code start} with the
     *  other side's content. */
    private void applyBlock(int start) {
        if (!canMutate() || resultEditing) {
            return;
        }
        applyLaterBetween(start, blockEndFrom(start));
    }

    /** The editable side with rows {@code [start, end)} taken from the other side, worked out off the FX thread. */
    private void applyLaterBetween(int start, int end) {
        List<Row> rows = model.rows();
        boolean rightEditable = editableSide == EditableSide.RIGHT;
        String editableText = rightEditable ? rightText : leftText;
        applyWorkedOut(() -> appliedText(rows, rightEditable, editableText, start, end), this::deliverApply);
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
        if (!canMutate() || resultEditing) {
            return;
        }
        applyLaterBetween(i, i + 1);
    }

    /** "Apply all": makes the editable file identical to the other side (its exact fetched text). Since
     *  this replaces the whole local file at once, it asks for confirmation first. */
    private void applyAll() {
        String otherText = editableSide == EditableSide.RIGHT ? leftText : rightText;
        javafx.stage.Window owner =
                root.getScene() == null ? null : root.getScene().getWindow();
        if (Confirm.ask(owner, applyAllQuestion())) {
            deliverApply(otherText);
        }
    }

    /** The words of the question before Apply All replaces the whole editable file with the other side. */
    static Confirm.Question applyAllQuestion() {
        return new Confirm.Question(
                tr("diff.applyAll.confirmTitle"), null, tr("diff.applyAll.confirm"), tr("confirm.action.replace"));
    }

    private void deliverApply(String text) {
        if (!canMutate() || resultEditing || applyPending) {
            return;
        }
        applyPending = true;
        updateEditButtons();
        onApply.accept(text, applied -> {
            applyPending = false;
            if (Boolean.TRUE.equals(applied)) {
                unappliedUndoDepth++;
                unsavedApplies = true;
            }
            updateEditButtons();
        });
    }

    private void updateEditButtons() {
        undoButton.setDisable(unappliedUndoDepth <= 0);
        saveButton.setDisable(!unsavedApplies);
        applyAllButton.setDisable(resultEditing || applyPending);
    }

    /** The editable side's full text after taking the other side's content for rows in {@code [start,end)}
     *  and keeping the editable side's content elsewhere (filler = no line). Pure: {@code editableText} is the
     *  editable side's text. */
    static String appliedText(List<Row> rows, boolean rightEditable, String editableText, int start, int end) {
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
        return DiffText.parse(editableText).compose(out);
    }

    private void installUnifiedGutter(CodeArea area, int[] lineNos, String[] signs) {
        int width = Math.max(2, String.valueOf(maxOf(lineNos)).length());
        IntFunction<Node> factory = i -> {
            int no = i < lineNos.length ? lineNos[i] : -1;
            Label num = new Label(no < 0 ? "" : String.valueOf(no));
            num.getStyleClass().add("diff-lineno");
            UiZoom.minWidth(num, width * 9.0 + 8);
            UiZoom.prefWidth(num, width * 9.0 + 8);
            num.setAlignment(Pos.CENTER_RIGHT);
            Label sign = new Label(i < signs.length ? signs[i] : " ");
            sign.getStyleClass().add("diff-sign");
            UiZoom.minWidth(sign, 14);
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
            Platform.runLater(() -> {
                CodeArea area = unifiedArea;
                if (area != null) {
                    area.showParagraphAtTop(top);
                }
            });
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
                    CodeArea l = leftArea;
                    CodeArea r = rightArea;
                    if (l != null && r != null) {
                        l.showParagraphAtTop(top);
                        r.showParagraphAtTop(top);
                    }
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

    @Override
    public boolean hasUnsavedChanges() {
        return hasDirtyResult();
    }

    @Override
    public @Nullable Object unsavedStateToken() {
        return hasDirtyResult() && resultArea != null ? resultArea.getText() : null;
    }

    @Override
    public boolean saveBeforeClose() {
        applyResult();
        return !hasDirtyResult();
    }

    @Override
    public String closeSaveActionKey() {
        return "diff.applyResult";
    }
}
