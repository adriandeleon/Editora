package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the leading documentation comment sitting immediately above a symbol's declaration line, so the
 * Structure tool window can show it as a hover tooltip. Pure and unit-tested — it works off the buffer's
 * lines alone (LSP document symbols carry no doc text), recognizing the common comment shapes:
 *
 * <ul>
 *   <li>Javadoc/JSDoc/PHPDoc block comments {@code /** … *}{@code /} (leading {@code *} stripped),
 *   <li>plain {@code /* … *}{@code /} block comments,
 *   <li>a run of {@code //} line comments (C/Java/JS/Go/Rust/…),
 *   <li>a run of {@code #} line comments (Python/Ruby/Shell/…),
 *   <li>a run of {@code --} line comments (SQL/Lua/Haskell).
 * </ul>
 *
 * Decorator/annotation lines ({@code @Override}, {@code @staticmethod}, {@code #[derive(...)]}) between the
 * comment and the declaration are skipped so the comment is still found. The result is trimmed to
 * {@link #MAX_LINES} lines / {@link #MAX_CHARS} characters so a huge banner comment can't blow up the tooltip.
 */
final class StructureDoc {

    private StructureDoc() {}

    /** Cap the tooltip so a giant leading comment stays readable. */
    static final int MAX_LINES = 20;

    static final int MAX_CHARS = 2000;

    /**
     * Returns the cleaned doc comment ending just above {@code declLine} (0-based index into {@code lines}),
     * or {@code ""} when there is none. Blank lines and annotation/decorator lines directly above the
     * declaration are skipped before looking for the comment.
     */
    static String commentAbove(List<String> lines, int declLine) {
        if (lines == null || declLine <= 0 || declLine > lines.size()) {
            return "";
        }
        int i = declLine - 1;
        // Skip blank lines and annotation/decorator lines between the comment and the declaration.
        while (i >= 0) {
            String t = lines.get(i).strip();
            if (t.isEmpty() || isAnnotation(t)) {
                i--;
            } else {
                break;
            }
        }
        if (i < 0) {
            return "";
        }
        String t = lines.get(i).strip();
        String result;
        if (t.endsWith("*/")) {
            result = blockComment(lines, i);
        } else if (t.startsWith("//")) {
            result = lineRun(lines, i, "//");
        } else if (t.startsWith("#") && !t.startsWith("#!")) {
            result = lineRun(lines, i, "#");
        } else if (t.startsWith("--")) {
            result = lineRun(lines, i, "--");
        } else {
            return "";
        }
        return cap(result);
    }

    /** True for a decorator/annotation line that should be stepped over: {@code @X}, {@code #[...]}. */
    private static boolean isAnnotation(String stripped) {
        return stripped.startsWith("@") || stripped.startsWith("#[");
    }

    /** Collects a {@code /* … *}{@code /} block ending at line {@code end} (which ends with {@code *}{@code /}). */
    private static String blockComment(List<String> lines, int end) {
        // Walk up to the line that opens the block.
        int start = end;
        while (start >= 0 && !lines.get(start).strip().startsWith("/*")) {
            // A block that never opened (e.g. we're inside code) — bail.
            if (start == 0) {
                break;
            }
            start--;
        }
        if (start < 0 || !lines.get(start).strip().startsWith("/*")) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (int k = start; k <= end; k++) {
            out.add(cleanBlockLine(lines.get(k)));
        }
        return join(out);
    }

    /** Strips {@code /**}, {@code /*}, {@code *}{@code /}, and a leading {@code *} bullet from a block line. */
    private static String cleanBlockLine(String raw) {
        String s = raw.strip();
        if (s.startsWith("/**")) {
            s = s.substring(3);
        } else if (s.startsWith("/*")) {
            s = s.substring(2);
        }
        if (s.endsWith("*/")) {
            s = s.substring(0, s.length() - 2);
        }
        s = s.strip();
        if (s.startsWith("*")) {
            s = s.substring(1).strip();
        }
        return s;
    }

    /** Collects a contiguous upward run of {@code prefix} line comments ending at {@code end}. */
    private static String lineRun(List<String> lines, int end, String prefix) {
        int start = end;
        while (start - 1 >= 0 && lines.get(start - 1).strip().startsWith(prefix)) {
            start--;
        }
        List<String> out = new ArrayList<>();
        for (int k = start; k <= end; k++) {
            String s = lines.get(k).strip().substring(prefix.length()).strip();
            out.add(s);
        }
        return join(out);
    }

    /** Joins lines, dropping leading/trailing blank lines and collapsing them into the paragraph. */
    private static String join(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isEmpty()) {
            from++;
        }
        while (to > from && lines.get(to - 1).isEmpty()) {
            to--;
        }
        return String.join("\n", lines.subList(from, to)).strip();
    }

    private static String cap(String s) {
        if (s.isEmpty()) {
            return "";
        }
        String[] ls = s.split("\n", -1);
        if (ls.length > MAX_LINES) {
            String[] head = new String[MAX_LINES];
            System.arraycopy(ls, 0, head, 0, MAX_LINES);
            s = String.join("\n", head) + "\n…";
        }
        if (s.length() > MAX_CHARS) {
            s = s.substring(0, MAX_CHARS) + "…";
        }
        return s;
    }
}
