package com.editora.logviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The log viewer's filter, fed one complete line at a time: keeps the lines whose (inherited) severity is at
 * least a floor and whose <em>record</em> matches a pattern. Pure (java.base only), so it is unit-tested.
 *
 * <p>A record is a line that carries a level plus the lines after it that carry none — a stack trace, a
 * wrapped message. Both tests work on the record, not the physical line: a level floor keeps an exception's
 * whole trace, and a pattern keeps the whole record when any one of its lines matches. Filtering line by line
 * showed {@code SocketTimeoutException: Read timed out} without the ERROR line that says what timed out, and
 * hid the trace of a record matched by its first line.
 *
 * <p>A record's lines arrive one after another, so a line that does not match is held until a later line of
 * the same record does (then all of them are kept, in order) or the next record starts (then they are
 * dropped). The filter is therefore incremental by construction: a followed log feeds its new lines into the
 * same instance that filtered the document.
 */
public final class LogRecordFilter {

    /** Receives each kept line, in document order. */
    public interface Sink {
        /** {@code line} (0-based {@code index} in the source) is kept; {@code level} is its inherited level. */
        void keep(String line, int index, LogLevel level);
    }

    /** Most unmatched lines of one record kept waiting for a later line to match — bounds a runaway record. */
    static final int MAX_HELD = 2000;

    private final LogLevel minLevel;
    private final Pattern pattern;

    private LogLevel carry;
    /** A record has started: lines without a level belong to it rather than standing alone. */
    private boolean inRecord;
    /** The current record already matched, so the rest of its lines are kept as they come. */
    private boolean recordKept;

    private final List<String> heldLines = new ArrayList<>();
    private int heldFirst;

    public LogRecordFilter(LogLevel minLevel, Pattern pattern) {
        this.minLevel = minLevel;
        this.pattern = pattern;
    }

    /** The inherited level after the last line fed in. */
    public LogLevel carry() {
        return carry;
    }

    /** Feeds the next complete line; {@code index} is its 0-based position in the source. */
    public void accept(String line, int index, Sink sink) {
        LogLevel own = LogPatterns.levelOf(line);
        if (own != null) {
            carry = own;
            inRecord = true;
            recordKept = false;
            heldLines.clear();
        }
        if (!passesLevel(carry)) {
            return;
        }
        if (pattern == null || recordKept) {
            sink.keep(line, index, carry);
            return;
        }
        if (pattern.matcher(line).find()) {
            for (int i = 0; i < heldLines.size(); i++) {
                sink.keep(heldLines.get(i), heldFirst + i, carry);
            }
            heldLines.clear();
            sink.keep(line, index, carry);
            recordKept = inRecord; // a line before the first record stands alone
        } else if (inRecord && heldLines.size() < MAX_HELD) {
            if (heldLines.isEmpty()) {
                heldFirst = index;
            }
            if (heldFirst + heldLines.size() == index) {
                heldLines.add(line);
            }
        }
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
        return pattern == null
                || (own == null && recordKept)
                || pattern.matcher(line).find();
    }

    /** The level {@code line} would have if it came next (its own, or the inherited one). */
    public LogLevel levelIfNext(String line) {
        LogLevel own = LogPatterns.levelOf(line);
        return own != null ? own : carry;
    }

    private boolean passesLevel(LogLevel effective) {
        // Lines above the first record have no level: they pass only when no floor is set.
        return minLevel == null || (effective != null && effective.atLeast(minLevel));
    }
}
