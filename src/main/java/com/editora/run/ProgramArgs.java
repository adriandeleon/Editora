package com.editora.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure tokenizer for a user-typed program-arguments string (unit-tested): whitespace-separated, with
 * single or double quotes grouping a token that contains spaces ({@code a "b c" 'd e'} → three args).
 * Quotes are stripped; there is no escape processing — this shapes a {@code ProcessBuilder} argv, not a
 * shell command line.
 */
public final class ProgramArgs {

    private ProgramArgs() {
    }

    /** Splits {@code text} into argv tokens; blank/null input yields an empty list. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }
}
