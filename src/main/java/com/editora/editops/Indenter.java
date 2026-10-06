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

    /** How many lines one wrapped statement is followed over. */
    private static final int MAX_STATEMENT_LINES = 40;

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
                    sb.append(removeOneIndent(ln, dedentWidth(unit, tabSize)));
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
            int removed = leading.length()
                    - removeOneIndent(leading, dedentWidth(unit, tabSize)).length();
            int newCaret = Math.max(ls, caret - removed);
            return new TabEdit(ls, ls + removed, "", newCaret, newCaret);
        }
        String beforeCaret = text.substring(ls, caret);
        if (beforeCaret.isBlank()) {
            // In leading whitespace: snap the line up to the indent the surrounding code implies, and put
            // the caret where typing starts.
            String leading = leadingWhitespace(text.substring(ls, lineEnd(text, caret)));
            String suggested = suggestedIndent(text, ls, style, unit, isHtml(language), tabSize);
            if (width(leading, tabSize) >= width(suggested, tabSize)) {
                // Already at (or past) that level, so the text is left alone — repeated Tab must not keep
                // piling on indentation (use Shift-Tab to dedent). The caret still moves to the end of the
                // indent, which is the whole point of pressing Tab there: after Enter inside a block the
                // line already carries its indent, so coming back to column 0 (C-a, Home, a click) and
                // pressing Tab used to do *nothing at all* rather than putting the caret where you type.
                // Emacs' own TAB behaves this way, and it stays a true no-op once the caret is there.
                int atIndentEnd = ls + leading.length();
                return new TabEdit(caret, caret, "", atIndentEnd, atIndentEnd);
            }
            int newCaret = ls + suggested.length();
            return new TabEdit(ls, ls + leading.length(), suggested, newCaret, newCaret);
        }
        int newCaret = caret + unit.length(); // mid-line → insert one unit at the caret
        return new TabEdit(caret, caret, unit, newCaret, newCaret);
    }

    /**
     * The indent the line at {@code lineStart} should have from context: the nearest previous non-blank
     * line's indent, plus one unit if that line opens a block. A line that itself begins with a closer
     * ({@code }}, {@code else}, {@code fi}, {@code </tag>} …) belongs one level out: at its bracket's opener,
     * else one unit less than a body line would get. {@code ""} when there's no line above.
     */
    private static String suggestedIndent(
            String text, int lineStart, Style style, String unit, boolean html, int tabSize) {
        String content = text.substring(lineStart, lineEnd(text, lineStart)).stripLeading();
        boolean closer = startsWithCloser(style, content);
        if (closer && style != Style.XML && isBracketCloser(content.charAt(0))) {
            String opener = openerLineIndent(style, text, lineStart, content.charAt(0));
            if (opener != null) {
                return opener;
            }
        }
        int pos = lineStart;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String prevLine = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            if (!prevLine.isBlank()) {
                String ind = leadingWhitespace(prevLine);
                boolean opens = opensBlock(style, prevLine, html);
                if (closer) {
                    return opens ? ind : removeOneIndent(ind, dedentWidth(unit, tabSize));
                }
                return opens ? ind + unit : ind;
            }
            pos = prevStart;
        }
        return "";
    }

    private static boolean isBracketCloser(char c) {
        return c == '}' || c == ')' || c == ']';
    }

    /** Whether a line's content begins with a token that closes (or continues, like {@code else}) a block. */
    private static boolean startsWithCloser(Style style, String content) {
        if (content.isEmpty() || style == Style.PLAIN) {
            return false;
        }
        if (style == Style.XML) {
            return content.startsWith("</");
        }
        if (isBracketCloser(content.charAt(0))) {
            return true;
        }
        if (style == Style.SHELL && content.startsWith(";;")) {
            return true;
        }
        int end = 0;
        while (end < content.length() && isWordChar(content.charAt(end))) {
            end++;
        }
        String word = content.substring(0, end);
        return switch (style) {
            case SHELL -> SHELL_CLOSERS.contains(word);
            case RUBY -> RUBY_CLOSERS.contains(word);
            case LUA -> LUA_CLOSERS.contains(word);
            case PY -> PY_CLOSERS.contains(word) && content.stripTrailing().endsWith(":");
            default -> false;
        };
    }

    /**
     * How many spaces one Shift-Tab removes: the indent unit's own width when it is spaces (EditorConfig's
     * {@code indent_size} can differ from {@code tab_width}), else the tab width.
     */
    private static int dedentWidth(String unit, int tabSize) {
        return unit.startsWith(" ") ? unit.length() : tabSize;
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
    private static final Set<String> PY_CLOSERS = Set.of("else", "elif", "except", "finally");

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
            case "xml", "html", "astro" -> Style.XML;
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
        // The indent the caret has already passed, not the whole line's: the text after the caret keeps the
        // whitespace it sits behind. Enter at column 0 of `    foo();` must not add four more spaces in front
        // of code that is still indented by its own four.
        String indent = leadingWhitespace(before);
        String unit = unitFor(text, tabSize, insertSpaces, indentSize);

        if (isPairSplit(style, before, after)) {
            String body = indent + unit;
            return new EnterEdit("\n" + body + "\n" + indent, 1 + body.length());
        }
        String newIndent = opensBlock(style, before, isHtml(language)) ? indent + unit : indent;
        return new EnterEdit("\n" + newIndent, 1 + newIndent.length());
    }

    /**
     * The indent the current line should have so a just-completed closer aligns with its opener: the
     * nearest previous non-blank line whose indent is strictly shallower (by visual width). {@code ""}
     * when none. Used for the electric de-indent.
     */
    public static String closerAlignIndent(String text, int caret, int tabSize) {
        return closerAlignIndent(text, caret, tabSize, null);
    }

    private static String closerAlignIndent(String text, int caret, int tabSize, Style style) {
        int ls = lineStart(text, caret);
        int curWidth = width(leadingWhitespace(text.substring(ls, lineEnd(text, caret))), tabSize);
        int pos = ls;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String prevLine = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            // A preprocessor line sits at column 0 whatever block it is in; it is not the opener.
            if (!prevLine.isBlank() && !(style == Style.BRACES && prevLine.startsWith("#"))) {
                String pind = leadingWhitespace(prevLine);
                // Nor is the first line of a wrapped statement (`foo \` / `bar`): it is shallower than its
                // own continuation lines, but it is body, not the block's opener.
                if (width(pind, tabSize) < curWidth && !isWrappedBodyLine(style, text, prevStart, ls)) {
                    return pind;
                }
            }
            pos = prevStart;
        }
        return "";
    }

    /**
     * As {@link #closerAlignIndent(String, int, int)}, but idempotent: returns {@code currentIndent} unchanged
     * when the closer's line no longer sits at body level, so a closer that is already aligned is never
     * stepped out to the <em>enclosing</em> block. "Shallower than the current indent" finds the opener only
     * while the closer is still as deep as its body; once aligned, the nearest shallower line belongs to the
     * block around it. The line is already aligned when the previous non-blank line is deeper (the body), or
     * is a block opener at the same indent (an empty block).
     */
    public static String closerAlignIndent(Style style, String text, int caret, int tabSize, String currentIndent) {
        int ls = lineStart(text, caret);
        int curWidth = width(leadingWhitespace(text.substring(ls, lineEnd(text, caret))), tabSize);
        int pos = ls;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String prevLine = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            if (!prevLine.isBlank() && !(style == Style.BRACES && prevLine.startsWith("#"))) {
                // A continuation line is deeper than the body it belongs to: measure its statement's
                // first line, or a closer left at body level after `foo(a,` / `b)` would count as aligned.
                int headStart = statementHead(style, text, prevStart);
                int prevWidth = width(leadingWhitespace(text.substring(headStart, lineEnd(text, headStart))), tabSize);
                if (prevWidth > curWidth || (prevWidth == curWidth && opensBlock(style, prevLine))) {
                    return currentIndent;
                }
                break;
            }
            pos = prevStart;
        }
        return closerAlignIndent(text, caret, tabSize, style);
    }

    /**
     * As {@link #closerAlignIndent(Style, String, int, int, String)}, knowing the closer being typed. A
     * bracket typed alone on its line is aligned by <em>matching</em> it — the indent of the line where the
     * statement holding its opener starts — rather than by guessing from indentation, which mistakes a
     * wrapped statement's first line for the opener ({@code return foo(a,} / {@code b);} / {@code }}). Falls
     * back to the indentation rule when no opener is found in range.
     */
    public static String closerAlignIndent(
            Style style, String text, int caret, int tabSize, String currentIndent, char typed) {
        int ls = lineStart(text, caret);
        if (isBracketCloser(typed) && text.substring(ls, caret).isBlank()) {
            String opener = openerLineIndent(style, text, ls, typed);
            if (opener != null) {
                return opener;
            }
        }
        return closerAlignIndent(style, text, caret, tabSize, currentIndent);
    }

    /**
     * The indent of the line that starts the statement holding the unmatched opener of {@code close}, looking
     * back from {@code ls} (a line start); {@code null} when there is none within the scan bound. Only
     * brackets of the typed kind are matched, so an unfinished {@code foo(} in the body does not capture a
     * {@code }}; once the opener is found, closers before it on its line ({@code int b) {}, {@code } else {})
     * are followed back to their own openers, so a multi-line header aligns to its first line.
     */
    private static String openerLineIndent(Style style, String text, int ls, char close) {
        char open = close == '}' ? '{' : close == ')' ? '(' : '[';
        StringBuilder brackets = new StringBuilder();
        int depth = 0;
        boolean found = false;
        int pending = 0;
        int pos = ls;
        int scanned = 0;
        while (pos > 0 && scanned < MAX_SCAN) {
            int prevStart = lineStart(text, pos - 1);
            String line = text.substring(prevStart, pos - 1);
            scanned += pos - prevStart;
            pos = prevStart;
            brackets.setLength(0);
            scanCode(style, line, brackets);
            for (int k = brackets.length() - 1; k >= 0; k--) {
                char b = brackets.charAt(k);
                if (found) {
                    if (isBracketCloser(b)) {
                        pending++;
                    } else if (pending > 0) {
                        pending--;
                    }
                } else if (b == close) {
                    depth++;
                } else if (b == open) {
                    if (depth == 0) {
                        found = true;
                    } else {
                        depth--;
                    }
                }
            }
            if (found && pending == 0) {
                return leadingWhitespace(line);
            }
        }
        return null;
    }

    /** Whether {@code line} is left unfinished: a trailing {@code \} or {@code ,}, or an unclosed {@code (}/{@code [}. */
    private static boolean continuesStatement(Style style, String line) {
        StringBuilder brackets = new StringBuilder();
        String code = scanCode(style, line, brackets).stripTrailing();
        if (code.isEmpty()) {
            return false;
        }
        char last = code.charAt(code.length() - 1);
        if (last == '\\' || last == ',') {
            return true;
        }
        int open = 0;
        for (int k = 0; k < brackets.length(); k++) {
            char b = brackets.charAt(k);
            if (b == '(' || b == '[') {
                open++;
            } else if ((b == ')' || b == ']') && open > 0) {
                open--;
            }
        }
        return open > 0;
    }

    /**
     * Whether the line at {@code start} is the first line of a statement that wraps onto the lines below it
     * (up to {@code limit}) <em>without</em> opening a block — {@code foo \} / {@code bar}, {@code foo(a,} /
     * {@code b)}. A wrapped header ({@code if a \} / {@code && b; then}) does open one, so it is judged on the
     * joined statement.
     */
    private static boolean isWrappedBodyLine(Style style, String text, int start, int limit) {
        if (style == null) {
            return false;
        }
        int end = lineEnd(text, start);
        String line = text.substring(start, end);
        if (opensBlock(style, line) || !continuesStatement(style, line)) {
            return false; // `foo(function()` opens a block itself, whatever follows it
        }
        StringBuilder joined = new StringBuilder(line.stripTrailing());
        if (joined.charAt(joined.length() - 1) == '\\') {
            joined.setLength(joined.length() - 1);
        }
        int lines = 0;
        while (end + 1 < limit && lines++ < MAX_STATEMENT_LINES) {
            int nextStart = end + 1;
            end = lineEnd(text, nextStart);
            String next = text.substring(nextStart, end);
            joined.append(' ').append(next.strip());
            if (!continuesStatement(style, next)) {
                break;
            }
            if (joined.charAt(joined.length() - 1) == '\\') {
                joined.setLength(joined.length() - 1);
            }
        }
        return !opensBlock(style, joined.toString());
    }

    /** Start of the line that begins the statement the line at {@code start} belongs to (itself when not wrapped). */
    private static int statementHead(Style style, String text, int start) {
        int head = start;
        for (int lines = 0; head > 0 && lines < MAX_STATEMENT_LINES; lines++) {
            int prevStart = lineStart(text, head - 1);
            String prevLine = text.substring(prevStart, head - 1);
            boolean chained = text.startsWith(
                    ".",
                    head
                            + leadingWhitespace(text.substring(head, lineEnd(text, head)))
                                    .length());
            if (prevLine.isBlank()
                    || opensBlock(style, prevLine)
                    || !(chained || continuesStatement(style, prevLine))) {
                break;
            }
            head = prevStart;
        }
        return head;
    }

    /** True when typing {@code c} is a closing bracket that should de-indent for the style. */
    public static boolean isCloserChar(Style style, char c) {
        return (style == Style.BRACES || style == Style.SHELL || style == Style.RUBY || style == Style.LUA)
                && (c == '}' || c == ')' || c == ']');
    }

    /**
     * Whether {@code lineUpToCaretPlusChar} (the line's text before the caret plus the just-typed char, or
     * {@code '\n'} for Enter) is leading whitespace followed by a <em>finished</em> closer for the style —
     * i.e. the keystroke should de-indent the line.
     *
     * <p>A keyword closer is finished only once a non-word character follows it: {@code fi} is also how
     * {@code find}, {@code file} and {@code first} begin, and {@code end} how {@code endpoint} does, so
     * de-indenting on the keyword's last letter moved every such line. The terminator — a space, {@code ;},
     * Enter — is what makes it a whole word. The symbolic {@code ;;} (shell) cannot be continued into a
     * longer word, so it is finished the moment it is typed.
     */
    public static boolean completesCloserKeyword(Style style, String lineUpToCaretPlusChar) {
        Set<String> closers = style == Style.SHELL
                ? SHELL_CLOSERS
                : style == Style.RUBY ? RUBY_CLOSERS : style == Style.LUA ? LUA_CLOSERS : Set.of();
        if (closers.isEmpty()) {
            return false;
        }
        String rest = lineUpToCaretPlusChar.substring(
                leadingWhitespace(lineUpToCaretPlusChar).length());
        if (rest.isEmpty()) {
            return false;
        }
        char last = rest.charAt(rest.length() - 1);
        if (isWordChar(last)) {
            return false; // still inside a word, which may yet turn out longer than the keyword
        }
        if (last != '\n' && Character.isISOControl(last)) {
            // The KEY_TYPED that follows Backspace/Escape/Delete carries a control character and types
            // nothing: `fix` + Backspace is on its way to `find`, not a finished `fi`.
            return false;
        }
        if (closers.contains(rest)) {
            return true; // a symbolic closer (;;), complete as typed
        }
        String word = rest.substring(0, rest.length() - 1);
        return !word.isEmpty() && isWordChar(word.charAt(word.length() - 1)) && closers.contains(word);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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

    private static boolean isHtml(String language) {
        return "html".equals(language) || "astro".equals(language);
    }

    private static boolean opensBlock(Style style, String before) {
        return opensBlock(style, before, false);
    }

    private static boolean opensBlock(Style style, String before, boolean html) {
        String code = stripTrailingComment(style, before).stripTrailing();
        if (code.isEmpty()) {
            return false;
        }
        char last = code.charAt(code.length() - 1);
        return switch (style) {
            case BRACES -> last == '{' || last == '(' || last == '[';
            case PY -> last == ':' || last == '{' || last == '(' || last == '[';
            case XML -> endsWithOpenTag(code, html);
            case SHELL -> last == '{' || last == '(' || shellOpener(code) || startsWithWord(code, "else", "elif");
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
                && !code.matches(".*\\bend\\b.*") // the whole word: `def end_date` still opens
                // an endless method (`def foo = 42`, `def sq(x) = x * x`) has no body; `def foo=(v)` is a setter
                && !code.strip().matches("def\\s+[\\w.]+[?!]?\\s*(\\([^)]*\\))?\\s+=\\s.*");
    }

    /**
     * A shell line that opens a block: a bare {@code do}/{@code then}, one that follows a {@code ;}
     * ({@code …; do}, {@code …;then}), or one that ends the compound command the line starts with
     * ({@code while read x do}, {@code case $x in}). A command that merely ends in such a word
     * ({@code echo what to do}) does not.
     */
    private static boolean shellOpener(String code) {
        String t = code.strip();
        for (String w : new String[] {"do", "then"}) {
            if (t.equals(w)) {
                return true;
            }
            if (t.endsWith(w)) {
                String head = t.substring(0, t.length() - w.length());
                if (head.endsWith(";")) {
                    return true;
                }
                if (Character.isWhitespace(head.charAt(head.length() - 1))
                        && (head.stripTrailing().endsWith(";")
                                || startsWithWord(t, "if", "elif", "while", "until", "for", "select"))) {
                    return true;
                }
            }
        }
        return startsWithWord(t, "case", "for", "select") && endsWithWord(t, "in");
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
        return endsWithOpenTag(code, false);
    }

    private static boolean endsWithOpenTag(String code, boolean html) {
        int lt = code.lastIndexOf('<');
        if (lt < 0 || !code.endsWith(">")) {
            return false;
        }
        String tag = code.substring(lt);
        if (html) { // <br>, <meta …>: a void element has no content to indent
            int end = 1;
            while (end < tag.length() && (Character.isLetterOrDigit(tag.charAt(end)) || tag.charAt(end) == '-')) {
                end++;
            }
            if (TagRename.VOID_ELEMENTS.contains(tag.substring(1, end).toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
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
    public static String unitFor(String text, int tabSize, Boolean insertSpaces, Integer indentSize) {
        if (insertSpaces == null) {
            return detectUnit(text, tabSize);
        }
        if (insertSpaces) {
            int n = indentSize != null && indentSize > 0 ? indentSize : tabSize;
            return " ".repeat(Math.max(1, n));
        }
        return "\t";
    }

    /**
     * The document's indent unit, inferred from its indented lines. Tabs or spaces is decided by the first
     * line that is really indented: a line whose only indentation is one space, or that continues a block
     * comment ({@code " * License"}), says nothing — it used to make a tab-indented file with a comment
     * header count as space-indented. For spaces the width is the file's own step (the most common increase
     * in indent between consecutive lines, 2–8), so a 2-space file is not given {@code tabSize} spaces;
     * without such evidence it is {@code tabSize}. When the file has no indentation at all (empty/flat), it
     * falls back to <b>spaces</b> — matching VSCode/IntelliJ's "spaces unless the file is detected to use
     * tabs" default.
     */
    public static String detectUnit(String text, int tabSize) {
        int limit = Math.min(text.length(), MAX_SCAN);
        int[] votes = null;
        boolean spaces = false;
        int prev = -1; // the previous code line's space indent
        int i = 0;
        while (i < limit) {
            int j = i;
            while (j < limit && text.charAt(j) == ' ') {
                j++;
            }
            char c = j < limit ? text.charAt(j) : '\n';
            int n = j - i;
            if (c == '\t') {
                if (!spaces) {
                    return "\t";
                }
                prev = -1;
            } else if (c != '\n' && c != '\r' && !(n > 0 && c == '*') && n != 1) {
                if (n >= 2) {
                    spaces = true;
                }
                int step = prev < 0 ? 0 : n - prev;
                if (step >= 2 && step <= 8) {
                    if (votes == null) {
                        votes = new int[9];
                    }
                    votes[step]++;
                }
                prev = n;
            }
            int nl = text.indexOf('\n', j);
            if (nl < 0) {
                break;
            }
            i = nl + 1;
        }
        int size = Math.max(1, tabSize);
        if (votes != null) {
            int best = 0;
            for (int step = 2; step <= 8; step++) {
                if (votes[step] > votes[best] || (votes[step] == votes[best] && votes[step] > 0 && step == size)) {
                    best = step;
                }
            }
            size = best;
        }
        return " ".repeat(size); // no tab evidence → spaces (the VSCode/IntelliJ default)
    }

    /**
     * Strips a trailing line comment that is not inside a string, honouring only the tokens the style's
     * languages use — so a decrement ({@code i--}), a shell {@code $#} or long option, a CSS id selector,
     * a JS private field, Python's {@code //} and Lua's {@code #t} are code, not comments:
     * <ul>
     *   <li>{@code //} — brace languages only;</li>
     *   <li>{@code #} — shell/Python/Ruby when it starts a word; brace languages (Terraform, PHP) only
     *       when it stands alone ({@code # note}, not {@code #main});</li>
     *   <li>{@code --} — Lua anywhere; brace languages (SQL) only when it stands alone.</li>
     * </ul>
     */
    private static String stripTrailingComment(Style style, String s) {
        return scanCode(style, s, null);
    }

    /**
     * {@link #stripTrailingComment}'s scan: returns {@code s} without its trailing comment — a line comment,
     * or in brace languages a {@code /* … *}{@code /} that only whitespace follows (or that is still open at
     * the end of the line) — and, when {@code brackets} is given, appends every bracket found outside strings
     * and comments to it, in order. A quote that opens no string (see {@link Quotes}) is ordinary text, so a
     * lone apostrophe or a Rust lifetime no longer hides the comment after it.
     */
    private static String scanCode(Style style, String s, StringBuilder brackets) {
        int limit = Math.min(s.length(), MAX_SCAN);
        int cut = -1; // start of a block comment that no code follows
        for (int i = 0; i < limit; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int close = Quotes.closing(s, i, limit);
                if (close > i) {
                    i = close;
                    cut = -1;
                    continue;
                }
            }
            if (startsComment(style, s, i)) {
                return s.substring(0, cut >= 0 ? cut : i);
            }
            if (style == Style.BRACES && c == '/' && s.startsWith("/*", i)) {
                if (cut < 0) {
                    cut = i;
                }
                int close = s.indexOf("*/", i + 2);
                if (close < 0 || close >= limit) {
                    return s.substring(0, cut);
                }
                i = close + 1;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                cut = -1;
            }
            if (brackets != null && (c == '(' || c == '[' || c == '{' || isBracketCloser(c))) {
                brackets.append(c);
            }
        }
        return cut >= 0 ? s.substring(0, cut) : s;
    }

    private static boolean startsComment(Style style, String s, int i) {
        char c = s.charAt(i);
        boolean wordStart = i == 0 || Character.isWhitespace(s.charAt(i - 1));
        if (c == '/') {
            return style == Style.BRACES && s.startsWith("//", i);
        }
        if (c == '#') {
            boolean alone = i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)) || s.charAt(i + 1) == '!';
            return switch (style) {
                case SHELL, PY, RUBY -> wordStart;
                case BRACES -> wordStart && alone;
                default -> false;
            };
        }
        if (c == '-' && s.startsWith("--", i)) {
            boolean alone = i + 2 == s.length() || Character.isWhitespace(s.charAt(i + 2));
            return style == Style.LUA || (style == Style.BRACES && wordStart && alone);
        }
        return false;
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

    /**
     * Converts the <b>leading</b> indentation of every line between tabs and spaces — VS Code's
     * {@code editor.action.indentationToSpaces} / {@code indentationToTabs}. Only the leading whitespace run
     * of each line is rewritten; alignment and content after the first non-whitespace char (so anything
     * inside a string too) are left untouched, and a whitespace-only line is left exactly as-is so trailing
     * whitespace isn't altered. Tab width is measured against tab stops. Returns {@code text} unchanged when
     * it is null/empty. Idempotent, and each direction reverses the other on a cleanly-indented file.
     */
    public static String convertIndentation(String text, boolean toSpaces, int tabSize) {
        if (text == null || text.isEmpty() || tabSize < 1) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            int lineEnd = text.indexOf('\n', i);
            boolean lastLine = lineEnd < 0;
            if (lastLine) {
                lineEnd = n;
            }
            // Measure the leading whitespace run in tab-stop-aware columns.
            int ws = i;
            int col = 0;
            while (ws < lineEnd) {
                char c = text.charAt(ws);
                if (c == ' ') {
                    col++;
                } else if (c == '\t') {
                    col += tabSize - (col % tabSize);
                } else {
                    break;
                }
                ws++;
            }
            if (ws == lineEnd) {
                out.append(text, i, lineEnd); // whitespace-only / empty line → leave verbatim
            } else {
                out.append(rebuildIndent(col, toSpaces, tabSize)).append(text, ws, lineEnd);
            }
            if (!lastLine) {
                out.append('\n');
            }
            i = lineEnd + 1;
            if (lastLine) {
                break;
            }
        }
        return out.toString();
    }

    /** {@code col} columns of indentation as all spaces, or as tabs-to-the-tab-stop plus trailing spaces. */
    private static String rebuildIndent(int col, boolean toSpaces, int tabSize) {
        if (col <= 0) {
            return "";
        }
        if (toSpaces) {
            return " ".repeat(col);
        }
        return "\t".repeat(col / tabSize) + " ".repeat(col % tabSize);
    }
}
