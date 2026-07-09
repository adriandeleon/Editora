package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Light shell-command tokenizer for the AI Agent chat's tool-call lines (the {@code ⚙ …} rows). Splits a
 * command string into colored {@link Span}s reusing the editor's {@code .text.<class>} token classes, so
 * the coloring tracks the active editor theme with no dedicated CSS. Deliberately "light": it colors the
 * leading command word, option flags, quoted strings, shell operators, {@code $variables}, and trailing
 * {@code #comments}, leaving plain arguments/paths in the default foreground.
 *
 * <p>Pure + directly unit-tested (the {@link AgentPanel#glyphFor} idiom); {@link AgentPanel} turns each
 * span into a styled JavaFX {@code Text} run.
 */
final class AgentCommandHighlighter {

    private AgentCommandHighlighter() {}

    /** A run of text and the token style class to pair with {@code text} ({@code null} = default foreground,
     *  no {@code .text} class). */
    record Span(String text, String styleClass) {}

    /** Tokenizes {@code command} into ordered spans whose concatenated {@link Span#text} equals the input. */
    static List<Span> spans(String command) {
        List<Span> out = new ArrayList<>();
        if (command == null || command.isEmpty()) {
            return out;
        }
        int n = command.length();
        int i = 0;
        boolean atCommandStart = true; // the first word of the line / of each pipeline segment
        while (i < n) {
            char c = command.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < n && Character.isWhitespace(command.charAt(i))) {
                    i++;
                }
                out.add(new Span(command.substring(start, i), null));
            } else if (c == '#') {
                // A '#' at a token boundary starts a comment to end of line (mid-word '#' stays in the word).
                out.add(new Span(command.substring(i), "comment"));
                break;
            } else if (c == '\'' || c == '"') {
                int start = i;
                char quote = c;
                i++;
                while (i < n && command.charAt(i) != quote) {
                    i++;
                }
                if (i < n) {
                    i++; // include the closing quote
                }
                out.add(new Span(command.substring(start, i), "string"));
                atCommandStart = false;
            } else if (isOperator(c)) {
                int start = i;
                while (i < n && isOperator(command.charAt(i))) {
                    i++;
                }
                out.add(new Span(command.substring(start, i), "operator"));
                atCommandStart = true; // the next word begins a new command
            } else {
                int start = i;
                while (i < n && !isWordBreak(command.charAt(i))) {
                    i++;
                }
                String word = command.substring(start, i);
                String cls;
                if (atCommandStart) {
                    cls = "function"; // the command / subcommand name
                    atCommandStart = false;
                } else if (word.charAt(0) == '-') {
                    cls = "constant"; // an option flag (-x / --long)
                } else if (word.charAt(0) == '$') {
                    cls = "constant"; // a variable reference
                } else {
                    cls = null; // an argument / path — kept plain
                }
                out.add(new Span(word, cls));
            }
        }
        return out;
    }

    private static boolean isOperator(char c) {
        return c == '|' || c == '&' || c == ';' || c == '<' || c == '>';
    }

    private static boolean isWordBreak(char c) {
        return Character.isWhitespace(c) || isOperator(c) || c == '\'' || c == '"';
    }
}
