package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;

import com.editora.config.Breakpoint;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Tracks the breakpoints of a single {@link CodeArea}, keyed by 0-based line. Mirrors
 * {@link BookmarkManager}: it owns the per-buffer breakpoint state, supplies the gutter a cheap
 * {@code isBreakpoint} test, and keeps breakpoint lines pinned to their text as the document is edited
 * (lines inserted/deleted above a breakpoint shift it; deleting a breakpoint's line drops it).
 *
 * <p>The line-shift arithmetic ({@link #shift}) and re-anchoring ({@link #reanchor}) are pure static
 * methods so they can be unit-tested without a JavaFX toolkit.
 */
public final class BreakpointManager implements LineMarks.Carrier {

    public static final int MAX_REANCHOR_SCAN = 2000;

    private final CodeArea area;
    /** line -> breakpoint, sorted; at most one breakpoint per line. */
    private NavigableMap<Integer, Breakpoint> byLine = new TreeMap<>();

    private Runnable onChanged = () -> {};
    private java.util.function.Consumer<java.util.Collection<Integer>> onLinesRepaint = c -> {};
    private boolean restoring;
    /** True while a narrow/widen text swap runs: the swap is not an edit, so nothing is shifted through it. */
    private boolean swapping;
    /** A marked line's text changed since the last {@link #snapshot()} (reported once, not per keystroke). */
    private boolean textStale;
    /** While narrowed, the breakpoints outside the region (in whole-document lines); {@code null} otherwise. */
    private LineMarks.Held<Breakpoint> held;

    private static final LineMarks.Kind<Breakpoint> KIND = new LineMarks.Kind<>() {
        @Override
        public int line(Breakpoint mark) {
            return mark.line();
        }

        @Override
        public String lineText(Breakpoint mark) {
            return mark.lineText();
        }

        @Override
        public Breakpoint withLine(Breakpoint mark, int line) {
            return mark.withLine(line);
        }

        @Override
        public Breakpoint withLineText(Breakpoint mark, String lineText) {
            return mark.withLineText(lineText);
        }
    };

    public BreakpointManager(CodeArea area) {
        this.area = area;
        area.multiPlainChanges().subscribe(this::onTextChanges);
    }

    /** Notified after any change (toggle/edit-driven shift) for persistence + live re-send to a session. */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** Notified when an <em>edit</em> moves breakpoints, with the lines (old ∪ new) to repaint. */
    public void setOnLinesRepaint(java.util.function.Consumer<java.util.Collection<Integer>> cb) {
        this.onLinesRepaint = cb == null ? c -> {} : cb;
    }

    public boolean isBreakpoint(int line) {
        return byLine.containsKey(line);
    }

    public Breakpoint get(int line) {
        return byLine.get(line);
    }

    public NavigableSet<Integer> lines() {
        return byLine.navigableKeySet();
    }

    /** Adds (capturing the line's text) or removes the breakpoint on {@code line}; returns the new state. */
    public boolean toggle(int line) {
        boolean nowOn;
        if (byLine.containsKey(line)) {
            byLine.remove(line);
            nowOn = false;
        } else {
            byLine.put(line, Breakpoint.plain(line, captureLineText(line)));
            nowOn = true;
        }
        fireChanged();
        return nowOn;
    }

    public void add(Breakpoint bp) {
        if (bp == null) {
            return;
        }
        byLine.put(bp.line(), bp);
        fireChanged();
    }

    public void remove(int line) {
        if (byLine.remove(line) != null) {
            fireChanged();
        }
    }

    public void setCondition(int line, String condition) {
        Breakpoint bp = byLine.get(line);
        if (bp != null) {
            byLine.put(line, bp.withCondition(condition == null ? "" : condition));
            fireChanged();
        }
    }

    public void setLogMessage(int line, String log) {
        Breakpoint bp = byLine.get(line);
        if (bp != null) {
            byLine.put(line, bp.withLogMessage(log == null ? "" : log));
            fireChanged();
        }
    }

    public void setEnabled(int line, boolean enabled) {
        Breakpoint bp = byLine.get(line);
        if (bp != null && bp.enabled() != enabled) {
            byLine.put(line, bp.withEnabled(enabled));
            fireChanged();
        }
    }

    public void clear() {
        boolean heldAny =
                held != null && !(held.before().isEmpty() && held.after().isEmpty());
        if (!byLine.isEmpty() || heldAny) {
            byLine = new TreeMap<>();
            held = held == null ? null : held.emptied();
            fireChanged();
        }
    }

    /** A sorted snapshot of this buffer's breakpoints (for persistence + sending to a DAP session). */
    public List<Breakpoint> snapshot() {
        textStale = false;
        return new ArrayList<>(byLine.values());
    }

    /**
     * The breakpoints in <em>whole-document</em> lines, whether or not the buffer is narrowed — what a debug
     * adapter needs. While narrowed, {@link #snapshot()} holds only the region's breakpoints with
     * region-relative lines; sending those armed the wrong lines and dropped every breakpoint outside the
     * region.
     */
    public List<Breakpoint> documentSnapshot() {
        if (held == null) {
            return snapshot();
        }
        return new ArrayList<>(LineMarks.release(
                        held, byLine.values(), KIND, area.getParagraphs().size())
                .values());
    }

    /** The whole-document line of the narrowed region's first line (0 when not narrowed). */
    public int regionFirstLine() {
        return held == null ? 0 : held.firstLine();
    }

    /**
     * Replaces the breakpoint state from a persisted list, without firing {@link #onChanged}. Each
     * breakpoint is re-anchored to its saved {@link Breakpoint#lineText()} (see {@link #reanchor}) so it
     * stays on its content after an external edit. Returns {@code true} if any resolved line differs from
     * its stored line (so the caller can persist the self-healed indices).
     */
    public boolean restore(List<Breakpoint> saved) {
        restoring = true;
        try {
            byLine = reanchor(
                    saved,
                    area.getParagraphs().size(),
                    line -> area.getParagraph(line).getText(),
                    MAX_REANCHOR_SCAN);
            return anyMoved(saved, byLine);
        } finally {
            restoring = false;
        }
    }

    private static boolean anyMoved(List<Breakpoint> saved, NavigableMap<Integer, Breakpoint> resolved) {
        if (saved == null) {
            return false;
        }
        for (Breakpoint bp : saved) {
            if (bp == null || bp.line() < 0) {
                continue;
            }
            Breakpoint at = resolved.get(bp.line());
            if (at == null || !sameText(at.lineText(), bp.lineText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameText(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /**
     * Pure re-anchoring (no toolkit): rebuilds the line→breakpoint map from a persisted list, moving each
     * breakpoint to the nearest line whose stripped text equals its saved {@code lineText}. A breakpoint
     * whose stored line already matches stays put; one whose content has drifted is re-found within
     * {@code maxScan} lines; one whose {@code lineText} is empty or gone is kept at its clamped stored line.
     *
     * <p>A line already claimed by an earlier breakpoint is <em>never</em> reused: two breakpoints whose
     * lines both still exist must not collapse onto one, because the map would silently drop one of them.
     * Adjacent identical lines make that easy to hit (two {@code });} in a row) — after an external edit
     * shifts them, the upper breakpoint re-anchors down onto the line the lower one still exact-matches.
     * Each breakpoint is therefore resolved against the lines still free, and the list is walked in
     * stored-line order so the outcome never depends on the caller's ordering (a persisted list need not
     * be line-ordered).
     */
    public static NavigableMap<Integer, Breakpoint> reanchor(
            List<Breakpoint> saved,
            int paragraphCount,
            java.util.function.IntFunction<String> lineTextAt,
            int maxScan) {
        NavigableMap<Integer, Breakpoint> out = new TreeMap<>();
        if (saved == null || paragraphCount <= 0) {
            return out;
        }
        int maxLine = paragraphCount - 1;
        List<Breakpoint> ordered = new ArrayList<>();
        for (Breakpoint bp : saved) {
            if (bp != null && bp.line() >= 0) {
                ordered.add(bp);
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(Breakpoint::line));
        for (Breakpoint bp : ordered) {
            int stored = Math.min(bp.line(), maxLine);
            int resolved = resolveLine(stored, bp.lineText(), maxLine, lineTextAt, maxScan, out::containsKey);
            if (resolved < 0) {
                continue; // every line is already claimed (fewer lines than breakpoints)
            }
            out.put(resolved, resolved == bp.line() ? bp : bp.withLine(resolved));
        }
        return out;
    }

    private static int resolveLine(
            int stored,
            String wanted,
            int maxLine,
            java.util.function.IntFunction<String> lineTextAt,
            int maxScan,
            java.util.function.IntPredicate taken) {
        boolean haveText = wanted != null && !wanted.isEmpty();
        if (haveText) {
            if (!taken.test(stored) && textAt(lineTextAt, stored).equals(wanted)) {
                return stored;
            }
            for (int r = 1; r <= maxScan; r++) {
                int down = stored + r;
                int up = stored - r;
                boolean downOk = down <= maxLine;
                boolean upOk = up >= 0;
                if (!downOk && !upOk) {
                    break;
                }
                if (downOk && !taken.test(down) && textAt(lineTextAt, down).equals(wanted)) {
                    return down;
                }
                if (upOk && !taken.test(up) && textAt(lineTextAt, up).equals(wanted)) {
                    return up;
                }
            }
        }
        return taken.test(stored) ? nearestFree(stored, maxLine, taken) : stored;
    }

    /** The nearest line to {@code stored} that no earlier breakpoint claimed (downward first), else -1. */
    private static int nearestFree(int stored, int maxLine, java.util.function.IntPredicate taken) {
        for (int r = 1; r <= maxLine + 1; r++) {
            int down = stored + r;
            int up = stored - r;
            boolean downOk = down <= maxLine;
            boolean upOk = up >= 0;
            if (!downOk && !upOk) {
                break;
            }
            if (downOk && !taken.test(down)) {
                return down;
            }
            if (upOk && !taken.test(up)) {
                return up;
            }
        }
        return -1;
    }

    private static String textAt(java.util.function.IntFunction<String> lineTextAt, int line) {
        return LineMarks.snapshotText(lineTextAt.apply(line));
    }

    private void onTextChanges(List<PlainTextChange> changes) {
        if (swapping || byLine.isEmpty()) {
            return; // hot-path early-out: nothing to track
        }
        LineMarkTracker.Result<Breakpoint> result = LineMarkTracker.apply(byLine, KIND, changes, area);
        if (result.moved()) {
            // Both the vacated and the new lines need their gutter markers repainted: the document edit
            // already rebuilds those graphics, but with the pre-shift set, so the moved marker would
            // otherwise vanish until the next manual refresh.
            java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
            affected.addAll(result.marks().keySet());
            byLine = result.marks();
            fireChanged();
            onLinesRepaint.accept(affected);
        } else if (result.retexted()) {
            // Only a marked line's own text changed. Typing on such a line does this per keystroke, so the
            // change is reported once and the fresh text is picked up by the next snapshot().
            byLine = result.marks();
            if (!textStale) {
                textStale = true;
                fireChanged();
            }
        }
    }

    /**
     * Pure line-shift arithmetic (no toolkit): breakpoints follow their content as lines are inserted, deleted,
     * joined or rewritten. The rules live in {@link LineMarks#shift}, shared with the other line-pinned
     * marks; this overload has no document text, so a rewritten span of a different length only clamps.
     */
    public static NavigableMap<Integer, Breakpoint> shift(
            NavigableMap<Integer, Breakpoint> current,
            int startLine,
            boolean atLineStart,
            int removedNL,
            int insertedNL,
            int paragraphCount) {
        return LineMarks.shift(current, KIND, startLine, atLineStart, removedNL, insertedNL, paragraphCount, null);
    }

    /**
     * Carries the breakpoints across the narrowing swap: those in the region are rebased onto it, the rest are
     * held aside in whole-document lines until {@link #widen}. Nothing is reported as changed — the
     * breakpoints have not moved in the file, and their region-relative lines must not be persisted.
     */
    @Override
    public void narrow(int start, int end, Runnable swap) {
        int firstLine = area.offsetToPosition(start, Bias.Forward).getMajor();
        int lastLine = area.offsetToPosition(end, Bias.Forward).getMajor();
        java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
        NavigableMap<Integer, Breakpoint> inside = new TreeMap<>();
        LineMarks.Held<Breakpoint> outside = LineMarks.hold(byLine.values(), KIND, firstLine, lastLine, inside);
        runSwap(swap);
        held = outside;
        byLine = inside;
        affected.addAll(byLine.keySet());
        onLinesRepaint.accept(affected);
    }

    /** Puts the held breakpoints back around the region's own, in whole-document lines, and reports the change. */
    @Override
    public void widen(Runnable swap) {
        int regionLines = area.getParagraphs().size();
        java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
        runSwap(swap);
        if (held == null) {
            return;
        }
        byLine = LineMarks.release(held, byLine.values(), KIND, regionLines);
        held = null;
        affected.addAll(byLine.keySet());
        fireChanged();
        onLinesRepaint.accept(affected);
    }

    private void runSwap(Runnable swap) {
        swapping = true;
        try {
            swap.run();
        } finally {
            swapping = false;
        }
    }

    private void fireChanged() {
        if (!restoring) {
            onChanged.run();
        }
    }

    private String captureLineText(int line) {
        if (line < 0 || line >= area.getParagraphs().size()) {
            return "";
        }
        return area.getParagraph(line).getText().strip();
    }
}
