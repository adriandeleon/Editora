package com.editora.editops;

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

    private Indenter() {}

    public enum Style {
        BRACES,
        XML,
        PY,
        SHELL,
        RUBY,
        LUA,
        PLAIN
    }

    /** The text to insert for a newline and the caret offset within it (relative to the insert start). */
    public record EnterEdit(String insert, int caretOffset) {}

    /** A smart-Tab edit: replace {@code [from,to)} with {@code replacement}, then select
     *  {@code [selStart,selEnd)} (a collapsed caret when equal). */
    public record TabEdit(int from, int to, String replacement, int selStart, int selEnd) {}

    /**
     * Smart Tab / Shift-Tab for a code buffer (returns {@code null} for {@link Style#PLAIN}, so prose keeps
     * the editor's default Tab). Uses the document's indent unit (tabs vs spaces), not a raw {@code \t}:
     * <ul>
     *   <li><b>Selection</b> → block indent every touched (non-blank) line by one unit; {@code shift}
     *       dedents each by up to one unit. The affected lines stay selected.</li>
     *   <li><b>Caret in leading whitespace / blank line</b> → indent the line by one unit ({@code shift}
     *       dedents it).</li>
     *   <li><b>Caret after content</b> → insert one unit at the caret ({@code shift} dedents the line).</li>
     * </ul>
     */
    public static TabEdit smartTab(String text, int selStart, int selEnd, String language, int tabSize, boolean shift) {
        return smartTab(text, selStart, selEnd, language, tabSize, shift, null, null);
    }

    /**
     * As {@link #smartTab(String, int, int, String, int, boolean)}, but forcing the indent unit from an
     * EditorConfig override when {@code insertSpaces != null} ({@code true} = {@code indentSize} spaces,
     * {@code false} = a tab); a {@code null} override falls back to {@link #detectUnit}.
     */
    public static TabEdit smartTab(
            String text,
            int selStart,
            int selEnd,
            String language,
            int tabSize,
            boolean shift,
            Boolean insertSpaces,
            Integer indentSize) {
        Style style = styleFor(language);
        if (style == Style.PLAIN) {
            return null;
        }
        String unit = unitFor(text, tabSize, insertSpaces, indentSize);
        int a = Math.min(selStart, selEnd);
        int b = Math.max(selStart, selEnd);

        if (a != b) { // block indent / dedent over the touched lines
            int firstLS = lineStart(text, a);
            int regionEnd = lineEnd(text, b > a ? b - 1 : b);
            String region = text.substring(firstLS, regionEnd);
            String[] lines = region.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                String ln = lines[i];
                if (shift) {
                    sb.append(removeOneIndent(ln, tabSize));
                } else {
                    sb.append(ln.isEmpty() ? ln : unit + ln); // don't indent blank lines on Tab
                }
            }
            return new TabEdit(firstLS, regionEnd, sb.toString(), firstLS, firstLS + sb.length());
        }

        int caret = a;
        int ls = lineStart(text, caret);
        if (shift) { // dedent the current line
            String leading = leadingWhitespace(text.substring(ls, lineEnd(text, caret)));
            int removed = leading.length() - removeOneIndent(leading, tabSize).length();
            int newCaret = Math.max(ls, caret - removed);
            return new TabEdit(ls, ls + removed, "", newCaret, newCaret);
        }
        String beforeCaret = text.substring(ls, caret);
        if (beforeCaret.isBlank()) {
            // In leading whitespace: snap the line up to the indent the surrounding code implies. Once it
            // is already at (or past) that level, Tab does nothing — repeated Tab must not keep piling on
            // indentation. (Use Shift-Tab to dedent.)
            String leading = leadingWhitespace(text.substring(ls, lineEnd(text, caret)));
            String suggested = suggestedIndent(text, ls, style, unit);
            if (width(leading, tabSize) >= width(suggested, tabSize)) {
                return new TabEdit(caret, caret, "", caret, caret); // no-op (consumed; no extra indent)
            }
            int newCaret = ls + suggested.length();
            return new TabEdit(ls, ls + leading.length(), suggested, newCaret, newCaret);
        }
        int newCaret = caret + unit.length(); // mid-line → insert one unit at the caret
        return new TabEdit(caret, caret, unit, newCaret, newCaret);
    }

    /** The indent the line at {@code lineStart} should have from context: the nearest previous non-blank
     *  line's indent, plus one unit if that line opens a block. {@code ""} when there's no line above. */
    private static String suggestedIndent(String text, int lineStart, Style style, String unit) {
        int pos = lineStart;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String prevLine = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            if (!prevLine.isBlank()) {
                String ind = leadingWhitespace(prevLine);
                return opensBlock(style, prevLine) ? ind + unit : ind;
            }
            pos = prevStart;
        }
        return "";
    }

    /** Removes one indent level from {@code line}'s start: a leading tab, else up to {@code tabSize} spaces. */
    static String removeOneIndent(String line, int tabSize) {
        if (line.startsWith("\t")) {
            return line.substring(1);
        }
        int n = 0;
        while (n < line.length() && n < Math.max(1, tabSize) && line.charAt(n) == ' ') {
            n++;
        }
        return line.substring(n);
    }

    private static final Set<String> SHELL_CLOSERS = Set.of("fi", "done", "esac", "else", "elif", ";;");
    private static final Set<String> RUBY_CLOSERS = Set.of("end", "else", "elsif", "when", "rescue", "ensure");
    private static final Set<String> LUA_CLOSERS = Set.of("end", "else", "elseif", "until");

    public static Style styleFor(String language) {
        return switch (language == null ? "" : language) {
            case "java",
                    "c",
                    "cpp",
                    "csharp",
                    "rust",
                    "go",
                    "kotlin",
                    "groovy",
                    "css",
                    "json",
                    "php",
                    "powershell",
                    "sql",
                    "batchfile",
                    "terraform",
                    "proto",
                    "graphql",
                    "javascript",
                    "typescript",
                    "javascriptreact",
                    "typescriptreact" -> Style.BRACES;
            case "xml", "html" -> Style.XML;
            case "python", "yaml" -> Style.PY;
            case "shell" -> Style.SHELL;
            case "ruby" -> Style.RUBY;
            case "lua" -> Style.LUA;
            default -> Style.PLAIN;
        };
    }

    /**
     * The edit for pressing Enter at {@code caret}: a newline plus the computed indentation, or — when
     * the caret sits between a matching pair — a split opening an indented middle line with the closer
     * dropped to the base indent.
     */
    public static EnterEdit enterEdit(String text, int caret, String language, int tabSize) {
        return enterEdit(text, caret, language, tabSize, null, null);
    }

    /** As {@link #enterEdit(String, int, String, int)}, but forcing the indent unit from an EditorConfig
     *  override when {@code insertSpaces != null} (else {@link #detectUnit}). */
    public static EnterEdit enterEdit(
            String text, int caret, String language, int tabSize, Boolean insertSpaces, Integer indentSize) {
        Style style = styleFor(language);
        int ls = lineStart(text, caret);
        String before = text.substring(ls, caret);
        String after = lineAfter(text, caret);
        String indent = leadingWhitespace(text.substring(ls, lineEnd(text, caret)));
        String unit = unitFor(text, tabSize, insertSpaces, indentSize);

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
        return (style == Style.BRACES || style == Style.SHELL || style == Style.RUBY || style == Style.LUA)
                && (c == '}' || c == ')' || c == ']');
    }

    /**
     * Whether {@code lineUpToCaretPlusChar} (the line's text before the caret plus the just-typed char)
     * is exactly leading whitespace followed by a closer keyword for the style — i.e. the keystroke just
     * completed a standalone closer. {@code ;;} (shell) is also matched.
     */
    public static boolean completesCloserKeyword(Style style, String lineUpToCaretPlusChar) {
        Set<String> closers = style == Style.SHELL
                ? SHELL_CLOSERS
                : style == Style.RUBY ? RUBY_CLOSERS : style == Style.LUA ? LUA_CLOSERS : Set.of();
        if (closers.isEmpty()) {
            return false;
        }
        String word = lineUpToCaretPlusChar.substring(
                leadingWhitespace(lineUpToCaretPlusChar).length());
        return closers.contains(word);
    }

    /** One indent level: a tab when {@code enclosingIndent} contains a tab, else {@code tabSize} spaces
     *  (so an empty enclosing indent defaults to spaces — "spaces unless detected otherwise"). */
    public static String indentUnit(String enclosingIndent, int tabSize) {
        if (enclosingIndent.indexOf('\t') >= 0) {
            return "\t";
        }
        return " ".repeat(Math.max(1, tabSize));
    }

    /**
     * How many characters Backspace should delete in one press when the caret sits in a line's leading
     * whitespace. Counting backward from the caret:
     * <ul>
     *   <li><b>Blank line</b> (everything before and after the caret is whitespace) with a line above it:
     *       the leading whitespace <em>plus the preceding newline</em> — so one press jumps back to the
     *       end of the previous line (undoing an auto-indented Enter, "back to where you hit Enter").</li>
     *   <li><b>Indented content line</b> (whitespace before the caret, real text after): just the leading
     *       whitespace — clears the indent back to column 1 without joining lines.</li>
     *   <li>Otherwise {@code 0} (caret not in leading-only whitespace) — let a normal Backspace run.</li>
     * </ul>
     * {@code beforeCaret}/{@code afterCaret} are the line text on each side of the caret;
     * {@code hasPreviousLine} is whether a line exists above the current one.
     */
    public static int smartBackspaceCount(String beforeCaret, String afterCaret, boolean hasPreviousLine) {
        if (beforeCaret.isEmpty() || !beforeCaret.isBlank()) {
            return 0;
        }
        if (afterCaret.isBlank() && hasPreviousLine) {
            return beforeCaret.length() + 1; // + the newline → join to the end of the previous line
        }
        return beforeCaret.length();
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
            case SHELL ->
                last == '{'
                        || last == '('
                        || endsWithWord(code, "do", "then", "in")
                        || startsWithWord(code, "else", "elif");
            case RUBY ->
                last == '{'
                        || endsWithDoBlock(code)
                        || rubyOpener(code)
                        || startsWithWord(code, "else", "elsif", "when", "rescue", "ensure", "begin");
            case LUA -> last == '{' || luaOpener(code);
            case PLAIN -> false;
        };
    }

    /**
     * A Lua line that opens a block: ends with a block keyword ({@code do}/{@code then}/{@code repeat}),
     * begins an {@code else}/{@code elseif} branch, or is a {@code function} definition (contains the
     * {@code function} keyword and ends with its parameter list) — unless the block is already closed on
     * the same line (e.g. an inline {@code ... end}).
     */
    private static boolean luaOpener(String code) {
        if (code.matches(".*\\bend\\b.*")) {
            return false; // opener and its `end` on one line — net zero indent
        }
        if (endsWithWord(code, "do", "then")
                || code.strip().equals("repeat")
                || startsWithWord(code, "else", "elseif")) {
            return true;
        }
        // function definition: `function f(...)`, `local function f(...)`, `x = function(...)`, anon `function()`
        return code.matches(".*\\bfunction\\b\\s*[\\w.:]*\\s*\\([^)]*\\)\\s*");
    }

    /** Ends with {@code do}, optionally followed by a {@code |block params|}. */
    private static boolean endsWithDoBlock(String code) {
        return code.matches(".*\\bdo\\b\\s*(\\|[^|]*\\|\\s*)?");
    }

    private static boolean rubyOpener(String code) {
        return startsWithWord(code, "def", "class", "module", "if", "unless", "while", "until", "case", "for")
                && !code.strip().contains(" end");
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
        return !tag.startsWith("</") && !tag.endsWith("/>") && !tag.startsWith("<!") && !tag.startsWith("<?");
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
        return (open == '{' && close == '}') || (open == '(' && close == ')') || (open == '[' && close == ']');
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** The indent unit to use: an EditorConfig override when {@code insertSpaces != null} (tab, or
     *  {@code indentSize}/{@code tabSize} spaces), else the document's {@link #detectUnit detected} unit. */
    static String unitFor(String text, int tabSize, Boolean insertSpaces, Integer indentSize) {
        if (insertSpaces == null) {
            return detectUnit(text, tabSize);
        }
        if (insertSpaces) {
            int n = indentSize != null && indentSize > 0 ? indentSize : tabSize;
            return " ".repeat(Math.max(1, n));
        }
        return "\t";
    }

    /** The document's indent unit, inferred from the first indented line: a tab if it starts with a tab,
     *  else {@code tabSize} spaces. When the file has no indentation at all (empty/flat), it falls back to
     *  <b>spaces</b> — matching VSCode/IntelliJ's "spaces unless the file is detected to use tabs" default. */
    public static String detectUnit(String text, int tabSize) {
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
        return " ".repeat(Math.max(1, tabSize)); // no evidence → spaces (the VSCode/IntelliJ default)
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
