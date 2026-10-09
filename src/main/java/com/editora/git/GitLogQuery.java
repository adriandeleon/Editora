package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A history search typed into the Git Log's filter box, parsed into the {@code git log} options that run it
 * over the whole history. Pure and toolkit-free.
 *
 * <p>The query is a list of blank-separated terms; double quotes keep blanks inside one term
 * ({@code author:"Ada Lovelace"}, {@code "exact phrase"}):
 *
 * <ul>
 *   <li>{@code author:name} — commits whose author name or e-mail contains {@code name} (several: any of them);
 *   <li>{@code content:text} or {@code -Stext} — commits that add or remove an occurrence of {@code text}
 *       (git's pickaxe; the last one given wins, git takes one);
 *   <li>{@code since:date} / {@code until:date} (also {@code after:} / {@code before:}) — anything git
 *       reads as a date: {@code 2026-01-31}, {@code "2 weeks ago"}, {@code yesterday};
 *   <li>{@code path:glob} (also {@code file:}) — commits touching a matching path, from the repository root;
 *   <li>anything else — including a term with an unknown or empty key — is message text; a commit must
 *       contain every such term.
 * </ul>
 *
 * Matching ignores case and treats every term as literal text, never as a regular expression.
 */
public record GitLogQuery(
        List<String> message, List<String> authors, String content, String since, String until, List<String> paths) {

    /** The search that matches every commit. */
    public static final GitLogQuery NONE = new GitLogQuery(List.of(), List.of(), "", "", "", List.of());

    public GitLogQuery {
        message = List.copyOf(message);
        authors = List.copyOf(authors);
        paths = List.copyOf(paths);
    }

    public boolean isEmpty() {
        return message.isEmpty()
                && authors.isEmpty()
                && content.isEmpty()
                && since.isEmpty()
                && until.isEmpty()
                && paths.isEmpty();
    }

    /** Whether the search reads file contents (the pickaxe), which is far slower than reading commits. */
    public boolean readsContent() {
        return !content.isEmpty();
    }

    public static GitLogQuery parse(String text) {
        List<String> message = new ArrayList<>();
        List<String> authors = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        String content = "";
        String since = "";
        String until = "";
        List<String> terms = terms(text);
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            if (term.equals("-S") && i + 1 < terms.size()) {
                content = terms.get(++i);
                continue;
            }
            if (term.startsWith("-S") && term.length() > 2) {
                content = term.substring(2);
                continue;
            }
            int colon = term.indexOf(':');
            String value = colon < 0 ? "" : term.substring(colon + 1);
            if (value.isEmpty()) {
                message.add(term);
                continue;
            }
            switch (term.substring(0, colon).toLowerCase(Locale.ROOT)) {
                case "author" -> authors.add(value);
                case "content" -> content = value;
                case "since", "after" -> since = value;
                case "until", "before" -> until = value;
                case "path", "file" -> paths.add(value);
                default -> message.add(term);
            }
        }
        return new GitLogQuery(message, authors, content, since, until, paths);
    }

    /** This search without its {@code path:} terms — what is left of it in a file history, which has one path. */
    public GitLogQuery withoutPaths() {
        return paths.isEmpty() ? this : new GitLogQuery(message, authors, content, since, until, List.of());
    }

    /**
     * {@code text} without its {@code path:} / {@code file:} terms, the other terms as they were typed
     * (quotes kept). A file history ignores those terms, so the header must not show them as searched for.
     */
    public static String withoutPathTerms(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int start = -1;
        boolean quoted = false;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (c == '"') {
                quoted = !quoted;
            }
            boolean blank = i == text.length() || ((c < 0x20 || c == 0x7f || c == ' ') && !quoted);
            if (!blank) {
                start = start < 0 ? i : start;
            } else if (start >= 0) {
                String raw = text.substring(start, i);
                if (parse(raw).paths().isEmpty()) {
                    kept.append(kept.isEmpty() ? "" : " ").append(raw);
                }
                start = -1;
            }
        }
        return kept.toString();
    }

    /**
     * Splits on blanks outside double quotes and drops the quotes: {@code author:"Ada L" fix} is the two
     * terms {@code author:Ada L} and {@code fix}. An unclosed quote runs to the end of the text. Control
     * characters (a NUL cannot be passed in an argument at all) count as blanks.
     */
    static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        if (text == null) {
            return terms;
        }
        StringBuilder term = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c < 0x20 || c == 0x7f || (c == ' ' && !quoted)) {
                if (quoted) {
                    term.append(' ');
                } else if (!term.isEmpty()) {
                    terms.add(term.toString());
                    term.setLength(0);
                }
            } else {
                term.append(c);
            }
        }
        if (!term.isEmpty()) {
            terms.add(term.toString());
        }
        return terms;
    }

    /**
     * The {@code git log} options of this search. Each value is attached to its option, so a term that
     * begins with {@code -} is still a value.
     */
    public List<String> options() {
        List<String> options = new ArrayList<>();
        if (isEmpty()) {
            return options;
        }
        if (!message.isEmpty() || !authors.isEmpty() || !content.isEmpty()) {
            options.add("--regexp-ignore-case");
            options.add("--fixed-strings");
        }
        for (String term : message) {
            options.add("--grep=" + term);
        }
        if (message.size() > 1) {
            options.add("--all-match");
        }
        for (String author : authors) {
            options.add("--author=" + author);
        }
        if (!content.isEmpty()) {
            options.add("-S" + content);
        }
        if (!since.isEmpty()) {
            options.add("--since=" + since);
        }
        if (!until.isEmpty()) {
            options.add("--until=" + until);
        }
        return options;
    }

    /**
     * The pathspecs of the {@code path:} terms, to go after {@code --}. {@code :(top)} anchors the pattern at
     * the repository root and, being the pathspec's one magic prefix, makes whatever follows the pattern —
     * a term such as {@code :!secret} or {@code :(exclude)x} cannot switch magic on.
     */
    public List<String> pathspecs() {
        List<String> specs = new ArrayList<>();
        for (String path : paths) {
            specs.add(":(top)" + path);
        }
        return specs;
    }
}
