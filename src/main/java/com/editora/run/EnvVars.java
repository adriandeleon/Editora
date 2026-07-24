package com.editora.run;

import java.util.LinkedHashMap;
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
