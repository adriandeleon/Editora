package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.Paragraph;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * The pinned scope headers drawn over the top of the editor — the rendering half of {@link StickyScroll}.
 *
 * <p>Each row is the real line, rebuilt from the area's <em>already-applied</em> style spans rather than
 * re-tokenized. That is not only cheaper: tm4e grammars are not thread-safe and the editor's background
 * highlighters own them, so asking for a fresh tokenization here would race those — the hazard
 * {@code pdf/CodeHtml} documents. Reading the spans the area already has is free and always agrees with
 * what is on screen.
 *
 * <p>Clicking a row jumps to that line, which is the other half of the feature: the header tells you where
 * you are, and it is also the way back to it.
 */
final class StickyScrollBar {

    /** Style class for the container; rows carry {@code sticky-scroll-row}. */
    private static final String STYLE_CLASS = "sticky-scroll";

    private final VBox box = new VBox();

    /**
     * One rendered row and what it was rendered from. RichTextFX paragraphs are immutable, so the same
     * {@code Paragraph} instance on the same line means the same text <em>and</em> the same style spans:
     * an edit or a re-highlight of the line replaces the instance, which is the whole invalidation rule.
     */
    private record Row(int line, Paragraph<?, ?, ?> paragraph, HBox node) {}

    /** What the bar shows right now. A scroll that pins the same lines reuses these nodes untouched. */
    private List<Row> rows = List.of();

    private CodeArea shownArea;
    private int shownTabSize;
    private IntConsumer onClick;
    /** Rows built since construction — the test seam that proves a scroll inside one scope builds none. */
    private int rowsBuilt;

    private StickyScroll.Index index = StickyScroll.Index.EMPTY;

    StickyScrollBar() {
        box.getStyleClass().add(STYLE_CLASS);
        box.setVisible(false);
        // MANAGED, deliberately. It looks like the other floating controls, which are free-positioned, but
        // it is placed with AnchorPane constraints — and AnchorPane lays out only its *managed* children.
        // Unmanaged, the anchors are ignored, the box stays 0x0 at the origin, and the feature is simply
        // invisible while every model-level test still passes.
        box.setManaged(true);
        // A pinned line can be thousands of characters long. Its intrinsic width must not become the
        // editor's minimum/preferred width and push the right-docked minimap outside the viewport.
        // AnchorPane supplies the bar's width; rows clip their text to that available space.
        box.setMinWidth(0);
        box.setPrefWidth(0);
        // The bar explains the code behind it; it must never swallow a click meant for the editor. Rows
        // re-enable picking for themselves so their own click still works.
        box.setPickOnBounds(false);
    }

    Node node() {
        return box;
    }

    void setFont(String family, int size) {
        box.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
    }

    /** True when the bar currently shows anything. */
    boolean isShowing() {
        return box.isVisible();
    }

    void hide() {
        if (box.isVisible()) {
            box.setVisible(false);
            box.getChildren().clear();
        }
        rows = List.of();
    }

    int rowsBuiltForTest() {
        return rowsBuilt;
    }

    /**
     * {@link StickyScroll#headerLines} for {@code regions}, answered from an index that is rebuilt only when
     * the fold regions themselves are replaced — so a scroll step walks the enclosing scopes, not the file.
     */
    List<Integer> pinnedLines(List<FoldRegions.Region> regions, int firstVisible) {
        if (!index.isFor(regions)) {
            index = StickyScroll.Index.of(regions);
        }
        return index.headerLines(firstVisible, StickyScroll.DEFAULT_MAX);
    }

    /**
     * Anchors the bar across the top of {@code pane}'s children, {@code rightInset} clear of the right edge.
     * Every {@code AnchorPane.setXAnchor} call requests a layout of the parent even for an unchanged value,
     * and this runs on each scroll step, so only a changed constraint is written.
     */
    static void anchor(Node bar, double rightInset) {
        if (!Double.valueOf(0).equals(AnchorPane.getTopAnchor(bar))) {
            AnchorPane.setTopAnchor(bar, 0d);
            AnchorPane.setLeftAnchor(bar, 0d);
        }
        Double right = AnchorPane.getRightAnchor(bar);
        if (right == null || right != rightInset) {
            AnchorPane.setRightAnchor(bar, rightInset);
        }
    }

    /**
     * Shows {@code lines} of {@code area}, or hides the bar when there are none. A row is rebuilt only when
     * its line number or its paragraph (text or styling) changed; an unchanged set touches no node at all,
     * which is the common case while scrolling inside one scope.
     *
     * @param tabSize columns a tab occupies — a {@code Text} node renders a tab as a single narrow glyph,
     *     so an indented header would sit at the wrong column against the code below it
     * @param onClick given the 0-based line of the row that was clicked
     */
    void update(CodeArea area, List<Integer> lines, int tabSize, IntConsumer onClick) {
        this.onClick = onClick;
        if (area == null || lines == null || lines.isEmpty()) {
            hide();
            return;
        }
        boolean reusable = area == shownArea && tabSize == shownTabSize;
        shownArea = area;
        shownTabSize = tabSize;
        int paragraphs = area.getParagraphs().size();
        List<Row> next = new ArrayList<>(lines.size());
        boolean same = reusable;
        for (int line : lines) {
            if (line < 0 || line >= paragraphs) {
                continue; // the document shrank under a stale scroll position
            }
            Paragraph<?, ?, ?> paragraph = area.getParagraph(line);
            Row row = reusable ? shown(line, paragraph) : null;
            if (row == null) {
                row = new Row(line, paragraph, row(area, line, tabSize));
                rowsBuilt++;
            }
            same &= next.size() < rows.size() && rows.get(next.size()) == row;
            next.add(row);
        }
        if (!same || next.size() != rows.size()) {
            rows = next;
            List<Node> nodes = new ArrayList<>(next.size());
            for (Row row : next) {
                nodes.add(row.node());
            }
            box.getChildren().setAll(nodes);
        }
        box.setVisible(!rows.isEmpty());
    }

    private Row shown(int line, Paragraph<?, ?, ?> paragraph) {
        for (Row row : rows) {
            if (row.line() == line && row.paragraph() == paragraph) {
                return row;
            }
        }
        return null;
    }

    private HBox row(CodeArea area, int line, int tabSize) {
        HBox flow = new HBox();
        flow.setMinWidth(0);
        flow.getStyleClass().add("sticky-scroll-row");
        flow.setPickOnBounds(true); // the container opts out of picking; a row opts back in
        flow.getChildren().setAll(runs(area, line, tabSize));
        // TextFlow wraps a long logical line to the available editor width. For minified files that turns
        // one pinned header into a many-line overlay which can consume half the viewport. HBox keeps the
        // styled Text runs on one visual line; clip their overflow at the editor edge instead.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(flow.widthProperty());
        clip.heightProperty().bind(flow.heightProperty());
        flow.setClip(clip);
        LazyTooltip.install(flow, () -> String.valueOf(line + 1));
        flow.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && onClick != null) {
                onClick.accept(line);
                e.consume();
            }
        });
        return flow;
    }

    /** The line's text split into styled runs, mirroring how the editor itself has coloured it. */
    private List<Text> runs(CodeArea area, int line, int tabSize) {
        String text = expandTabs(area.getParagraph(line).getText(), tabSize);
        List<Text> out = new java.util.ArrayList<>();
        StyleSpans<Collection<String>> spans;
        try {
            spans = area.getStyleSpans(line);
        } catch (RuntimeException ex) {
            spans = null; // never let a styling hiccup blank the bar; plain text still reads fine
        }
        if (spans == null) {
            out.add(run(text, null));
            return out;
        }
        // The spans index the ORIGINAL text, so they are walked against it and the expansion is applied
        // per run. Expanding first and then slicing by span length would drift on any indented line.
        String raw = area.getParagraph(line).getText();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (pos >= raw.length()) {
                break;
            }
            int end = Math.min(raw.length(), pos + span.getLength());
            if (end > pos) {
                out.add(run(expandTabs(raw.substring(pos, end), tabSize), span.getStyle()));
            }
            pos = end;
        }
        if (pos < raw.length()) {
            out.add(run(expandTabs(raw.substring(pos), tabSize), null));
        }
        if (out.isEmpty()) {
            out.add(run(text, null));
        }
        return out;
    }

    private static Text run(String s, Collection<String> styles) {
        Text t = new Text(s);
        // "text" is what the editor's own token rules key off (.text.<class> in syntax.css), so a pinned
        // line picks up the active editor theme with no rules of its own.
        t.getStyleClass().add("text");
        if (styles != null) {
            t.getStyleClass().addAll(styles);
        }
        return t;
    }

    private static String expandTabs(String s, int tabSize) {
        if (s.indexOf('\t') < 0) {
            return s;
        }
        int width = Math.max(1, tabSize);
        StringBuilder sb = new StringBuilder(s.length() + width);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                sb.append(" ".repeat(width - sb.length() % width));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
