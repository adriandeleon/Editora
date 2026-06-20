package com.editora.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A session-only, in-memory timeline of document checkpoints for the Undo History tool window. A
 * checkpoint is the whole document text at an undo-group boundary (captured when editing settles, on the
 * same {@link UndoMerge#PAUSE} cadence as undo coalescing), so the user can jump back to any recent state
 * with a single (undoable) restore — independent of, and finer-grained than, save-based Local History.
 *
 * <p>Bounded to {@link #MAX} entries and not used for huge files (the caller guards on size); the
 * mutation/eviction/labeling logic here is pure and unit-tested.
 */
public final class UndoHistory {

    /** Max checkpoints kept (oldest evicted); bounds memory since each holds a full document snapshot. */
    public static final int MAX = 50;

    private static final int PREVIEW_MAX = 80;

    /** One captured document state. {@code linePreview} is the caret line at capture (for the row label). */
    public record Checkpoint(long seq, long epochMillis, String linePreview, String text, int caret) {}

    private final ArrayDeque<Checkpoint> entries = new ArrayDeque<>(); // oldest first, newest last
    private long seq = 0;

    /**
     * Records the current document state, unless it equals the most recent checkpoint. Returns true when a
     * checkpoint was actually added (so the caller can refresh the panel).
     */
    public boolean add(String text, int caret, long epochMillis) {
        if (text == null) {
            return false;
        }
        Checkpoint last = entries.peekLast();
        if (last != null && last.text().equals(text)) {
            return false; // editing settled but the text is unchanged (e.g. type-then-delete)
        }
        entries.addLast(new Checkpoint(++seq, epochMillis, lineAt(text, caret), text, clamp(caret, text.length())));
        while (entries.size() > MAX) {
            entries.removeFirst();
        }
        return true;
    }

    /** Checkpoints newest-first (the order the panel lists them). */
    public List<Checkpoint> entriesNewestFirst() {
        List<Checkpoint> out = new ArrayList<>(entries);
        java.util.Collections.reverse(out);
        return out;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    /** Clamps {@code v} into {@code [0, len]} (pure). */
    public static int clamp(int v, int len) {
        return v < 0 ? 0 : Math.min(v, len);
    }

    /** The line containing {@code caret}, stripped and capped, for a row label (pure). */
    static String lineAt(String text, int caret) {
        int c = clamp(caret, text.length());
        int start = text.lastIndexOf('\n', c - 1) + 1; // 0 if none
        int end = text.indexOf('\n', c);
        if (end < 0) {
            end = text.length();
        }
        String line = text.substring(start, end).strip();
        if (line.isEmpty()) {
            return "";
        }
        return line.length() > PREVIEW_MAX ? line.substring(0, PREVIEW_MAX) + "…" : line;
    }
}
