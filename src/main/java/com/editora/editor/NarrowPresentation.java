package com.editora.editor;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What a narrowed {@link EditorBuffer} shows of its narrowing besides the text: the gutter's line numbers (the
 * region is a window onto the file, so its rows keep the file's numbers rather than restarting at 1), the
 * {@code narrowed} style class on the area(s), and an amber banner above the code naming the accessible lines
 * with a Widen button — the status bar's pill is easy to miss while the text itself looks like a short, complete
 * file. Also the file-line arithmetic behind {@link EditorBuffer#narrowFirstLine()}.
 */
final class NarrowPresentation {

    private final EditorBuffer buffer;
    private final CodeArea area;
    private final LogView logView;
    private final FoldManager folds;
    /** Whether the buffer is narrowed; {@link #firstLine} the 0-based file line its region starts on. */
    private boolean narrowed;

    private int firstLine;
    /** Line breaks in the held-aside text after the region: the file's lines past the region, for "of N". */
    private int suffixLines;

    private @Nullable HBox bar;
    private @Nullable Label text;
    /** The paragraph count the banner text was last computed from (-1: never, or widened since). */
    private int barLines = -1;

    /** Takes over the gutter's numbering ({@link FoldManager#setLineNumbers}): a filtered log keeps its numbers too. */
    NarrowPresentation(EditorBuffer buffer, CodeArea area, LogView logView, FoldManager folds) {
        this.buffer = buffer;
        this.area = area;
        this.logView = logView;
        this.folds = folds;
        folds.setLineNumbers(this::lineNumberAt, this::lineNumberSpan);
        // The banner counts the region's lines; only a changed paragraph count can move it.
        area.getParagraphs().addListener((javafx.collections.ListChangeListener<Object>) c -> {
            if (narrowed && bar != null) {
                refreshText();
            }
        });
    }

    int firstLine() {
        return firstLine;
    }

    /** The gutter's number for {@code paragraph}: its file line while narrowed, else the log filter's answer. */
    int lineNumberAt(int paragraph) {
        return narrowed ? firstLine + paragraph + 1 : logView.lineNumberAt(paragraph);
    }

    /** Lines to size the gutter for: the region's last file line while narrowed, else the log filter's answer. */
    int lineNumberSpan() {
        return narrowed ? firstLine + area.getParagraphs().size() : logView.lineNumberSpan();
    }

    /** The banner, as a list for the editor's top bars: empty while widened. */
    List<Node> bars() {
        return narrowed && bar != null ? List.of(bar) : List.of();
    }

    /** The banner's text, or null while widened (tests). */
    @Nullable
    String bannerText() {
        return narrowed && text != null ? text.getText() : null;
    }

    /** The area now holds {@code [start, end)} of {@code full}. Called after the text swap and caret moves. */
    void narrowed(String full, int start, int end, @Nullable CodeArea area2) {
        narrowed = true;
        firstLine = NarrowLines.firstLine(full, start);
        suffixLines = NarrowLines.firstLine(full, full.length()) - NarrowLines.firstLine(full, end);
        if (bar == null) {
            bar = build();
        }
        refreshText();
        apply(area2);
    }

    /** The area holds the whole document again. */
    void widened(@Nullable CodeArea area2) {
        narrowed = false;
        firstLine = 0;
        suffixLines = 0;
        barLines = -1;
        apply(area2);
    }

    private void apply(@Nullable CodeArea area2) {
        style(area);
        if (area2 != null) {
            style(area2);
        }
        folds.refreshLineNumbers(); // the text swap keeps the visible cells, which re-read their number only when
        // rebuilt
        buffer.refreshTopBars(); // the banner joined or left the editor's top bars
    }

    /** {@code .editor-area.narrowed}: lets the stylesheet tint the gutter of a narrowed view. */
    private void style(CodeArea a) {
        if (!narrowed) {
            a.getStyleClass().remove("narrowed");
        } else if (!a.getStyleClass().contains("narrowed")) {
            a.getStyleClass().add("narrowed");
        }
    }

    private HBox build() {
        text = new Label();
        text.getStyleClass().add("narrow-bar-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button widen = new Button(tr("editor.narrow.widen"));
        widen.getStyleClass().add("narrow-bar-widen");
        widen.setFocusTraversable(false);
        widen.setOnAction(e -> buffer.widen()); // the narrow-changed hook reconciles the rest of the UI
        HBox box = new HBox(10, text, spacer, widen);
        box.getStyleClass().add("narrow-bar");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** "Narrowed to lines A–B of N": A–B the region's place in the file, N the whole file's line count. */
    private void refreshText() {
        int lines = area.getParagraphs().size();
        if (lines == barLines || text == null) {
            return;
        }
        barLines = lines;
        int last = firstLine + lines;
        text.setText(tr("editor.narrow.bar", firstLine + 1, last, last + suffixLines));
    }
}
