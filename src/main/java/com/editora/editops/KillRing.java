package com.editora.editops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Emacs kill ring: a bounded, newest-first list of killed text plus a yank pointer.
 *
 * <p>Pure and toolkit-free (mirroring {@link EmacsEdits} / {@link Transposer}); the controller owns
 * the sequencing decisions — whether a kill continues the previous one, and whether a yank-pop is
 * currently legal — and passes them in, so this class has no notion of "the last command".
 *
 * <p>Semantics follow GNU Emacs:
 *
 * <ul>
 *   <li>A kill pushes a new entry, or — when {@code merge} is set, i.e. the previous command was also
 *       a kill — accumulates into the newest entry: a {@link Direction#FORWARD} kill appends
 *       ({@code C-k C-k} yields both lines in order), a {@link Direction#BACKWARD} kill prepends
 *       ({@code M-DEL M-DEL} yields both words in reading order).
 *   <li>Any kill or save resets the yank pointer to the newest entry, so {@code C-y} yanks the most
 *       recent kill.
 *   <li>{@link #rotate()} ({@code M-y}, yank-pop) steps the pointer towards older entries, wrapping.
 *   <li>The ring holds at most {@link #DEFAULT_MAX} entries; the oldest are dropped.
 * </ul>
 */
public final class KillRing {

    /** Emacs {@code kill-ring-max}. */
    public static final int DEFAULT_MAX = 120;

    /** Which side of the existing head an accumulating kill joins onto. */
    public enum Direction {
        /** Killed text that lay after the caret ({@code C-k}, {@code M-d}): append. */
        FORWARD,
        /** Killed text that lay before the caret ({@code M-DEL}): prepend. */
        BACKWARD
    }

    private final int max;
    private final List<String> entries = new ArrayList<>(); // index 0 = newest
    private int yankIndex;

    public KillRing() {
        this(DEFAULT_MAX);
    }

    public KillRing(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        this.max = max;
    }

    /**
     * Records killed text. When {@code merge} is set the text accumulates into the newest entry on the
     * side given by {@code direction} (Emacs' consecutive-kill behaviour) instead of pushing a new one.
     * Empty text is ignored. The yank pointer is reset to the newest entry.
     */
    public void kill(String text, Direction direction, boolean merge) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (merge && !entries.isEmpty()) {
            String head = entries.get(0);
            entries.set(0, direction == Direction.BACKWARD ? text + head : head + text);
        } else {
            push(text);
        }
        yankIndex = 0;
    }

    /**
     * Records text that was copied rather than killed ({@code M-w}, {@code kill-ring-save}) — always a
     * new entry, never an accumulation. The yank pointer is reset to the newest entry.
     */
    public void save(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        push(text);
        yankIndex = 0;
    }

    /**
     * Adopts text that arrived from outside the editor, so that copying in another application and then
     * yanking does the expected thing (Emacs' {@code interprogram-paste-function}). No-op when the text
     * is empty or already the current entry. Returns whether it was adopted.
     */
    public boolean adoptExternal(String text) {
        if (text == null || text.isEmpty() || text.equals(current())) {
            return false;
        }
        push(text);
        yankIndex = 0;
        return true;
    }

    /** The entry the next yank would insert, or {@code null} when the ring is empty. */
    public String current() {
        return entries.isEmpty() ? null : entries.get(yankIndex);
    }

    /**
     * Emacs {@code yank-pop}: steps the yank pointer one entry towards older kills, wrapping around to
     * the newest, and returns the entry now under it ({@code null} when the ring is empty).
     */
    public String rotate() {
        if (entries.isEmpty()) {
            return null;
        }
        yankIndex = (yankIndex + 1) % entries.size();
        return current();
    }

    /** Resets the yank pointer to the newest entry. */
    public void resetYank() {
        yankIndex = 0;
    }

    /** 0-based position of the yank pointer, newest first. */
    public int yankIndex() {
        return yankIndex;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The ring contents, newest first (unmodifiable) — for a picker over past kills. */
    public List<String> entries() {
        return Collections.unmodifiableList(entries);
    }

    public void clear() {
        entries.clear();
        yankIndex = 0;
    }

    private void push(String text) {
        entries.add(0, text);
        while (entries.size() > max) {
            entries.remove(entries.size() - 1);
        }
    }
}
