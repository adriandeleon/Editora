package com.editora.editorconfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure EditorConfig glob matcher. Compiles a section glob to a regex and tests it against a file path
 * (relative to the {@code .editorconfig}'s directory, {@code /}-separated). Supports {@code *} (not across
 * {@code /}), {@code **} (across {@code /}), {@code ?}, {@code [seq]}/{@code [!seq]}, {@code {a,b,c}}, and
 * {@code {n1..n2}} numeric ranges. A glob with no {@code /} matches the basename in any directory; a leading
 * {@code /} anchors it to the {@code .editorconfig} directory.
 *
 * <p><b>A section glob is untrusted input.</b> An {@code .editorconfig} comes with whatever repository was
 * opened, and its sections are matched on the JavaFX thread as a file opens. A backtracking regex compiled
 * from {@code [*a*a*a*a*a*a*a*a*b]} or {@code [{a,a}{a,a}{a,a}…]} takes exponential time on a non-matching
 * name — a frozen window from opening one file. So the translation is bounded on every axis that can blow
 * up, and a section that exceeds a bound is <em>ignored</em> (it simply does not match), never trusted:
 *
 * <ul>
 *   <li>the glob's length ({@link #MAX_GLOB_LENGTH}, the spec's own limit), its wildcard count
 *       ({@link #MAX_WILDCARDS}) and brace nesting ({@link #MAX_BRACE_DEPTH});
 *   <li>consecutive wildcards collapse ({@code ***} ≡ {@code **}, {@code **}{@code /**}{@code /} ≡
 *       {@code **}{@code /}), so a run costs one loop, not one per star;
 *   <li>the compiled regex's size ({@link #MAX_REGEX_LENGTH});
 *   <li>and — the actual guarantee — the <em>work</em> a match may do: the input is read through a counting
 *       {@link CharSequence} that aborts the match once it has taken more steps than any ordinary glob
 *       needs. A {@link Budget} shared across one file's sections bounds the total as well, so a thousand
 *       individually-cheap hostile sections cannot add up either.
 * </ul>
 *
 * <p>Ordinary globs ({@code *.md}, {@code **}{@code /*.{js,ts}}, {@code [!a-c]*}, {@code file{1..9}.txt}) sit
 * orders of magnitude below every bound and behave exactly as the spec says.
 */
public final class EditorConfigGlob {

    /** Cap on a numeric-range alternation; beyond this we fall back to a generic integer pattern. */
    private static final int MAX_RANGE = 8192;

    private static final Pattern NUM_RANGE = Pattern.compile("(-?\\d+)\\.\\.(-?\\d+)");

    /** Longest section name honoured (the EditorConfig specification's limit). */
    static final int MAX_GLOB_LENGTH = 4096;

    /** Most {@code *} / {@code **} wildcards (after collapsing runs) a section may use. Real ones use 1–3. */
    static final int MAX_WILDCARDS = 16;

    /** Deepest {@code {a,{b,{c}}}} nesting translated (also bounds this class's own recursion). */
    static final int MAX_BRACE_DEPTH = 8;

    /** Largest regex a glob may expand to (numeric ranges and nested alternations multiply). */
    static final int MAX_REGEX_LENGTH = 100_000;

    /** Character reads one section's match may take. Ordinary globs need a few hundred; the largest numeric
     *  range behind a wildcard needs tens of thousands; exponential backtracking needs billions. */
    static final long MATCH_BUDGET = 500_000;

    /** Work one whole {@code .editorconfig} may spend matching a path, summed over its sections. */
    static final long FILE_BUDGET = 4_000_000;

    private static final String ANY_DIRS = "(?:.*/)?";
    private static final String ANY = ".*";

    private EditorConfigGlob() {}

    /**
     * A shared allowance of matching work (regex characters compiled + input characters read). One instance
     * per {@code .editorconfig} per resolved path; once it runs out the remaining sections do not match.
     */
    public static final class Budget {
        private long remaining;

        public Budget() {
            this(FILE_BUDGET);
        }

        Budget(long units) {
            this.remaining = units;
        }

        boolean exhausted() {
            return remaining <= 0;
        }
    }

    /** Thrown (and caught here) when a glob or a match exceeds a bound: the section is ignored. */
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Rejected() {
            super(null, null, false, false); // control flow only: no message, no stack trace
        }
    }

    public static boolean matches(String glob, String relPath) {
        return matches(glob, relPath, new Budget());
    }

    /** As {@link #matches(String, String)}, drawing on (and charging) a {@link Budget} shared by the caller. */
    public static boolean matches(String glob, String relPath, Budget budget) {
        if (glob == null || relPath == null || glob.length() > MAX_GLOB_LENGTH || budget.exhausted()) {
            return false;
        }
        String g = glob;
        if (g.indexOf('/') < 0) {
            g = "**/" + g; // no separator → match the basename in any directory
        } else if (g.startsWith("/")) {
            g = g.substring(1); // leading slash → anchored to the .editorconfig directory
        }
        StringBuilder re = new StringBuilder("^");
        Steps input = null;
        try {
            // also inside the try: a malformed glob must never throw out of matches()
            appendPattern(re, g, 0, new int[1]);
            re.append('$');
            // An ordinary glob reads the path a handful of times over; anything far beyond that is backtracking.
            long allowance = Math.min(budget.remaining - re.length(), MATCH_BUDGET);
            if (allowance <= 0) {
                return false;
            }
            input = new Steps(relPath, allowance);
            return Pattern.compile(re.toString()).matcher(input).matches();
        } catch (RuntimeException | StackOverflowError e) {
            return false; // malformed, over a bound, or abandoned mid-match — the section is ignored
        } finally {
            budget.remaining -= re.length() + (input == null ? 0 : input.taken);
        }
    }

    /** The match input, counting every character the regex engine reads and giving up past {@code limit}. */
    private static final class Steps implements CharSequence {
        private final String text;
        private final long limit;
        private long taken;

        Steps(String text, long limit) {
            this.text = text;
            this.limit = limit;
        }

        @Override
        public char charAt(int index) {
            if (++taken > limit) {
                throw new Rejected();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** A brace-range bound as a long, or null when it doesn't fit (so the caller degrades to "any integer"). */
    private static Long parseBound(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * Appends the regex for {@code g}. {@code wildcards[0]} counts the loops emitted so far across the whole
     * glob (after collapsing, so {@code ***} and {@code **}{@code /**}{@code /} count once).
     */
    private static void appendPattern(StringBuilder re, String g, int depth, int[] wildcards) {
        int n = g.length();
        int i = 0;
        while (i < n) {
            if (re.length() > MAX_REGEX_LENGTH) {
                throw new Rejected();
            }
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    int run = 1;
                    while (i + run < n && g.charAt(i + run) == '*') {
                        run++;
                    }
                    String loop;
                    if (run == 1) {
                        loop = "[^/]*";
                        i++;
                    } else if (run == 2 && i + 2 < n && g.charAt(i + 2) == '/') {
                        loop = ANY_DIRS; // `**/` matches any number of directories, including none
                        i += 3;
                    } else {
                        loop = ANY; // `***`, `****`… are `**`: one loop however long the run
                        i += run;
                    }
                    // `**/**/` is `**/`, `**` twice is `**`: don't stack loops that mean the same thing
                    if (run == 1 || !endsWith(re, loop)) {
                        if (++wildcards[0] > MAX_WILDCARDS) {
                            throw new Rejected();
                        }
                        re.append(loop);
                    }
                }
                case '?' -> {
                    re.append("[^/]");
                    i++;
                }
                case '[' -> {
                    int close = classEnd(g, i);
                    if (close < 0) {
                        re.append("\\[");
                        i++;
                    } else {
                        appendClass(re, g, i, close);
                        i = close + 1;
                    }
                }
                case '{' -> {
                    int close = matchingBrace(g, i);
                    if (close < 0) {
                        re.append("\\{");
                        i++;
                    } else {
                        appendBrace(re, g.substring(i + 1, close), depth + 1, wildcards);
                        i = close + 1;
                    }
                }
                default -> {
                    if ("\\.^$+|()".indexOf(c) >= 0) {
                        re.append('\\');
                    }
                    re.append(c);
                    i++;
                }
            }
        }
    }

    private static void appendClass(StringBuilder re, String g, int open, int close) {
        re.append('[');
        int j = open + 1;
        if (j < close && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
            re.append('^');
            j++;
        }
        while (j < close) {
            char c = g.charAt(j++);
            if (c == '\\' || c == '[') {
                re.append('\\');
            }
            re.append(c);
        }
        re.append(']');
    }

    private static boolean endsWith(StringBuilder re, String suffix) {
        int at = re.length() - suffix.length();
        return at >= 0 && re.indexOf(suffix, at) == at;
    }

    private static void appendBrace(StringBuilder re, String inner, int depth, int[] wildcards) {
        if (depth > MAX_BRACE_DEPTH) {
            throw new Rejected();
        }
        Matcher m = NUM_RANGE.matcher(inner);
        if (m.matches()) {
            // NUM_RANGE accepts any digit count, so a bound past Long.MAX (e.g. `{1..99999999999999999999}` in
            // a hostile .editorconfig) overflows Long.parseLong. The MAX_RANGE cap only fires once BOTH bounds
            // parse, so the throw escaped — and matches()' try/catch is after this call. A bound we can't hold
            // in a long can't be a small enumerable range anyway, so fall back to "match any integer".
            Long lo = parseBound(m.group(1));
            Long hi = parseBound(m.group(2));
            re.append(lo == null || hi == null ? "-?\\d+" : numericRange(lo, hi));
            return;
        }
        List<String> parts = splitTopLevelCommas(inner);
        if (parts.size() == 1) {
            // A single alternative with no comma isn't a brace expansion — treat literally (e.g. `{foo}`).
            re.append("\\{");
            appendPattern(re, inner, depth, wildcards);
            re.append("\\}");
            return;
        }
        re.append("(?:");
        for (int k = 0; k < parts.size(); k++) {
            if (k > 0) {
                re.append('|');
            }
            appendPattern(re, parts.get(k), depth, wildcards);
        }
        re.append(')');
    }

    private static String numericRange(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        if (hi - lo > MAX_RANGE) {
            return "-?\\d+"; // pathological range → match any integer
        }
        StringBuilder sb = new StringBuilder("(?:");
        for (long v = lo; v <= hi; v++) {
            if (v > lo) {
                sb.append('|');
            }
            sb.append(v);
        }
        return sb.append(')').toString();
    }

    /** Index of the closing {@code ]} of a character class started at {@code open}, or -1. */
    private static int classEnd(String g, int open) {
        // A `]` right after `[` or `[!`/`[^` is a literal member, not the close.
        int j = open + 1;
        if (j < g.length() && (g.charAt(j) == '!' || g.charAt(j) == '^')) {
            j++;
        }
        if (j < g.length() && g.charAt(j) == ']') {
            j++;
        }
        for (; j < g.length(); j++) {
            if (g.charAt(j) == ']') {
                return j;
            }
        }
        return -1;
    }

    private static int matchingBrace(String g, int open) {
        int depth = 0;
        for (int j = open; j < g.length(); j++) {
            char c = g.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevelCommas(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int j = 0; j < inner.length(); j++) {
            char c = inner.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, j));
                start = j + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }
}
