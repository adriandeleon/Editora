package com.editora.editor;

import javafx.scene.Node;

import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogQuery;
import org.jspecify.annotations.Nullable;

/**
 * The log viewer side of a buffer: forcing log mode on another file, the floating log control, level
 * highlighting and its overview stripe, the filter, and a merged log's sources.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The filter, follow and trim state itself is {@link
 * com.editora.editor.logviewer.LogView}.
 */
final class BufferLog {

    private final EditorBuffer b;

    BufferLog(EditorBuffer b) {
        this.b = b;
    }

    /** Forces log-viewer mode on a buffer whose extension isn't {@code .log} ("View as Log"). */
    boolean logViewForced;

    private com.editora.logviewer.@Nullable LogSources logSources; // a merged log's (Log: Merge Logs…)
    /** Whether this buffer is shown as a log by request ("View as Log", or its content) rather than by name. */
    boolean isLogViewForced() {
        return logViewForced;
    }
    /** Forces (or clears) log-viewer mode on a buffer whose name isn't a log's; rebuilds the host. */
    void setLogViewForced(boolean forced) {
        if (this.logViewForced == forced) {
            return;
        }
        this.logViewForced = forced;
        b.viewLayout.rebuildViewHost();
    }
    /**
     * Docks the log control (Follow / level / pattern) above the text; {@code null} removes it. It is a bar of
     * its own rather than a control floating in the corner: log lines are the longest lines the editor shows,
     * and a floating control sat on top of the end of the first two.
     */
    void setLogControl(@Nullable Node control) {
        b.logControl = control;
        b.refreshTopBars();
    }
    /** Turns the size-independent level overlay on/off (controller gates on the feature + {@link EditorBuffer#isLog()}). */
    void setLogHighlightEnabled(boolean on) {
        boolean want = on && b.isLog();
        if (want || b.logOverlay != null) {
            b.logOverlay().setActive(want);
        }
        if (want || b.overlays.logStripe != null) {
            b.overlays.logStripe().setActive(want);
            b.updateMarkdownLintStripe();
        }
    }
    /**
     * Narrows the visible lines to the records at or above {@code minLevel} that {@code text} keeps (its
     * patterns are regular expressions, or plain text when not valid ones); {@code null}/{@code NONE} clears
     * the filter. {@link EditorBuffer#getContent()} stays the whole log throughout.
     */
    void applyLogFilter(@Nullable LogLevel minLevel, LogQuery text) {
        b.widen(); // the filter is derived from the area, which must therefore be the whole document
        b.logView.applyFilter(minLevel, text);
    }
    /**
     * The text a log filter is computed from, and {@link EditorBuffer#logFilterEpoch()} the token to hand back with the
     * result: a large log is filtered off the FX thread, and {@link EditorBuffer#installLogFilter} takes the result only
     * if the text has done nothing but grow since.
     */
    String logFilterSource() {
        b.widen();
        return b.logView.filterSource();
    }
    /** Tags each line of a merged log with the log it came from (see {@code LogMerge}); null for none. */
    void setLogSources(com.editora.logviewer.@Nullable LogSources sources) {
        logSources = sources;
        if (sources != null || b.logOverlay != null) {
            b.logOverlay().setSources(sources);
        }
    }
    /** The sources of a merged log, or null. */
    com.editora.logviewer.@Nullable LogSources getLogSources() {
        return logSources;
    }
}
