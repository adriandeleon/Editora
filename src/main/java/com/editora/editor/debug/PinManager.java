package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.editora.config.PinnedLine;
import com.editora.editor.LineMarkTracker;
import com.editora.editor.LineMarks;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.jspecify.annotations.Nullable;

/**
 * The values pinned to this buffer's lines while debugging (Visual Studio's pinned DataTips, IntelliJ's inline
 * watches): expressions, by line, that the debugger evaluates at every stop and the editor paints at the line's end.
 * Like bookmarks and breakpoints, pins follow their line through edits ({@link LineMarkTracker}), through narrowing,
 * and — by the text of the line, kept with them — through edits made outside the editor.
 */
public final class PinManager implements LineMarks.Carrier {

    /** Most pins one line keeps: the line's end has room for a few. */
    public static final int MAX_PER_LINE = 4;

    private static final int MAX_REANCHOR_SCAN = 2000;

    private final CodeArea area;
    private NavigableMap<Integer, PinnedLine> byLine = new TreeMap<>();
    private Runnable onChanged = () -> {};
    private boolean restoring;
    private boolean swapping;
    private LineMarks.@Nullable Held<PinnedLine> held;

    private static final LineMarks.Kind<PinnedLine> KIND = new LineMarks.Kind<>() {
        @Override
        public int line(PinnedLine mark) {
            return mark.line();
        }

        @Override
        public String lineText(PinnedLine mark) {
            return mark.lineText();
        }

        @Override
        public PinnedLine withLine(PinnedLine mark, int line) {
            return mark.withLine(line);
        }

        @Override
        public PinnedLine withLineText(PinnedLine mark, String lineText) {
            return mark.withLineText(lineText);
        }
    };

    public PinManager(CodeArea area) {
        this.area = area;
        area.multiPlainChanges().subscribe(this::onTextChanges);
    }

    /** Notified after any change (a pin added or removed, or an edit moving pins) for persistence and repaint. */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /** Whether any value is pinned in this buffer. */
    public boolean isEmpty() {
        return byLine.isEmpty();
    }

    /** The expressions pinned to {@code line}, in the order they were pinned. */
    public List<String> expressions(int line) {
        PinnedLine p = byLine.get(line);
        return p == null ? List.of() : p.expressions();
    }

    /** Every pinned line, by line (a view: copy before changing the pins). */
    public NavigableMap<Integer, PinnedLine> byLine() {
        return java.util.Collections.unmodifiableNavigableMap(byLine);
    }

    /**
     * Pins {@code expression} to {@code line}, or unpins it when it is pinned there already. Returns whether it is
     * pinned now; false too when the line already holds {@link #MAX_PER_LINE}.
     */
    public boolean toggle(int line, String expression) {
        String expr = expression == null ? "" : expression.strip();
        if (expr.isEmpty() || line < 0 || line >= area.getParagraphs().size()) {
            return false;
        }
        PinnedLine p = byLine.get(line);
        List<String> list = new ArrayList<>(p == null ? List.of() : p.expressions());
        boolean pinned;
        if (list.remove(expr)) {
            pinned = false;
        } else if (list.size() >= MAX_PER_LINE) {
            return false;
        } else {
            list.add(expr);
            pinned = true;
        }
        if (list.isEmpty()) {
            byLine.remove(line);
        } else {
            byLine.put(line, new PinnedLine(line, list, captureLineText(line)));
        }
        fireChanged();
        return pinned;
    }

    /** Removes every pin on {@code line}; whether there was one. */
    public boolean clearLine(int line) {
        if (byLine.remove(line) == null) {
            return false;
        }
        fireChanged();
        return true;
    }

    /** Removes every pin of this buffer. */
    public void clear() {
        boolean heldAny =
                held != null && !(held.before().isEmpty() && held.after().isEmpty());
        if (!byLine.isEmpty() || heldAny) {
            byLine = new TreeMap<>();
            held = held == null ? null : held.emptied();
            fireChanged();
        }
    }

    /** The pins, by line, for persistence (whole-document lines unless narrowed: the caller checks). */
    public List<PinnedLine> snapshot() {
        List<PinnedLine> out = new ArrayList<>();
        for (PinnedLine p : byLine.values()) {
            out.add(p.withLineText(captureLineText(p.line())));
        }
        return out;
    }

    /**
     * Replaces the pins from a persisted list without reporting a change, each re-anchored to its saved line text
     * (see {@link #reanchor}). Returns whether any moved, so the caller can persist the healed lines.
     */
    public boolean restore(List<PinnedLine> saved) {
        restoring = true;
        try {
            byLine = reanchor(
                    saved,
                    area.getParagraphs().size(),
                    line -> area.getParagraph(line).getText(),
                    MAX_REANCHOR_SCAN);
            if (saved == null) {
                return false;
            }
            for (PinnedLine p : saved) {
                if (p != null && p.line() >= 0 && !byLine.containsKey(p.line())) {
                    return true;
                }
            }
            return false;
        } finally {
            restoring = false;
        }
    }

    /**
     * Pure re-anchoring: each saved line goes to the nearest line whose stripped text equals its snapshot (its stored
     * line first), never onto a line an earlier one took; one whose text is gone stays at its clamped line. A line
     * with no expression is dropped.
     */
    public static NavigableMap<Integer, PinnedLine> reanchor(
            @Nullable List<PinnedLine> saved,
            int paragraphCount,
            java.util.function.IntFunction<String> lineTextAt,
            int maxScan) {
        NavigableMap<Integer, PinnedLine> out = new TreeMap<>();
        if (saved == null || paragraphCount <= 0) {
            return out;
        }
        int maxLine = paragraphCount - 1;
        List<PinnedLine> ordered = new ArrayList<>();
        for (PinnedLine p : saved) {
            if (p != null && p.line() >= 0 && !p.expressions().isEmpty()) {
                ordered.add(p);
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(PinnedLine::line));
        for (PinnedLine p : ordered) {
            int stored = Math.min(p.line(), maxLine);
            int at = find(stored, p.lineText(), maxLine, lineTextAt, maxScan, out);
            if (at >= 0) {
                out.put(at, at == p.line() ? p : p.withLine(at));
            }
        }
        return out;
    }

    private static int find(
            int stored,
            String wanted,
            int maxLine,
            java.util.function.IntFunction<String> lineTextAt,
            int maxScan,
            NavigableMap<Integer, PinnedLine> taken) {
        if (wanted != null && !wanted.isEmpty()) {
            for (int r = 0; r <= maxScan; r++) {
                for (int line : r == 0 ? new int[] {stored} : new int[] {stored + r, stored - r}) {
                    if (line >= 0
                            && line <= maxLine
                            && !taken.containsKey(line)
                            && LineMarks.snapshotText(lineTextAt.apply(line)).equals(wanted)) {
                        return line;
                    }
                }
            }
        }
        for (int r = 0; r <= maxLine; r++) {
            for (int line : r == 0 ? new int[] {stored} : new int[] {stored + r, stored - r}) {
                if (line >= 0 && line <= maxLine && !taken.containsKey(line)) {
                    return line;
                }
            }
        }
        return -1;
    }

    private void onTextChanges(List<PlainTextChange> changes) {
        if (swapping || byLine.isEmpty()) {
            return; // hot-path early-out: nothing to track
        }
        LineMarkTracker.Result<PinnedLine> result = LineMarkTracker.apply(byLine, KIND, changes, area);
        if (result.moved() || result.retexted()) {
            boolean moved = result.moved();
            byLine = result.marks();
            if (moved) {
                fireChanged();
            }
        }
    }

    /** Carries the pins across narrowing as bookmarks are carried: the region's rebased, the rest held aside. */
    @Override
    public void narrow(int start, int end, Runnable swap) {
        int firstLine = area.offsetToPosition(start, Bias.Forward).getMajor();
        int lastLine = area.offsetToPosition(end, Bias.Forward).getMajor();
        NavigableMap<Integer, PinnedLine> inside = new TreeMap<>();
        LineMarks.Held<PinnedLine> outside = LineMarks.hold(byLine.values(), KIND, firstLine, lastLine, inside);
        runSwap(swap);
        held = outside;
        byLine = inside;
    }

    @Override
    public void widen(Runnable swap) {
        int regionLines = area.getParagraphs().size();
        runSwap(swap);
        if (held == null) {
            return;
        }
        byLine = LineMarks.release(held, byLine.values(), KIND, regionLines);
        held = null;
        fireChanged();
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
        return LineMarks.snapshotText(area.getParagraph(line).getText());
    }
}
