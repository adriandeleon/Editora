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

    private final CodeArea area;
    /** line -> bookmark, sorted; at most one bookmark per line. */
    private NavigableMap<Integer, Bookmark> byLine = new TreeMap<>();
    private Runnable onChanged = () -> { };
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

    /** Replaces the bookmark state from a persisted list, without firing {@link #onChanged}. */
    public void restore(List<Bookmark> saved) {
        restoring = true;
        try {
            byLine = new TreeMap<>();
            if (saved != null) {
                for (Bookmark bm : saved) {
                    if (bm != null && bm.line() >= 0) {
                        byLine.put(bm.line(), bm);
                    }
                }
            }
        } finally {
            restoring = false;
        }
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
            byLine = shifted;
            fireChanged();
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
