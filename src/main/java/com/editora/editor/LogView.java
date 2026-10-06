package com.editora.editor;

import java.util.regex.Pattern;

import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * The log-viewer state of one {@link EditorBuffer}: the level/regex filter, tail-follow, and the complete text
 * that a filter hides.
 *
 * <p>A filter really replaces the editor text with the matching lines, so the area is <b>not</b> the document
 * while one is active. {@link #fullText()} is, and {@link EditorBuffer#getContent()} returns it — otherwise a
 * save (or autosave, local history, the dirty-close prompt) would write the matching subset over the log file.
 * Everything here that rewrites the area runs with {@link #adjusting()} set, so the buffer's dirty tracking can
 * tell a programmatic view change from a user edit: filtering and followed appends never dirty a buffer.
 *
 * <p>Follow mode bounds memory by dropping the oldest lines past {@link #FOLLOW_CAP}. Once that has happened
 * the buffer is no longer the whole file ({@link #trimmed()}) and must not be saved over it.
 */
final class LogView {

    /** Max characters kept in a following log buffer before the oldest lines are trimmed (bounds memory). */
    static final int FOLLOW_CAP = 12 * 1024 * 1024;

    private final CodeArea area;
    /** Runs when a filter is applied or cleared (a filtered view is read-only; see {@link #filtered()}). */
    private final Runnable onFilterChanged;

    private final int cap;
    /**
     * How far past {@link #cap} the text may grow before it is cut back to the cap. Trimming at exactly the cap
     * would shift the whole retained text on every poll of a log that sits at its limit.
     */
    private final int slack;

    /** While a filter is active, the complete unfiltered text (the area shows only matching lines). */
    private StringBuilder full;
    /** {@link #full} as a String, built on demand and shared until the next append. */
    private String fullSnapshot;

    private LogLevel minLevel;
    private Pattern regex;
    /** Inherited level at the end of {@link #full}, so an appended chunk filters with the right carry. */
    private LogLevel carry;
    /** While following ({@code tail -f}), each append auto-scrolls to the bottom. */
    private boolean following;

    private boolean trimmed;
    private boolean adjusting;

    LogView(CodeArea area, Runnable onFilterChanged) {
        this(area, onFilterChanged, FOLLOW_CAP, FOLLOW_CAP / 8);
    }

    LogView(CodeArea area, Runnable onFilterChanged, int cap, int slack) {
        this.area = area;
        this.onFilterChanged = onFilterChanged;
        this.cap = cap;
        this.slack = slack;
    }

    /** Whether a level/regex filter is narrowing the visible lines. The area then holds a subset. */
    boolean filtered() {
        return full != null;
    }

    /** The complete text behind an active filter. Only valid while {@link #filtered()}. */
    String fullText() {
        if (fullSnapshot == null) {
            fullSnapshot = full.toString();
        }
        return fullSnapshot;
    }

    /** Length of {@link #fullText()} without building it. */
    int fullLength() {
        return full.length();
    }

    boolean following() {
        return following;
    }

    void setFollowing(boolean following) {
        this.following = following;
        if (following) {
            scrollToBottom();
        }
    }

    /** The level floor of the active filter (null when unfiltered or no floor). */
    LogLevel minLevel() {
        return minLevel;
    }

    /** True once follow mode has dropped lines: the buffer is a tail of its file, not the file. */
    boolean trimmed() {
        return trimmed;
    }

    /** True while this class is rewriting the area — a view change, not a user edit. */
    boolean adjusting() {
        return adjusting;
    }

    /**
     * Narrows the visible lines to those whose (inherited) level is at least {@code min} and which match
     * {@code rx}; {@code null}/{@code null} clears the filter and restores the full text.
     */
    void applyFilter(LogLevel min, Pattern rx) {
        if (min == null && rx == null) {
            if (full != null) {
                String text = fullText();
                clearFilterState();
                replaceArea(text);
                onFilterChanged.run();
            }
            return;
        }
        String source = full != null ? fullText() : area.getText();
        full = new StringBuilder(source);
        fullSnapshot = source;
        minLevel = min;
        regex = rx;
        carry = LogFilter.endCarry(source, null);
        replaceArea(LogFilter.filter(source, min, rx, null));
        onFilterChanged.run();
    }

    /**
     * Appends {@code text} read from the file's tail. While filtered the full text grows and only the matching
     * subset is shown. Both are trimmed to the cap <em>whether or not</em> the chunk matched: the full text
     * used to be trimmed only on a match, so a filter most lines failed let it grow without bound.
     */
    void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (full != null) {
            String add = LogFilter.filter(text, minLevel, regex, carry);
            carry = LogFilter.endCarry(text, carry);
            full.append(text);
            fullSnapshot = null;
            trimFull();
            if (!add.isEmpty()) {
                appendArea(add);
            }
        } else {
            appendArea(text);
        }
        if (following) {
            scrollToBottom();
        }
    }

    /** Replaces the whole log with {@code fullText} (e.g. on rotation), keeping any active filter. */
    void reset(String fullText) {
        String text = fullText == null ? "" : fullText;
        trimmed = false;
        if (full != null) {
            full = new StringBuilder(text);
            fullSnapshot = text;
            carry = LogFilter.endCarry(text, null);
            replaceArea(LogFilter.filter(text, minLevel, regex, null));
        } else {
            replaceArea(text);
        }
    }

    /**
     * Detaches the filter from the area ahead of a whole-document replacement, returning the action that
     * re-derives it from the new text. The full text held here would otherwise go stale and be what
     * {@link EditorBuffer#getContent()} returns — and therefore what the next save writes.
     *
     * @param freshLoad the replacement is the file as read from disk, not text computed from this buffer
     */
    Runnable suspendFilter(boolean freshLoad) {
        if (freshLoad) {
            trimmed = false; // the buffer is about to hold the whole file again
        }
        if (full == null) {
            return () -> {};
        }
        LogLevel min = minLevel;
        Pattern rx = regex;
        clearFilterState();
        return () -> applyFilter(min, rx);
    }

    private void clearFilterState() {
        full = null;
        fullSnapshot = null;
        minLevel = null;
        regex = null;
        carry = null;
    }

    /** Programmatically replaces the area text (the overlay redraws off the resulting plain-change). */
    private void replaceArea(String text) {
        adjusting = true;
        try {
            area.replaceText(text == null ? "" : text);
            // Undoing across the swap would put the other view's text back while this state still describes
            // the current one — the same reason narrowing drops its history at the boundary.
            area.getUndoManager().forgetHistory();
        } finally {
            adjusting = false;
        }
        scrollToBottom();
    }

    private void appendArea(String text) {
        adjusting = true;
        try {
            area.appendText(text);
            trimArea();
        } finally {
            adjusting = false;
        }
    }

    /** Drops the oldest lines once the displayed text overshoots the follow cap (keeps memory bounded). */
    private void trimArea() {
        int len = area.getLength();
        if (len <= cap + slack) {
            return;
        }
        int cut = len - cap;
        // Round up to the next line start so a half line is never left at the top.
        int line = area.offsetToPosition(cut, TwoDimensional.Bias.Forward).getMajor();
        int end = line + 1 < area.getParagraphs().size() ? area.getAbsolutePosition(line + 1, 0) : cut;
        area.deleteText(0, Math.min(end, len));
        trimmed = true;
    }

    private void trimFull() {
        int len = full.length();
        if (len <= cap + slack) {
            return;
        }
        int cut = len - cap;
        int nl = full.indexOf("\n", cut);
        full.delete(0, nl < 0 ? cut : nl + 1);
        trimmed = true;
    }

    private void scrollToBottom() {
        int total = area.getParagraphs().size();
        if (total > 0) {
            area.showParagraphAtBottom(total - 1);
        }
    }
}
