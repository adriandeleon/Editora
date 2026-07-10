package com.editora.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            case "xml", "html" -> xml(text);
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
                    "typst",
                    "dot" -> braces(text);
            // plaintext and line/indentation-based languages have no delimiter folding.
            default -> List.of();
        };
    }

    // --- Brace/bracket matching (java, json, ...) ---

    private static List<Region> braces(String text) {
        List<Region> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>(); // line of each open delimiter
        int line = 0;
        int n = text.length();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringQuote = 0;
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
            if (stringQuote != 0) {
                if (c == '\\') {
                    i++; // skip escaped char
                } else if (c == stringQuote) {
                    stringQuote = 0;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '"' || c == '\'') {
                stringQuote = c;
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
