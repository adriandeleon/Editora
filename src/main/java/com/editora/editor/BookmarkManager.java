package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

import com.editora.config.Bookmark;

/**
 * Tracks the bookmarks of a single {@link CodeArea}, keyed by 0-based line. Analogous to
 * {@link FoldManager}: it owns the per-buffer bookmark state, supplies the gutter a cheap
 * {@code isBookmarked} test, and keeps bookmark lines pinned to their text as the document is edited
 * (lines inserted/deleted above a bookmark shift it; deleting a bookmark's line drops it).
 *
 * <p>The line-shift arithmetic is a pure static method ({@link #shift}) so it can be unit-tested
 * without a JavaFX toolkit.
 */
public final class BookmarkManager {

    /** Bounds the outward line scan when re-anchoring a drifted bookmark (one-time, at file open). */
    private static final int MAX_REANCHOR_SCAN = 2000;

    private final CodeArea area;
    /** line -> bookmark, sorted; at most one bookmark per line. */
    private NavigableMap<Integer, Bookmark> byLine = new TreeMap<>();
    private Runnable onChanged = () -> { };
    /** Notified with the lines to repaint when an <em>edit</em> shifts bookmarks (old ∪ new lines). */
    private java.util.function.Consumer<java.util.Collection<Integer>> onLinesRepaint = c -> { };
    /** Suppresses {@link #onChanged} while we programmatically restore saved bookmarks. */
    private boolean restoring;

    public BookmarkManager(CodeArea area) {
        this.area = area;
        area.plainTextChanges().subscribe(this::onTextChange);
    }

    /** Notified after any change (toggle/note/remove or an edit-driven line shift) for persistence. */
    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> { } : onChanged;
    }

    /**
     * Sets the callback invoked when an <em>edit</em> moves bookmarks to new lines, with the set of
     * lines (the old and new positions) whose gutter markers must be repainted. (Toggle/remove repaint
     * their own line directly; only edit-driven {@link #shift}s need this, since the document edit
     * rebuilds the gutter graphics with a possibly-stale bookmark set.)
     */
    public void setOnLinesRepaint(java.util.function.Consumer<java.util.Collection<Integer>> cb) {
        this.onLinesRepaint = cb == null ? c -> { } : cb;
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
        if (!byLine.isEmpty()) {
            byLine = new TreeMap<>();
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
            byLine = reanchor(saved, area.getParagraphs().size(),
                    line -> area.getParagraph(line).getText(), MAX_REANCHOR_SCAN);
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
     * line. Collisions dedup naturally via the map (last writer wins).
     *
     * @param lineTextAt returns the <em>raw</em> text of a 0-based line; it is stripped here for the
     *                   comparison (matching how {@link #captureLineText} stores it).
     */
    public static NavigableMap<Integer, Bookmark> reanchor(List<Bookmark> saved, int paragraphCount,
            java.util.function.IntFunction<String> lineTextAt, int maxScan) {
        NavigableMap<Integer, Bookmark> out = new TreeMap<>();
        if (saved == null || paragraphCount <= 0) {
            return out;
        }
        int maxLine = paragraphCount - 1;
        for (Bookmark bm : saved) {
            if (bm == null || bm.line() < 0) {
                continue;
            }
            int stored = Math.min(bm.line(), maxLine);
            int resolved = resolveLine(stored, bm.lineText(), maxLine, lineTextAt, maxScan);
            out.put(resolved, resolved == bm.line() ? bm : bm.withLine(resolved));
        }
        return out;
    }

    private static int resolveLine(int stored, String wanted, int maxLine,
            java.util.function.IntFunction<String> lineTextAt, int maxScan) {
        if (wanted == null || wanted.isEmpty()) {
            return stored; // nothing to match against (e.g. a blank-line bookmark)
        }
        if (textAt(lineTextAt, stored).equals(wanted)) {
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
            if (downOk && textAt(lineTextAt, down).equals(wanted)) {
                return down;
            }
            if (upOk && textAt(lineTextAt, up).equals(wanted)) {
                return up;
            }
        }
        return stored; // content gone — keep at the clamped stored line (the user can clear it)
    }

    private static String textAt(java.util.function.IntFunction<String> lineTextAt, int line) {
        String t = lineTextAt.apply(line);
        return t == null ? "" : t.strip();
    }

    private void onTextChange(PlainTextChange change) {
        if (byLine.isEmpty()) {
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
        NavigableMap<Integer, Bookmark> shifted =
                shift(byLine, startLine, atLineStart, removedNL, insertedNL, area.getParagraphs().size());
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
     * Pure line-shift arithmetic (no toolkit), so it is unit-testable. Bookmarks <em>follow their
     * content</em> (forward gravity): an edit at {@code startLine} that adds/removes newlines moves
     * bookmarks below it by the net line delta, and — crucially — a bookmark <em>on</em> the edited
     * line moves too when the edit is at the line's start ({@code atLineStart}), because the whole
     * line's text is pushed down. An edit within a line (not at its start) leaves that line's bookmark
     * put. Bookmarks inside a deleted line span are dropped.
     *
     * <p>This is the standard "sticky marker" behavior: the edit pivots between line {@code pivot} and
     * {@code pivot+1}, where {@code pivot = atLineStart ? startLine-1 : startLine}.
     */
    public static NavigableMap<Integer, Bookmark> shift(NavigableMap<Integer, Bookmark> current,
            int startLine, boolean atLineStart, int removedNL, int insertedNL, int paragraphCount) {
        int delta = insertedNL - removedNL;
        int pivot = atLineStart ? startLine - 1 : startLine;
        int removedEndLine = pivot + removedNL;
        int maxLine = Math.max(0, paragraphCount - 1);
        NavigableMap<Integer, Bookmark> out = new TreeMap<>();
        for (Bookmark bm : current.values()) {
            int line = bm.line();
            if (line <= pivot) {
                out.put(line, bm);
            } else if (line <= removedEndLine) {
                continue; // inside the deleted span: drop the bookmark
            } else {
                int moved = Math.min(Math.max(line + delta, 0), maxLine);
                out.put(moved, bm.withLine(moved));
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
