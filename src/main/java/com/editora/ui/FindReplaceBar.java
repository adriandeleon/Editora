package com.editora.ui;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.fxmisc.richtext.CodeArea;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

/** A non-modal find/replace bar operating on the currently active {@link CodeArea}. */
public class FindReplaceBar extends HBox {

    private final Supplier<CodeArea> activeArea;
    private final Consumer<String> status;

    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox caseSensitive = new CheckBox("Aa");
    private final CheckBox regex = new CheckBox(".*");

    public FindReplaceBar(Supplier<CodeArea> activeArea, Consumer<String> status) {
        this.activeArea = activeArea;
        this.status = status;
        getStyleClass().add("find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setVisible(false);
        setManaged(false);
        build();
    }

    private void build() {
        findField.setPromptText("Find");
        replaceField.setPromptText("Replace");

        Button next = new Button("Next");
        Button prev = new Button("Prev");
        Button replace = new Button("Replace");
        Button replaceAll = new Button("All");
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

        getChildren().addAll(new Label("Find:"), findField, prev, next,
                new Label("Replace:"), replaceField, replace, replaceAll,
                caseSensitive, regex, close);
    }

    public void show(boolean backward) {
        setVisible(true);
        setManaged(true);
        findField.requestFocus();
        findField.selectAll();
        if (backward) {
            status.accept("Reverse search");
        }
    }

    public void hideBar() {
        setVisible(false);
        setManaged(false);
        CodeArea area = activeArea.get();
        if (area != null) {
            area.requestFocus();
        }
    }

    public boolean isShown() {
        return isVisible();
    }

    private void findNext() {
        find(true);
    }

    private void findPrevious() {
        find(false);
    }

    private void find(boolean forward) {
        CodeArea area = activeArea.get();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            return;
        }
        String text = area.getText();
        int caret = area.getCaretPosition();
        int[] match = forward
                ? searchForward(text, query, caret)
                : searchBackward(text, query, Math.max(0, area.getSelection().getStart()));
        if (match == null) {
            // wrap around
            match = forward ? searchForward(text, query, 0)
                    : searchBackward(text, query, text.length());
        }
        if (match == null) {
            status.accept("Not found: " + query);
            return;
        }
        area.selectRange(match[0], match[1]);
        area.requestFollowCaret();
        status.accept("Match at " + match[0]);
    }

    private int[] searchForward(String text, String query, int from) {
        if (regex.isSelected()) {
            Matcher m = matcher(text, query);
            if (m != null && m.find(Math.min(from, text.length()))) {
                return new int[]{m.start(), m.end()};
            }
            return null;
        }
        int idx = caseSensitive.isSelected()
                ? text.indexOf(query, from)
                : text.toLowerCase().indexOf(query.toLowerCase(), from);
        return idx < 0 ? null : new int[]{idx, idx + query.length()};
    }

    private int[] searchBackward(String text, String query, int before) {
        if (regex.isSelected()) {
            Matcher m = matcher(text, query);
            if (m == null) {
                return null;
            }
            int[] last = null;
            while (m.find()) {
                if (m.end() <= before) {
                    last = new int[]{m.start(), m.end()};
                } else {
                    break;
                }
            }
            return last;
        }
        int start = Math.max(0, before - 1);
        int idx = caseSensitive.isSelected()
                ? text.lastIndexOf(query, start)
                : text.toLowerCase().lastIndexOf(query.toLowerCase(), start);
        return idx < 0 ? null : new int[]{idx, idx + query.length()};
    }

    private Matcher matcher(String text, String query) {
        try {
            int flags = caseSensitive.isSelected() ? 0 : Pattern.CASE_INSENSITIVE;
            return Pattern.compile(query, flags).matcher(text);
        } catch (PatternSyntaxException e) {
            status.accept("Bad regex: " + e.getDescription());
            return null;
        }
    }

    private void replaceCurrent() {
        CodeArea area = activeArea.get();
        if (area == null) {
            return;
        }
        if (!area.getSelectedText().isEmpty()) {
            area.replaceSelection(replaceField.getText());
        }
        findNext();
    }

    private void replaceAll() {
        CodeArea area = activeArea.get();
        String query = findField.getText();
        if (area == null || query.isEmpty()) {
            return;
        }
        String text = area.getText();
        String result;
        int count;
        if (regex.isSelected()) {
            Matcher m = matcher(text, query);
            if (m == null) {
                return;
            }
            StringBuffer sb = new StringBuffer();
            count = 0;
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replaceField.getText()));
                count++;
            }
            m.appendTail(sb);
            result = sb.toString();
        } else {
            int[] counter = {0};
            result = replacePlain(text, query, replaceField.getText(), counter);
            count = counter[0];
        }
        if (count > 0) {
            area.replaceText(result);
        }
        status.accept("Replaced " + count + " occurrence(s)");
    }

    private String replacePlain(String text, String query, String replacement, int[] counter) {
        StringBuilder sb = new StringBuilder();
        String haystack = caseSensitive.isSelected() ? text : text.toLowerCase();
        String needle = caseSensitive.isSelected() ? query : query.toLowerCase();
        int i = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, i)) >= 0) {
            sb.append(text, i, idx).append(replacement);
            i = idx + query.length();
            counter[0]++;
        }
        sb.append(text.substring(i));
        return sb.toString();
    }
}
