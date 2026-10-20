package com.editora.editops;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Python's typing indentation — pure, unit-tested, used by {@link Indenter} for the {@code python} language.
 *
 * <p>Python's indentation is its syntax, so the generic "indent after a {@code :}" rule is not enough:
 * <ul>
 *   <li><b>Enter</b> measures from the line that <em>starts</em> the statement, not the caret's line: after
 *       {@code result = f(a,} / {@code         b)} the next line goes back to {@code result}'s indent, and the
 *       body of a wrapped {@code if (a and} / {@code b):} one unit past the {@code if}. Inside an open bracket
 *       the next line hangs one unit in when the bracket ends its line, or lines up with the first argument
 *       (PEP 8's visual indent) when something follows it.</li>
 *   <li>After {@code return}, {@code pass}, {@code raise}, {@code break} or {@code continue} the block is
 *       over: Enter steps out one level.</li>
 *   <li>Typing the {@code :} of {@code else}, {@code elif …}, {@code except …}, {@code finally} or
 *       {@code case …} lines the clause up with the statement it belongs to ({@code if}, {@code try}, …).</li>
 *   <li>Backspace in the indentation steps back one indent level rather than clearing it.</li>
 * </ul>
 */
public final class PythonIndent {

    private PythonIndent() {}

    /** Statements after which nothing more runs in the block: Enter steps out a level. */
    private static final Set<String> BLOCK_ENDERS = Set.of("return", "pass", "raise", "break", "continue");

    /** A clause keyword → the keywords of the statements it can continue. */
    private static final Map<String, Set<String>> CLAUSE_OPENERS = Map.of(
            "else", Set.of("if", "elif", "for", "while", "try", "except"),
            "elif", Set.of("if", "elif"),
            "except", Set.of("try", "except"),
            "finally", Set.of("try", "except", "else"),
            "case", Set.of("case"));

    /**
     * The indent for the line Enter opens at {@code caret} in {@code text} (which starts at a line start and
     * may hold the lines above the caret — the more it holds, the better wrapped statements are followed).
     */
    static String afterEnter(String text, int caret, String unit, int tabSize) {
        String head = text.substring(boundedStart(text, lineStartOf(text, caret)), caret);
        List<PythonScan.Line> lines = PythonScan.scan(head);
        int index = lines.size() - 1;
        PythonScan.Line cur = lines.get(index);
        String curIndent = cur.indent(head);
        if (cur.stringAtEnd()) {
            return curIndent; // inside a triple-quoted string (a docstring): keep the line's own indent
        }
        if (cur.depthAtEnd() > 0) {
            return insideBracket(head, lines, unit, tabSize);
        }
        int first = PythonScan.statementStart(lines, index);
        PythonScan.Line start = lines.get(first);
        String base = start.indent(head);
        if (cur.continued()) {
            return first == index ? base + unit : curIndent; // `x = 1 + \` continues one unit in
        }
        if (cur.lastCode() == ':') {
            return base + unit;
        }
        int lineEnd = text.indexOf('\n', caret);
        boolean atEnd =
                text.substring(caret, lineEnd < 0 ? text.length() : lineEnd).isBlank();
        if (atEnd && BLOCK_ENDERS.contains(PythonScan.firstWord(head, start))) {
            return Indenter.removeOneIndent(base, dedentWidth(unit, tabSize));
        }
        return base;
    }

    /** Inside an open bracket: hang one unit in when the bracket ends its line, else line up after it. */
    private static String insideBracket(String head, List<PythonScan.Line> lines, String unit, int tabSize) {
        int open = innermostOpenBracket(head, lines);
        if (open < 0) {
            return lines.get(lines.size() - 1).indent(head);
        }
        int ls = lineStartOf(head, open);
        String openerIndent = Indenter.leadingWhitespace(head.substring(ls));
        int after = open + 1;
        int lineEnd = head.indexOf('\n', after);
        if (lineEnd < 0) {
            lineEnd = head.length();
        }
        String rest = head.substring(after, lineEnd);
        String trimmed = rest.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || unit.startsWith("\t")) {
            return openerIndent + unit;
        }
        int firstArg = after + (rest.length() - rest.stripLeading().length());
        int column = Indenter.width(head.substring(ls, firstArg), tabSize);
        return " ".repeat(column);
    }

    /** Offset of the innermost bracket still open at the end of {@code head}, or -1. */
    private static int innermostOpenBracket(String head, List<PythonScan.Line> lines) {
        int index = lines.size() - 1;
        int first = PythonScan.statementStart(lines, index);
        int from = lines.get(first).start();
        // Re-read the statement keeping a stack of open bracket offsets (strings and comments skipped).
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        char triple = 0;
        for (int i = from; i < head.length(); i++) {
            char c = head.charAt(i);
            if (triple != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == triple && head.startsWith(String.valueOf(c).repeat(3), i)) {
                    triple = 0;
                    i += 2;
                }
                continue;
            }
            if (c == '#') {
                int nl = head.indexOf('\n', i);
                if (nl < 0) {
                    break;
                }
                i = nl;
                continue;
            }
            if (c == '"' || c == '\'') {
                if (head.startsWith(String.valueOf(c).repeat(3), i)) {
                    triple = c;
                    i += 2;
                    continue;
                }
                int j = i + 1;
                while (j < head.length() && head.charAt(j) != c && head.charAt(j) != '\n') {
                    j += head.charAt(j) == '\\' ? 2 : 1;
                }
                i = Math.min(j, head.length());
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                stack.push(i);
            } else if ((c == ')' || c == ']' || c == '}') && !stack.isEmpty()) {
                stack.pop();
            }
        }
        return stack.isEmpty() ? -1 : stack.peek();
    }

    /**
     * Whether {@code lineUpToCaretPlusChar} — the line before the caret plus the character just typed
     * ({@code '\n'} for Enter) — completes a clause line: {@code else:}, {@code finally:},
     * {@code elif …:}, {@code except …:}, {@code case …:}, with the {@code :} just typed (or followed by Enter).
     */
    static boolean completesClause(String lineUpToCaretPlusChar) {
        if (lineUpToCaretPlusChar.isEmpty()) {
            return false;
        }
        char typed = lineUpToCaretPlusChar.charAt(lineUpToCaretPlusChar.length() - 1);
        String line = lineUpToCaretPlusChar.substring(0, lineUpToCaretPlusChar.length() - 1);
        if (typed == ':') {
            line = line + ':';
        } else if (typed != '\n') {
            return false;
        }
        return clauseKeyword(line) != null;
    }

    /** The clause keyword ({@code else}, {@code elif}, …) a complete clause line starts with, or {@code null}. */
    static @Nullable String clauseKeyword(String line) {
        List<PythonScan.Line> lines = PythonScan.scan(line);
        PythonScan.Line l = lines.get(lines.size() - 1);
        if (lines.size() != 1 || l.lastCode() != ':' || l.depthAtEnd() != 0 || l.stringAtEnd()) {
            return null;
        }
        String word = PythonScan.firstWord(line, l);
        if (!CLAUSE_OPENERS.containsKey(word)) {
            return null;
        }
        String rest = line.substring(l.indentEnd() + word.length()).strip();
        if (rest.equals(":")) {
            // `else:`, `finally:`, a bare `except:` — but `elif:` / `case:` are not clauses.
            return word.equals("elif") || word.equals("case") ? null : word;
        }
        if (word.equals("else") || word.equals("finally")) {
            return null;
        }
        // `elif x:`, `except ValueError as e:`, `except*`, `case [a, b]:` — the keyword must stand alone.
        if (rest.startsWith("=") && !rest.startsWith("==")) {
            return null; // `case = 1` assigns a name that happens to be a soft keyword
        }
        char next = line.charAt(l.indentEnd() + word.length());
        return Character.isWhitespace(next) || "(*[{\"'".indexOf(next) >= 0 ? word : null;
    }

    /**
     * The indent a clause line ({@code else:} …) at {@code caret} should have: the indent of the nearest
     * statement above it, at its own depth or shallower, that the clause can continue — the {@code if} of
     * an {@code else}, the {@code try} of an {@code except}. {@code null} when the line is not a clause or no
     * such statement is in {@code text}.
     */
    static @Nullable String clauseIndent(String text, int caret, int tabSize, String currentIndent) {
        int ls = lineStartOf(text, caret);
        int le = text.indexOf('\n', caret);
        String lineText = text.substring(ls, le < 0 ? text.length() : le);
        String keyword = clauseKeyword(lineText.stripTrailing().endsWith(":") ? lineText : lineText + ":");
        if (keyword == null) {
            keyword = clauseKeyword(text.substring(ls, caret) + ":");
        }
        Set<String> openers = keyword == null ? null : CLAUSE_OPENERS.get(keyword);
        if (openers == null) {
            return null;
        }
        String above = ls == 0 ? "" : text.substring(boundedStart(text, ls), ls - 1);
        if (above.isEmpty()) {
            return null;
        }
        List<PythonScan.Line> lines = PythonScan.scan(above);
        int limit = Indenter.width(currentIndent, tabSize) + 1;
        for (int i = lines.size() - 1; i >= 0 && limit > 0; i--) {
            PythonScan.Line l = lines.get(i);
            if (!l.statement()) {
                continue;
            }
            int w = Indenter.width(l.indent(above), tabSize);
            if (w >= limit) {
                continue;
            }
            if (openers.contains(PythonScan.keyword(above, l))) {
                return l.indent(above);
            }
            limit = w; // an enclosing statement of another kind: only shallower lines can still match
        }
        return null;
    }

    /**
     * How many characters Backspace deletes with the caret in a line's indentation ({@code before} is all
     * whitespace): back to the previous indent stop, so one press leaves one level. {@code 0} when
     * {@code before} is not leading whitespace.
     */
    static int backspaceCount(String before, String unit, int tabSize) {
        if (before.isEmpty() || !before.isBlank()) {
            return 0;
        }
        if (before.endsWith("\t")) {
            return 1;
        }
        int spaces = 0;
        while (spaces < before.length() && before.charAt(before.length() - 1 - spaces) == ' ') {
            spaces++;
        }
        int step = unit.startsWith("\t") ? Math.max(1, tabSize) : Math.max(1, unit.length());
        int w = Indenter.width(before, tabSize);
        int remove = w % step == 0 ? step : w % step;
        return Math.min(remove, spaces);
    }

    private static int dedentWidth(String unit, int tabSize) {
        return unit.startsWith(" ") ? unit.length() : tabSize;
    }

    /**
     * Where a back-scan from the line starting at {@code lineStart} begins reading: whole lines upward until
     * {@link Indenter#MAX_SCAN} characters are covered, then {@link Indenter#MAX_STATEMENT_LINES} + 1 more —
     * the same bound {@link IndentWindow} slices the document by, so a slice and the whole text agree.
     */
    static int boundedStart(String text, int lineStart) {
        int pos = lineStart;
        for (int scanned = 0; pos > 0 && scanned < Indenter.MAX_SCAN; ) {
            int prev = lineStartOf(text, pos - 1);
            scanned += pos - prev;
            pos = prev;
        }
        for (int k = 0; k <= Indenter.MAX_STATEMENT_LINES && pos > 0; k++) {
            pos = lineStartOf(text, pos - 1);
        }
        return pos;
    }

    private static int lineStartOf(String text, int offset) {
        int nl = text.lastIndexOf('\n', offset - 1);
        return nl < 0 ? 0 : nl + 1;
    }
}
