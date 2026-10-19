package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what the Clojure build tools' actions popups list from their project files: a {@code deps.edn}'s aliases,
 * a {@code bb.edn}'s tasks, a {@code project.clj}'s name. A small EDN reader — enough to walk the top-level map
 * and the one map below it, skipping strings, comments, {@code #_} forms and metadata — not a full one: a value is
 * never interpreted beyond telling a map from anything else. Pure.
 */
public final class ClojureProjects {

    private ClojureProjects() {}

    /** How an alias is run: {@code -M} (its {@code :main-opts}), {@code -X} ({@code :exec-fn}), {@code -T} (a tool). */
    public enum AliasKind {
        MAIN("-M"),
        EXEC("-X"),
        TOOL("-T");

        private final String flag;

        AliasKind(String flag) {
            this.flag = flag;
        }

        /** The CLI flag the alias joins ({@code -M:test}). */
        public String flag() {
            return flag;
        }
    }

    /** A {@code deps.edn} alias the popup can run, without its leading colon ({@code test}, {@code build}). */
    public record Alias(String name, AliasKind kind) {}

    /**
     * The aliases of {@code depsEdn} that run something, in file order: one with {@code :main-opts} runs as
     * {@code -M:<alias>}, one with {@code :exec-fn} as {@code -X:<alias>}, one with only {@code :ns-default} as a
     * tool ({@code -T:<alias>}). An alias that only adds dependencies or paths runs nothing on its own and is left
     * out. Unreadable text gives none.
     */
    public static List<Alias> aliases(String depsEdn) {
        List<Alias> out = new ArrayList<>();
        for (Entry alias : entriesOf(depsEdn, ":aliases")) {
            if (!alias.key().startsWith(":") || alias.key().length() < 2) {
                continue;
            }
            String value = alias.value();
            AliasKind kind = value.contains(":main-opts")
                    ? AliasKind.MAIN
                    : value.contains(":exec-fn")
                            ? AliasKind.EXEC
                            : value.contains(":ns-default") ? AliasKind.TOOL : null;
            if (kind != null) {
                out.add(new Alias(alias.key().substring(1), kind));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The task names of {@code bbEdn}'s {@code :tasks}, in file order: its symbol keys ({@code test},
     * {@code build:uberjar}), not the keyword options beside them ({@code :requires}, {@code :init}) nor a private
     * task ({@code -helper}), which {@code bb tasks} does not list either.
     */
    public static List<String> babashkaTasks(String bbEdn) {
        List<String> out = new ArrayList<>();
        for (Entry task : entriesOf(bbEdn, ":tasks")) {
            String key = task.key();
            if (!key.isEmpty() && !key.startsWith(":") && !key.startsWith("-") && !key.startsWith("\"")) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    private static final Pattern DEFPROJECT = Pattern.compile("\\(\\s*defproject\\s+(?:[\\w.\\-]+/)?([\\w.\\-]+)");

    /** The project name of a {@code project.clj} ({@code (defproject my-org/my-app "1.0"} → {@code my-app}), or null. */
    public static String leiningenName(String projectClj) {
        if (projectClj == null) {
            return null;
        }
        Matcher m = DEFPROJECT.matcher(projectClj);
        return m.find() ? m.group(1) : null;
    }

    // --- the EDN walk ------------------------------------------------------------------------------

    /** A map entry: the key's text and the value's. */
    record Entry(String key, String value) {}

    /** The entries of the map under {@code key} in {@code text}'s top-level map; empty when there is none. */
    static List<Entry> entriesOf(String text, String key) {
        if (text == null) {
            return List.of();
        }
        try {
            int top = skipSpace(text, 0);
            if (top >= text.length() || text.charAt(top) != '{') {
                return List.of();
            }
            for (Entry e : entries(text, top)) {
                if (e.key().equals(key)) {
                    String value = e.value();
                    int at = skipSpace(value, 0);
                    return at < value.length() && value.charAt(at) == '{' ? entries(value, at) : List.of();
                }
            }
        } catch (IndexOutOfBoundsException malformed) {
            return List.of(); // an unclosed form: the file is mid-edit
        }
        return List.of();
    }

    /** The entries of the map whose {@code {} is at {@code open}. */
    private static List<Entry> entries(String text, int open) {
        List<Entry> out = new ArrayList<>();
        int i = open + 1;
        while (true) {
            i = skipSpace(text, i);
            if (i >= text.length() || text.charAt(i) == '}') {
                return out;
            }
            int keyEnd = skipForm(text, i);
            String key = text.substring(i, keyEnd).strip();
            int valueStart = skipSpace(text, keyEnd);
            if (valueStart >= text.length() || text.charAt(valueStart) == '}') {
                return out;
            }
            int valueEnd = skipForm(text, valueStart);
            out.add(new Entry(key, text.substring(valueStart, valueEnd)));
            i = valueEnd;
        }
    }

    /** Past whitespace, commas, {@code ;} comments and {@code #_} discarded forms. */
    private static int skipSpace(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
            } else if (c == ';') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '#' && i + 1 < text.length() && text.charAt(i + 1) == '_') {
                i = skipForm(text, skipSpace(text, i + 2));
            } else {
                return i;
            }
        }
        return i;
    }

    /** The index just past the form at {@code i}. */
    private static int skipForm(String text, int i) {
        char c = text.charAt(i);
        switch (c) {
            case '"' -> {
                int j = i + 1;
                while (text.charAt(j) != '"') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                return j + 1;
            }
            case '\\' -> {
                int j = i + 2; // the character itself, then any letters of a named one (\newline)
                while (j < text.length() && Character.isLetterOrDigit(text.charAt(j))) {
                    j++;
                }
                return j;
            }
            case '(', '[', '{' -> {
                char close = c == '(' ? ')' : c == '[' ? ']' : '}';
                int j = skipSpace(text, i + 1);
                while (text.charAt(j) != close) {
                    j = skipSpace(text, skipForm(text, j));
                }
                return j + 1;
            }
            case '^', '\'', '`', '~', '@' -> {
                int j = i + 1;
                if (c == '~' && text.charAt(j) == '@') {
                    j++;
                }
                if (c == '^') {
                    j = skipSpace(text, skipForm(text, skipSpace(text, j))); // the metadata, then its form
                }
                return skipForm(text, skipSpace(text, j));
            }
            case '#' -> {
                char next = text.charAt(i + 1);
                if (next == '{' || next == '(' || next == '"') {
                    return skipForm(text, i + 1);
                }
                if (next == '\'') {
                    return skipForm(text, i + 2); // #'var
                }
                if (next == '#') {
                    return skipToken(text, i + 2); // ##Inf, ##NaN
                }
                return skipForm(text, skipSpace(text, skipToken(text, i + 1))); // a tag (#inst "…"), its form
            }
            default -> {
                return skipToken(text, i);
            }
        }
    }

    private static int skipToken(String text, int i) {
        int j = i;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (Character.isWhitespace(c) || c == ',' || "()[]{}\";".indexOf(c) >= 0) {
                break;
            }
            j++;
        }
        return Math.max(j, i + 1);
    }
}
