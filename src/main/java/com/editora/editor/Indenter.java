package com.editora.editor;

import java.util.Set;

/**
 * Computes auto/smart indentation as the user types — pure (no toolkit), so it is unit-tested.
 * {@link EditorBuffer} calls it on Enter and when a closing token is typed.
 *
 * <p>Each language maps to an indent {@link Style}. The baseline (every language) is <em>inheritance</em>:
 * a new line keeps the current line's leading whitespace. A line that <em>opens a block</em> for the
 * style adds one indent level, pressing Enter between a matching pair splits into an IDE-style stanza,
 * and — at the keystroke that <em>completes</em> a closer (a {@code )]}} bracket, or a keyword like
 * {@code end}/{@code fi}) — the line is re-aligned to its opener's indent.
 */
public final class Indenter {

    /** Bounds line/back-scans so Enter stays cheap on huge files. */
    private static final int MAX_SCAN = 8000;

    private Indenter() {
    }

    public enum Style { BRACES, XML, PY, SHELL, RUBY, PLAIN }

    /** The text to insert for a newline and the caret offset within it (relative to the insert start). */
    public record EnterEdit(String insert, int caretOffset) {
    }

    private static final Set<String> SHELL_CLOSERS = Set.of("fi", "done", "esac", "else", "elif", ";;");
    private static final Set<String> RUBY_CLOSERS =
            Set.of("end", "else", "elsif", "when", "rescue", "ensure");

    public static Style styleFor(String language) {
        return switch (language == null ? "" : language) {
            case "java", "c", "cpp", "csharp", "rust", "go", "kotlin", "groovy", "css", "json",
                 "powershell", "sql", "batchfile" -> Style.BRACES;
            case "xml", "html" -> Style.XML;
            case "python", "yaml" -> Style.PY;
            case "shell" -> Style.SHELL;
            case "ruby" -> Style.RUBY;
            default -> Style.PLAIN;
        };
    }

    /**
     * The edit for pressing Enter at {@code caret}: a newline plus the computed indentation, or — when
     * the caret sits between a matching pair — a split opening an indented middle line with the closer
     * dropped to the base indent.
     */
    public static EnterEdit enterEdit(String text, int caret, String language, int tabSize) {
        Style style = styleFor(language);
        int ls = lineStart(text, caret);
        String before = text.substring(ls, caret);
        String after = lineAfter(text, caret);
        String indent = leadingWhitespace(text.substring(ls, lineEnd(text, caret)));
        String unit = detectUnit(text, tabSize);

        if (isPairSplit(style, before, after)) {
            String body = indent + unit;
            return new EnterEdit("\n" + body + "\n" + indent, 1 + body.length());
        }
        String newIndent = opensBlock(style, before) ? indent + unit : indent;
        return new EnterEdit("\n" + newIndent, 1 + newIndent.length());
    }

    /**
     * The indent the current line should have so a just-completed closer aligns with its opener: the
     * nearest previous non-blank line whose indent is strictly shallower (by visual width). {@code ""}
     * when none. Used for the electric de-indent.
     */
    public static String closerAlignIndent(String text, int caret, int tabSize) {
        int ls = lineStart(text, caret);
        int curWidth = width(leadingWhitespace(text.substring(ls, lineEnd(text, caret))), tabSize);
        int pos = ls;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String prevLine = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            if (!prevLine.isBlank()) {
                String pind = leadingWhitespace(prevLine);
                if (width(pind, tabSize) < curWidth) {
                    return pind;
                }
            }
            pos = prevStart;
        }
        return "";
    }

    /** True when typing {@code c} is a closing bracket that should de-indent for the style. */
    public static boolean isCloserChar(Style style, char c) {
        return (style == Style.BRACES || style == Style.SHELL || style == Style.RUBY)
                && (c == '}' || c == ')' || c == ']');
    }

    /**
     * Whether {@code lineUpToCaretPlusChar} (the line's text before the caret plus the just-typed char)
     * is exactly leading whitespace followed by a closer keyword for the style — i.e. the keystroke just
     * completed a standalone closer. {@code ;;} (shell) is also matched.
     */
    public static boolean completesCloserKeyword(Style style, String lineUpToCaretPlusChar) {
        Set<String> closers = style == Style.SHELL ? SHELL_CLOSERS
                : style == Style.RUBY ? RUBY_CLOSERS : Set.of();
        if (closers.isEmpty()) {
            return false;
        }
        String word = lineUpToCaretPlusChar.substring(leadingWhitespace(lineUpToCaretPlusChar).length());
        return closers.contains(word);
    }

    /** One indent level: {@code tabSize} spaces when {@code enclosingIndent} is spaces-only, else a tab. */
    public static String indentUnit(String enclosingIndent, int tabSize) {
        if (!enclosingIndent.isEmpty() && enclosingIndent.indexOf('\t') < 0) {
            return " ".repeat(Math.max(1, tabSize));
        }
        return "\t";
    }

    /**
     * How many characters Backspace should delete to <b>clear the whole indent in one press</b> when the
     * caret sits in a line's leading whitespace — so a single Backspace jumps back to column 1 instead of
     * deleting one space at a time. {@code beforeCaret} is the line text from its start to the caret.
     *
     * <p>Returns the full count of that leading whitespace, or {@code 0} when the caret is not within
     * leading-only whitespace (there's non-whitespace before it, or it's empty) — the caller should then
     * let a normal single-character Backspace run (e.g. to join the previous line at column 0).
     */
    public static int smartBackspaceCount(String beforeCaret) {
        return (!beforeCaret.isEmpty() && beforeCaret.isBlank()) ? beforeCaret.length() : 0;
    }

    // --- block-open detection ---------------------------------------------------------------------

    private static boolean opensBlock(Style style, String before) {
        String code = stripTrailingComment(before).stripTrailing();
        if (code.isEmpty()) {
            return false;
        }
        char last = code.charAt(code.length() - 1);
        return switch (style) {
            case BRACES -> last == '{' || last == '(' || last == '[';
            case PY -> last == ':' || last == '{' || last == '(' || last == '[';
            case XML -> endsWithOpenTag(code);
            case SHELL -> last == '{' || last == '(' || endsWithWord(code, "do", "then", "in")
                    || startsWithWord(code, "else", "elif");
            case RUBY -> last == '{' || endsWithDoBlock(code) || rubyOpener(code)
                    || startsWithWord(code, "else", "elsif", "when", "rescue", "ensure", "begin");
            case PLAIN -> false;
        };
    }

    /** Ends with {@code do}, optionally followed by a {@code |block params|}. */
    private static boolean endsWithDoBlock(String code) {
        return code.matches(".*\\bdo\\b\\s*(\\|[^|]*\\|\\s*)?");
    }

    private static boolean rubyOpener(String code) {
        return startsWithWord(code, "def", "class", "module", "if", "unless", "while", "until",
                "case", "for") && !code.strip().contains(" end");
    }

    private static boolean startsWithWord(String code, String... words) {
        String t = code.strip();
        for (String w : words) {
            if (t.equals(w) || t.startsWith(w + " ") || t.startsWith(w + ";")) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithWord(String code, String... words) {
        for (String w : words) {
            if (code.equals(w) || code.endsWith(" " + w) || code.endsWith("\t" + w)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithOpenTag(String code) {
        int lt = code.lastIndexOf('<');
        if (lt < 0 || !code.endsWith(">")) {
            return false;
        }
        String tag = code.substring(lt);
        return !tag.startsWith("</") && !tag.endsWith("/>") && !tag.startsWith("<!")
                && !tag.startsWith("<?");
    }

    // --- pair split (Enter between an opener and its closer) --------------------------------------

    private static boolean isPairSplit(Style style, String before, String after) {
        String b = before.stripTrailing();
        String a = after.stripLeading();
        if (b.isEmpty() || a.isEmpty()) {
            return false;
        }
        if (style == Style.XML) {
            return endsWithOpenTag(b) && a.startsWith("</");
        }
        if (style == Style.PLAIN) {
            return false;
        }
        char open = b.charAt(b.length() - 1);
        char close = a.charAt(0);
        return (open == '{' && close == '}') || (open == '(' && close == ')')
                || (open == '[' && close == ']');
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** The document's indent unit: tab vs {@code tabSize} spaces, inferred from the first indented line. */
    static String detectUnit(String text, int tabSize) {
        int limit = Math.min(text.length(), MAX_SCAN);
        int i = 0;
        while (i < limit) {
            char c = text.charAt(i);
            if (c == '\t') {
                return "\t";
            }
            if (c == ' ') {
                // count the run; a leading space run means space indentation
                return " ".repeat(Math.max(1, tabSize));
            }
            // skip to next line
            int nl = text.indexOf('\n', i);
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        return "\t";
    }

    /** Strips a trailing line comment ({@code //}, {@code #}, {@code --}) that is not inside a string. */
    private static String stripTrailingComment(String s) {
        char quote = 0;
        int limit = Math.min(s.length(), MAX_SCAN);
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                return s.substring(0, i);
            } else if (c == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                return s.substring(0, i);
            } else if (c == '#') {
                return s.substring(0, i);
            }
        }
        return s;
    }

    private static int width(String indent, int tabSize) {
        int w = 0;
        for (int i = 0; i < indent.length(); i++) {
            w += indent.charAt(i) == '\t' ? Math.max(1, tabSize) : 1;
        }
        return w;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static int lineStart(String text, int caret) {
        int nl = text.lastIndexOf('\n', caret - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEnd(String text, int caret) {
        int nl = text.indexOf('\n', caret);
        return nl < 0 ? text.length() : nl;
    }

    private static String lineAfter(String text, int caret) {
        return text.substring(caret, lineEnd(text, caret));
    }
}
