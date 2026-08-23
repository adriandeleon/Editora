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

    /**
     * Total document text retained across all checkpoints, in chars. A count cap alone is not a memory
     * bound when the entries vary in size by three orders of magnitude: {@link #MAX} snapshots of a
     * just-under-{@code UNDO_HISTORY_MAX_BYTES} document is ~50 MB <em>per buffer</em>, and several edited
     * buffers multiply it. Whichever cap binds first wins, so a small file still gets its full 50 steps
     * (the common case) while a large one keeps fewer, deeper-in-time steps instead of a fixed 50.
     *
     * <p>Chars, not bytes, because that is what a snapshot's {@code String} actually costs to hold:
     * ~1 byte/char for the Latin-1 text that dominates source files (compact strings), 2 for the rest —
     * so this is a close approximation from above for code and a factor-of-two one for CJK prose. The
     * same lesson as the Typst page cache (#461), which bounds pages rather than entries.
     */
    static final int MAX_RETAINED_CHARS = 16_000_000;

    private static final int PREVIEW_MAX = 80;

    /** One captured document state. {@code linePreview} is the caret line at capture (for the row label). */
    public record Checkpoint(long seq, long epochMillis, String linePreview, String text, int caret) {}

    private final ArrayDeque<Checkpoint> entries = new ArrayDeque<>(); // oldest first, newest last
    private long seq = 0;
    private long retainedChars = 0; // sum of entries' text lengths, kept in step with the deque

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
        retainedChars += text.length();
        // Evict oldest-first until BOTH caps hold. The newest checkpoint is never evicted, even when it
        // alone exceeds the char budget: it is the state the user just left, and dropping it would make
        // a large file silently have no history at all rather than a short one.
        while (entries.size() > MAX || (retainedChars > MAX_RETAINED_CHARS && entries.size() > 1)) {
            retainedChars -= entries.removeFirst().text().length();
        }
        return true;
    }

    /** Total document text currently retained across all checkpoints, in chars (for tests/diagnostics). */
    long retainedChars() {
        return retainedChars;
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
        retainedChars = 0;
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
