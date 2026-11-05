package com.editora.editops;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Python's "defun" for narrowing and the defun motions — pure, unit-tested. Emacs' language-neutral rule (a
 * defun starts at a column-0 line, see {@link SexpNav}) does not fit Python: a decorator would be its own defun,
 * a column-0 line inside a docstring would end one, and a method is never found. Here, as in Emacs'
 * python-mode, the defun is the innermost {@code def}/{@code class} around the point, from its first decorator
 * to the last line of its body (review 2 A102).
 */
public final class PythonDefun {

    private PythonDefun() {}

    /**
     * {@code {start, end}} of the innermost {@code def} or {@code class} holding {@code pos} — decorators
     * included, ending just after its last body line (its newline included) — or {@code null} when {@code pos}
     * is in no definition. With the point on a decorator, that is the definition it decorates.
     */
    public static int @Nullable [] bounds(String text, int pos) {
        List<PythonScan.Line> lines = PythonScan.scan(text);
        int at = Math.max(0, Math.min(pos, text.length()));
        int line = PythonScan.lineAt(lines, at);
        int header = enclosingHeader(text, lines, line);
        return header < 0 ? null : span(text, lines, header);
    }

    /**
     * Emacs {@code beginning-of-defun} for Python: the start (first decorator) of the definition around
     * {@code pos}; from that start already, the start of the nearest definition above. {@code pos} itself when
     * there is none.
     */
    public static int beginning(String text, int pos) {
        List<PythonScan.Line> lines = PythonScan.scan(text);
        int at = Math.max(0, Math.min(pos, text.length()));
        int header = enclosingHeader(text, lines, PythonScan.lineAt(lines, at));
        if (header >= 0) {
            int start = span(text, lines, header)[0];
            if (start < at) {
                return start;
            }
        }
        int best = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(text, lines.get(i))) {
                int start = span(text, lines, i)[0];
                if (start < at) {
                    best = start;
                }
            }
        }
        return best < 0 ? at : best;
    }

    /**
     * Emacs {@code end-of-defun} for Python: just after the last line of the definition around {@code pos}; when
     * already there (or in none), the end of the next definition below. {@code pos} itself when there is none.
     */
    public static int end(String text, int pos) {
        List<PythonScan.Line> lines = PythonScan.scan(text);
        int at = Math.max(0, Math.min(pos, text.length()));
        int header = enclosingHeader(text, lines, PythonScan.lineAt(lines, at));
        if (header >= 0) {
            int end = span(text, lines, header)[1];
            if (end > at) {
                return end;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            if (isHeader(text, lines.get(i)) && lines.get(i).start() >= at) {
                return span(text, lines, i)[1];
            }
        }
        return at;
    }

    /** Index of the innermost def/class header whose definition holds line {@code line}, or -1. */
    private static int enclosingHeader(String text, List<PythonScan.Line> lines, int line) {
        int start = PythonScan.statementStart(lines, line);
        // On a decorator (or a run of them), the definition is the one they decorate.
        PythonScan.Line s = lines.get(start);
        if (s.statement() && isDecorator(text, s)) {
            for (int i = PythonScan.statementEnd(lines, start) + 1; i < lines.size(); i++) {
                PythonScan.Line l = lines.get(i);
                if (!l.statement()) {
                    continue;
                }
                if (isDecorator(text, l)) {
                    i = PythonScan.statementEnd(lines, i);
                    continue;
                }
                return isHeader(text, l) ? i : -1;
            }
            return -1;
        }
        for (int i = start; i >= 0; i--) {
            PythonScan.Line l = lines.get(i);
            if (l.statement() && isHeader(text, l) && bodyEnd(text, lines, i) >= line) {
                return i;
            }
        }
        return -1;
    }

    /** {@code {start, end}} offsets of the definition headed at line {@code header}. */
    private static int[] span(String text, List<PythonScan.Line> lines, int header) {
        int first = header;
        int width = indentWidth(text, lines.get(header));
        for (int i = header - 1; i >= 0; i--) {
            int st = PythonScan.statementStart(lines, i);
            PythonScan.Line l = lines.get(st);
            if (!l.statement() || !isDecorator(text, l) || indentWidth(text, l) != width) {
                break;
            }
            first = st;
            i = st;
        }
        PythonScan.Line last = lines.get(bodyEnd(text, lines, header));
        int end = last.end() < text.length() ? last.end() + 1 : last.end();
        return new int[] {lines.get(first).start(), end};
    }

    /**
     * The last line of the definition headed at {@code header}: its (possibly wrapped) header, then every line
     * deeper than it — blank lines and comments in between included, but never trailing ones.
     */
    private static int bodyEnd(String text, List<PythonScan.Line> lines, int header) {
        int width = indentWidth(text, lines.get(header));
        int last = PythonScan.statementEnd(lines, header);
        for (int i = last + 1; i < lines.size(); i++) {
            PythonScan.Line l = lines.get(i);
            if (l.blank() || l.commentOnly()) {
                continue; // a comment, even at column 0, does not end a block
            }
            if (l.logicalStart() && indentWidth(text, l) <= width) {
                break;
            }
            last = i;
        }
        return last;
    }

    private static boolean isHeader(String text, PythonScan.Line l) {
        String word = PythonScan.keyword(text, l);
        return l.statement() && (word.equals("def") || word.equals("class"));
    }

    private static boolean isDecorator(String text, PythonScan.Line l) {
        return l.indentEnd() < l.end() && text.charAt(l.indentEnd()) == '@';
    }

    private static int indentWidth(String text, PythonScan.Line l) {
        return Indenter.width(l.indent(text), 8);
    }
}
