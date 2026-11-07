package com.editora.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

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
    public static Map<String, String> resolve(
            String text, java.util.function.Function<String, ? extends @Nullable String> inherited) {
        return resolve(text, inherited, null);
    }

    /** An {@code @file} entry names a file that cannot be read. */
    public static final class EnvFileException extends RuntimeException {
        private final java.nio.file.Path file;

        public EnvFileException(java.nio.file.Path file, Throwable cause) {
            super(file.toString(), cause);
            this.file = file;
        }

        public java.nio.file.Path file() {
            return file;
        }
    }

    /**
     * {@link #resolve(String, java.util.function.Function)}, where an entry {@code @path} (no {@code =}; quoted when
     * the path has spaces) loads a {@code .env} file — VS Code's {@code envFile}. Its path is read against {@code
     * baseDir} (the working directory) when relative. Its variables are set where the entry stands: a later {@code
     * NAME=value} overrides them, and they can be used as {@code $NAME} after it. Before this, such an entry was
     * skipped like any other without a name.
     *
     * @throws EnvFileException when a named file cannot be read
     */
    public static Map<String, String> resolve(
            String text,
            java.util.function.Function<String, ? extends @Nullable String> inherited,
            java.nio.file.@Nullable Path baseDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        // Tokenize without expansion first (only to find the pairs), then expand each token on its own so a
        // later value sees an earlier one: FOO=a BAR=$FOO.
        java.util.function.Function<String, @Nullable String> lookup =
                name -> out.containsKey(name) ? out.get(name) : inherited == null ? null : inherited.apply(name);
        for (String raw : splitPairs(text)) {
            List<String> one = ProgramArgs.parse(raw, lookup);
            if (one.size() != 1) {
                continue;
            }
            String token = one.get(0);
            int eq = token.indexOf('=');
            if (eq < 0 && token.length() > 1 && token.charAt(0) == '@') {
                java.nio.file.Path file = java.nio.file.Path.of(token.substring(1));
                if (!file.isAbsolute() && baseDir != null) {
                    file = baseDir.resolve(file);
                }
                String content;
                try {
                    content = java.nio.file.Files.readString(file);
                } catch (java.io.IOException | java.io.UncheckedIOException e) {
                    throw new EnvFileException(file, e);
                }
                out.putAll(parseDotEnv(content, lookup));
                continue;
            }
            if (eq <= 0) {
                continue;
            }
            out.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return out;
    }

    /**
     * Pure: the variables of a {@code .env} file, as dotenv tools read one: {@code NAME=value} per line, blank lines
     * and {@code #} comments skipped, an optional {@code export} prefix. A value in single quotes is literal; in
     * double quotes {@code \n}, {@code \t}, {@code \"} and {@code \\} are escapes and {@code $NAME} / {@code
     * ${NAME}} expand; unquoted, it ends at a {@code " #"} comment, is trimmed, and expands. A name expands to a
     * variable set earlier in the file, else to {@code inherited}'s, else to nothing.
     */
    public static Map<String, String> parseDotEnv(
            String content, java.util.function.Function<String, ? extends @Nullable String> inherited) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.function.Function<String, @Nullable String> lookup =
                name -> out.containsKey(name) ? out.get(name) : inherited == null ? null : inherited.apply(name);
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ") || line.startsWith("export\t")) {
                line = line.substring(7).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            if (value.startsWith("'")) {
                int end = value.indexOf('\'', 1);
                value = end < 0 ? value.substring(1) : value.substring(1, end);
            } else if (value.startsWith("\"")) {
                StringBuilder sb = new StringBuilder();
                int i = 1;
                for (; i < value.length() && value.charAt(i) != '"'; i++) {
                    char c = value.charAt(i);
                    if (c == '\\' && i + 1 < value.length()) {
                        char n = value.charAt(++i);
                        sb.append(
                                switch (n) {
                                    case 'n' -> '\n';
                                    case 't' -> '\t';
                                    case 'r' -> '\r';
                                    default -> n;
                                });
                    } else {
                        sb.append(c);
                    }
                }
                value = expand(sb.toString(), lookup);
            } else {
                int comment = value.indexOf(" #");
                if (comment >= 0) {
                    value = value.substring(0, comment).strip();
                }
                value = expand(value, lookup);
            }
            out.put(name, value);
        }
        return out;
    }

    /** {@code $NAME} and {@code ${NAME}} in {@code text} replaced by {@code lookup}'s value ("" when unset). */
    private static String expand(String text, java.util.function.Function<String, @Nullable String> lookup) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '$' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            int start;
            int end;
            if (text.charAt(i + 1) == '{') {
                start = i + 2;
                end = text.indexOf('}', start);
                if (end < 0) {
                    out.append(c);
                    continue;
                }
                String value = lookup.apply(text.substring(start, end));
                out.append(value == null ? "" : value);
                i = end;
                continue;
            }
            start = i + 1;
            end = start;
            while (end < text.length()
                    && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')
                    && !(end == start && Character.isDigit(text.charAt(end)))) {
                end++;
            }
            if (end == start) {
                out.append(c);
                continue;
            }
            String value = lookup.apply(text.substring(start, end));
            out.append(value == null ? "" : value);
            i = end - 1;
        }
        return out.toString();
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
