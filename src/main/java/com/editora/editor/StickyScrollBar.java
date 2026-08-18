package com.editora.editor;

import java.util.Collection;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.fxmisc.richtext.CodeArea;
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

    StickyScrollBar() {
        box.getStyleClass().add(STYLE_CLASS);
        box.setVisible(false);
        // MANAGED, deliberately. It looks like the other floating controls, which are free-positioned, but
        // it is placed with AnchorPane constraints — and AnchorPane lays out only its *managed* children.
        // Unmanaged, the anchors are ignored, the box stays 0x0 at the origin, and the feature is simply
        // invisible while every model-level test still passes.
        box.setManaged(true);
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
    }

    /**
     * Rebuilds the bar to show {@code lines} of {@code area}, or hides it when there are none.
     *
     * @param tabSize columns a tab occupies — a {@code Text} node renders a tab as a single narrow glyph,
     *     so an indented header would sit at the wrong column against the code below it
     * @param onClick given the 0-based line of the row that was clicked
     */
    void update(CodeArea area, List<Integer> lines, int tabSize, IntConsumer onClick) {
        if (area == null || lines == null || lines.isEmpty()) {
            hide();
            return;
        }
        box.getChildren().clear();
        int paragraphs = area.getParagraphs().size();
        for (int line : lines) {
            if (line < 0 || line >= paragraphs) {
                continue; // the document shrank under a stale scroll position
            }
            box.getChildren().add(row(area, line, tabSize, onClick));
        }
        boolean any = !box.getChildren().isEmpty();
        box.setVisible(any);
        if (!any) {
            box.getChildren().clear();
        }
    }

    private TextFlow row(CodeArea area, int line, int tabSize, IntConsumer onClick) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("sticky-scroll-row");
        flow.setPickOnBounds(true); // the container opts out of picking; a row opts back in
        flow.getChildren().setAll(runs(area, line, tabSize));
        Tooltip.install(flow, new Tooltip(String.valueOf(line + 1)));
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
