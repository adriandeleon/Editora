package com.editora.logviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The log viewer's filter, fed one complete line at a time: keeps the lines whose (inherited) severity is at
 * least a floor and whose <em>record</em> matches a pattern, minus those a second pattern hides. Pure
 * (java.base only), so it is unit-tested.
 *
 * <p>A record is a line that carries a level plus the lines after it that carry none — a stack trace, a
 * wrapped message. Both tests work on the record, not the physical line: a level floor keeps an exception's
 * whole trace, and a pattern keeps the whole record when any one of its lines matches. Filtering line by line
 * showed {@code SocketTimeoutException: Read timed out} without the ERROR line that says what timed out, and
 * hid the trace of a record matched by its first line.
 *
 * <p>The pattern to hide works on the record too, from its first line: when the line that starts a record
 * matches, the whole record is hidden, its stack trace with it. Any other line that matches is hidden on its
 * own — {@code at org.springframework} shortens a trace without hiding the error. Judging a record by a later
 * line would mean holding every record back until the next one starts, and a followed log would then not show
 * its newest record until another arrived.
 *
 * <p>A record's lines arrive one after another, so a line that does not match is held until a later line of
 * the same record does (then all of them are kept, in order) or the next record starts (then they are
 * dropped). The filter is therefore incremental by construction: a followed log feeds its new lines into the
 * same instance that filtered the document.
 *
 * <p>Patterns run through a {@link LogMatcher}, so one that backtracks without end cannot hang the filter:
 * a line that takes too long counts as not matching, and {@link #slow()} says it happened.
 */
public final class LogRecordFilter {

    /** Receives each kept line, in document order. */
    public interface Sink {
        /** {@code line} (0-based {@code index} in the source) is kept; {@code level} is its inherited level. */
        void keep(String line, int index, @Nullable LogLevel level);
    }

    /** Most unmatched lines of one record kept waiting for a later line to match — bounds a runaway record. */
    static final int MAX_HELD = 2000;

    private final @Nullable LogLevel minLevel;
    private final @Nullable LogMatcher include;
    private final @Nullable LogMatcher exclude;

    private @Nullable LogLevel carry;
    /** A record has started: lines without a level belong to it rather than standing alone. */
    private boolean inRecord;
    /** The current record already matched, so the rest of its lines are kept as they come. */
    private boolean recordKept;
    /** The current record's first line matched the pattern to hide, so all of the record is hidden. */
    private boolean recordHidden;

    private final List<String> heldLines = new ArrayList<>();
    private int[] heldIndex = new int[16];

    /** Keeps the records at or above {@code minLevel} that match {@code include} (null: all of them). */
    public LogRecordFilter(@Nullable LogLevel minLevel, @Nullable Pattern include) {
        this(minLevel, LogFilter.Patterns.including(include));
    }

    public LogRecordFilter(@Nullable LogLevel minLevel, LogFilter.Patterns patterns) {
        this.minLevel = minLevel;
        Pattern in = patterns.include();
        Pattern out = patterns.exclude();
        this.include = in == null ? null : new LogMatcher(in);
        this.exclude = out == null ? null : new LogMatcher(out);
    }

    /** The inherited level after the last line fed in. */
    public @Nullable LogLevel carry() {
        return carry;
    }

    /** Whether a pattern took too long on some line, which was then judged as not matching it. */
    public boolean slow() {
        return (include != null && include.slowLines() > 0) || (exclude != null && exclude.slowLines() > 0);
    }

    /** Feeds the next complete line; {@code index} is its 0-based position in the source. */
    public void accept(String line, int index, Sink sink) {
        LogLevel own = LogPatterns.levelOf(line);
        if (own != null) {
            carry = own;
            inRecord = true;
            recordKept = false;
            recordHidden = false;
            heldLines.clear();
        }
        if (!passesLevel(carry)) {
            return;
        }
        if (hidden(line, own != null)) {
            return;
        }
        if (include == null || recordKept) {
            sink.keep(line, index, carry);
            return;
        }
        if (include.find(line)) {
            for (int i = 0; i < heldLines.size(); i++) {
                sink.keep(heldLines.get(i), heldIndex[i], carry);
            }
            heldLines.clear();
            sink.keep(line, index, carry);
            recordKept = inRecord; // a line before the first record stands alone
        } else if (inRecord && heldLines.size() < MAX_HELD) {
            if (heldLines.size() == heldIndex.length) {
                heldIndex = Arrays.copyOf(heldIndex, heldIndex.length * 2);
            }
            heldIndex[heldLines.size()] = index;
            heldLines.add(line);
        }
    }

    /**
     * Whether the pattern to hide takes {@code line}: it belongs to a record whose first line was hidden, or it
     * matches — and if it starts a record, that whole record is hidden from here on.
     */
    private boolean hidden(String line, boolean startsRecord) {
        if (exclude == null) {
            return false;
        }
        if (recordHidden) {
            return true;
        }
        if (!exclude.find(line)) {
            return false;
        }
        if (startsRecord) {
            recordHidden = true;
        }
        return true;
    }

    /**
     * Whether {@code line} would be kept if it came next, without consuming it — for the unfinished last line
     * of a file that is still being written, which is shown as it stands and judged again once it is complete.
     */
    public boolean wouldKeep(String line) {
        LogLevel own = LogPatterns.levelOf(line);
        LogLevel effective = own != null ? own : carry;
        if (!passesLevel(effective)) {
            return false;
        }
        if (exclude != null && ((own == null && recordHidden) || exclude.find(line))) {
            return false;
        }
        return include == null || (own == null && recordKept) || include.find(line);
    }

    /** The level {@code line} would have if it came next (its own, or the inherited one). */
    public @Nullable LogLevel levelIfNext(String line) {
        LogLevel own = LogPatterns.levelOf(line);
        return own != null ? own : carry;
    }

    private boolean passesLevel(@Nullable LogLevel effective) {
        // Lines above the first record have no level: they pass only when no floor is set.
        return minLevel == null || (effective != null && effective.atLeast(minLevel));
    }
}
