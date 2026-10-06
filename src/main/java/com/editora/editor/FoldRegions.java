package com.editora.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li><b>Markdown</b>: fenced code blocks (```` ``` ````) and heading sections (a heading folds down to
 *       the line before the next heading of the same or higher level).</li>
 *   <li><b>Line/indentation-based languages</b> (python, ruby, shell, yaml, ini, sql, powershell,
 *       batch) and plaintext: no delimiter folding.</li>
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
            case "typst" -> typst(text);
            // plaintext and line/indentation-based languages have no delimiter folding.
            default -> List.of();
        };
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
     */
    record Syntax(
            boolean slashSlash,
            boolean hash,
            boolean backtick,
            boolean backtickEscapes,
            boolean tripleQuote,
            boolean multiLineDouble,
            boolean lifetimes) {

        static Syntax of(String language) {
            String l = language == null ? "" : language;
            boolean script = l.startsWith("javascript") || l.startsWith("typescript");
            return new Syntax(
                    !l.equals("css"),
                    l.equals("terraform") || l.equals("php") || l.equals("graphql") || l.equals("caddyfile"),
                    script || l.equals("go"),
                    script,
                    l.equals("java") || l.equals("kotlin") || l.equals("groovy") || l.equals("csharp"),
                    l.equals("rust") || l.equals("php"),
                    l.equals("rust"));
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

    // --- Markdown (fenced code blocks + heading sections) ---

    private static List<Region> markdown(String text) {
        String[] lines = text.split("\n", -1);
        List<Region> out = new ArrayList<>();
        boolean[] inFence = new boolean[lines.length];

        int fenceStart = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("```")) {
                if (fenceStart < 0) {
                    fenceStart = i;
                } else {
                    for (int j = fenceStart; j <= i; j++) {
                        inFence[j] = true;
                    }
                    if (i > fenceStart) {
                        out.add(new Region(fenceStart, i));
                    }
                    fenceStart = -1;
                }
            }
        }

        for (int i = 0; i < lines.length; i++) {
            if (inFence[i]) {
                continue;
            }
            int level = headingLevel(lines[i]);
            if (level == 0) {
                continue;
            }
            int end = lines.length - 1;
            for (int j = i + 1; j < lines.length; j++) {
                if (!inFence[j]) {
                    int l = headingLevel(lines[j]);
                    if (l > 0 && l <= level) {
                        end = j - 1;
                        break;
                    }
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
