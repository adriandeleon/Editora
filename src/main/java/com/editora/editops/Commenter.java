package com.editora.editops;

/**
 * Toggles comments — pure (no toolkit), so it is unit-tested. {@link EditorBuffer}/the controller
 * applies the returned {@link Edit}.
 *
 * <p>The style depends on the language. A <b>single</b> line uses the line comment when the language
 * has one (else a block comment); a <b>multi-line</b> selection uses the block/region comment when the
 * language has one (else line-comments each line). Toggling detects the existing comment and removes it.
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
        return useBlock ? toggleBlock(text, selStart, selEnd, style) : toggleLines(text, selStart, effEnd, style);
    }

    // --- line comments ----------------------------------------------------------------------------

    private static Edit toggleLines(String text, int selStart, int effEnd, CommentStyle style) {
        String token = style.line();
        int from = lineStart(text, selStart);
        int to = lineEnd(text, effEnd);
        String[] lines = text.substring(from, to).split("\n", -1);

        boolean allCommented = true;
        boolean anyContent = false;
        for (String line : lines) {
            if (!line.isBlank()) {
                anyContent = true;
                if (!line.strip().startsWith(token)) {
                    allCommented = false;
                    break;
                }
            }
        }
        boolean uncomment = anyContent && allCommented;

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

    private static String addLineComment(String line, String token) {
        int i = firstNonWs(line);
        if (i == line.length()) {
            return line; // skip blank lines
        }
        return line.substring(0, i) + token + " " + line.substring(i);
    }

    private static String removeLineComment(String line, String token) {
        int i = firstNonWs(line);
        if (!line.substring(i).startsWith(token)) {
            return line;
        }
        int after = i + token.length();
        if (after < line.length() && line.charAt(after) == ' ') {
            after++; // also drop the single space we inserted
        }
        return line.substring(0, i) + line.substring(after);
    }

    // --- block comments ---------------------------------------------------------------------------

    private static Edit toggleBlock(String text, int selStart, int selEnd, CommentStyle style) {
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

        String replacement;
        if (core.startsWith(bs) && core.endsWith(be) && core.length() >= bs.length() + be.length()) {
            String inner = stripOneSpaceEachSide(core.substring(bs.length(), core.length() - be.length()));
            replacement = lead + inner + trail;
        } else {
            replacement = lead + bs + " " + core + " " + be + trail;
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
