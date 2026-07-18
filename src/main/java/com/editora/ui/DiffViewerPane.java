package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
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

    private final String title;
    private final String leftName;
    private final String rightName;
    private final String headerLeft;
    private final String headerRight;
    private String leftText;
    private String rightText;
    private DiffModel model;
    private final IGrammar grammar;
    private String fontStyle; // mutable so text zoom can resize the diff areas live (#533)
    private final boolean showLineNumbers;
    private java.util.function.Consumer<String> onExportPatch = p -> {};
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
    private java.util.function.Consumer<String> onApply = t -> {};

    private final BorderPane root = new BorderPane();
    private final Label summary = new Label();
    private final Label changeNav = new Label();
    private final Button toggleButton = new Button();
    private final Button applyAllButton = new Button();
    private final Button undoButton = new Button();
    private final Button saveButton = new Button();
    private Runnable onUndo = () -> {};
    private Runnable onSave = () -> {};

    private boolean unified; // false = side-by-side (default)
    private int changeCursor = -1; // index into model.changeBlockStarts for prev/next nav

    // Side-by-side areas (built once, lazily).
    private CodeArea leftArea;
    private CodeArea rightArea;
    private Node sideBySideNode;
    private CodeArea unifiedArea;
    private Node unifiedNode;
    private boolean syncing; // re-entrancy guard for scroll sync

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
        root.setTop(buildToolbar());
        showSideBySide();
    }

    public void setOnExportPatch(java.util.function.Consumer<String> onExportPatch) {
        this.onExportPatch = onExportPatch == null ? p -> {} : onExportPatch;
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
    }

    /** Installs the controller's re-fetch-and-rerender hook (run on focus-regain / after git mutation). */
    public void setRefresher(Runnable refresher) {
        this.refresher = refresher == null ? () -> {} : refresher;
    }

    /**
     * Marks which side is the editable/local file and the callback that writes the applied text back.
     * When set (not {@link EditableSide#NONE}), each change block shows an "apply change" chevron in
     * that side's gutter (side-by-side view) that replaces the hunk with the other side's content.
     */
    public void setEditable(
            EditableSide side, java.util.function.Consumer<String> onApply, Runnable onUndo, Runnable onSave) {
        this.editableSide = side == null ? EditableSide.NONE : side;
        this.onApply = onApply == null ? t -> {} : onApply;
        this.onUndo = onUndo == null ? () -> {} : onUndo;
        this.onSave = onSave == null ? () -> {} : onSave;
        boolean editable = this.editableSide != EditableSide.NONE;
        for (Button b : new Button[] {applyAllButton, undoButton, saveButton}) {
            b.setVisible(editable);
            b.setManaged(editable);
        }
        // Called after construction (the view is already built without arrows) — rebuild so the
        // per-line "apply" chevrons appear in the editable side's gutter.
        sideBySideNode = null;
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

    /** Replaces the compared content + diff model and re-renders the current view (rebuilds both the
     *  side-by-side and unified nodes lazily). Keeps the toolbar, headers, grammar, and view mode. */
    public void updateContent(String newLeft, String newRight, DiffModel newModel) {
        this.leftText = newLeft;
        this.rightText = newRight;
        this.model = newModel;
        // Drop cached nodes so the next show* rebuilds from the new model.
        sideBySideNode = null;
        unifiedNode = null;
        leftArea = null;
        rightArea = null;
        unifiedArea = null;
        changeCursor = -1;
        updateSummary();
        if (unified) {
            showUnified();
        } else {
            showSideBySide();
        }
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
        Button export = iconButton(
                Icons.saveAs(),
                tr("diff.exportPatch"),
                () -> onExportPatch.accept(patchText("a/" + leftName, "b/" + rightName)));
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
        editButton(undoButton, Icons.undo(), tr("diff.undo"), () -> onUndo.run());
        editButton(saveButton, Icons.save(), tr("diff.save"), () -> onSave.run());
        toggleButton.setOnAction(e -> toggleView());
        toggleButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        toggleButton.setFocusTraversable(false);
        updateToggleButton();
        HBox bar = new HBox(
                2,
                summary,
                spacer(),
                changeNav,
                next,
                prev,
                sep(),
                applyAllButton,
                undoButton,
                saveButton,
                sep(),
                toggleButton,
                export);
        bar.getStyleClass().add("diff-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        return bar;
    }

    /** Configures an editable-only toolbar button (hidden until {@link #setEditable} shows it). */
    private void editButton(Button b, Node icon, String tip, Runnable action) {
        b.setGraphic(icon);
        b.setTooltip(new Tooltip(tip));
        b.getStyleClass().addAll("flat", "diff-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        b.setVisible(false);
        b.setManaged(false);
    }

    private void updateToggleButton() {
        toggleButton.setGraphic(unified ? Icons.splitVertical() : Icons.previewOnly());
        toggleButton.setTooltip(new Tooltip(unified ? tr("diff.viewSideBySide") : tr("diff.viewUnified")));
    }

    private Button iconButton(Node icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "diff-toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

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

    public void goNextChange() {
        nextChange();
    }

    public void goPreviousChange() {
        prevChange();
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
        root.setCenter(sideBySideNode);
    }

    private void buildSideBySide() {
        List<Row> rows = model.rows();
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
            if (r.type() == RowType.MODIFIED) {
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
            leftArea.setParagraphStyle(i, leftLineClasses(rows.get(i).type()));
            rightArea.setParagraphStyle(i, rightLineClasses(rows.get(i).type()));
        }
        installGutter(leftArea, leftNos, editableSide == EditableSide.LEFT);
        installGutter(rightArea, rightNos, editableSide == EditableSide.RIGHT);
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
        javafx.scene.layout.VBox.setVgrow(leftScroll, Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(rightScroll, Priority.ALWAYS);
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(leftBox, rightBox);
        split.setDividerPositions(0.5);
        sideBySideNode = split;
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
        root.setCenter(unifiedNode);
    }

    private void buildUnified() {
        List<UnifiedRow> rows = model.unified();
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
        applyStyle(unifiedArea, text, List.of());
        int[] nos = new int[rows.size()];
        String[] signs = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            UnifiedRow r = rows.get(i);
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

    // --- shared rendering -----------------------------------------------------------------------

    private CodeArea readOnlyArea(String extraClass) {
        CodeArea area = new CodeArea();
        area.getStyleClass().addAll("editor-area", "diff-area", extraClass);
        area.setEditable(false);
        area.setFocusTraversable(true);
        area.setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.OFF);
        area.setWrapText(false);
        area.setStyle(fontStyle);
        return area;
    }

    /** Applies syntax highlighting (if a grammar is known) plus intra-line word emphasis. */
    private void applyStyle(CodeArea area, String text, List<int[]> wordRanges) {
        if (text.isEmpty()) {
            return; // RichTextFX rejects zero-length spans
        }
        StyleSpans<Collection<String>> base = null;
        if (grammar != null) {
            try {
                base = TextMateHighlighter.compute(text, grammar);
            } catch (RuntimeException ignored) {
                base = null;
            }
        }
        StyleSpans<Collection<String>> words = wordRanges.isEmpty() ? null : buildWordSpans(text.length(), wordRanges);
        if (base != null && words != null) {
            area.setStyleSpans(0, base.overlay(words, DiffViewerPane::union));
        } else if (base != null) {
            area.setStyleSpans(0, base);
        } else if (words != null) {
            area.setStyleSpans(0, words);
        }
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

    /** A right-aligned original-line-number gutter; filler lines (-1) show blank. When {@code editable}
     *  (this side is the local file), each change block's first row also gets an "apply change" chevron
     *  that copies that hunk from the other side into this file. */
    private void installGutter(CodeArea area, int[] lineNos, boolean editable) {
        boolean apply = editable && editableSide != EditableSide.NONE && onApply != null;
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
            HBox gutter;
            if (!apply) {
                gutter = new HBox(num);
            } else {
                // Hunk apply (double chevron, at each change block's first row) + per-line apply (single
                // chevron, on every changed row). Both copy the other side's content into the local file.
                HBox hunkSlot = arrowSlot(
                        blockStarts.contains(i)
                                ? (right ? Icons.doubleChevronRight() : Icons.doubleChevronLeft())
                                : null,
                        tr("diff.applyChange"),
                        () -> applyBlock(i));
                HBox lineSlot = arrowSlot(
                        i < rows.size() && rows.get(i).type() != RowType.EQUAL
                                ? (right ? Icons.chevronRight() : Icons.chevronLeft())
                                : null,
                        tr("diff.applyLine"),
                        () -> applyRow(i));
                gutter = new HBox(hunkSlot, lineSlot, num);
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
            Tooltip.install(slot, new Tooltip(tip));
            slot.setOnMouseClicked(e -> {
                onClick.run();
                e.consume();
            });
            slot.getChildren().add(icon);
        }
        return slot;
    }

    /** Whole-hunk apply: replaces the editable side's contiguous change block at {@code start} with the
     *  other side's content. */
    private void applyBlock(int start) {
        onApply.accept(computeApplied(start, blockEndFrom(start)));
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
        onApply.accept(computeApplied(i, i + 1));
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
            onApply.accept(otherText);
        }
    }

    /** The editable side's full text after taking the other side's content for rows in {@code [start,end)}
     *  and keeping the editable side's content elsewhere (filler = no line). */
    private String computeApplied(int start, int end) {
        boolean rightEditable = editableSide == EditableSide.RIGHT;
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
        return String.join("\n", out);
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
    }

    /** Refreshes the toolbar's +added/−removed summary and the change-count indicator from the model. */
    private void updateSummary() {
        summary.setText(tr("diff.summary", model.added(), model.removed()));
        updateChangeNav();
    }

    /** Scrolls to, and selects, the change block starting at side-by-side row {@code sideRow}, so the
     *  user sees which change the nav advanced to. */
    private void scrollToRow(int sideRow) {
        int sideEnd = blockEndFrom(sideRow);
        if (unified && unifiedArea != null) {
            int u = unifiedRowFor(sideRow);
            int top = Math.max(0, u);
            selectLines(unifiedArea, u, unifiedRowFor(sideEnd));
            // Pin the row to the top AFTER the selection (selectRange schedules a caret-follow scroll that
            // would otherwise leave the block bottom-aligned). One pulse later runs after that follow.
            Platform.runLater(() -> unifiedArea.showParagraphAtTop(top));
        } else if (leftArea != null && rightArea != null) {
            int top = Math.max(0, sideRow);
            // Highlight the block on both panes (caret at the block top — see selectLines). The rows are 1:1
            // aligned (filler lines), so navigation pins BOTH panes to the same top row explicitly, with the
            // scroll sync suppressed. Relying on the focus-gated sync listener to align the follower fails on a
            // backward jump: selectRange's caret-follow may already have left the driven pane at `top`, so its
            // estimatedScrollY never changes, the listener never fires, and the follower is stranded at its own
            // caret-follow position. Setting both deterministically can't desync.
            selectLines(leftArea, sideRow, sideEnd);
            selectLines(rightArea, sideRow, sideEnd);
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
