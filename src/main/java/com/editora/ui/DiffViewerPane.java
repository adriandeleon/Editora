package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

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

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import org.eclipse.tm4e.core.grammar.IGrammar;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
import com.editora.diff.PatchWriter;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.TabContent;
import com.editora.editor.TextMateHighlighter;

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
    private final String leftText;
    private final String rightText;
    private final DiffModel model;
    private final IGrammar grammar;
    private final String fontStyle;
    private final boolean showLineNumbers;
    private java.util.function.Consumer<String> onExportPatch = p -> { };

    private final BorderPane root = new BorderPane();
    private final Label summary = new Label();
    private final Label changeNav = new Label();
    private final Button toggleButton = new Button();

    private boolean unified; // false = side-by-side (default)
    private int changeCursor = -1; // index into model.changeBlockStarts for prev/next nav

    // Side-by-side areas (built once, lazily).
    private CodeArea leftArea;
    private CodeArea rightArea;
    private Node sideBySideNode;
    private CodeArea unifiedArea;
    private Node unifiedNode;
    private boolean syncing; // re-entrancy guard for scroll sync

    public DiffViewerPane(String title, String headerLeft, String headerRight, String leftName,
            String rightName, String leftText, String rightText, DiffModel model, String fontFamily,
            int fontSize, boolean showLineNumbers) {
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
        // Syntax grammar from the "new" file's name (falls back to the old name's extension).
        IGrammar g = grammarFor(rightName);
        this.grammar = g != null ? g : grammarFor(leftName);

        root.getStyleClass().add("diff-viewer");
        root.setTop(buildToolbar());
        showSideBySide();
    }

    public void setOnExportPatch(java.util.function.Consumer<String> onExportPatch) {
        this.onExportPatch = onExportPatch == null ? p -> { } : onExportPatch;
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
        summary.setText(tr("diff.summary", model.added(), model.removed()));
        changeNav.getStyleClass().add("diff-summary");
        updateChangeNav();
        Button prev = iconButton(Icons.arrowUp(), tr("diff.prevChange"), this::prevChange);
        Button next = iconButton(Icons.arrowDown(), tr("diff.nextChange"), this::nextChange);
        Button export = iconButton(Icons.saveAs(), tr("diff.exportPatch"),
                () -> onExportPatch.accept(patchText("a/" + leftName, "b/" + rightName)));
        toggleButton.setOnAction(e -> toggleView());
        toggleButton.getStyleClass().addAll("flat", "diff-toolbar-button");
        toggleButton.setFocusTraversable(false);
        updateToggleButton();
        HBox bar = new HBox(2, summary, spacer(), changeNav, prev, next, sep(), toggleButton, export);
        bar.getStyleClass().add("diff-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(3, 6, 3, 6));
        return bar;
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
        installGutter(leftArea, leftNos);
        installGutter(rightArea, rightNos);
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

    private void syncScroll(CodeArea from, CodeArea to) {
        from.estimatedScrollYProperty().addListener((o, ov, nv) -> {
            if (syncing || nv == null) {
                return;
            }
            syncing = true;
            try {
                to.estimatedScrollYProperty().setValue(nv);
            } finally {
                syncing = false;
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
        StyleSpans<Collection<String>> words = wordRanges.isEmpty() ? null
                : buildWordSpans(text.length(), wordRanges);
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
            out.add(new int[]{lineOffset + r[0], lineOffset + r[1]});
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

    /** A right-aligned original-line-number gutter; filler lines (-1) show blank. */
    private void installGutter(CodeArea area, int[] lineNos) {
        if (!showLineNumbers) {
            return;
        }
        int width = Math.max(2, String.valueOf(maxOf(lineNos)).length());
        IntFunction<Node> factory = i -> {
            int no = i < lineNos.length ? lineNos[i] : -1;
            Label l = new Label(no < 0 ? "" : String.valueOf(no));
            l.getStyleClass().add("diff-lineno");
            l.setMinWidth(width * 9.0 + 12);
            l.setPrefWidth(width * 9.0 + 12);
            l.setAlignment(Pos.CENTER_RIGHT);
            return l;
        };
        area.setParagraphGraphicFactory(factory);
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
        changeNav.setText(changeCursor < 0
                ? tr("diff.changeCount", total)
                : tr("diff.changePos", changeCursor + 1, total));
    }

    /** Scrolls the active view to a side-by-side row index (mapped to the unified row when needed). */
    private void scrollToRow(int sideRow) {
        if (unified && unifiedArea != null) {
            int u = unifiedRowFor(sideRow);
            unifiedArea.showParagraphAtTop(Math.max(0, u));
        } else if (leftArea != null) {
            leftArea.showParagraphAtTop(Math.max(0, sideRow));
        }
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
