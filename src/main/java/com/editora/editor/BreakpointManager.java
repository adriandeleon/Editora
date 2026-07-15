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
public final class BreakpointManager {

    private static final int MAX_REANCHOR_SCAN = 2000;

    private final CodeArea area;
    /** line -> breakpoint, sorted; at most one breakpoint per line. */
    private NavigableMap<Integer, Breakpoint> byLine = new TreeMap<>();

    private Runnable onChanged = () -> {};
    private java.util.function.Consumer<java.util.Collection<Integer>> onLinesRepaint = c -> {};
    private boolean restoring;

    public BreakpointManager(CodeArea area) {
        this.area = area;
        area.plainTextChanges().subscribe(this::onTextChange);
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
        if (!byLine.isEmpty()) {
            byLine = new TreeMap<>();
            fireChanged();
        }
    }

    /** A sorted snapshot of this buffer's breakpoints (for persistence + sending to a DAP session). */
    public List<Breakpoint> snapshot() {
        return new ArrayList<>(byLine.values());
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
        for (Breakpoint bp : saved) {
            if (bp == null || bp.line() < 0) {
                continue;
            }
            int stored = Math.min(bp.line(), maxLine);
            int resolved = resolveLine(stored, bp.lineText(), maxLine, lineTextAt, maxScan);
            out.put(resolved, resolved == bp.line() ? bp : bp.withLine(resolved));
        }
        return out;
    }

    private static int resolveLine(
            int stored, String wanted, int maxLine, java.util.function.IntFunction<String> lineTextAt, int maxScan) {
        if (wanted == null || wanted.isEmpty()) {
            return stored;
        }
        if (textAt(lineTextAt, stored).equals(wanted)) {
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
            if (downOk && textAt(lineTextAt, down).equals(wanted)) {
                return down;
            }
            if (upOk && textAt(lineTextAt, up).equals(wanted)) {
                return up;
            }
        }
        return stored;
    }

    private static String textAt(java.util.function.IntFunction<String> lineTextAt, int line) {
        String t = lineTextAt.apply(line);
        return t == null ? "" : t.strip();
    }

    private void onTextChange(PlainTextChange change) {
        if (byLine.isEmpty()) {
            return; // hot-path early-out
        }
        var pos = area.offsetToPosition(change.getPosition(), Bias.Forward);
        int startLine = pos.getMajor();
        boolean atLineStart = pos.getMinor() == 0;
        int removedNL = countNewlines(change.getRemoved());
        int insertedNL = countNewlines(change.getInserted());
        if (removedNL == 0 && insertedNL == 0) {
            return; // intra-line edit
        }
        NavigableMap<Integer, Breakpoint> shifted = shift(
                byLine,
                startLine,
                atLineStart,
                removedNL,
                insertedNL,
                area.getParagraphs().size());
        if (!shifted.equals(byLine)) {
            java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
            affected.addAll(shifted.keySet());
            byLine = shifted;
            fireChanged();
            onLinesRepaint.accept(affected);
        }
    }

    /**
     * Pure line-shift arithmetic (no toolkit). Breakpoints follow their content (forward gravity): an
     * edit at {@code startLine} that adds/removes newlines moves breakpoints below it by the net line
     * delta, a breakpoint on the edited line moves when the edit is at the line start, and breakpoints in
     * a deleted line span are dropped. Mirrors {@link BookmarkManager#shift}.
     */
    public static NavigableMap<Integer, Breakpoint> shift(
            NavigableMap<Integer, Breakpoint> current,
            int startLine,
            boolean atLineStart,
            int removedNL,
            int insertedNL,
            int paragraphCount) {
        int delta = insertedNL - removedNL;
        int pivot = atLineStart ? startLine - 1 : startLine;
        int removedEndLine = pivot + removedNL;
        // When the deletion starts MID-line (not at column 0), the last line it touches isn't fully deleted —
        // its trailing content merges onto the edit, i.e. a line-join (Backspace at column 0, or Delete at a
        // line's end). A breakpoint on that join line must follow its surviving content to pivot+insertedNL,
        // not vanish — otherwise you'd set a breakpoint, join the line above, and silently debug without it.
        // (For an at-line-start deletion the last removed line's content really is gone; its successor is the
        // survivor and the shift branch below already handles that, so this only applies when !atLineStart.)
        boolean joinSurvives = !atLineStart && removedNL > 0;
        int maxLine = Math.max(0, paragraphCount - 1);
        NavigableMap<Integer, Breakpoint> out = new TreeMap<>();
        for (Breakpoint bp : current.values()) {
            int line = bp.line();
            if (line <= pivot) {
                out.put(line, bp);
            } else if (line == removedEndLine && joinSurvives) {
                int moved = Math.min(Math.max(pivot + insertedNL, 0), maxLine);
                out.put(moved, bp.withLine(moved));
            } else if (line <= removedEndLine) {
                continue; // inside the deleted span
            } else {
                int moved = Math.min(Math.max(line + delta, 0), maxLine);
                out.put(moved, bp.withLine(moved));
            }
        }
        return out;
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

    private static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
