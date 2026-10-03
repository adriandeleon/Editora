package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;

import com.editora.config.Bookmark;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Tracks the bookmarks of a single {@link CodeArea}, keyed by 0-based line. Analogous to
 * {@link FoldManager}: it owns the per-buffer bookmark state, supplies the gutter a cheap
 * {@code isBookmarked} test, and keeps bookmark lines pinned to their text as the document is edited
 * (lines inserted/deleted above a bookmark shift it; deleting a bookmark's line drops it).
 *
 * <p>The line-shift arithmetic is a pure static method ({@link #shift}) so it can be unit-tested
 * without a JavaFX toolkit.
 */
public final class BookmarkManager implements LineMarks.Carrier {

    /** Bounds the outward line scan when re-anchoring a drifted bookmark (one-time, at file open). */
    private static final int MAX_REANCHOR_SCAN = 2000;

    private final CodeArea area;
    /** line -> bookmark, sorted; at most one bookmark per line. */
    private NavigableMap<Integer, Bookmark> byLine = new TreeMap<>();

    private Runnable onChanged = () -> {};
    /** Notified with the lines to repaint when an <em>edit</em> shifts bookmarks (old ∪ new lines). */
    private java.util.function.Consumer<java.util.Collection<Integer>> onLinesRepaint = c -> {};
    /** Suppresses {@link #onChanged} while we programmatically restore saved bookmarks. */
    private boolean restoring;
    /** True while a narrow/widen text swap runs: the swap is not an edit, so nothing is shifted through it. */
    private boolean swapping;
    /** While narrowed, the bookmarks outside the region (in whole-document lines); {@code null} otherwise. */
    private LineMarks.Held<Bookmark> held;

    private static final LineMarks.Kind<Bookmark> KIND = new LineMarks.Kind<>() {
        @Override
        public int line(Bookmark mark) {
            return mark.line();
        }

        @Override
        public String lineText(Bookmark mark) {
            return mark.lineText();
        }

        @Override
        public Bookmark withLine(Bookmark mark, int line) {
            return mark.withLine(line);
        }
    };

    public BookmarkManager(CodeArea area) {
        this.area = area;
        area.plainTextChanges().subscribe(this::onTextChange);
    }

    /** Notified after any change (toggle/note/remove or an edit-driven line shift) for persistence. */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    /**
     * Sets the callback invoked when an <em>edit</em> moves bookmarks to new lines, with the set of
     * lines (the old and new positions) whose gutter markers must be repainted. (Toggle/remove repaint
     * their own line directly; only edit-driven {@link #shift}s need this, since the document edit
     * rebuilds the gutter graphics with a possibly-stale bookmark set.)
     */
    public void setOnLinesRepaint(java.util.function.Consumer<java.util.Collection<Integer>> cb) {
        this.onLinesRepaint = cb == null ? c -> {} : cb;
    }

    public boolean isBookmarked(int line) {
        return byLine.containsKey(line);
    }

    public NavigableSet<Integer> lines() {
        return byLine.navigableKeySet();
    }

    /** Adds (capturing the line's text) or removes the bookmark on {@code line}; returns the new state. */
    public boolean toggle(int line) {
        boolean nowOn;
        if (byLine.containsKey(line)) {
            byLine.remove(line);
            nowOn = false;
        } else {
            byLine.put(line, new Bookmark(line, "", captureLineText(line)));
            nowOn = true;
        }
        fireChanged();
        return nowOn;
    }

    public void add(int line, String note) {
        byLine.put(line, new Bookmark(line, note, captureLineText(line)));
        fireChanged();
    }

    public void remove(int line) {
        if (byLine.remove(line) != null) {
            fireChanged();
        }
    }

    /** Sets/clears the note on the bookmark at {@code line} (no-op if there is none). */
    public void setNote(int line, String note) {
        Bookmark bm = byLine.get(line);
        if (bm != null) {
            byLine.put(line, bm.withNote(note == null ? "" : note));
            fireChanged();
        }
    }

    /** Next bookmarked line strictly after {@code fromLine}, wrapping to the first; null if none. */
    public Integer next(int fromLine) {
        if (byLine.isEmpty()) {
            return null;
        }
        Integer k = byLine.higherKey(fromLine);
        return k != null ? k : byLine.firstKey();
    }

    /** Previous bookmarked line strictly before {@code fromLine}, wrapping to the last; null if none. */
    public Integer previous(int fromLine) {
        if (byLine.isEmpty()) {
            return null;
        }
        Integer k = byLine.lowerKey(fromLine);
        return k != null ? k : byLine.lastKey();
    }

    /** Removes all bookmarks in this buffer. */
    public void clear() {
        boolean heldAny =
                held != null && !(held.before().isEmpty() && held.after().isEmpty());
        if (!byLine.isEmpty() || heldAny) {
            byLine = new TreeMap<>();
            held = held == null ? null : held.emptied();
            fireChanged();
        }
    }

    /** A sorted snapshot of this buffer's bookmarks (for persistence). */
    public List<Bookmark> snapshot() {
        return new ArrayList<>(byLine.values());
    }

    /**
     * Replaces the bookmark state from a persisted list, without firing {@link #onChanged}. Each
     * bookmark is <em>re-anchored to its saved {@link Bookmark#lineText()}</em> (see {@link #reanchor})
     * so it stays on its content even after the file was edited <em>outside</em> the editor (where
     * {@link #onTextChange} never ran to shift indices). Returns {@code true} if any bookmark's
     * resolved line differs from its stored line, so the caller can persist the self-healed indices.
     */
    public boolean restore(List<Bookmark> saved) {
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

    /** True if a saved bookmark's line differs from where it was re-anchored to in {@code resolved}. */
    private static boolean anyMoved(List<Bookmark> saved, NavigableMap<Integer, Bookmark> resolved) {
        if (saved == null) {
            return false;
        }
        for (Bookmark bm : saved) {
            if (bm == null || bm.line() < 0) {
                continue;
            }
            // A saved bookmark "moved" if no resolved entry sits at its original line carrying its text.
            Bookmark at = resolved.get(bm.line());
            if (at == null || !sameText(at.lineText(), bm.lineText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameText(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /**
     * Pure, unit-testable re-anchoring (no toolkit): rebuilds the line→bookmark map from a persisted
     * list, moving each bookmark to the nearest line whose stripped text equals its saved
     * {@code lineText}. A bookmark whose stored line already matches stays put (the common O(1) case);
     * one whose content has drifted (file edited outside the editor) is re-found within {@code maxScan}
     * lines; one whose {@code lineText} is empty or no longer present is kept at its clamped stored
     * line.
     *
     * <p>A line already claimed by an earlier bookmark is <em>never</em> reused: two bookmarks whose
     * lines both still exist must not collapse onto one, because the map would silently drop one of them
     * <em>and its note</em>. Adjacent identical lines make that easy to hit (two {@code });} in a row) —
     * after an external edit shifts them, the upper bookmark re-anchors down onto the line the lower one
     * still exact-matches. Each bookmark is therefore resolved against the lines still free, and the list
     * is walked in stored-line order so the outcome never depends on the caller's ordering (the persisted
     * list is in the user's chosen order, not line order).
     *
     * @param lineTextAt returns the <em>raw</em> text of a 0-based line; it is stripped here for the
     *                   comparison (matching how {@link #captureLineText} stores it).
     */
    public static NavigableMap<Integer, Bookmark> reanchor(
            List<Bookmark> saved, int paragraphCount, java.util.function.IntFunction<String> lineTextAt, int maxScan) {
        NavigableMap<Integer, Bookmark> out = new TreeMap<>();
        if (saved == null || paragraphCount <= 0) {
            return out;
        }
        int maxLine = paragraphCount - 1;
        List<Bookmark> ordered = new ArrayList<>();
        for (Bookmark bm : saved) {
            if (bm != null && bm.line() >= 0) {
                ordered.add(bm);
            }
        }
        ordered.sort(java.util.Comparator.comparingInt(Bookmark::line));
        for (Bookmark bm : ordered) {
            int stored = Math.min(bm.line(), maxLine);
            int resolved = resolveLine(stored, bm.lineText(), maxLine, lineTextAt, maxScan, out::containsKey);
            if (resolved < 0) {
                continue; // every line is already claimed (fewer lines than bookmarks)
            }
            out.put(resolved, resolved == bm.line() ? bm : bm.withLine(resolved));
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
                return stored; // already correct — the common case
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
        // Content gone (or nothing to match against, e.g. a blank-line bookmark) — keep at the clamped
        // stored line; if an earlier bookmark took it, step aside rather than overwrite it.
        return taken.test(stored) ? nearestFree(stored, maxLine, taken) : stored;
    }

    /** The nearest line to {@code stored} that no earlier bookmark claimed (downward first), else -1. */
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
        String t = lineTextAt.apply(line);
        return t == null ? "" : t.strip();
    }

    private void onTextChange(PlainTextChange change) {
        if (swapping || byLine.isEmpty()) {
            return; // hot-path early-out: nothing to track
        }
        var pos = area.offsetToPosition(change.getPosition(), Bias.Forward);
        int startLine = pos.getMajor();
        boolean atLineStart = pos.getMinor() == 0;
        int removedNL = countNewlines(change.getRemoved());
        int insertedNL = countNewlines(change.getInserted());
        if (removedNL == 0 && insertedNL == 0) {
            return; // intra-line edit: no line moved
        }
        NavigableMap<Integer, Bookmark> shifted = LineMarks.shift(
                byLine,
                KIND,
                startLine,
                atLineStart,
                removedNL,
                insertedNL,
                area.getParagraphs().size(),
                line -> area.getParagraph(line).getText());
        if (!shifted.equals(byLine)) {
            // Both the vacated and the new lines need their gutter markers repainted: the document edit
            // already rebuilds those graphics, but with the pre-shift bookmark set, so the moved marker
            // would otherwise vanish until the next manual refresh.
            java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
            affected.addAll(shifted.keySet());
            byLine = shifted;
            fireChanged();
            onLinesRepaint.accept(affected);
        }
    }

    /**
     * Pure line-shift arithmetic (no toolkit): bookmarks follow their content as lines are inserted, deleted,
     * joined or rewritten. The rules live in {@link LineMarks#shift}, shared with the other line-pinned
     * marks; this overload has no document text, so a rewritten span of a different length only clamps.
     */
    public static NavigableMap<Integer, Bookmark> shift(
            NavigableMap<Integer, Bookmark> current,
            int startLine,
            boolean atLineStart,
            int removedNL,
            int insertedNL,
            int paragraphCount) {
        return LineMarks.shift(current, KIND, startLine, atLineStart, removedNL, insertedNL, paragraphCount, null);
    }

    /**
     * Carries the bookmarks across the narrowing swap: those in the region are rebased onto it, the rest are
     * held aside in whole-document lines until {@link #widen}. Nothing is reported as changed — the
     * bookmarks have not moved in the file, and their region-relative lines must not be persisted.
     */
    @Override
    public void narrow(int start, int end, Runnable swap) {
        int firstLine = area.offsetToPosition(start, Bias.Forward).getMajor();
        int lastLine = area.offsetToPosition(end, Bias.Forward).getMajor();
        java.util.Set<Integer> affected = new java.util.HashSet<>(byLine.keySet());
        NavigableMap<Integer, Bookmark> inside = new TreeMap<>();
        LineMarks.Held<Bookmark> outside = LineMarks.hold(byLine.values(), KIND, firstLine, lastLine, inside);
        runSwap(swap);
        held = outside;
        byLine = inside;
        affected.addAll(byLine.keySet());
        onLinesRepaint.accept(affected);
    }

    /** Puts the held bookmarks back around the region's own, in whole-document lines, and reports the change. */
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
