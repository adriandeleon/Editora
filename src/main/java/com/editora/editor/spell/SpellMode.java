package com.editora.editor.spell;

import java.util.BitSet;
import java.util.Collection;
import java.util.function.IntFunction;

import com.editora.text.HtmlElements;
import com.editora.text.HtmlRegions;
import com.editora.text.LanguageRegistry;

/**
 * Which of a buffer's words spell check looks at, decided by its language. Pure.
 *
 * <ul>
 *   <li>{@link #PROSE} and {@link #MARKDOWN}: every word, except inline code and links (and, in Markdown,
 *       fenced code blocks, front matter, link targets and definitions, HTML tags and lint directives).
 *   <li>{@link #HTML} and {@link #TYPST}: the document's text content — what is left once tags, attributes,
 *       strings, math and code are taken out — plus comments. They used to be treated as code, so the prose
 *       of a web page or a Typst paper was never checked at all.
 *   <li>{@link #CODE}: comments and strings only.
 * </ul>
 */
public enum SpellMode {
    PROSE(SpellChecker.Syntax.PLAIN),
    MARKDOWN(SpellChecker.Syntax.PLAIN),
    HTML(SpellChecker.Syntax.HTML),
    TYPST(SpellChecker.Syntax.TYPST),
    CODE(SpellChecker.Syntax.PLAIN);

    private final SpellChecker.Syntax syntax;

    SpellMode(SpellChecker.Syntax syntax) {
        this.syntax = syntax;
    }

    /** How a line of this kind of buffer is cut into tokens. */
    public SpellChecker.Syntax syntax() {
        return syntax;
    }

    /** The mode for a language name as {@link LanguageRegistry} reports it; null is plain text. */
    public static SpellMode forLanguage(String language) {
        if (language == null || LanguageRegistry.PLAINTEXT.equals(language)) {
            return PROSE;
        }
        return switch (language) {
            case "markdown" -> MARKDOWN;
            case "html", HtmlElements.TEMPLATE_LANGUAGE, HtmlElements.HANDLEBARS_LANGUAGE -> HTML;
            case "typst" -> TYPST;
            default -> CODE;
        };
    }

    /** Whether a word carrying the syntax classes {@code style} is one this mode checks. */
    public boolean eligible(Collection<String> style) {
        switch (this) {
            case PROSE, MARKDOWN -> {
                // Skip Markdown inline `code` and links/URLs; check the rest (prose, headings, bold/italic).
                return !style.contains("code") && !style.contains("link");
            }
            case HTML, TYPST -> {
                // Text content carries no class at all, or only a presentational one. Anything else — a tag
                // or attribute name, a string (an attribute value, Typst math), a keyword — is markup.
                for (String c : style) {
                    if (!c.equals("comment") && !c.equals("heading") && !c.equals("bold") && !c.equals("italic")) {
                        return false;
                    }
                }
                return true;
            }
            default -> {
                return style.contains("comment") || style.contains("string"); // code: only comments/strings
            }
        }
    }

    /** Whether this mode has code lines at all — the only modes whose scan reads the document. */
    public boolean marksCodeLines() {
        return this == MARKDOWN || this == HTML || this == TYPST;
    }

    /**
     * {@link #codeLines(int, IntFunction)} over a whole text snapshot — one paragraph per {@code \n}, as
     * RichTextFX splits it — for a scan that runs away from the FX thread.
     */
    public BitSet codeLines(String text) {
        if (!marksCodeLines()) {
            return new BitSet();
        }
        String[] lines = text.split("\n", -1);
        return codeLines(lines.length, i -> lines[i]);
    }

    /**
     * The 0-based lines that are code as a whole and so never checked, whatever their words are styled as:
     * Markdown fenced blocks, the lines an HTML page's {@code <script>}, {@code <style>}, {@code <pre>} or {@code <code>}
     * content covers whole (the rest is by column: {@link #skips}), and
     * Typst lines that open with a {@code #} instruction. Empty for prose and code buffers.
     */
    public BitSet codeLines(int count, IntFunction<String> lineAt) {
        return switch (this) {
            case MARKDOWN -> markdownCodeLines(count, lineAt);
            case HTML -> skips(joined(count, lineAt)).lines(); // the lines that are code as a whole
            case TYPST -> typstCodeLines(count, lineAt);
            default -> new BitSet();
        };
    }

    private static String joined(int count, IntFunction<String> lineAt) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                b.append('\n');
            }
            b.append(lineAt.apply(i));
        }
        return b.toString();
    }

    /**
     * Markdown's lines that hold no prose: fenced code, a leading YAML front-matter block (keys, slugs and
     * tags are identifiers), and link reference definitions ({@code [label]: target "title"} — a label and a
     * URL; a footnote definition {@code [^1]: …} is prose and stays checked).
     */
    static BitSet markdownCodeLines(int count, IntFunction<String> lineAt) {
        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            lines[i] = lineAt.apply(i);
        }
        com.editora.markdown.MarkdownBlocks blocks = com.editora.markdown.MarkdownBlocks.scan(lines);
        BitSet code = new BitSet();
        for (int i = 0; i < count; i++) {
            // Comments and raw HTML blocks stay checked (their text is prose; prose() blanks the tags).
            if (blocks.isCode(i)
                    || blocks.kind(i) == com.editora.markdown.MarkdownBlocks.Kind.FRONT_MATTER
                    || (blocks.isText(i)
                            && REFERENCE_DEFINITION.matcher(lines[i]).lookingAt())) {
                code.set(i);
            }
        }
        return code;
    }

    private static final java.util.regex.Pattern REFERENCE_DEFINITION =
            java.util.regex.Pattern.compile(" {0,3}\\[(?!\\^)[^\\]]+\\]:\\s*\\S");

    /** What {@link #prose} blanks in a Markdown line; see there. */
    private static final java.util.regex.Pattern MARKDOWN_NOT_PROSE = java.util.regex.Pattern.compile(
            // a link's or image's target, "(dest "title")", behind the closing bracket of its text
            "(?<=\\])\\((?:<[^>\\n]*>|[^()\\s]*(?:\\([^()]*\\)[^()\\s]*)*)(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)"
                    // a reference link's label, "[text][label]"
                    + "|(?<=\\])\\[[^\\]]*\\]"
                    // the linter's own directives
                    + "|<!--\\s*markdownlint-[^>]*-->"
                    // an HTML tag or an <autolink>
                    + "|</?[A-Za-z][^<>]*>");

    /**
     * {@code line} with what is not prose blanked out, same length, for cutting into words. Only Markdown
     * changes it: a link's target, a reference label, an HTML tag and a lint directive become spaces. That
     * both drops them and separates what they touched — the checker judges a whole whitespace-delimited
     * token at once, so {@code [text](https://…)} or {@code <kbd>Ctrl</kbd>} read as one URL-like token and
     * the text in it was never checked.
     */
    public String prose(String line) {
        if (this == HTML) {
            return htmlProse(line);
        }
        if (this != MARKDOWN || (line.indexOf(']') < 0 && line.indexOf('<') < 0)) {
            return line;
        }
        java.util.regex.Matcher m = MARKDOWN_NOT_PROSE.matcher(line);
        if (!m.find()) {
            return line;
        }
        char[] out = line.toCharArray();
        do {
            java.util.Arrays.fill(out, m.start(), m.end(), ' ');
        } while (m.find());
        return new String(out);
    }

    /** The elements whose content an HTML page's spell check skips: code, not prose (HTML review C13). */
    static final java.util.Set<String> HTML_CODE_ELEMENTS = java.util.Set.of("pre", "code", "kbd", "samp", "var");

    /** The attributes whose values a visitor reads, so spell check reads them too (HTML review C13). */
    static final java.util.Set<String> HTML_PROSE_ATTRIBUTES =
            java.util.Set.of("alt", "title", "placeholder", "aria-label", "aria-description");

    private static final java.util.regex.Pattern HTML_PROSE_ATTRIBUTE = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w-])(?:alt|title|placeholder|aria-label|aria-description)\\s*=\\s*([\"'])");

    /**
     * What spell check skips and reads in a text of this mode (see {@link Skips}): for HTML, the content of
     * {@code <script>}, {@code <style>}, {@code <pre>}, {@code <code>}, {@code <kbd>}, {@code <samp>} and
     * {@code <var>} — by column, so the prose around an inline script on the same line is still checked — and, read
     * although they are strings, the values of {@code alt}, {@code title}, {@code placeholder}, {@code aria-label} and
     * {@code aria-description}. A tag mentioned in a comment opens nothing (E20). Other modes: their
     * {@link #codeLines(String)}.
     */
    public Skips skips(String text) {
        if (this != HTML) {
            return new Skips(codeLines(text), java.util.Map.of(), java.util.Map.of());
        }
        int[] starts = lineStarts(text);
        BitSet lines = new BitSet();
        java.util.Map<Integer, int[]> skip = new java.util.HashMap<>();
        java.util.Map<Integer, int[]> read = new java.util.HashMap<>();
        for (HtmlRegions.Region r :
                HtmlRegions.scan(text, false, text.length(), HTML_CODE_ELEMENTS, HTML_PROSE_ATTRIBUTES)) {
            switch (r.kind()) {
                case ATTRIBUTE -> addColumns(read, null, starts, text.length(), r.start(), r.end());
                case SCRIPT, DATA, OTHER_SCRIPT, STYLE, ELEMENT ->
                    addColumns(skip, lines, starts, text.length(), r.start(), r.end());
                default -> {}
            }
        }
        return new Skips(lines, skip, read);
    }

    /** Line {@code i}'s start offset, for every line of {@code text}. */
    private static int[] lineStarts(String text) {
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        int[] starts = new int[count];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts[line++] = i + 1;
            }
        }
        return starts;
    }

    /**
     * Adds {@code [from, to)} to {@code columns} as per-line column pairs; a line it covers whole goes into
     * {@code whole} instead, when given.
     */
    private static void addColumns(
            java.util.Map<Integer, int[]> columns,
            @org.jspecify.annotations.Nullable BitSet whole,
            int[] starts,
            int length,
            int from,
            int to) {
        if (to <= from) {
            return;
        }
        int line = lineOf(starts, from);
        while (line < starts.length && starts[line] < to) {
            int lineStart = starts[line];
            int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : length;
            int a = Math.max(from, lineStart);
            int b = Math.min(to, lineEnd);
            if (whole != null && a == lineStart && b == lineEnd) {
                whole.set(line);
            } else if (b > a) {
                int[] old = columns.get(line);
                int[] grown = old == null ? new int[2] : java.util.Arrays.copyOf(old, old.length + 2);
                grown[grown.length - 2] = a - lineStart;
                grown[grown.length - 1] = b - lineStart;
                columns.put(line, grown);
            }
            line++;
        }
    }

    private static int lineOf(int[] starts, int offset) {
        int i = java.util.Arrays.binarySearch(starts, offset);
        return i >= 0 ? i : -i - 2;
    }

    /**
     * What a spell-check scan found: lines that are code as a whole, column ranges of other lines that are code
     * ({@code skip}), and column ranges read as prose whatever their syntax style ({@code read}: an HTML
     * {@code alt}/{@code title} value is a string). Column pairs are {@code {from, to, from, to…}}, per 0-based line.
     */
    public record Skips(BitSet lines, java.util.Map<Integer, int[]> skip, java.util.Map<Integer, int[]> read) {
        public static final Skips NONE = new Skips(new BitSet(), java.util.Map.of(), java.util.Map.of());

        /** Whether column {@code col} of line {@code line} is code, not checked. */
        public boolean skips(int line, int col) {
            return lines.get(line) || within(skip.get(line), col);
        }

        /** Whether column {@code col} of line {@code line} is prose whatever its style says. */
        public boolean reads(int line, int col) {
            return within(read.get(line), col);
        }

        public boolean isEmpty() {
            return lines.isEmpty() && skip.isEmpty() && read.isEmpty();
        }

        /** Equal when they skip and read the same columns (the column arrays compared by content). */
        @Override
        public boolean equals(@org.jspecify.annotations.Nullable Object o) {
            return o instanceof Skips other
                    && lines.equals(other.lines)
                    && sameColumns(skip, other.skip)
                    && sameColumns(read, other.read);
        }

        @Override
        public int hashCode() {
            return lines.hashCode() * 31 + skip.size() * 7 + read.size();
        }

        private static boolean sameColumns(java.util.Map<Integer, int[]> a, java.util.Map<Integer, int[]> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (java.util.Map.Entry<Integer, int[]> e : a.entrySet()) {
                if (!java.util.Arrays.equals(e.getValue(), b.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean within(int @org.jspecify.annotations.Nullable [] pairs, int col) {
            if (pairs == null) {
                return false;
            }
            for (int k = 0; k + 1 < pairs.length; k += 2) {
                if (col >= pairs[k] && col < pairs[k + 1]) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * An HTML line with the name, {@code =} and quotes of an {@code alt}/{@code title}/… attribute blanked out, same
     * length: its value is then words of its own ({@code title="Clik here"} was one structured token, never checked).
     */
    private static String htmlProse(String line) {
        if (line.indexOf('=') < 0) {
            return line;
        }
        java.util.regex.Matcher m = HTML_PROSE_ATTRIBUTE.matcher(line);
        if (!m.find()) {
            return line;
        }
        char[] out = line.toCharArray();
        do {
            java.util.Arrays.fill(out, m.start(), m.end(), ' ');
            int close = line.indexOf(m.group(1).charAt(0), m.end());
            if (close >= 0) {
                out[close] = ' ';
            }
        } while (m.find());
        return new String(out);
    }

    static BitSet typstCodeLines(int count, IntFunction<String> lineAt) {
        BitSet code = new BitSet();
        for (int i = 0; i < count; i++) {
            String line = lineAt.apply(i);
            int k = 0;
            while (k < line.length() && line.charAt(k) <= ' ') {
                k++;
            }
            if (k + 1 < line.length() && line.charAt(k) == '#' && Character.isLetter(line.charAt(k + 1))) {
                code.set(i); // #let, #set, #show, #import, #figure(…): an instruction, not a sentence
            }
        }
        return code;
    }
}
