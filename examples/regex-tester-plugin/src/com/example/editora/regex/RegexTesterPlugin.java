package com.example.editora.regex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.ToolWindowSide;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A live regular-expression tester tool window: enter a pattern + flags + a test string and see the match
 * count, each match's span, and its capture groups — recomputed as you type. Uses {@link java.util.regex}.
 */
public class RegexTesterPlugin implements Plugin {

    private static final int MAX_MATCHES = 500;

    @Override
    public void start(PluginContext ctx) {
        TextField pattern = new TextField();
        pattern.setPromptText("Regular expression, e.g. (\\w+)@(\\w+)");
        CheckBox ignoreCase = new CheckBox("Ignore case");
        CheckBox multiline = new CheckBox("Multiline (^$)");
        CheckBox dotAll = new CheckBox("Dot matches newline");
        HBox flags = new HBox(12, ignoreCase, multiline, dotAll);

        TextArea subject = new TextArea();
        subject.setPromptText("Test string…");
        subject.setWrapText(true);
        VBox.setVgrow(subject, Priority.ALWAYS);

        TextArea results = new TextArea();
        results.setEditable(false);
        results.setWrapText(true);
        results.getStyleClass().add("regex-results");
        VBox.setVgrow(results, Priority.ALWAYS);

        Runnable update = () -> results.setText(evaluate(
                pattern.getText(), subject.getText(),
                ignoreCase.isSelected(), multiline.isSelected(), dotAll.isSelected()));

        pattern.textProperty().addListener((o, a, b) -> update.run());
        subject.textProperty().addListener((o, a, b) -> update.run());
        ignoreCase.selectedProperty().addListener((o, a, b) -> update.run());
        multiline.selectedProperty().addListener((o, a, b) -> update.run());
        dotAll.selectedProperty().addListener((o, a, b) -> update.run());

        Label title = new Label("Regex Tester");
        title.getStyleClass().add("tool-window-title");
        VBox box = new VBox(6, title, pattern, flags, new Label("Test string"), subject,
                new Label("Matches"), results);
        box.setPadding(new Insets(8));
        Region content = box;

        ctx.registerToolWindow("regex", "Regex Tester", ToolWindowSide.BOTTOM, content, null);
    }

    /** Compiles + runs the pattern, returning a human-readable report (or the syntax error). Pure-ish. */
    private static String evaluate(String regex, String text, boolean ic, boolean ml, boolean dotAll) {
        if (regex == null || regex.isEmpty()) {
            return "Enter a pattern.";
        }
        int flags = 0;
        if (ic) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (ml) {
            flags |= Pattern.MULTILINE;
        }
        if (dotAll) {
            flags |= Pattern.DOTALL;
        }
        Pattern p;
        try {
            p = Pattern.compile(regex, flags);
        } catch (PatternSyntaxException e) {
            return "Invalid pattern: " + e.getDescription() + " (index " + e.getIndex() + ")";
        }
        Matcher m = p.matcher(text == null ? "" : text);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find() && n < MAX_MATCHES) {
            n++;
            sb.append(n).append(". [").append(m.start()).append('-').append(m.end()).append("] ")
                    .append(quote(m.group())).append('\n');
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append("     group ").append(g).append(": ").append(quote(m.group(g))).append('\n');
            }
            if (m.end() == m.start()) { // zero-width match: avoid an infinite loop
                if (m.end() >= text.length()) {
                    break;
                }
                m.region(m.end() + 1, text.length());
            }
        }
        String header = n == 0 ? "No matches." : (n + (n == MAX_MATCHES ? "+ matches:\n" : " match(es):\n"));
        return header + sb;
    }

    private static String quote(String s) {
        return s == null ? "(null)" : "“" + s + "”";
    }
}
