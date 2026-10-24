package com.editora.editor.fold;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editor.RegexLiterals;
import com.editora.editor.language.LanguageRegistry;
import com.editora.typst.TypstOutline;

/**
 * Detects foldable regions in a document. A {@link Region} spans two or more lines and is collapsed
 * "into" its first line (the header stays visible; the lines after it are hidden), mirroring the way
 * RichTextFX's {@code foldParagraphs(start, end)} works.
 *
 * <p>Detection is delimiter-based and language-aware:
 * <ul>
 *   <li><b>Braces</b> (brace-delimited languages such as {@code java}, {@code json}, {@code c},
 *       {@code cpp}, {@code rust}, {@code go}, {@code kotlin}, {@code groovy}, {@code csharp},
 *       {@code css}): matched {@code &#123;&#125;} / {@code []} pairs, skipping delimiters inside
 *       strings, char literals, and {@code //} / {@code /* *&#47;} comments.</li>
 *   <li><b>XML</b> ({@code xml} / {@code html}, and by extension fxml): matched element tags, skipping
 *       comments, CDATA, processing instructions, doctypes, and self-closing tags.</li>
 *   <li><b>Markdown</b>: fenced code blocks (backticks or tildes), front matter, multi-line HTML comments
 *       and heading sections, ATX or setext (a heading folds down to the line before the next heading of
 *       the same or higher level).</li>
 *   <li><b>Shell</b>: its blocks as {@link com.editora.editops.ShellScan} reads them — functions and
 *       {@code { }} groups, {@code if … fi}, loops, {@code case … esac} and each multi-line item, multi-line
 *       {@code ( )} / {@code $( )}, and here-documents.</li>
 *   <li><b>Python</b>: blocks by indentation, plus statements that span lines (wrapped calls, multi-line
 *       literals, docstrings).</li>
 *   <li><b>Scala</b>: braces, plus Scala 3's optional-brace blocks by indentation (through their {@code end}
 *       marker).</li>
 *   <li><b>Clojure</b>: every form that spans lines — {@code ( )}, {@code [ ]}, {@code &#123; &#125;}.</li>
 *   <li><b>Other line/indentation-based languages</b> (ruby, yaml, ini, sql, powershell, batch) and
 *       plaintext: no delimiter folding.</li>
 * </ul>
 *
 * <p>Line indices are 0-based and correspond to {@code CodeArea} paragraph indices.
 */
public final class FoldRegions {

    /** A foldable span of lines, inclusive, with {@code startLine < endLine}. */
    public record Region(int startLine, int endLine) {}

    private static final Pattern XML_TOKEN = Pattern.compile(
            "<!--.*?-->" // comment
                    + "|<!\\[CDATA\\[.*?\\]\\]>" // CDATA
                    + "|<!DOCTYPE[^>]*>" // doctype
                    + "|<\\?.*?\\?>" // processing instruction
                    + "|<(/?)([\\w:.-]+)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>", // open/close/self-closing tag
            Pattern.DOTALL);

    private FoldRegions() {}

    /** Detects foldable regions for the given text and language name (see {@link LanguageRegistry}). */
    public static List<Region> detect(String text, String language) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return switch (language == null ? "" : language) {
            case "markdown" -> markdown(text);
            case "markwhen" -> markwhen(text);
            case "xml", "html", "astro" -> xml(text);
            // Brace-delimited languages fold on matched {} / [].
            case "java",
                    "json",
                    "c",
                    "cpp",
                    "rust",
                    "go",
                    "kotlin",
                    "groovy",
                    "csharp",
                    "css",
                    "php",
                    "terraform",
                    "caddyfile",
                    "proto",
                    "graphql",
                    "javascript",
                    "typescript",
                    "javascriptreact",
                    "typescriptreact",
                    "dot" -> braces(text, Syntax.of(language));
            case "scala" -> scala(text);
            case "clojure" -> lisp(text);
            case "typst" -> typst(text);
            case "http" -> http(text);
            case "shell" -> shell(text);
            case "python" -> python(text);
            // plaintext and the other line/indentation-based languages have no delimiter folding.
            default -> List.of();
        };
    }

    /**
     * Python's blocks, by indentation as Python reads it ({@link com.editora.editops.PythonScan}): a statement
     * ending in {@code :} folds over its body — every following line until a statement at its own indent or
     * shallower — and a statement that spans lines without opening a block (a wrapped call, a multi-line
     * list or dict, a docstring) folds over its own lines. A comment at the header's level or shallower does
     * not end a block, and trailing blank lines are left outside it.
     */
    static List<Region> python(String text) {
        List<com.editora.editops.PythonScan.Line> lines = com.editora.editops.PythonScan.scan(text);
        List<Region> out = new ArrayList<>();
        int n = lines.size();
        for (int i = 0; i < n; i++) {
            com.editora.editops.PythonScan.Line l = lines.get(i);
            if (!l.statement()) {
                continue;
            }
            int end = com.editora.editops.PythonScan.statementEnd(lines, i);
            com.editora.editops.PythonScan.Line last = lines.get(end);
            if (last.lastCode() == ':' && last.depthAtEnd() == 0 && !last.stringAtEnd()) {
                int w = pythonIndentWidth(text, l);
                for (int j = end + 1; j < n; j++) {
                    com.editora.editops.PythonScan.Line m = lines.get(j);
                    if (m.blank() || (m.commentOnly() && pythonIndentWidth(text, m) <= w)) {
                        continue;
                    }
                    if (m.statement() && pythonIndentWidth(text, m) <= w) {
                        break;
                    }
                    end = j;
                }
            }
            if (end > i) {
                out.add(new Region(i, end));
            }
        }
        return canonicalOrder(out);
    }

    /** A line's indent width as Python measures it (a tab advances to the next multiple of 8). */
    private static int pythonIndentWidth(String text, com.editora.editops.PythonScan.Line line) {
        int w = 0;
        for (int k = line.start(); k < line.indentEnd(); k++) {
            w = text.charAt(k) == '\t' ? (w / 8 + 1) * 8 : w + 1;
        }
        return w;
    }

    /** Shell blocks that span lines, from the function/statement header to the closing line. */
    static List<Region> shell(String text) {
        List<Region> out = new ArrayList<>();
        for (com.editora.editops.ShellScan.Block b :
                com.editora.editops.ShellScan.scan(text).blocks()) {
            if (b.closeLine() > b.headerLine()) {
                out.add(new Region(b.headerLine(), b.closeLine()));
            }
        }
        return canonicalOrder(out);
    }

    /** Languages whose block comments are {@code /* *}{@code /} — the same set {@link #detect} braces-folds. */
    private static final java.util.Set<String> SLASH_STAR_LANGUAGES = java.util.Set.of(
            "java",
            "json",
            "c",
            "cpp",
            "rust",
            "go",
            "kotlin",
            "groovy",
            "scala",
            "csharp",
            "css",
            "php",
            "terraform",
            "caddyfile",
            "proto",
            "graphql",
            "javascript",
            "typescript",
            "javascriptreact",
            "typescriptreact",
            "typst",
            "dot");

    /**
     * Multi-line block comments as foldable regions (VS Code's {@code foldAllBlockComments}):
     * {@code /* *}{@code /} spans for the brace languages, {@code <!-- -->} for xml/html. A separate pass
     * rather than part of {@link #detect}'s scanners so {@code FoldManager} can also fold <em>exactly
     * these</em> on command — a {@link Region} carries no kind, so the kind lives in which detector
     * produced it. Pure; unit-tested.
     */
    public static List<Region> blockComments(String text, String language) {
        if (text == null || text.isEmpty() || language == null) {
            return List.of();
        }
        if ("xml".equals(language) || "html".equals(language) || "astro".equals(language)) {
            return xmlComments(text);
        }
        if (!SLASH_STAR_LANGUAGES.contains(language)) {
            return List.of();
        }
        // The same string/comment state walk as braces(), recording block-comment spans instead of
        // delimiter pairs (sharing one parameterized walker would cost more clarity than two loops).
        List<Region> out = new ArrayList<>();
        int line = 0;
        int n = text.length();
        boolean inLineComment = false;
        int blockStartLine = -1;
        Syntax syntax = Syntax.of(language);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                inLineComment = false;
                continue;
            }
            if (inLineComment) {
                continue;
            }
            if (blockStartLine >= 0) {
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    if (line > blockStartLine) {
                        out.add(new Region(blockStartLine, line));
                    }
                    blockStartLine = -1;
                    i++;
                }
                continue;
            }
            if (syntax.startsLineComment(text, i)) {
                inLineComment = true;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                blockStartLine = line;
                i++;
            } else if (c == '/') {
                i = syntax.regexEnd(text, i);
            } else if (c == '"' || c == '\'' || c == '`') {
                int end = syntax.literalEnd(text, i);
                line += newlinesIn(text, i, end);
                i = end;
            }
        }
        return out;
    }

    /** Multi-line {@code <!-- -->} comments in xml/html, via the same tokenizer {@link #xml} uses. */
    private static List<Region> xmlComments(String text) {
        List<Region> out = new ArrayList<>();
        int[] newlines = newlineOffsets(text);
        Matcher m = XML_TOKEN.matcher(text);
        while (m.find()) {
            if (m.group(2) != null || !text.startsWith("<!--", m.start())) {
                continue; // a tag, or a CDATA/PI/doctype token
            }
            int start = lineOf(newlines, m.start());
            int end = lineOf(newlines, m.end() - 1);
            if (end > start) {
                out.add(new Region(start, end));
            }
        }
        return out;
    }

    /**
     * One marker family per comment style, matched against the <b>trimmed</b> line so indentation never
     * matters: {@code //#region} (VS Code JS/TS), {@code //region} (IntelliJ Java), {@code #region}
     * (C#, and Python/YAML/shell comments), {@code #pragma region} (C/C++), {@code <!-- #region -->}
     * (XML/HTML), {@code --region} (Lua/SQL). Case-insensitive; an optional label may follow.
     */
    private static final Pattern MARKER_START = Pattern.compile(
            "^(?://\\s*#?region\\b|#\\s?region\\b|#pragma\\s+region\\b|<!--\\s*#region\\b|--\\s*#?region\\b).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MARKER_END = Pattern.compile(
            "^(?://\\s*#?endregion\\b|#\\s?endregion\\b|#pragma\\s+endregion\\b|<!--\\s*#endregion\\b"
                    + "|--\\s*#?endregion\\b).*",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code #region}/{@code #endregion} marker regions (VS Code's {@code foldAllMarkerRegions}). Markers
     * nest (a stack pairs them); an unmatched start or end is ignored. <b>Not</b> detected in markdown or
     * markwhen — a {@code # region} heading is indistinguishable from the C#/Python marker spelling — nor
     * plaintext, where prose starting a line with "#region" suddenly growing a fold chevron would read as
     * a bug. Pure; unit-tested.
     */
    public static List<Region> markers(String text, String language) {
        if (text == null || text.isEmpty() || language == null) {
            return List.of();
        }
        if ("markdown".equals(language)
                || "markwhen".equals(language)
                || LanguageRegistry.plaintext().equals(language)) {
            return List.of();
        }
        List<Region> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (MARKER_START.matcher(t).matches()) {
                stack.push(i);
            } else if (MARKER_END.matcher(t).matches() && !stack.isEmpty()) {
                int start = stack.pop();
                if (i > start) {
                    out.add(new Region(start, i));
                }
            }
        }
        return out;
    }

    /**
     * The detector emission convention, made explicit for callers merging lists from several detectors:
     * <b>innermost-first</b> — end line ascending, then start line descending, so of two regions closing
     * on the same line the inner (later-starting) one comes first. {@code FoldManager.foldRecursivelyAtCaret}
     * collapses deepest-first and depends on it (the same convention {@code LspFolding} re-sorts server
     * answers into). Also dedups.
     */
    public static List<Region> canonicalOrder(List<Region> regions) {
        return regions.stream()
                .distinct()
                .sorted(java.util.Comparator.comparingInt(Region::endLine)
                        .thenComparing(java.util.Comparator.comparingInt(Region::startLine)
                                .reversed()))
                .toList();
    }

    // --- Brace/bracket matching (java, json, ...) ---

    /**
     * What the brace walker needs to know about a language's literals and comments. Without it every
     * {@code '} and {@code "} opened a string that ran — across newlines — to the next such character, so
     * one unpaired quote (a Rust lifetime, an apostrophe in a template literal, a {@code #} comment or JSX
     * text) swallowed every brace after it and the rest of the file lost its fold regions.
     *
     * @param slashSlash      {@code //} starts a line comment (not in CSS, where it is part of a URL)
     * @param hash            {@code #} starts a line comment
     * @param backtick        a backtick opens a multi-line literal (template literal, Go raw string)
     * @param backtickEscapes whether {@code \} escapes inside that literal (not in a Go raw string)
     * @param tripleQuote     {@code """} opens a multi-line text block / raw string
     * @param multiLineDouble a {@code "} string may span lines
     * @param lifetimes       a {@code '} may be a Rust lifetime or loop label rather than a char literal
     * @param regexLiterals   a {@code /} where an operand is expected opens a regular expression (JS/TS)
     */
    record Syntax(
            boolean slashSlash,
            boolean hash,
            boolean backtick,
            boolean backtickEscapes,
            boolean tripleQuote,
            boolean multiLineDouble,
            boolean lifetimes,
            boolean regexLiterals) {

        static Syntax of(String language) {
            String l = language == null ? "" : language;
            boolean script = l.startsWith("javascript") || l.startsWith("typescript");
            return new Syntax(
                    !l.equals("css"),
                    l.equals("terraform") || l.equals("php") || l.equals("graphql") || l.equals("caddyfile"),
                    script || l.equals("go"),
                    script,
                    l.equals("java")
                            || l.equals("kotlin")
                            || l.equals("groovy")
                            || l.equals("scala")
                            || l.equals("csharp"),
                    l.equals("rust") || l.equals("php"),
                    // Rust lifetimes, and Scala 2's 'symbol literals: a quote no quote closes one character later
                    l.equals("rust") || l.equals("scala"),
                    script);
        }

        /**
         * Index of the last character of the regular-expression literal opened by the {@code /} at {@code i}, or
         * {@code i} itself when that slash divides or the language has no regex literals (see {@link
         * RegexLiterals}). Without this a regex holding a backtick ({@code s.split(/`/)}) opened a template
         * literal that swallowed every brace below it. The caller has ruled out {@code //} and {@code /*}.
         */
        int regexEnd(String text, int i) {
            return regexLiterals ? RegexLiterals.end(text, i) : i;
        }

        /** Whether a line comment starts at {@code i}. */
        boolean startsLineComment(String text, int i) {
            char c = text.charAt(i);
            if (c == '/') {
                return slashSlash && i + 1 < text.length() && text.charAt(i + 1) == '/';
            }
            // PHP's #[Attribute] is code, not a comment.
            return c == '#' && hash && !(i + 1 < text.length() && text.charAt(i + 1) == '[');
        }

        /**
         * Index of the last character of the literal opened by the quote at {@code i}; {@code i} itself
         * when the quote opens nothing (a lifetime, a backtick in a language without backtick literals).
         * An ordinary string that is not closed on its line ends at the line's end.
         */
        int literalEnd(String text, int i) {
            int n = text.length();
            char quote = text.charAt(i);
            if (quote == '`' && !backtick) {
                return i;
            }
            if (quote == '"' && tripleQuote && text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                return close < 0 ? n - 1 : close + 2;
            }
            if (quote == '\'' && lifetimes) {
                boolean escaped = i + 1 < n && text.charAt(i + 1) == '\\';
                int close = i + 1 < n ? i + 1 + Character.charCount(text.codePointAt(i + 1)) : n;
                if (!escaped && !(close < n && text.charAt(close) == '\'')) {
                    return i; // 'a in <'a> or 'outer: — no closing quote after one character
                }
            }
            boolean multiLine = quote == '`' || (quote == '"' && multiLineDouble);
            boolean escapes = quote != '`' || backtickEscapes;
            for (int j = i + 1; j < n; j++) {
                char c = text.charAt(j);
                if (c == '\\' && escapes) {
                    j++; // the escaped character, a line continuation included
                } else if (c == quote) {
                    return j;
                } else if (c == '\n' && !multiLine) {
                    return j - 1;
                }
            }
            return n - 1;
        }
    }

    private static int newlinesIn(String text, int from, int to) {
        int count = 0;
        for (int k = from + 1; k <= to && k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<Region> braces(String text, Syntax syntax) {
        List<Region> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>(); // line of each open delimiter
        int line = 0;
        int n = text.length();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                inLineComment = false;
                continue;
            }
            if (inLineComment) {
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (syntax.startsLineComment(text, i)) {
                inLineComment = true;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '/') {
                i = syntax.regexEnd(text, i);
            } else if (c == '"' || c == '\'' || c == '`') {
                int end = syntax.literalEnd(text, i);
                line += newlinesIn(text, i, end);
                i = end;
            } else if (c == '{' || c == '[') {
                stack.push(line);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    int start = stack.pop();
                    if (line > start) {
                        out.add(new Region(start, line));
                    }
                }
            }
        }
        return out;
    }

    // --- Scala (braces, and Scala 3's indentation-based blocks) ---

    /**
     * Scala folds on its braces and, for Scala 3's optional braces, on indentation: a line ending with a block
     * opener ({@link com.editora.editops.ScalaIndent#opensIndentedBlock}: {@code class A:}, {@code def f =},
     * {@code case x =>}, {@code then} …) folds over the deeper lines below it, and through the {@code end}
     * marker at its own indent when one closes the block. Trailing blank lines stay outside the fold.
     */
    static List<Region> scala(String text) {
        List<Region> out = new ArrayList<>(braces(text, Syntax.of("scala")));
        String[] lines = text.split("\n", -1);
        boolean inComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // A line inside a /* */ comment opens nothing, whatever it ends with.
            boolean wasInComment = inComment;
            int open = line.lastIndexOf("/*");
            int close = line.lastIndexOf("*/");
            if (open > close) {
                inComment = true;
            } else if (close >= 0) {
                inComment = false;
            }
            if (wasInComment || !com.editora.editops.ScalaIndent.opensIndentedBlock(line)) {
                continue;
            }
            int width = indentWidth(line);
            int end = i;
            int j = i + 1;
            for (; j < lines.length; j++) {
                if (lines[j].isBlank()) {
                    continue;
                }
                if (indentWidth(lines[j]) <= width) {
                    break;
                }
                end = j;
            }
            if (end == i) {
                continue;
            }
            if (j < lines.length
                    && indentWidth(lines[j]) == width
                    && com.editora.editops.ScalaIndent.isEndMarker(lines[j])) {
                end = j;
            }
            out.add(new Region(i, end));
        }
        return canonicalOrder(out);
    }

    /** A line's indent width, a tab counting as four columns. */
    private static int indentWidth(String line) {
        int w = 0;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == ' ') {
                w++;
            } else if (c == '\t') {
                w += 4;
            } else {
                break;
            }
        }
        return w;
    }

    // --- Lisp forms (clojure) ---

    /**
     * Clojure folds on every form that spans lines: {@code ( )}, {@code [ ]} and {@code &#123; &#125;} (the
     * {@code #&#123;} set and {@code #(} function included). Brackets inside strings (and {@code #"…"} regexes,
     * which may span lines), {@code ;} comments and {@code \c} character literals ({@code \(}, {@code \;},
     * {@code "}) are skipped.
     */
    static List<Region> lisp(String text) {
        List<Region> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        int line = 0;
        int n = text.length();
        boolean inString = false;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                continue;
            }
            if (inString) {
                if (c == '\\' && i + 1 < n) {
                    if (text.charAt(i + 1) == '\n') {
                        line++;
                    }
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '\\' -> {
                    if (i + 1 < n && text.charAt(i + 1) != '\n') {
                        i++; // a character literal
                    }
                }
                case ';' -> {
                    int nl = text.indexOf('\n', i);
                    i = (nl < 0 ? n : nl) - 1; // the newline itself is counted on the next turn
                }
                case '"' -> inString = true;
                case '(', '[', '{' -> stack.push(line);
                case ')', ']', '}' -> {
                    if (!stack.isEmpty()) {
                        int start = stack.pop();
                        if (line > start) {
                            out.add(new Region(start, line));
                        }
                    }
                }
                default -> {}
            }
        }
        return out;
    }

    // --- XML element nesting (xml, fxml, html) ---

    private static List<Region> xml(String text) {
        List<Region> out = new ArrayList<>();
        int[] newlines = newlineOffsets(text);
        Deque<int[]> stack = new ArrayDeque<>(); // {nameHash, line}
        Deque<String> names = new ArrayDeque<>();
        Matcher m = XML_TOKEN.matcher(text);
        while (m.find()) {
            String name = m.group(2);
            if (name == null) {
                continue; // comment / CDATA / PI / doctype
            }
            boolean closing = "/".equals(m.group(1));
            boolean selfClosing = "/".equals(m.group(4));
            if (selfClosing) {
                continue;
            }
            int line = lineOf(newlines, m.start());
            if (closing) {
                // Pop until we find the matching open tag (lenient about unbalanced markup).
                while (!names.isEmpty() && !names.peek().equals(name)) {
                    names.pop();
                    stack.pop();
                }
                if (!names.isEmpty()) {
                    names.pop();
                    int startLine = stack.pop()[1];
                    if (line > startLine) {
                        out.add(new Region(startLine, line));
                    }
                }
            } else {
                names.push(name);
                stack.push(new int[] {0, line});
            }
        }
        return out;
    }

    // --- Markdown (code fences, front matter, comment blocks, heading sections, quotes, list items) ---

    /**
     * Folds on the document's real blocks ({@link com.editora.markdown.MarkdownBlocks}) and headings
     * ({@link com.editora.markdown.MarkdownOutline}) rather than on a second, simpler reading of them: a
     * {@code ~~~} fence folds, a fence inside a longer fence is its content and not a fence of its own, the
     * front matter and a multi-line HTML comment fold, a setext heading opens a section like an ATX one, and
     * a {@code #} line in code or in a commented-out section opens none.
     */
    /**
     * A {@code .http} file folds per request: from its {@code ###} separator (or, for the first request, its
     * request line) to the last non-blank line before the next one — the block JetBrains folds. Comments, directives
     * and {@code @var} lines between the separator and the request line fold with it.
     */
    static List<Region> http(String text) {
        List<Region> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (com.editora.http.HttpFile.Request r : com.editora.http.HttpFile.parse(text)) {
            int start = r.startLine();
            if (start > 0 && lines[start - 1].strip().startsWith("###")) {
                start--; // the separator (with its title) is the fold header
            } else {
                for (int k = start - 1; k >= 0; k--) {
                    String t = lines[k].strip();
                    if (t.startsWith("###")) {
                        start = k; // comments/directives sit between the separator and the request line
                        break;
                    }
                    boolean declaration = t.startsWith("@") && t.indexOf('=') > 1; // "@name = value" (HttpFile's rule)
                    if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("//") && !declaration) {
                        break;
                    }
                }
            }
            if (r.endLine() > start) {
                out.add(new Region(start, r.endLine()));
            }
        }
        return out;
    }

    private static List<Region> markdown(String text) {
        com.editora.markdown.MarkdownBlocks blocks = com.editora.markdown.MarkdownBlocks.scan(text);
        int count = blocks.lineCount();
        List<Region> out = new ArrayList<>();
        if (blocks.frontMatterEnd() > 0) {
            out.add(new Region(0, blocks.frontMatterEnd()));
        }
        for (com.editora.markdown.MarkdownBlocks.Fence fence : blocks.fences()) {
            if (fence.lastLine() > fence.openLine()) {
                out.add(new Region(fence.openLine(), fence.lastLine()));
            }
        }
        for (int i = 0; i < count; i++) {
            if (blocks.kind(i) == com.editora.markdown.MarkdownBlocks.Kind.HTML_COMMENT) {
                int end = i;
                while (end + 1 < count
                        && blocks.kind(end + 1) == com.editora.markdown.MarkdownBlocks.Kind.HTML_COMMENT) {
                    end++;
                }
                if (end > i) {
                    out.add(new Region(i, end));
                }
                i = end;
            }
        }
        List<com.editora.markdown.MarkdownOutline.Heading> headings =
                com.editora.markdown.MarkdownOutline.headings(text);
        for (int h = 0; h < headings.size(); h++) {
            int start = headings.get(h).line();
            int end = count - 1;
            for (int k = h + 1; k < headings.size(); k++) {
                if (headings.get(k).level() <= headings.get(h).level()) {
                    end = headings.get(k).line() - 1;
                    break;
                }
            }
            while (end > start && blocks.line(end).isBlank()) {
                end--;
            }
            if (end > start) {
                out.add(new Region(start, end));
            }
        }
        markdownQuotesAndItems(blocks, out);
        return out;
    }

    /**
     * A block quote of two or more lines folds, and so does a list item that runs over more than its own line
     * (wrapped text, a nested list, a code block inside it). An item ends at the next line indented no deeper
     * than its marker; trailing blank lines stay outside the fold.
     */
    private static void markdownQuotesAndItems(com.editora.markdown.MarkdownBlocks blocks, List<Region> out) {
        int count = blocks.lineCount();
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>(); // {indent, startLine}
        int lastContent = -1;
        int quoteStart = -1;
        for (int i = 0; i <= count; i++) {
            String line = i < count ? blocks.line(i) : "x"; // a sentinel at indent 0 closes everything
            boolean text = i < count && blocks.isText(i);
            boolean quoted = text && isQuoteLine(line);
            if (quoted && quoteStart < 0) {
                quoteStart = i;
            } else if (!quoted && quoteStart >= 0) {
                if (i - 1 > quoteStart) {
                    out.add(new Region(quoteStart, i - 1));
                }
                quoteStart = -1;
            }
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            while (!open.isEmpty() && open.peek()[0] >= indent) {
                int[] item = open.pop();
                if (lastContent > item[1]) {
                    out.add(new Region(item[1], lastContent));
                }
            }
            if (text && com.editora.markdown.MarkdownLine.parse(line).isListItem()) {
                open.push(new int[] {indent, i});
            }
            lastContent = i;
        }
    }

    private static boolean isQuoteLine(String line) {
        int i = 0;
        while (i < line.length() && i < 4 && line.charAt(i) == ' ') {
            i++;
        }
        return i < 4 && i < line.length() && line.charAt(i) == '>';
    }

    // --- Typst (= heading sections, plus the brace/bracket pairs of its code mode) ---

    /**
     * Typst folds on <em>both</em> its heading sections and its delimiter pairs.
     *
     * <p>It was brace-only, which meant a document folded at {@code #align(center)[…]} and {@code #table(…)}
     * but not at a single one of its sections — the structure a reader actually navigates by. Headings are
     * the Markdown rule with Typst's marker: a heading folds down to the line before the next heading of the
     * same or higher level, trailing blank lines trimmed off so the fold does not swallow the gap before the
     * next section.
     *
     * <p>The heading scan is {@link TypstOutline#headings} rather than a second copy of the rule, so folding
     * and the Structure outline can never disagree about what a section is — and folding inherits its
     * skipping of raw blocks and (nesting) block comments for free.
     */
    private static List<Region> typst(String text) {
        String[] lines = text.split("\n", -1);
        List<Region> out = new ArrayList<>(braces(text, Syntax.of("typst")));
        TypstOutline.Outline outline = TypstOutline.scan(text);
        // Raw blocks fold like Markdown's fenced code: braces() cannot see them, and a long embedded
        // listing is exactly the thing a reader wants out of the way.
        for (TypstOutline.RawBlock r : outline.rawBlocks()) {
            if (r.endLine() > r.startLine()) {
                out.add(new Region(r.startLine(), r.endLine()));
            }
        }
        List<TypstOutline.Heading> heads = outline.headings();
        for (int k = 0; k < heads.size(); k++) {
            TypstOutline.Heading h = heads.get(k);
            int end = lines.length - 1;
            for (int j = k + 1; j < heads.size(); j++) {
                if (heads.get(j).level() <= h.level()) {
                    end = heads.get(j).line() - 1;
                    break;
                }
            }
            while (end > h.line() && lines[end].isBlank()) {
                end--;
            }
            if (end > h.line()) {
                out.add(new Region(h.line(), end));
            }
        }
        return out;
    }

    // --- Markwhen (#-header sections, like Markdown headings but with no fenced code) ---

    private static List<Region> markwhen(String text) {
        String[] lines = text.split("\n", -1);
        List<Region> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int level = headingLevel(lines[i]);
            if (level == 0) {
                continue;
            }
            int end = lines.length - 1;
            for (int j = i + 1; j < lines.length; j++) {
                int l = headingLevel(lines[j]);
                if (l > 0 && l <= level) {
                    end = j - 1;
                    break;
                }
            }
            while (end > i && lines[end].isBlank()) {
                end--;
            }
            if (end > i) {
                out.add(new Region(i, end));
            }
        }
        return out;
    }

    /** ATX heading level (1-6), or 0 if the line is not a heading. */
    private static int headingLevel(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        if (i >= 1 && i <= 6 && i < line.length() && Character.isWhitespace(line.charAt(i))) {
            return i;
        }
        return 0;
    }

    private static int[] newlineOffsets(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] offsets = new int[count];
        int k = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets[k++] = i;
            }
        }
        return offsets;
    }

    /** 0-based line number for a character offset, via the precomputed newline offsets. */
    private static int lineOf(int[] newlines, int offset) {
        int lo = 0;
        int hi = newlines.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (newlines[mid] < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
