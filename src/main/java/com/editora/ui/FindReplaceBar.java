package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import com.editora.editor.EditorBuffer;
import com.editora.editor.SearchMatcher;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * A non-modal find/replace bar operating on the currently active {@link EditorBuffer}. Searches
 * incrementally (as you type, debounced), highlights <b>all</b> matches via the buffer's search
 * overlay, supports case-sensitive / regex / whole-word, and shows a "{n} of {total}" count.
 */
public class FindReplaceBar extends HBox {

    private final Supplier<EditorBuffer> activeBuffer;
    private final Consumer<String> status;

    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox caseSensitive = new CheckBox("Aa");
    private final CheckBox regex = new CheckBox(".*");
    private final CheckBox wholeWord = new CheckBox("W");
    private final Label countLabel = new Label();

    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    private List<int[]> matches = List.of();
    private int activeIndex = -1;
    private int searchAnchor; // caret offset when the search started — incremental jumps anchor here

    public FindReplaceBar(Supplier<EditorBuffer> activeBuffer, Consumer<String> status) {
        this.activeBuffer = activeBuffer;
        this.status = status;
        getStyleClass().add("find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);
        build();
    }

    private void build() {
        findField.setPromptText(tr("find.prompt"));
        replaceField.setPromptText(tr("find.replacePrompt"));
        wholeWord.setTooltip(new Tooltip(tr("find.wholeWord")));
        countLabel.getStyleClass().add("find-count");

        Button next = new Button(tr("find.next"));
        Button prev = new Button(tr("find.prev"));
        Button replace = new Button(tr("find.replace"));
        Button replaceAll = new Button(tr("find.all"));
        Button close = new Button("✕");

        next.setOnAction(e -> findNext());
        prev.setOnAction(e -> findPrevious());
        replace.setOnAction(e -> replaceCurrent());
        replaceAll.setOnAction(e -> replaceAll());
        close.setOnAction(e -> hideBar());

        findField.setOnAction(e -> findNext());
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideBar();
            }
        });
        // Incremental: re-search (debounced) on query or option changes.
        findField.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        caseSensitive.selectedProperty().addListener((o, a, b) -> recompute());
        regex.selectedProperty().addListener((o, a, b) -> recompute());
        wholeWord.selectedProperty().addListener((o, a, b) -> recompute());
        debounce.setOnFinished(e -> recompute());

        getChildren()
                .addAll(
                        new Label(tr("find.label")),
                        findField,
                        countLabel,
                        prev,
                        next,
                        new Label(tr("find.replaceLabel")),
                        replaceField,
                        replace,
                        replaceAll,
                        caseSensitive,
                        regex,
                        wholeWord,
                        close);
    }

    public void show(boolean backward) {
        setVisible(true);
        setManaged(true);
        CodeArea area = area();
        searchAnchor = area == null ? 0 : area.getCaretPosition();
        findField.requestFocus();
        findField.selectAll();
        recompute();
        if (backward) {
            status.accept(tr("find.reverseSearch"));
        }
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        EditorBuffer buffer = activeBuffer.get();
        if (buffer != null) {
            buffer.clearSearchMatches();
            buffer.getFocusedArea().requestFocus();
        }
        matches = List.of();
        activeIndex = -1;
    }

    public boolean isShown() {
        return isVisible();
    }

    private CodeArea area() {
        EditorBuffer buffer = activeBuffer.get();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    /** Recomputes the full match set for the current query/options, highlights all, and selects the nearest. */
    private void recompute() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            matches = List.of();
            activeIndex = -1;
            if (buffer != null) {
                buffer.clearSearchMatches();
            }
            countLabel.setText("");
            return;
        }
        if (regex.isSelected()) {
            String err = SearchMatcher.regexError(query);
            if (err != null) {
                matches = List.of();
                activeIndex = -1;
                buffer.clearSearchMatches();
                countLabel.setText("");
                status.accept(tr("find.badRegex", err));
                return;
            }
        }
        matches = SearchMatcher.matches(
                area.getText(), query, caseSensitive.isSelected(), regex.isSelected(), wholeWord.isSelected());
        if (matches.isEmpty()) {
            activeIndex = -1;
            buffer.clearSearchMatches();
            countLabel.setText("");
            status.accept(tr("find.notFound", query));
            return;
        }
        activeIndex = SearchMatcher.nextIndex(matches, searchAnchor, true);
        applyActive(buffer, area, false);
    }

    /** Cycles to the next match (a repeated C-s). */
    public void findNext() {
        navigate(true);
    }

    /** Cycles to the previous match (C-r while the bar is open). */
    public void findPrevious() {
        navigate(false);
    }

    /** Moves focus to the replace field (the bar is already showing the find/replace inputs). */
    public void focusReplace() {
        replaceField.requestFocus();
    }

    private void navigate(boolean forward) {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        if (area == null) {
            return;
        }
        if (matches.isEmpty()) {
            recompute();
            if (matches.isEmpty()) {
                return;
            }
        }
        int from = forward ? area.getSelection().getEnd() : area.getSelection().getStart();
        activeIndex = SearchMatcher.nextIndex(matches, from, forward);
        applyActive(buffer, area, true);
    }

    /** Selects/scrolls to the active match, refreshes the overlay + count. */
    private void applyActive(EditorBuffer buffer, CodeArea area, boolean focusEditor) {
        int[] m = matches.get(activeIndex);
        area.selectRange(m[0], m[1]);
        area.requestFollowCaret();
        buffer.setSearchMatches(matches, activeIndex);
        countLabel.setText(tr("find.count", activeIndex + 1, matches.size()));
        if (focusEditor) {
            // navigation via buttons/Enter keeps the find field focused for further typing
            findField.requestFocus();
        }
    }

    private void replaceCurrent() {
        CodeArea area = area();
        if (area == null) {
            return;
        }
        if (!area.getSelectedText().isEmpty()) {
            area.replaceSelection(replaceField.getText());
        }
        recompute();
        navigate(true);
    }

    private void replaceAll() {
        EditorBuffer buffer = activeBuffer.get();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            return;
        }
        String text = area.getText();
        String replacement = replaceField.getText();
        String result;
        int count;
        if (regex.isSelected()) {
            String err = SearchMatcher.regexError(query);
            if (err != null) {
                status.accept(tr("find.badRegex", err));
                return;
            }
            Matcher m = Pattern.compile(query, caseSensitive.isSelected() ? 0 : Pattern.CASE_INSENSITIVE)
                    .matcher(text);
            StringBuffer sb = new StringBuffer();
            count = 0;
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                count++;
            }
            m.appendTail(sb);
            result = sb.toString();
        } else {
            List<int[]> all =
                    SearchMatcher.matches(text, query, caseSensitive.isSelected(), false, wholeWord.isSelected());
            count = all.size();
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (int[] mm : all) {
                sb.append(text, i, mm[0]).append(replacement);
                i = mm[1];
            }
            sb.append(text.substring(i));
            result = sb.toString();
        }
        if (count > 0) {
            area.replaceText(result);
        }
        recompute();
        status.accept(tr("find.replaced", count));
    }
}
