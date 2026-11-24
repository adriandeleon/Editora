package com.editora.logviewer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a user's filter pattern over log lines with a time limit. A pattern such as {@code (a+)+$} backtracks
 * exponentially on a line it almost matches, and {@link Matcher} cannot be interrupted, so the line is handed
 * to it through a {@link CharSequence} that checks the clock every few thousand reads and gives up when one
 * line has taken longer than {@link #LIMIT_NANOS}. A line that timed out counts as not matching; after
 * {@link #MAX_SLOW} of them the matcher stops trying altogether, so a bad pattern costs a log at most a few
 * hundred milliseconds wherever it runs (the FX thread filters a small log, and the overlay highlights
 * visible lines there too).
 *
 * <p>Only a pattern that can backtrack badly pays for the check (see {@link #canBacktrack}); the rest are
 * matched on the line directly. Not thread-safe: one instance per filter run or overlay.
 */
public final class LogMatcher {

    /** Longest one line may take to match before it is treated as not matching. */
    static final long LIMIT_NANOS = 50_000_000L;

    /** Lines that may time out before the matcher gives up on the pattern. */
    static final int MAX_SLOW = 3;

    /** Reads between two looks at the clock (a power of two minus one, as a mask). */
    private static final int CHECK_MASK = 0xFFF;

    private final Pattern pattern;
    private final Matcher matcher;
    private final boolean guarded;
    private final Budget budget = new Budget();
    private final long limitNanos;
    private int slow;

    public LogMatcher(Pattern pattern) {
        this(pattern, LIMIT_NANOS);
    }

    LogMatcher(Pattern pattern, long limitNanos) {
        this.pattern = pattern;
        this.matcher = pattern.matcher("");
        this.guarded = canBacktrack(pattern);
        this.limitNanos = limitNanos;
    }

    /** The pattern this matches with. */
    public Pattern pattern() {
        return pattern;
    }

    /** Quantifiers a pattern may hold before it is timed anyway: {@code .*a.*b.*c} is already cubic. */
    private static final int MANY_QUANTIFIERS = 3;

    /**
     * Whether {@code pattern} can backtrack badly enough to need the clock: a repeated group ({@code (a+)+},
     * {@code (.*a){20}}), a back-reference, or {@value #MANY_QUANTIFIERS} or more quantifiers. Everything else
     * — a word, an alternation, {@code time.*out} — runs in about linear time and is matched on the line
     * directly, at no cost: reading the line through the guard made such filters some 10% slower.
     */
    static boolean canBacktrack(Pattern pattern) {
        if ((pattern.flags() & Pattern.LITERAL) != 0) {
            return false;
        }
        String source = pattern.pattern();
        int quantifiers = 0;
        boolean afterGroup = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\' && i + 1 < source.length()) {
                char next = source.charAt(++i);
                if ((next >= '1' && next <= '9') || next == 'k') {
                    return true; // a back-reference
                }
                afterGroup = false;
                continue;
            }
            boolean quantifier = c == '*' || c == '+' || c == '{';
            if (quantifier && afterGroup) {
                return true;
            }
            if (quantifier && ++quantifiers >= MANY_QUANTIFIERS) {
                return true;
            }
            afterGroup = c == ')';
        }
        return false;
    }

    /** Whether {@code line} contains a match. False, too, when it took too long or the matcher has given up. */
    public boolean find(String line) {
        if (gaveUp()) {
            return false;
        }
        if (!guarded) {
            return matcher.reset(line).find();
        }
        budget.start(line, System.nanoTime() + limitNanos);
        try {
            return matcher.reset(budget).find();
        } catch (TooSlow e) {
            slow++;
            return false;
        } finally {
            budget.end();
        }
    }

    /** Receives one match as a {@code [start, end)} range of the line. */
    public interface RangeSink {
        void range(int start, int end);
    }

    /**
     * Reports up to {@code max} non-empty matches in {@code line}, in order, and returns how many were reported.
     * Matching stops (keeping what was reported) when the line takes too long.
     */
    public int ranges(String line, int max, RangeSink sink) {
        if (gaveUp() || max <= 0) {
            return 0;
        }
        int reported = 0;
        if (guarded) {
            budget.start(line, System.nanoTime() + limitNanos);
        }
        try {
            Matcher m = matcher.reset(guarded ? budget : line);
            while (reported < max && m.find()) {
                if (m.end() > m.start()) {
                    sink.range(m.start(), m.end());
                    reported++;
                }
            }
        } catch (TooSlow e) {
            slow++;
        } finally {
            budget.end();
        }
        return reported;
    }

    /** Lines that took too long to match so far. */
    public int slowLines() {
        return slow;
    }

    /** Whether so many lines took too long that the pattern is no longer tried. */
    public boolean gaveUp() {
        return slow >= MAX_SLOW;
    }

    /** Thrown from inside the matcher when the line's time is up; shared, without a stack trace. */
    private static final class TooSlow extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final TooSlow INSTANCE = new TooSlow();

        private TooSlow() {
            super("pattern too slow", null, false, false);
        }
    }

    /** The line as the matcher reads it, looking at the clock every {@link #CHECK_MASK}+1 reads. */
    private static final class Budget implements CharSequence {
        private String text = "";
        private long deadline;
        private int reads;

        void start(String line, long deadline) {
            this.text = line;
            this.deadline = deadline;
            this.reads = 0;
        }

        void end() {
            text = ""; // do not hold a long line past its match
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if ((++reads & CHECK_MASK) == 0 && System.nanoTime() > deadline) {
                throw TooSlow.INSTANCE;
            }
            return text.charAt(index);
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
}
