package com.editora.editor;

import java.util.BitSet;
import java.util.Collection;
import java.util.function.IntFunction;

/**
 * Which of a buffer's words spell check looks at, decided by its language. Pure.
 *
 * <ul>
 *   <li>{@link #PROSE} and {@link #MARKDOWN}: every word, except inline code and links (and, in Markdown,
 *       fenced code blocks).
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
            case "html" -> HTML;
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

    /**
     * The 0-based lines that are code as a whole and so never checked, whatever their words are styled as:
     * Markdown fenced blocks, an HTML page's {@code <script>}, {@code <style>} and {@code <pre>} blocks, and
     * Typst lines that open with a {@code #} instruction. Empty for prose and code buffers.
     */
    public BitSet codeLines(int count, IntFunction<String> lineAt) {
        return switch (this) {
            case MARKDOWN -> SpellCheckOverlay.fencedCodeLines(count, lineAt);
            case HTML -> htmlCodeLines(count, lineAt);
            case TYPST -> typstCodeLines(count, lineAt);
            default -> new BitSet();
        };
    }

    private static final String[] HTML_CODE_BLOCKS = {"script", "style", "pre"};

    static BitSet htmlCodeLines(int count, IntFunction<String> lineAt) {
        BitSet code = new BitSet();
        String open = null; // the element whose closing tag ends the current block
        for (int i = 0; i < count; i++) {
            String line = lineAt.apply(i);
            int from = 0;
            if (open == null) {
                if (line.indexOf('<') < 0) {
                    continue; // the usual line: no tag at all
                }
                int at = -1;
                for (String name : HTML_CODE_BLOCKS) {
                    int found = indexOfTag(line, "<" + name, 0);
                    if (found >= 0 && (at < 0 || found < at)) {
                        at = found;
                        open = name;
                    }
                }
                if (open == null) {
                    continue;
                }
                from = at + 1;
            }
            code.set(i);
            if (indexOfTag(line, "</" + open, from) >= 0) {
                open = null; // closed on this line (the line itself stays marked: it holds code)
            }
        }
        return code;
    }

    /** The index of {@code tag} (case-insensitive, followed by something that ends a tag name), or -1. */
    private static int indexOfTag(String line, String tag, int from) {
        int n = tag.length();
        for (int i = line.indexOf('<', from); i >= 0; i = line.indexOf('<', i + 1)) {
            if (line.regionMatches(true, i, tag, 0, n)
                    && (i + n == line.length() || !Character.isLetterOrDigit(line.charAt(i + n)))) {
                return i;
            }
        }
        return -1;
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
