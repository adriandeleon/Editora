package com.editora.editor;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogRecordFilter;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * The log-viewer state of one {@link EditorBuffer}: the level/pattern filter, tail-follow, and the complete text
 * that a filter hides.
 *
 * <p>A filter really replaces the editor text with the matching lines, so the area is <b>not</b> the document
 * while one is active. {@link #fullText()} is, and {@link EditorBuffer#getContent()} returns it — otherwise a
 * save (or autosave, local history, the dirty-close prompt) would write the matching subset over the log file.
 * Everything here that rewrites the area runs with {@link #adjusting()} set, so the buffer's dirty tracking and
 * its undo history can tell a programmatic view change from a user edit: filtering and followed appends never
 * dirty a buffer and are never something Undo takes back.
 *
 * <p>While filtered, every visible line is one kept line followed by {@code '\n'}, and this class knows which
 * line of the full text each one is ({@link #lineNumberAt}) and what level it has ({@link #levelAt}). The gutter
 * and the level tints read those instead of deriving them from the visible text, where a stack-trace line would
 * be numbered and coloured after whatever line the filter happened to leave above it.
 *
 * <p>A followed log grows a chunk at a time, and a chunk can end mid-line. Only complete lines are judged by the
 * filter; the unfinished last line is shown as it stands and judged again when its newline arrives.
 *
 * <p>Follow mode bounds memory by dropping the oldest lines once the text has grown {@link #FOLLOW_CAP} past
 * what it held when following started. Once that has happened the buffer is no longer the whole file
 * ({@link #trimmed()}) and must not be saved over it.
 */
final class LogView {

    /** Max characters a following log buffer may grow by before the oldest lines are trimmed (bounds memory). */
    static final int FOLLOW_CAP = 12 * 1024 * 1024;

    private static final LogLevel[] LEVELS = LogLevel.values();

    private final CodeArea area;
    /** Runs when a filter is applied or cleared (a filtered view is read-only; see {@link #filtered()}). */
    private final Runnable onFilterChanged;
    /** Runs when what the log control shows may have changed: the line counts, following, trimmed. */
    private Runnable onStateChanged = () -> {};

    private final int cap;
    /**
     * How far past the limit the text may grow before it is cut back to it. Trimming at exactly the limit
     * would shift the whole retained text on every poll of a log that sits at its limit.
     */
    private final int slack;
    /**
     * Characters held when following started. They are what the user opened and are never trimmed for being
     * there: the cap bounds how much a follow <em>adds</em>. (Following a 40 MB log used to drop its first
     * 28 MB on the first new line.)
     */
    private int heldAtFollowStart;

    /** While a filter is active, the complete unfiltered text (the area shows only matching lines). */
    private StringBuilder full;
    /** {@link #full} as a String, built on demand and shared until the next append. */
    private String fullSnapshot;

    private LogLevel minLevel;
    /** The pattern as the user typed it, and compiled. */
    private String query;

    private Pattern regex;
    /** The filter, positioned after the last complete line of {@link #full}. */
    private LogRecordFilter filter;
    /** Length of the prefix of {@link #full} made of complete lines the filter has judged. */
    private int consumed;
    /** Line index (in the text as it was when the filter was applied) of the next line the filter will judge. */
    private int nextLine;
    /** Lines trimmed from the top of {@link #full} since the filter was applied. */
    private int droppedLines;

    /** For each visible line: its line index (same origin as {@link #nextLine}) and level ordinal ({@code -1} none). */
    private int[] visibleLine = new int[0];

    private byte[] visibleLevel = new byte[0];
    private int visibleCount;
    /** Characters at the end of the area showing the unfinished last line (it is also the last visible line). */
    private int provisionalChars;

    /** While following ({@code tail -f}), an append keeps the view at the end if it was there. */
    private boolean following;

    private boolean trimmed;
    private boolean adjusting;
    /** Bumped by every change to the text that is not an append: a filter computed before it is stale. */
    private int epoch;

    LogView(CodeArea area, Runnable onFilterChanged) {
        this(area, onFilterChanged, FOLLOW_CAP, FOLLOW_CAP / 8);
    }

    LogView(CodeArea area, Runnable onFilterChanged, int cap, int slack) {
        this.area = area;
        this.onFilterChanged = onFilterChanged;
        this.cap = cap;
        this.slack = slack;
        area.plainTextChanges().filter(c -> !adjusting).subscribe(c -> epoch++); // a user edit
    }

    void setOnStateChanged(Runnable listener) {
        onStateChanged = listener == null ? () -> {} : listener;
    }

    /** Whether a level/pattern filter is narrowing the visible lines. The area then holds a subset. */
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
        boolean changed = this.following != following;
        this.following = following;
        if (following) {
            heldAtFollowStart = full != null ? full.length() : area.getLength();
            scrollToBottom();
        }
        if (changed) {
            onStateChanged.run();
        }
    }

    /** The level floor of the active filter (null when unfiltered or no floor). */
    LogLevel minLevel() {
        return minLevel;
    }

    /** The pattern of the active filter as it was typed (null when unfiltered or no pattern). */
    String query() {
        return query;
    }

    /** True once follow mode has dropped lines: the buffer is a tail of its file, not the file. */
    boolean trimmed() {
        return trimmed;
    }

    /** True while this class is rewriting the area — a view change, not a user edit. */
    boolean adjusting() {
        return adjusting;
    }

    /** Lines the filter is showing. Only meaningful while {@link #filtered()}. */
    int visibleLines() {
        return visibleCount;
    }

    /** Lines in the complete text (an unfinished last line included). Only meaningful while {@link #filtered()}. */
    int totalLines() {
        return nextLine - droppedLines + (consumed < full.length() ? 1 : 0);
    }

    /**
     * The 1-based line number, in the complete text, of visible paragraph {@code paragraph}: {@code -1} when
     * unfiltered (the paragraph index is the line), {@code 0} for the empty row past the last line.
     */
    int lineNumberAt(int paragraph) {
        if (full == null) {
            return -1;
        }
        return paragraph < 0 || paragraph >= visibleCount ? 0 : visibleLine[paragraph] - droppedLines + 1;
    }

    /** Lines to size the gutter for: {@code -1} when unfiltered (the paragraph count). */
    int lineNumberSpan() {
        return full == null ? -1 : totalLines();
    }

    /**
     * The inherited level of visible paragraph {@code paragraph} as the complete text has it. Only valid while
     * {@link #filtered()}; null for a line with no level, or past the last line.
     */
    LogLevel levelAt(int paragraph) {
        if (full == null || paragraph < 0 || paragraph >= visibleCount || visibleLevel[paragraph] < 0) {
            return null;
        }
        return LEVELS[visibleLevel[paragraph]];
    }

    // --- filtering -------------------------------------------------------------------------------

    /** The text a filter is computed from. */
    String filterSource() {
        return full != null ? fullText() : area.getText();
    }

    /** See {@link #epoch}: a {@link LogFilter.Run} is installable only under the epoch its source was read at. */
    int epoch() {
        return epoch;
    }

    /** Whether {@code min}/{@code pattern} is the filter already showing (so applying it again is no work). */
    boolean showsFilter(LogLevel min, String pattern) {
        String wanted = pattern == null || pattern.isEmpty() ? null : pattern;
        return min == minLevel && Objects.equals(wanted, query) && (full != null || (min == null && wanted == null));
    }

    /**
     * Narrows the visible lines to the records whose (inherited) level is at least {@code min} and which match
     * {@code pattern}; {@code null}/{@code null} clears the filter and restores the full text. The caret stays
     * on the line it was on, or the nearest one still visible.
     */
    void applyFilter(LogLevel min, String pattern) {
        if (showsFilter(min, pattern)) {
            return;
        }
        Pattern rx = LogFilter.compileFilter(pattern);
        if (min == null && rx == null) {
            clearFilter();
            return;
        }
        String source = filterSource();
        install(LogFilter.run(source, min, rx), source, min, pattern);
    }

    /**
     * Installs a filter computed elsewhere (off the FX thread) from {@link #filterSource()} read under
     * {@code atEpoch}. Returns false, changing nothing, when the text has since changed in any way other than
     * growing at its end — the caller computes again. Lines appended in the meantime are filtered here.
     */
    boolean install(LogFilter.Run run, int atEpoch, LogLevel min, String pattern) {
        if (atEpoch != epoch) {
            return false;
        }
        install(run, filterSource(), min, pattern);
        return true;
    }

    private void install(LogFilter.Run run, String current, LogLevel min, String pattern) {
        boolean atEnd = following && showsEnd();
        int caretLine = caretSourceLine();
        full = new StringBuilder(current);
        fullSnapshot = current;
        minLevel = min;
        query = pattern == null || pattern.isEmpty() ? null : pattern;
        regex = LogFilter.compileFilter(query);
        adopt(run);
        replaceArea(run.text());
        showPending();
        placeCaret(caretLine, atEnd);
        onFilterChanged.run();
        onStateChanged.run();
    }

    private void adopt(LogFilter.Run run) {
        filter = run.filter();
        consumed = run.consumed();
        nextLine = run.total();
        droppedLines = 0;
        visibleLine = run.lines();
        visibleLevel = run.levels();
        visibleCount = run.kept();
        provisionalChars = 0;
    }

    private void clearFilter() {
        if (full == null) {
            return;
        }
        boolean atEnd = following && showsEnd();
        int caretLine = caretSourceLine();
        String text = fullText();
        clearFilterState();
        replaceArea(text);
        placeCaret(caretLine, atEnd);
        onFilterChanged.run();
        onStateChanged.run();
    }

    /** The line of the complete text (0-based, from its current top) the caret is on. */
    private int caretSourceLine() {
        int paragraph = area.getCurrentParagraph();
        if (full == null) {
            return paragraph;
        }
        if (visibleCount == 0) {
            return 0;
        }
        return visibleLine[Math.min(paragraph, visibleCount - 1)] - droppedLines;
    }

    /**
     * Puts the caret on {@code sourceLine}, or the first visible line at or after it, and brings it into view —
     * unless the view was following at the end of the log, where it stays.
     */
    private void placeCaret(int sourceLine, boolean atEnd) {
        int paragraph;
        if (full == null) {
            paragraph = sourceLine;
        } else {
            int at = Arrays.binarySearch(visibleLine, 0, visibleCount, sourceLine + droppedLines);
            paragraph = at >= 0 ? at : -at - 1;
            if (paragraph >= visibleCount) {
                paragraph = visibleCount - 1; // nothing at or after it is visible: the last line that is
            }
        }
        paragraph = Math.max(0, Math.min(paragraph, area.getParagraphs().size() - 1));
        area.moveTo(paragraph, 0);
        if (atEnd) {
            scrollToBottom();
        } else {
            area.showParagraphAtCenter(paragraph);
        }
    }

    // --- following -------------------------------------------------------------------------------

    /**
     * Appends {@code text} read from the file's tail. While filtered the full text grows and only the matching
     * lines are shown. Neither the caret nor the selection moves, and the view scrolls only if it was already
     * showing the end — reading older lines of a busy log must not be a fight with every new one.
     */
    void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean atEnd = following && showsEnd();
        if (full != null) {
            hideProvisional();
            full.append(text);
            fullSnapshot = null;
            showPending();
            trimFull();
        } else {
            insertAtEnd(text);
            trimArea();
        }
        if (atEnd) {
            scrollToBottom();
        }
        onStateChanged.run();
    }

    /** Replaces the whole log with {@code fullText} (e.g. on rotation), keeping any active filter. */
    void reset(String fullText) {
        String text = fullText == null ? "" : fullText;
        trimmed = false;
        heldAtFollowStart = 0;
        if (full != null) {
            LogFilter.Run run = LogFilter.run(text, minLevel, regex);
            full = new StringBuilder(text);
            fullSnapshot = text;
            adopt(run);
            replaceArea(run.text());
            showPending();
        } else {
            replaceArea(text);
        }
        scrollToBottom();
        onStateChanged.run();
    }

    /**
     * Detaches the filter from the area ahead of a whole-document replacement, returning the action that
     * re-derives it from the new text. The full text held here would otherwise go stale and be what
     * {@link EditorBuffer#getContent()} returns — and therefore what the next save writes.
     *
     * @param freshLoad the replacement is the file as read from disk, not text computed from this buffer
     */
    Runnable suspendFilter(boolean freshLoad) {
        epoch++;
        if (freshLoad) {
            trimmed = false; // the buffer is about to hold the whole file again
            heldAtFollowStart = 0;
        }
        if (full == null) {
            return () -> {};
        }
        LogLevel min = minLevel;
        String pattern = query;
        clearFilterState();
        return () -> applyFilter(min, pattern);
    }

    private void clearFilterState() {
        full = null;
        fullSnapshot = null;
        minLevel = null;
        query = null;
        regex = null;
        filter = null;
        consumed = 0;
        nextLine = 0;
        droppedLines = 0;
        visibleLine = new int[0];
        visibleLevel = new byte[0];
        visibleCount = 0;
        provisionalChars = 0;
    }

    /**
     * Judges the complete lines of {@link #full} the filter has not seen yet and shows the kept ones, then the
     * unfinished last line if it would be kept as it stands. Expects no provisional line to be showing.
     */
    private void showPending() {
        LogFilter.Collector kept = new LogFilter.Collector(256);
        int pos = consumed;
        for (int nl = full.indexOf("\n", pos); nl >= 0; nl = full.indexOf("\n", pos)) {
            filter.accept(full.substring(pos, nl), nextLine++, kept);
            pos = nl + 1;
        }
        consumed = pos;
        for (int i = 0; i < kept.count; i++) {
            addVisible(kept.lineAt(i), kept.levelAt(i));
        }
        StringBuilder add = kept.text;
        if (consumed < full.length()) {
            String unfinished = full.substring(consumed);
            if (filter.wouldKeep(unfinished)) {
                LogLevel level = filter.levelIfNext(unfinished);
                addVisible(nextLine, (byte) (level == null ? -1 : level.ordinal()));
                add.append(unfinished).append('\n');
                provisionalChars = unfinished.length() + 1;
            }
        }
        if (add.length() > 0) {
            insertAtEnd(add.toString());
        }
    }

    private void hideProvisional() {
        if (provisionalChars > 0) {
            int len = area.getLength();
            delete(len - provisionalChars, len);
            visibleCount--;
            provisionalChars = 0;
        }
    }

    private void addVisible(int line, byte level) {
        if (visibleCount == visibleLine.length) {
            int size = Math.max(64, visibleCount * 2);
            visibleLine = Arrays.copyOf(visibleLine, size);
            visibleLevel = Arrays.copyOf(visibleLevel, size);
        }
        visibleLine[visibleCount] = line;
        visibleLevel[visibleCount] = level;
        visibleCount++;
    }

    // --- area edits ------------------------------------------------------------------------------

    /** Programmatically replaces the area text (the overlay redraws off the resulting plain-change). */
    private void replaceArea(String text) {
        adjusting = true;
        try {
            epoch++;
            area.replaceText(text == null ? "" : text);
            // Undoing across the swap would put the other view's text back while this state still describes
            // the current one — the same reason narrowing drops its history at the boundary.
            area.getUndoManager().forgetHistory();
        } finally {
            adjusting = false;
        }
    }

    /**
     * Adds {@code text} at the end through the document rather than the area: the area's own
     * {@code appendText} moves the caret to the end of what it inserted, dropping the selection with it.
     */
    private void insertAtEnd(String text) {
        int len = area.getLength();
        replaceInDocument(len, len, text);
    }

    private void delete(int start, int end) {
        if (end > start) {
            replaceInDocument(start, end, "");
        }
    }

    private void replaceInDocument(int start, int end, String text) {
        adjusting = true;
        try {
            area.getContent()
                    .replace(
                            start,
                            end,
                            ReadOnlyStyledDocument.fromString(
                                    text,
                                    area.getInitialParagraphStyle(),
                                    area.getInitialTextStyle(),
                                    area.getSegOps()));
        } finally {
            adjusting = false;
        }
    }

    /** Characters the retained text may reach before the oldest lines go. */
    private long limit() {
        return (long) cap + heldAtFollowStart;
    }

    /** Drops the oldest lines once the displayed text overshoots the follow limit (keeps memory bounded). */
    private void trimArea() {
        int len = area.getLength();
        if (len <= limit() + slack) {
            return;
        }
        int cut = (int) (len - limit());
        // Round up to the next line start so a half line is never left at the top.
        int line = area.offsetToPosition(cut, TwoDimensional.Bias.Forward).getMajor();
        int end = line + 1 < area.getParagraphs().size() ? area.getAbsolutePosition(line + 1, 0) : cut;
        delete(0, Math.min(end, len));
        dropped();
    }

    /**
     * Drops the oldest lines of the complete text once it overshoots the follow limit — whether or not anything
     * appended matched the filter — and with them the visible lines that were among those.
     */
    private void trimFull() {
        int len = full.length();
        if (len <= limit() + slack) {
            return;
        }
        int cut = (int) (len - limit());
        int nl = full.indexOf("\n", cut);
        int end = nl < 0 ? cut : nl + 1;
        boolean intoUnfinished = end > consumed; // one line longer than the limit: it loses its head
        if (intoUnfinished) {
            hideProvisional();
        }
        int lines = 0;
        for (int i = full.indexOf("\n"); i >= 0 && i < end; i = full.indexOf("\n", i + 1)) {
            lines++;
        }
        full.delete(0, end);
        fullSnapshot = null;
        consumed = Math.max(0, consumed - end);
        droppedLines += lines;
        int gone = 0;
        while (gone < visibleCount && visibleLine[gone] < droppedLines) {
            gone++;
        }
        if (gone > 0) {
            delete(0, gone < area.getParagraphs().size() ? area.getAbsolutePosition(gone, 0) : area.getLength());
            System.arraycopy(visibleLine, gone, visibleLine, 0, visibleCount - gone);
            System.arraycopy(visibleLevel, gone, visibleLevel, 0, visibleCount - gone);
            visibleCount -= gone;
        }
        if (intoUnfinished) {
            showPending();
        }
        dropped();
    }

    /** The text lost its top: positions in the undo history no longer mean what they did, and nor does a save. */
    private void dropped() {
        trimmed = true;
        epoch++;
        area.getUndoManager().forgetHistory();
    }

    /** Whether the last line is on screen (true before the first layout: nothing has been scrolled away from). */
    private boolean showsEnd() {
        try {
            return area.lastVisibleParToAllParIndex() >= area.getParagraphs().size() - 2;
        } catch (RuntimeException notLaidOut) {
            return true;
        }
    }

    private void scrollToBottom() {
        int total = area.getParagraphs().size();
        if (total > 0) {
            area.showParagraphAtBottom(total - 1);
        }
    }
}
