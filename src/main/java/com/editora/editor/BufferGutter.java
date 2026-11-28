package com.editora.editor;

import java.util.List;

import com.editora.editor.EditorBuffer.Split;
import com.editora.editor.fold.StickyScroll;
import com.editora.editor.git.BlameInfo;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What a buffer shows in its gutter and how it answers clicks there: line numbers (or no gutter at all in Simple
 * UI mode), git change bars, the blame column, bookmarks, breakpoints and logpoints, and the debugger's execution
 * line. The gutter cells themselves are built by {@link com.editora.editor.fold.FoldManager}.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferGutter {

    private final EditorBuffer b;

    BufferGutter(EditorBuffer b) {
        this.b = b;
    }

    /** Render size (px) of the blame annotation text — must match {@code .blame-author}/{@code .blame-date}
     *  in {@code app.css} so the measured column width matches what's actually drawn (not the editor font). */
    private static final double BLAME_FONT_SIZE = 10;
    /** IntelliJ-style blame "Annotate" gutter column: per-0-based-line annotations (null/empty clears it).
     *  Already formatted + localized by the controller (author/date/tooltip/heatmap), so {@code editor}
     *  stays git-free. Off on huge files. Computes the fixed column width from the widest author+date, then
     *  rebuilds the gutter so the column appears/disappears. */
    void setBlame(java.util.@Nullable List<BlameInfo> lines) {
        if (!b.gitLines.setBlame(b.hugeFile ? null : lines)) {
            return; // every git refresh comes through here, mostly with nothing: no gutter rebuild for that
        }
        b.blameColumnWidth = b.gitLines.blame() == null ? 0 : measureBlameColumnWidth(b.gitLines.blame());
        refreshGutter();
    }
    /**
     * The distinct blame labels ({@code author + "  " + date}) of {@code lines}: a few dozen to a few hundred
     * per file, where measuring every line was one text layout per line on the FX thread (perf review
     * 2026-11-23 E5).
     */
    static java.util.Set<String> distinctBlameLabels(java.util.List<BlameInfo> lines) {
        java.util.Set<String> labels = new java.util.HashSet<>();
        for (BlameInfo bi : lines) {
            if (bi != null && !bi.isEmpty()) {
                labels.add(bi.author() + "  " + bi.date());
            }
        }
        return labels;
    }
    /** Measures the annotation column once: the widest "author + date" across all lines, in the actual
     *  (small) gutter font so the column isn't padded for the larger editor font, capped so a runaway long
     *  author can't let it dominate (it ellipsizes instead), plus a little cell padding. Off-scene
     *  {@code Text} layout bounds use the font directly, so this is safe. */
    double measureBlameColumnWidth(java.util.List<BlameInfo> lines) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text();
        probe.setFont(javafx.scene.text.Font.font(b.fontFamily, BLAME_FONT_SIZE));
        double max = 0;
        for (String label : distinctBlameLabels(lines)) {
            probe.setText(label);
            max = Math.max(max, probe.getLayoutBounds().getWidth());
        }
        if (max <= 0) {
            return 0;
        }
        double capped = Math.min(max, BLAME_FONT_SIZE * 17.0); // bound a runaway author; longer ones ellipsize
        return Math.ceil(capped) + 10; // + cell padding / inter-label gap
    }
    /** Show/hide the line-number gutter. The fold-chevron column is always present. */
    void setLineNumbersVisible(boolean visible) {
        if (visible == b.lineNumbersVisible) {
            return; // unchanged: refreshGutter() rebuilds every visible row's graphic, so don't re-do it
        }
        b.lineNumbersVisible = visible;
        refreshGutter();
    }
    /** Show/hide the entire gutter (Simple UI mode removes it completely). */
    void setGutterVisible(boolean visible) {
        if (visible == b.gutterVisible) {
            return; // unchanged: see setLineNumbersVisible
        }
        b.gutterVisible = visible;
        refreshGutter();
    }
    /** Rebuilds the gutter graphic factory (line numbers + fold chevrons + markers) from current state, or
     *  removes the gutter entirely (null factory) when {@link EditorBuffer#gutterVisible} is off. */
    void refreshGutter() {
        b.rulerPlacement.markRulerInputsDirty(); // the gutter's width is where column 0 starts
        b.noteOverlay.refresh();
        if (b.indentGuides != null) {
            b.indentGuides.refresh(); // and where the guides start: the new gutter is laid out in the next pulse
        }
        b.area.setParagraphGraphicFactory(b.gutterVisible ? b.folds.gutterFactory(b.lineNumbersVisible) : null);
        applyNoGutterStyle(b.area);
        if (b.area2 != null) { // the same gutter: fold chevrons, bookmark, breakpoint and run markers
            b.area2.setParagraphGraphicFactory(
                    b.gutterVisible ? b.folds.gutterFactory(b.lineNumbersVisible, true) : null);
            applyNoGutterStyle(b.area2);
        }
    }
    /** With no gutter (Simple UI mode; a second view without line numbers) the text would sit flush against
     *  the editor's left edge; the {@code .no-gutter} class adds a small left padding so it doesn't. The
     *  gutter itself supplies that inset when present, so the class is removed then. */
    private void applyNoGutterStyle(CodeArea a) {
        a.getStyleClass().remove("no-gutter");
        if (a.getParagraphGraphicFactory() == null) {
            a.getStyleClass().add("no-gutter");
        }
    }
    /** Sets the Git change bars (0-based on-disk line → CSS class); {@code null} = not tracked, no slot.
     *  Toggling tracking rebuilds the gutter factory; otherwise only the changed lines repaint. Off in
     *  large-file mode (the gutter is minimal there). */
    void setChangeBars(java.util.@Nullable Map<Integer, String> lineClasses) {
        setChangeBars(lineClasses, null);
    }
    /** As {@link #setChangeBars(java.util.Map)} plus a per-line hunk-text map for the change-bar tooltip. */
    void setChangeBars(
            java.util.@Nullable Map<Integer, String> lineClasses, java.util.@Nullable Map<Integer, String> hunkText) {
        // Never tracked in large/huge-file mode. A null answer = the reserved slot appeared or disappeared.
        java.util.Set<Integer> repaint = b.gitLines.setBars(b.largeFile ? null : lineClasses, hunkText);
        if (repaint == null) {
            refreshGutter(); // rebuild the factory
        } else {
            repaint.forEach(this::refreshGutterLine);
        }
    }
    /** Sets the bookmark add/remove handler ({@code (buffer, line)}) used by the right-click menu item;
     *  the controller adds, or confirms a removal. */
    void setBookmarkToggleRequest(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            b.bookmarkToggleRequest = handler;
        }
    }
    /** Rebuilds a single line's gutter graphic (cheap, viewport-safe — same primitive folds use). */
    void refreshGutterLine(int line) {
        if (line >= 0 && line < b.area.getParagraphs().size()) {
            b.area.recreateParagraphGraphic(line);
            if (b.area2 != null && b.split != Split.NONE) {
                b.area2.recreateParagraphGraphic(line); // the split's second view shows the same markers
            }
        }
    }
    /** Toggles the bookmark on {@code line} and refreshes just that line's gutter marker. */
    boolean toggleBookmark(int line) {
        boolean on = b.bookmarks.toggle(line);
        refreshGutterLine(line);
        return on;
    }
    /** Removes the bookmark on {@code line} (if any) and refreshes that line's gutter. */
    void removeBookmark(int line) {
        b.bookmarks.remove(line);
        refreshGutterLine(line);
    }
    /**
     * Replaces this buffer's bookmarks from persisted state and repaints the gutter. Returns
     * {@code true} if a bookmark was re-anchored to its content (the file changed outside the editor),
     * so the caller can persist the corrected indices.
     */
    boolean applyBookmarks(List<com.editora.config.Bookmark> saved) {
        boolean reanchored = b.bookmarks.restore(saved);
        refreshGutter();
        return reanchored;
    }
    /** Enables/disables the leftmost breakpoint gutter strip (rebuilds the gutter when it changes). */
    void setBreakpointsEnabled(boolean enabled) {
        if (enabled != b.debugEnabled) {
            b.debugEnabled = enabled;
            refreshGutter(); // the breakpoint slot appeared/disappeared on every row
        }
    }
    /** Sets the breakpoint-strip click handler ({@code (buffer, line)}) — the controller persists + re-sends. */
    void setGutterBreakpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            b.gutterBreakpointClick = handler;
        }
    }
    /** Sets the breakpoint-strip Shift+click handler ({@code (buffer, line)}) — the controller adds a logpoint. */
    void setGutterLogpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            b.gutterLogpointClick = handler;
        }
    }
    /**
     * Toggles the breakpoint on {@code line} and refreshes just that line's gutter strip. When that removed one, the
     * listener of {@link #setOnBreakpointRemoved} is told which.
     */
    boolean toggleBreakpoint(int line) {
        com.editora.config.Breakpoint was = b.breakpoints.get(line);
        boolean on = b.breakpoints.toggle(line);
        refreshGutterLine(line);
        if (!on && was != null) {
            breakpointRemoved.accept(b, was);
        }
        return on;
    }
    /** Told the breakpoint {@link #toggleBreakpoint} removed, as it was (its line is the buffer's view line). */
    private java.util.function.BiConsumer<EditorBuffer, com.editora.config.Breakpoint> breakpointRemoved =
            (buffer, breakpoint) -> {};
    /**
     * Sets who is told when {@link #toggleBreakpoint} removes a breakpoint (the controller records how to put back one
     * that carried a condition). {@link #dropBreakpoint} tells nobody.
     */
    void setOnBreakpointRemoved(java.util.function.BiConsumer<EditorBuffer, com.editora.config.Breakpoint> listener) {
        breakpointRemoved = listener == null ? (buffer, breakpoint) -> {} : listener;
    }
    /**
     * Removes the breakpoint on {@code line}, if any, without telling the removal listener: a removal nobody needs to
     * take back, because the program made it (a temporary breakpoint that was hit) or because the key that made it
     * sets the breakpoint again (the last lambda of a lambda breakpoint toggled off).
     */
    void dropBreakpoint(int line) {
        b.breakpoints.remove(line);
        refreshGutterLine(line);
    }
    /**
     * Puts {@code breakpoint} back on its (view) line as it is, condition and all. False, changing nothing, when the
     * line is past the end of the text or has a breakpoint now.
     */
    boolean restoreBreakpoint(com.editora.config.Breakpoint breakpoint) {
        int line = breakpoint.line();
        if (line < 0 || line >= b.area.getParagraphs().size() || b.breakpoints.isBreakpoint(line)) {
            return false;
        }
        b.breakpoints.add(breakpoint);
        refreshGutterLine(line);
        return true;
    }
    /** Replaces this buffer's breakpoints from persisted state; returns whether any was re-anchored. */
    boolean applyBreakpoints(List<com.editora.config.Breakpoint> saved) {
        boolean reanchored = b.breakpoints.restore(saved);
        refreshGutter();
        return reanchored;
    }
    /**
     * Marks {@code line} as the current execution point (a distinct background and a gutter arrow), puts the
     * caret there and scrolls it into view clear of the sticky-scroll headers (review B6). Clears any previous mark.
     */
    void setExecutionLine(int line) {
        if (b.executionLine.set(b.area, b.folds, line, () -> b.lineHighlightColor, this::refreshGutterLine)) {
            b.area.moveTo(line, 0);
            b.area.requestFocus();
            b.executionLine.reveal(
                    b.area,
                    line,
                    b.stickyHeaders.stickyScrollEnabled ? b.stickyLines.size() : 0,
                    t -> b.stickyHeaders.stickyScrollEnabled
                            ? StickyScroll.navigationFirstVisible(b.folds.regions(), t)
                            : t);
        }
    }
    /** Removes the execution-point highlight (if any), and the time the run to it took. */
    void clearExecutionLine() {
        b.setStepTime(-1, null);
        b.executionLine.clear(b.area, this::refreshGutterLine);
    }
    /** Sets the blame-annotation click handler ({@code (buffer, line)}) — the controller shows that
     *  line's commit. */
    void setGutterBlameClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            b.gutterBlameClick = handler;
        }
    }
    /** Removes all bookmarks in this buffer and repaints the gutter. */
    void clearBookmarks() {
        b.bookmarks.clear();
        refreshGutter();
    }
}
