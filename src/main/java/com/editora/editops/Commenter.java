package com.editora.editops;

/**
 * Toggles comments — pure (no toolkit), so it is unit-tested. {@link EditorBuffer}/the controller
 * applies the returned {@link Edit}.
 *
 * <p>The style depends on the language. A <b>single</b> line uses the line comment when the language
 * has one (else a block comment); a <b>multi-line</b> selection uses the block/region comment when the
 * language has one (else line-comments each line). Toggling detects the existing comment and removes it:
 * lines that are all line-commented are uncommented whatever the selection's shape. Block comments do not
 * nest, so text that already holds one is line-commented instead, or — in a language with block comments
 * only — left alone ({@code null}).
 */
public final class Commenter {

    private Commenter() {}

    /** A language's comment tokens; any field may be null when that form is unsupported. */
    public record CommentStyle(String line, String blockStart, String blockEnd) {
        public boolean hasLine() {
            return line != null && !line.isEmpty();
        }

        public boolean hasBlock() {
            return blockStart != null && blockEnd != null;
        }
    }

    /** Replace {@code [from, to)} with {@code replacement}, then select {@code [selStart, selEnd]}. */
    public record Edit(int from, int to, String replacement, int selStart, int selEnd) {}

    public static CommentStyle styleFor(String language) {
        return switch (language == null ? "" : language) {
            case "java",
                    "c",
                    "cpp",
                    "csharp",
                    "rust",
                    "go",
                    "kotlin",
                    "groovy",
                    "json",
                    "php",
                    "javascript",
                    "typescript",
                    "javascriptreact",
                    "typescriptreact",
                    "proto",
                    "typst",
                    "dot" -> new CommentStyle("//", "/*", "*/");
            case "plantuml" -> new CommentStyle("'", "/'", "'/");
            case "css" -> new CommentStyle(null, "/*", "*/");
            case "sql" -> new CommentStyle("--", "/*", "*/");
            case "lua" -> new CommentStyle("--", "--[[", "]]");
            case "terraform" -> new CommentStyle("#", "/*", "*/");
            case "python",
                    "shell",
                    "yaml",
                    "ruby",
                    "dockerfile",
                    "toml",
                    "http",
                    "systemd",
                    "desktop",
                    "dotenv",
                    "ssh-config",
                    "git-config",
                    "crontab",
                    "caddyfile",
                    "hosts",
                    "fstab",
                    "properties",
                    "deb822",
                    "apt-sources",
                    "interfaces",
                    "makefile",
                    "just",
                    "gitattributes",
                    "ignore",
                    "graphql" -> new CommentStyle("#", null, null);
            case "powershell" -> new CommentStyle("#", "<#", "#>");
            case "ini" -> new CommentStyle(";", null, null);
            case "batchfile" -> new CommentStyle("REM", null, null);
            case "markwhen" -> new CommentStyle("//", null, null);
            case "xml", "html", "markdown" -> new CommentStyle(null, "<!--", "-->");
            default -> new CommentStyle(null, null, null); // plaintext: no comment syntax
        };
    }

    /** Computes the toggle edit for the selection {@code [selStart, selEnd]}, or null if unsupported. */
    public static Edit toggle(String text, int selStart, int selEnd, CommentStyle style) {
        // A selection ending exactly at a line start doesn't include that trailing line.
        int effEnd = selEnd > selStart && lineStart(text, selEnd) == selEnd ? selEnd - 1 : selEnd;
        boolean multi = lineStart(text, selStart) != lineStart(text, effEnd);

        if (!style.hasLine() && !style.hasBlock()) {
            return null; // no comment syntax for this language
        }
        // Multi-line prefers a block/region comment; a single line prefers a line comment.
        boolean useBlock = multi ? style.hasBlock() : !style.hasLine();
        if (useBlock && style.hasLine() && allLineCommented(selectedLines(text, selStart, effEnd), style.line())) {
            useBlock = false; // lines commented one at a time are uncommented, not wrapped in a block
        }
        Edit edit = useBlock
                ? toggleBlock(text, selStart, selEnd, effEnd, style)
                : toggleLines(text, selStart, effEnd, style);
        if (edit == null || selStart != selEnd || edit.selStart() == edit.selEnd()) {
            return edit; // a selection stays selected; the block form has already placed a bare caret
        }
        // A bare caret stays a bare caret, on the text it was on: leaving the toggled line selected meant
        // the next typed character (or Enter) replaced it.
        int caret = caretAfter(text.substring(edit.from(), edit.to()), edit.replacement(), selStart - edit.from());
        return new Edit(edit.from(), edit.to(), edit.replacement(), edit.from() + caret, edit.from() + caret);
    }

    /**
     * Where a caret at {@code rel} in {@code before} lands once it has become {@code after}: anchored to the
     * unchanged text after it when there is some (so it rides along with the code as a marker is inserted
     * or removed in front), else to the unchanged text before it.
     */
    static int caretAfter(String before, String after, int rel) {
        int max = Math.min(before.length(), after.length());
        int prefix = 0;
        while (prefix < max && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        int fromEnd = before.length() - rel;
        if (fromEnd <= suffix) {
            return after.length() - fromEnd;
        }
        return rel <= prefix ? rel : Math.min(after.length(), prefix);
    }

    // --- line comments ----------------------------------------------------------------------------

    private static Edit toggleLines(String text, int selStart, int effEnd, CommentStyle style) {
        String token = style.line();
        int from = lineStart(text, selStart);
        int to = lineEnd(text, effEnd);
        String[] lines = selectedLines(text, selStart, effEnd);
        boolean uncomment = allLineCommented(lines, token);

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(uncomment ? removeLineComment(lines[i], token) : addLineComment(lines[i], token));
        }
        String replacement = out.toString();
        return new Edit(from, to, replacement, from, from + replacement.length());
    }

    /** The whole lines the selection {@code [selStart, effEnd]} touches. */
    private static String[] selectedLines(String text, int selStart, int effEnd) {
        return text.substring(lineStart(text, selStart), lineEnd(text, effEnd)).split("\n", -1);
    }

    /** Whether every non-blank line starts with the line-comment {@code token} (and there is one). */
    private static boolean allLineCommented(String[] lines, String token) {
        boolean anyContent = false;
        for (String line : lines) {
            if (!line.isBlank()) {
                anyContent = true;
                if (!startsWithToken(line.strip(), token)) {
                    return false;
                }
            }
        }
        return anyContent;
    }

    /**
     * Whether {@code s} begins with the line-comment {@code token}. A token that is a word (batch
     * {@code REM}) matches in any case and only as a whole word, so {@code rem x} is a comment and
     * {@code REMOTE_HOST=1} is not.
     */
    static boolean startsWithToken(String s, String token) {
        if (!Character.isLetter(token.charAt(token.length() - 1))) {
            return s.startsWith(token);
        }
        return s.regionMatches(true, 0, token, 0, token.length())
                && (s.length() == token.length()
                        || !Character.isLetterOrDigit(s.charAt(token.length())) && s.charAt(token.length()) != '_');
    }

    private static String addLineComment(String line, String token) {
        int i = firstNonWs(line);
        if (i == line.length()) {
            return line; // skip blank lines
        }
        return line.substring(0, i) + token + " " + line.substring(i);
    }

    private static String removeLineComment(String line, String token) {
        int i = firstNonWs(line);
        if (!startsWithToken(line.substring(i), token)) {
            return line;
        }
        int after = i + token.length();
        if (after < line.length() && line.charAt(after) == ' ') {
            after++; // also drop the single space we inserted
        }
        return line.substring(0, i) + line.substring(after);
    }

    // --- block comments ---------------------------------------------------------------------------

    private static Edit toggleBlock(String text, int selStart, int selEnd, int effEnd, CommentStyle style) {
        int from;
        int to;
        if (selStart == selEnd) { // no selection: comment the current line's content
            int ls = lineStart(text, selStart);
            from = ls + firstNonWs(text.substring(ls, lineEnd(text, selStart)));
            to = lineEnd(text, selStart);
            if (from >= to) { // blank line
                from = ls;
            }
        } else {
            from = selStart;
            to = selEnd;
        }
        String r = text.substring(from, to);
        String lead = r.substring(0, r.length() - stripLeading(r).length());
        String trail = r.substring(stripTrailing(r).length());
        String core = r.strip();
        String bs = style.blockStart();
        String be = style.blockEnd();
        if (core.isEmpty()) {
            if (selStart != selEnd) {
                return new Edit(from, to, r, from, to); // only whitespace selected: nothing to comment
            }
            trail = ""; // `lead` is the whole blank line already
        }

        // One comment only: a selection that merely starts with one comment and ends with another
        // ("/* a */ code /* b */") is not "already commented".
        boolean wrapped = core.startsWith(bs)
                && core.endsWith(be)
                && core.length() >= bs.length() + be.length()
                && core.indexOf(be, bs.length()) == core.length() - be.length();
        if (!wrapped && core.contains(be)) {
            // Block comments do not nest: the first terminator inside would end the new comment early and
            // leave the rest as broken code, which a second toggle then wraps again.
            if (!style.hasLine()) {
                return null;
            }
            // (A selection that starts with one comment and ends with another is still wrapped, as before.)
            if (!(core.startsWith(bs) && core.endsWith(be))) {
                return toggleLines(text, selStart, effEnd, style);
            }
        }
        String replacement;
        int caret; // where a bare caret lands, computed rather than diffed: `<p>` and `<!-- <p>` share a `<`
        int rel = selStart - from - lead.length();
        if (wrapped) {
            String raw = core.substring(bs.length(), core.length() - be.length());
            String inner = stripOneSpaceEachSide(raw);
            replacement = lead + inner + trail;
            int opener = bs.length() + (raw.startsWith(" ") ? 1 : 0);
            caret = lead.length() + Math.clamp(rel - opener, 0, inner.length());
        } else {
            replacement = lead + bs + " " + core + " " + be + trail;
            caret = lead.length() + bs.length() + 1 + Math.clamp(rel, 0, core.length());
        }
        if (selStart == selEnd) {
            return new Edit(from, to, replacement, from + caret, from + caret);
        }
        return new Edit(from, to, replacement, from, from + replacement.length());
    }

    private static String stripOneSpaceEachSide(String s) {
        if (s.startsWith(" ")) {
            s = s.substring(1);
        }
        if (s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static int firstNonWs(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String stripTrailing(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return s.substring(0, i);
    }

    private static int lineStart(String text, int offset) {
        int nl = text.lastIndexOf('\n', offset - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEnd(String text, int offset) {
        int nl = text.indexOf('\n', offset);
        return nl < 0 ? text.length() : nl;
    }
}
