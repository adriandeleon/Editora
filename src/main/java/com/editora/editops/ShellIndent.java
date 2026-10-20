package com.editora.editops;

import org.jspecify.annotations.Nullable;

/**
 * Shell indentation from {@link ShellScan}'s structure rather than from the shape of one line: what Enter, a
 * typed closer ({@code fi}, {@code done}, {@code esac}, {@code ;;}, a here-doc's delimiter) and smart Tab do
 * in a shell buffer. Pure; called by {@link Indenter}.
 *
 * <ul>
 *   <li>A body ({@code then}, {@code do}, {@code else}, {@code case … in}, a {@code pattern)}, {@code {},
 *       {@code (}) indents the next line one level past the line its statement starts on.</li>
 *   <li>A case item's {@code ;;} sits at its body's level (shfmt and the Google style guide); the next pattern
 *       goes back to the pattern's own level.</li>
 *   <li>A closer aligns with the line that opened its block, found by matching, not guessed from indentation.</li>
 *   <li>Inside a here-doc nothing is re-indented — the body is data — except its delimiter line, which bash
 *       only recognises unindented ({@code <<}) or tab-indented ({@code <<-}).</li>
 * </ul>
 *
 * <p>Every scan starts at {@link #scanStart}, the same line {@link IndentWindow} cuts its slice at, so the
 * answer for a slice is the answer for the whole text.
 */
final class ShellIndent {

    private ShellIndent() {}

    /**
     * The indent of the line Enter opens at {@code caret}, or null to fall back to the line-shape rules. Only
     * the text before the caret is read (from {@link #scanStart} of the caret's line).
     */
    static String afterEnter(String text, int caret, String unit) {
        int ls = lineStart(text, caret);
        return afterLine(text, scanStart(text, ls), caret, unit);
    }

    /** {@link #afterEnter} at {@code end} (a line end), scanning from {@code from}. */
    private static String afterLine(String text, int from, int end, String unit) {
        ShellScan.Result r = ShellScan.scan(text.substring(from, end));
        int last = r.lineCount() - 1;
        String current = r.indentOf(last);
        if (r.startState(last) == ShellScan.IN_HEREDOC) {
            ShellScan.Block h = r.heredocAt(last);
            // After the delimiter, code resumes at the level of the command that opened the here-doc.
            return h.closeLine() == last ? r.indentOf(h.headerLine()) : current;
        }
        if (r.endsInString()) {
            return current;
        }
        ShellScan.Block b = r.lastBlock(last);
        return switch (r.lastEvent(last)) {
            case ShellScan.BODY -> r.indentOf(b.headerLine()) + unit;
            case ShellScan.CLOSE -> r.indentOf(b.headerLine());
            default -> current;
        };
    }

    /**
     * The indent the caret's line should get when {@code typed} (0 for none; {@code '\n'} for Enter) completes
     * a closer on it, or null to fall back to the bracket/indentation rules ({@code }}, {@code )}, or a closer
     * whose opener is out of reach). A line that closes nothing keeps {@code currentIndent}.
     */
    static @Nullable String closer(
            String text, int caret, char typed, String currentIndent, @Nullable String unit, int tabSize) {
        int ls = lineStart(text, caret);
        String upTo = text.substring(ls, caret);
        String content = (upTo + (typed != 0 && typed != '\n' ? String.valueOf(typed) : "")).strip();
        ShellScan.Result r = ShellScan.scan(text.substring(scanStart(text, ls), ls));
        int line = r.lineCount() - 1; // the caret's line, empty in the scanned text
        if (r.startState(line) == ShellScan.IN_HEREDOC) {
            ShellScan.Block h = r.heredocAt(line);
            boolean atEnd = text.substring(caret, lineEnd(text, caret)).isBlank();
            if ((typed == '\n' || typed == 0) && atEnd && content.equals(h.delimiter())) {
                // `<<-` strips leading tabs only; any other indent hides the delimiter from bash.
                return h.dash() && currentIndent.chars().allMatch(ch -> ch == '\t') ? currentIndent : "";
            }
            return currentIndent;
        }
        if (r.startState(line) == ShellScan.IN_STRING || r.endsInString()) {
            return currentIndent;
        }
        String word = closerWord(content);
        if (word == null) {
            return content.startsWith("}") || content.startsWith(")") ? null : currentIndent;
        }
        if (word.startsWith(";")) {
            ShellScan.Block item = r.innermostOpen(ShellScan.Kind.CASE_ITEM);
            if (item == null) {
                return null;
            }
            String body = firstBodyIndent(r, item.headerLine(), line, tabSize);
            return body != null ? body : r.indentOf(item.headerLine()) + unitOrDefault(unit, r, item, tabSize);
        }
        ShellScan.Kind kind = switch (word) {
            case "fi", "else", "elif" -> ShellScan.Kind.IF;
            case "done" -> ShellScan.Kind.LOOP;
            default -> ShellScan.Kind.CASE; // esac
        };
        ShellScan.Block b = r.innermostOpen(kind);
        return b == null ? null : r.indentOf(b.headerLine());
    }

    /**
     * The indent smart Tab gives the line at {@code ls}: a closer's alignment when the line starts with one,
     * else what Enter at the end of the previous non-blank line would give (inside a here-doc: that line's
     * indent, never a block's). Null when nothing above is in reach.
     */
    static @Nullable String forLine(String text, int ls, String unit, int tabSize) {
        int from = scanStart(text, ls);
        int le = lineEnd(text, ls);
        String line = text.substring(ls, le);
        String leading = line.substring(0, line.length() - line.stripLeading().length());
        ShellScan.Result here = ShellScan.scan(text.substring(from, ls));
        int last = here.lineCount() - 1;
        boolean data = here.startState(last) != ShellScan.CODE;
        if (data
                && here.startState(last) == ShellScan.IN_HEREDOC
                && line.strip().equals(here.heredocAt(last).delimiter())) {
            return closer(text, le, (char) 0, leading, unit, tabSize); // never indent a delimiter out of sight
        }
        if (!data && closerWord(line.strip()) != null) {
            return closer(text, le, (char) 0, leading, unit, tabSize);
        }
        int p = ls;
        while (p > from) {
            int prevStart = lineStart(text, p - 1);
            if (!text.substring(prevStart, p - 1).isBlank()) {
                return afterLine(text, from, p - 1, unit);
            }
            p = prevStart;
        }
        return null; // nothing above in reach: the plain rules decide, for a slice as for the whole text
    }

    /** {@code fi}/{@code else}/{@code elif}/{@code done}/{@code esac}, or {@code ;;}/{@code ;&}/{@code ;;&}, that
     *  {@code content} (a line without its indent) starts with as a whole word; else null. */
    static @Nullable String closerWord(String content) {
        if (content.startsWith(";;") || content.startsWith(";&")) {
            return content.startsWith(";;&") ? ";;&" : content.substring(0, 2);
        }
        int k = 0;
        while (k < content.length() && Character.isLetter(content.charAt(k))) {
            k++;
        }
        String w = content.substring(0, k);
        boolean whole = k == content.length() || ShellScan.isMeta(content.charAt(k));
        return whole && (w.equals("fi") || w.equals("else") || w.equals("elif") || w.equals("done") || w.equals("esac"))
                ? w
                : null;
    }

    /** The indent of the first non-blank line after {@code header} and before {@code line} (a case item's body). */
    private static @Nullable String firstBodyIndent(ShellScan.Result r, int header, int line, int tabSize) {
        for (int k = header + 1; k < line; k++) {
            if (r.startState(k) != ShellScan.CODE) {
                continue;
            }
            int s = r.lineStart(k);
            int e = k + 1 < r.lineCount() ? r.lineStart(k + 1) - 1 : s;
            String ind = r.indentOf(k);
            if (e > s + ind.length()) {
                return ind;
            }
        }
        return null;
    }

    private static String unitOrDefault(@Nullable String unit, ShellScan.Result r, ShellScan.Block item, int tabSize) {
        if (unit != null) {
            return unit;
        }
        return Indenter.indentUnit(r.indentOf(item.headerLine()), tabSize);
    }

    /**
     * Where a scan for the line starting at {@code ls} begins: the same line {@link IndentWindow#backScanStart}
     * cuts the slice at, so a slice and the whole text give the same answer.
     */
    static int scanStart(String text, int ls) {
        int first = ls;
        int scanned = 0;
        while (first > 0 && scanned < Indenter.MAX_SCAN) {
            int prev = lineStart(text, first - 1);
            scanned += first - prev;
            first = prev;
        }
        for (int k = 0; k < Indenter.MAX_STATEMENT_LINES + 1 && first > 0; k++) {
            first = lineStart(text, first - 1);
        }
        return first;
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
