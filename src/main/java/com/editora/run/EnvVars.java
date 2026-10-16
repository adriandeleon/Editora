package com.editora.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a run configuration's environment-variable string into a name→value map. The text is tokenized with
 * the same quote-aware splitter as program arguments ({@link ProgramArgs#tokenize}), so a value containing
 * spaces works when quoted: {@code FOO=bar GREETING="hello world"}. Each token splits on its <b>first</b>
 * {@code =}, so a value may itself contain {@code =} (e.g. a base64 or connection string).
 *
 * <p>Tokens without an {@code =}, or with an empty name, are skipped rather than failing — a half-typed entry
 * shouldn't stop the launch. Insertion order is preserved. Pure and unit-tested.
 */
public final class EnvVars {

    private EnvVars() {}

    /**
     * The variables a run configuration sets, read the way a shell reads {@code NAME=value} pairs: quotes and
     * backslash escapes as in {@link ProgramArgs#parse}, and {@code $NAME} / {@code ${NAME}} expanded outside
     * single quotes — from a variable set earlier in the same text, else from {@code inherited} (the
     * environment the program would otherwise get). So {@code PATH=/opt/x/bin:$PATH} prepends to the real
     * PATH instead of replacing it with the literal text. Tokens without a name are skipped, as in
     * {@link #parse}.
     *
     * @throws ProgramArgs.UnclosedQuoteException when a quote is never closed
     */
    public static Map<String, String> resolve(String text, java.util.function.Function<String, String> inherited) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        // Tokenize without expansion first (only to find the pairs), then expand each token on its own so a
        // later value sees an earlier one: FOO=a BAR=$FOO.
        java.util.function.Function<String, String> lookup =
                name -> out.containsKey(name) ? out.get(name) : inherited == null ? null : inherited.apply(name);
        for (String raw : splitPairs(text)) {
            List<String> one = ProgramArgs.parse(raw, lookup);
            if (one.size() != 1) {
                continue;
            }
            String token = one.get(0);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return out;
    }

    /** {@code text} cut at unquoted, unescaped whitespace, each piece keeping its quotes and escapes. */
    private static List<String> splitPairs(String text) {
        List<String> pieces = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == 0 && Character.isWhitespace(c)) {
                if (!cur.isEmpty()) {
                    pieces.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(c);
            if (c == '\\' && quote != '\'' && i + 1 < text.length()) {
                cur.append(text.charAt(++i));
            } else if (quote == 0 && (c == '"' || c == '\'')) {
                quote = c;
            } else if (c == quote) {
                quote = 0;
            }
        }
        if (quote != 0) {
            throw new ProgramArgs.UnclosedQuoteException(quote);
        }
        if (!cur.isEmpty()) {
            pieces.add(cur.toString());
        }
        return pieces;
    }

    /** Name→value pairs from {@code text} ({@code ""}/null ⇒ an empty map). */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String token : ProgramArgs.tokenize(text)) {
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue; // no '=' at all, or an empty name like "=value"
            }
            out.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return out;
    }
}
