package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Pure line arithmetic shared by the gutter marks that pin themselves to a line — bookmarks
 * ({@link BookmarkManager}) and breakpoints ({@link BreakpointManager}). Both used to carry their own copy
 * of {@link #shift}; one helper keeps "what happens to a mark when its line is edited" a single decision.
 * No toolkit, so it is unit-tested directly.
 */
final class LineMarks {

    /** Bounds the outward scan when a displaced mark looks for its line text in a rewritten span. */
    static final int MAX_SCAN = 2000;

    private LineMarks() {}

    /** The three things the arithmetic needs from a mark type. */
    interface Kind<T> {
        int line(T mark);

        /** The stripped text of the line the mark was set on ("" when unknown). */
        String lineText(T mark);

        T withLine(T mark, int line);

        /** The mark with a fresh line-text snapshot (its line was edited in place). */
        default T withLineText(T mark, String lineText) {
            return mark;
        }
    }

    /**
     * One text change, in the terms {@link #shift} needs.
     *
     * @param insertedEndsWithNewline the inserted text ends in a newline, so (for an edit at a line start) the
     *     line after the inserted ones is an untouched line, not part of the rewritten span
     * @param removedEndsWithNewline the removed text ends in a newline (whole lines were removed); when it does
     *     not, a rewrite at a line start also replaced the text of the line its removal ended on
     * @param lastLineRemoved the last line the removal reached lost <em>all</em> of its text (a selection
     *     ending at a line's end), so nothing of it survives to be joined onto the edit line
     */
    record Edit(
            int startLine,
            boolean atLineStart,
            int removedNL,
            int insertedNL,
            boolean insertedEndsWithNewline,
            boolean removedEndsWithNewline,
            boolean lastLineRemoved) {

        /**
         * Describes replacing {@code removed} by {@code inserted} at {@code (startLine, startCol)}.
         * {@code lineTextAt} reads a line of the document <em>after</em> the edit.
         */
        static Edit of(String removed, String inserted, int startLine, int startCol, IntFunction<String> lineTextAt) {
            int removedNL = countNewlines(removed);
            int insertedNL = countNewlines(inserted);
            boolean lastLineRemoved = false;
            int lastRemovedNL = removed.lastIndexOf('\n');
            if (startCol > 0
                    && lastRemovedNL >= 0
                    && !removed.substring(lastRemovedNL + 1).isBlank()) {
                // Text of the last removed line was deleted; did any of that line survive after the edit?
                int endCol = insertedNL == 0
                        ? startCol + inserted.length()
                        : inserted.length() - inserted.lastIndexOf('\n') - 1;
                String endLine = lineTextAt == null ? null : lineTextAt.apply(startLine + insertedNL);
                lastLineRemoved = endLine != null
                        && endLine.substring(Math.min(endCol, endLine.length())).isBlank();
            }
            return new Edit(
                    startLine,
                    startCol == 0,
                    removedNL,
                    insertedNL,
                    inserted.endsWith("\n"),
                    removed.endsWith("\n"),
                    lastLineRemoved);
        }
    }

    static int countNewlines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** A line's text as a mark stores it: stripped, and cut to the stored snapshot length. */
    static String snapshotText(String raw) {
        String t = raw == null ? "" : raw.strip();
        return t.length() > com.editora.config.Bookmark.MAX_LINE_TEXT
                ? t.substring(0, com.editora.config.Bookmark.MAX_LINE_TEXT)
                : t;
    }

    /**
     * Something whose positions must be carried across the two whole-text swaps of narrowing. The swap is
     * not an edit of the document — the same text is still there, part of it merely held aside — so it must
     * not be tracked like one: marks inside the region are rebased, marks outside it are held until widen.
     */
    interface Carrier {
        /** Runs {@code swap}, which replaces the document with its {@code [start, end)} region. */
        void narrow(int start, int end, Runnable swap);

        /** Runs {@code swap}, which puts the whole document back around the (possibly edited) region. */
        void widen(Runnable swap);
    }

    /** Runs the narrowing {@code swap} with every carrier following it. */
    static void narrow(int start, int end, Runnable swap, Carrier... carriers) {
        Runnable chain = swap;
        for (Carrier carrier : carriers) {
            Runnable inner = chain;
            chain = () -> carrier.narrow(start, end, inner);
        }
        chain.run();
    }

    /** Runs the widening {@code swap} with every carrier following it. */
    static void widen(Runnable swap, Carrier... carriers) {
        Runnable chain = swap;
        for (Carrier carrier : carriers) {
            Runnable inner = chain;
            chain = () -> carrier.widen(inner);
        }
        chain.run();
    }

    /**
     * Line-shift arithmetic. Marks <em>follow their content</em> (forward gravity): an edit at
     * {@code startLine} that adds/removes newlines moves marks below it by the net line delta, and a mark
     * <em>on</em> the edited line moves too when the edit is at the line's start ({@code atLineStart}),
     * because the whole line's text is pushed down. An edit within a line (not at its start) leaves that
     * line's mark put. The edit pivots between line {@code pivot} and {@code pivot+1}, where
     * {@code pivot = atLineStart ? startLine-1 : startLine}.
     *
     * <p>What happens to the marks <em>inside</em> the removed line span depends on whether the edit was a
     * deletion or a rewrite:
     *
     * <ul>
     *   <li><b>Deletion</b> (no newline inserted): the lines are gone and so are their marks.</li>
     *   <li><b>Rewrite</b> (newlines removed <em>and</em> inserted — Replace All over several lines, a
     *       formatter's ranged edit, a history restore or diff apply replacing the whole document): the
     *       lines were replaced, not deleted, so the marks survive. Each follows its stored line text to the
     *       nearest line of the new span that still has it ({@code lineTextAt}, when given) — which is what
     *       keeps a mark on its statement through Move Line Up/Down, a one-change swap of two lines. What
     *       cannot be matched stays line for line when the line count is unchanged, and is otherwise clamped
     *       to the nearest free line of the span. The span is the rewritten lines only: never the untouched
     *       line that follows an edit ending in a newline. A mark is dropped only when the span has fewer
     *       lines than marks.</li>
     * </ul>
     *
     * @param lineTextAt the <em>raw</em> text of a 0-based line of the document <em>after</em> the edit, or
     *                   {@code null} to skip text matching and only clamp
     */
    static <T> NavigableMap<Integer, T> shift(
            NavigableMap<Integer, T> current,
            Kind<T> kind,
            int startLine,
            boolean atLineStart,
            int removedNL,
            int insertedNL,
            int paragraphCount,
            IntFunction<String> lineTextAt) {
        return shift(
                current,
                kind,
                new Edit(startLine, atLineStart, removedNL, insertedNL, false, true, false),
                paragraphCount,
                lineTextAt);
    }

    /** {@link #shift(NavigableMap, Kind, int, boolean, int, int, int, IntFunction)} for a fully described edit. */
    static <T> NavigableMap<Integer, T> shift(
            NavigableMap<Integer, T> current,
            Kind<T> kind,
            Edit edit,
            int paragraphCount,
            IntFunction<String> lineTextAt) {
        int startLine = edit.startLine();
        boolean atLineStart = edit.atLineStart();
        int removedNL = edit.removedNL();
        int insertedNL = edit.insertedNL();
        int delta = insertedNL - removedNL;
        int pivot = atLineStart ? startLine - 1 : startLine;
        int removedEndLine = pivot + removedNL;
        // A mid-line deletion (not at column 0) joins its last touched line onto the edit — that line's
        // trailing content survives (a line-join: Backspace at column 0, or Delete at a line's end). A mark on
        // that join line must follow its content to pivot+insertedNL rather than be dropped — otherwise you
        // set a breakpoint, join the line above, and silently debug without it. (An at-line-start deletion
        // genuinely removes that line's content; its successor is the survivor and is handled by the shift
        // branch, so this only applies when !atLineStart.) When the removal took ALL of that last line's text
        // (a selection from the end of one line to the end of a later one), nothing was joined: the line is
        // as deleted as the ones before it, and its mark must not reappear on the line above.
        boolean joinSurvives = !atLineStart && removedNL > 0 && !edit.lastLineRemoved();
        boolean rewritten = removedNL > 0 && insertedNL > 0;
        // A rewrite from a line start that stops inside a later line (Move Line Up/Down swaps two lines this
        // way) replaced that last line's text too, so its mark is one of the rewritten ones — not a mark
        // "below the edit" that merely shifts.
        int rewrittenTail = rewritten && atLineStart && !edit.removedEndsWithNewline() ? removedEndLine + 1 : -1;
        int maxLine = Math.max(0, paragraphCount - 1);
        NavigableMap<Integer, T> out = new TreeMap<>();
        List<T> displaced = null;
        for (T mark : current.values()) {
            int line = kind.line(mark);
            if (line <= pivot) {
                out.put(line, mark);
            } else if (line == removedEndLine && joinSurvives) {
                put(out, kind, mark, clamp(pivot + insertedNL, 0, maxLine));
            } else if (line > removedEndLine && line != rewrittenTail) {
                put(out, kind, mark, clamp(line + delta, 0, maxLine));
            } else if (!rewritten) {
                continue; // inside a deleted span: the line is gone, and the mark with it
            } else {
                if (displaced == null) {
                    displaced = new ArrayList<>();
                }
                // The tail line sits below the removed newlines, so its position in the new span is shifted.
                displaced.add(line == rewrittenTail && delta != 0 ? kind.withLine(mark, line + delta) : mark);
            }
        }
        if (displaced != null) {
            int first = Math.min(pivot + 1, maxLine);
            // An edit at a line start whose text ends in a newline inserts whole lines: the line after them
            // is the untouched one that followed the removed block, and no mark may be planted on it.
            int lastInserted = startLine + insertedNL - (atLineStart && edit.insertedEndsWithNewline() ? 1 : 0);
            int last = clamp(lastInserted, first, maxLine);
            placeDisplaced(out, kind, displaced, first, last, lineTextAt, delta == 0);
        }
        return out;
    }

    /**
     * Puts the marks of a rewritten span back onto its new lines {@code [first, last]}: text matches first
     * (so a clamped mark never takes the line another mark's content actually moved to), then the rest by
     * position. Lines already holding a mark are never reused.
     */
    private static <T> void placeDisplaced(
            NavigableMap<Integer, T> out,
            Kind<T> kind,
            List<T> displaced,
            int first,
            int last,
            IntFunction<String> lineTextAt,
            boolean sameLineCount) {
        List<T> unmatched = new ArrayList<>();
        if (sameLineCount) {
            // Same number of lines: a mark whose line still reads the same (or that has no text to go by)
            // has not moved. Settle those first so a moved line never takes a line that is rightfully kept.
            List<T> moved = new ArrayList<>();
            for (T mark : displaced) {
                int line = kind.line(mark);
                String text = kind.lineText(mark);
                if (lineTextAt == null
                        || text == null
                        || text.isEmpty()
                        || text.equals(snapshotText(lineTextAt.apply(line)))) {
                    out.put(line, mark);
                } else {
                    moved.add(mark);
                }
            }
            displaced = moved;
        }
        for (T mark : displaced) {
            int wanted = clamp(kind.line(mark), first, last);
            int found = lineTextAt == null ? -1 : findText(out, kind.lineText(mark), wanted, first, last, lineTextAt);
            if (found >= 0) {
                put(out, kind, mark, found);
            } else {
                unmatched.add(mark);
            }
        }
        for (T mark : unmatched) {
            int line = kind.line(mark);
            // With an unchanged line count an unmatched mark keeps its line (the text was edited, not moved).
            int free = sameLineCount && !out.containsKey(line)
                    ? line
                    : nearestFree(out, clamp(line, first, last), first, last);
            if (free >= 0) {
                put(out, kind, mark, free);
            }
        }
    }

    /** The free line nearest {@code wanted} in {@code [first, last]} whose stripped text is {@code text}, else -1. */
    private static <T> int findText(
            NavigableMap<Integer, T> taken,
            String text,
            int wanted,
            int first,
            int last,
            IntFunction<String> lineTextAt) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        for (int r = 0; r <= MAX_SCAN; r++) {
            int down = wanted + r;
            int up = wanted - r;
            boolean downOk = down <= last;
            boolean upOk = up >= first;
            if (!downOk && !upOk) {
                break;
            }
            if (downOk && !taken.containsKey(down) && text.equals(snapshotText(lineTextAt.apply(down)))) {
                return down;
            }
            if (upOk && r > 0 && !taken.containsKey(up) && text.equals(snapshotText(lineTextAt.apply(up)))) {
                return up;
            }
        }
        return -1;
    }

    /** The line nearest {@code wanted} in {@code [first, last]} that holds no mark (downward first), else -1. */
    private static <T> int nearestFree(NavigableMap<Integer, T> taken, int wanted, int first, int last) {
        for (int r = 0; r <= last - first; r++) {
            int down = wanted + r;
            int up = wanted - r;
            if (down <= last && !taken.containsKey(down)) {
                return down;
            }
            if (up >= first && !taken.containsKey(up)) {
                return up;
            }
        }
        return -1;
    }

    /**
     * The marks outside a narrowed region, in whole-document lines, with the region's extent at the moment
     * it was cut out — everything {@link #release} needs to put them back around the edited region.
     */
    record Held<T>(List<T> before, List<T> after, int firstLine, int regionLines) {

        /** This hold with no marks in it (the user cleared them while narrowed). */
        Held<T> emptied() {
            return new Held<>(List.of(), List.of(), firstLine, regionLines);
        }
    }

    /**
     * Splits {@code all} around the region {@code [firstLine, lastLine]}: marks inside go into
     * {@code inside} rebased to region-relative lines, marks outside are returned held aside.
     */
    static <T> Held<T> hold(
            Collection<T> all, Kind<T> kind, int firstLine, int lastLine, NavigableMap<Integer, T> inside) {
        List<T> before = new ArrayList<>();
        List<T> after = new ArrayList<>();
        for (T mark : all) {
            int line = kind.line(mark);
            if (line < firstLine) {
                before.add(mark);
            } else if (line > lastLine) {
                after.add(mark);
            } else {
                put(inside, kind, mark, line - firstLine);
            }
        }
        return new Held<>(before, after, firstLine, lastLine - firstLine + 1);
    }

    /**
     * The whole-document marks after widening: the held marks before the region as they were, the region's
     * current marks ({@code inside}, region-relative) moved back down by the region's first line, and the
     * held marks after it shifted by however many lines the region gained or lost while narrowed.
     */
    static <T> NavigableMap<Integer, T> release(Held<T> held, Collection<T> inside, Kind<T> kind, int regionLinesNow) {
        NavigableMap<Integer, T> out = new TreeMap<>();
        for (T mark : held.before()) {
            out.put(kind.line(mark), mark);
        }
        for (T mark : inside) {
            put(out, kind, mark, kind.line(mark) + held.firstLine());
        }
        int delta = regionLinesNow - held.regionLines();
        for (T mark : held.after()) {
            put(out, kind, mark, kind.line(mark) + delta);
        }
        return out;
    }

    private static <T> void put(NavigableMap<Integer, T> out, Kind<T> kind, T mark, int line) {
        out.put(line, line == kind.line(mark) ? mark : kind.withLine(mark, line));
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
