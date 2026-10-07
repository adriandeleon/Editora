package com.editora.editor;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * One buffer's Git gutter data — change bars, their hunk tooltips and the blame column — looked up by
 * <em>buffer</em> line.
 *
 * <p>Git reports all three by line of the file on disk. {@link DiskLineMap} carries them through unsaved
 * edits, so an inserted or deleted line shifts everything below it instead of leaving each annotation on its
 * old line number. While the buffer is narrowed the area shows a region of the document; lookups add the
 * lines hidden above it.
 */
public final class GitGutterLines implements LineMarks.Carrier {

    private static final int[] NO_MARKS = new int[0];

    /** A batch with more structural replacements than this is not followed one by one (settling is quadratic). */
    private static final int MAX_FOLLOWED_BATCH = 256;

    private final CodeArea area;
    private final DiskLineMap map = new DiskLineMap();
    /** Disk line → CSS class; {@code null} when the buffer is not under change tracking. */
    private Map<Integer, String> bars;
    /** Disk line → hunk text for the bar's tooltip; may be {@code null}. */
    private Map<Integer, String> tooltips;
    /** The changes behind the bars, in file order (empty when unknown): what the hunk commands act on. */
    private List<GitHunk> hunks = List.of();
    /** {@link GitHunk#line()} of each hunk, for a search without boxing. */
    private int[] hunkStarts = NO_MARKS;
    /** Hunks put back by Revert Hunk: their bars are gone until the buffer and the disk agree again. */
    private final Set<GitHunk> reverted = new HashSet<>();
    /** {@link #marks()}, or {@code null} when it has to be recomputed. */
    private int[] marks;
    /** A change bar was clicked (area line). */
    private IntConsumer onBarClick = line -> {};
    /** The overview column that draws {@link #marks()}. */
    private final Minimap minimap;
    /** Per disk line; {@code null} = blame off. */
    private List<BlameInfo> blame;
    /** Document lines hidden above a narrowed region (0 when widened). */
    private int hiddenAbove;
    /** True while narrowing/widening swaps the area's text: that replacement is not an edit. */
    private boolean swapping;

    private final ReadOnlyBooleanProperty dirty;
    private final Runnable repaintGutter;
    /** The buffer became clean and the map has not been reset for it yet (see {@link #cleaned}). */
    private boolean cleanPending;

    /**
     * @param dirty the buffer's unsaved-changes flag: whenever it clears, buffer and disk lines agree again
     * @param repaintGutter rebuilds every visible gutter graphic (after the whole mapping changed)
     */
    GitGutterLines(CodeArea area, ReadOnlyBooleanProperty dirty, Runnable repaintGutter, Minimap minimap) {
        this.area = area;
        this.dirty = dirty;
        this.repaintGutter = repaintGutter;
        this.minimap = minimap;
        minimap.setGitMarks(this::marks);
        dirty.addListener((o, was, now) -> {
            if (!now) {
                cleaned();
            }
        });
    }

    /**
     * The buffer is clean. When that came from an edit (an undo back to the saved text) this may run before
     * or after {@link #edited} is told about the same edit, so the reset waits until the event is over; an
     * edit that arrives first applies it itself.
     */
    private void cleaned() {
        cleanPending = true;
        Platform.runLater(() -> {
            cleanPending = false;
            if (!dirty.get()) {
                reset();
            }
        });
    }

    /** Wires the change-bar column of {@code folds} to this data. */
    void attach(FoldManager folds) {
        folds.setChangeHook(this::barsTracked, this::barAt, this::tooltipAt, line -> onBarClick.accept(line));
    }

    boolean barsTracked() {
        return bars != null;
    }

    String barAt(int line) {
        if (bars == null) {
            return null;
        }
        int disk = map.diskLine(line + hiddenAbove);
        return !reverted.isEmpty() && reverted.contains(hunkAtDisk(disk)) ? null : bars.get(disk);
    }

    String tooltipAt(int line) {
        return tooltips == null || barAt(line) == null ? null : tooltips.get(map.diskLine(line + hiddenAbove));
    }

    /** Runs {@code handler} with the area line of a clicked change bar. */
    public void setOnBarClick(IntConsumer handler) {
        onBarClick = handler == null ? line -> {} : handler;
    }

    /**
     * Sets the changes behind the bars just given to {@code setChangeBars} (same diff, same on-disk lines).
     * Without them the bars still paint, but there is nothing to navigate, peek, revert or stage.
     */
    public void setHunks(List<GitHunk> list) {
        hunks = bars == null || list == null ? List.of() : List.copyOf(list);
        hunkStarts = new int[hunks.size()];
        for (int i = 0; i < hunkStarts.length; i++) {
            hunkStarts[i] = hunks.get(i).line();
        }
        marksChanged();
    }

    /** Whether there is any change to act on. */
    public boolean hasHunks() {
        return !hunks.isEmpty();
    }

    /**
     * Whether the buffer has been rewritten so thoroughly since it last matched the disk (a formatter run, a
     * reload under unsaved edits) that its lines can no longer be related to the file's.
     */
    public boolean lost() {
        return map.lost();
    }

    private GitHunk hunkAtDisk(int disk) {
        if (disk < 0 || hunkStarts.length == 0) {
            return null;
        }
        int at = java.util.Arrays.binarySearch(hunkStarts, disk);
        int index = at >= 0 ? at : -at - 2; // the last hunk starting at or above this line
        if (index < 0) {
            return null;
        }
        GitHunk hunk = hunks.get(index);
        return disk < hunk.line() + hunk.markerCount() ? hunk : null;
    }

    /** The change marked on area line {@code line}, or {@code null}. */
    public GitHunk hunkAt(int line) {
        GitHunk hunk = hunkAtDisk(map.diskLine(line + hiddenAbove));
        return hunk == null || reverted.contains(hunk) ? null : hunk;
    }

    /** Where a hunk sits in the buffer: {@code count} lines from area line {@code line} (0 = a deletion). */
    public record Span(int line, int count) {}

    /**
     * The buffer lines {@code hunk} covers now, or {@code null} when they cannot be named exactly: one of
     * its lines was removed or rewritten by an unsaved edit, lines were inserted inside it, or the buffer is
     * narrowed. A caller that edits by this answer must refuse on {@code null} rather than guess.
     */
    public Span locate(GitHunk hunk) {
        if (hunk == null || map.lost() || hiddenAbove != 0) {
            return null;
        }
        int total = area.getParagraphs().size();
        if (hunk.count() == 0) {
            int at = map.bufferLine(hunk.line());
            if (at < 0 || at > total) {
                int above = hunk.line() > 0 ? map.bufferLine(hunk.line() - 1) : DiskLineMap.UNKNOWN;
                at = above < 0 ? DiskLineMap.UNKNOWN : above + 1;
            }
            return at < 0 || at > total ? null : new Span(at, 0);
        }
        int first = map.bufferLine(hunk.line());
        if (first < 0 || first + hunk.count() > total) {
            return null;
        }
        for (int i = 1; i < hunk.count(); i++) {
            if (map.diskLine(first + i) != hunk.line() + i) {
                return null;
            }
        }
        return new Span(first, hunk.count());
    }

    /** Revert Hunk put {@code hunk}'s old lines back: its bar is not shown again until the buffer is clean. */
    public void reverted(GitHunk hunk) {
        reverted.add(hunk);
        marksChanged();
        repaintIfShown(); // the marker below a deletion is not on a line the edit replaced
    }

    /**
     * The marked runs of the buffer as flat triples {@code {line, count, kind ordinal}}, top to bottom —
     * what the minimap draws and change navigation steps through. Follows unsaved edits like the bars do.
     * Computed on demand and kept until the bars or the line mapping change; the caller must not modify it.
     */
    public int[] marks() {
        if (marks == null) {
            marks = computeMarks();
        }
        return marks;
    }

    private int[] computeMarks() {
        int total = area.getParagraphs().size();
        if (hunks.isEmpty() || map.lost() || hiddenAbove != 0 || total == 0) {
            return NO_MARKS;
        }
        int[] out = new int[hunks.size() * 3];
        int n = 0;
        if (map.identity()) {
            for (GitHunk hunk : hunks) {
                if (hunk.line() < total && !reverted.contains(hunk)) {
                    out[n++] = hunk.line();
                    out[n++] = Math.min(hunk.markerCount(), total - hunk.line());
                    out[n++] = hunk.kind().ordinal();
                }
            }
            return n == out.length ? out : java.util.Arrays.copyOf(out, n);
        }
        GitHunk run = null;
        for (int line = 0; line <= total; line++) {
            GitHunk hunk = line < total ? hunkAtDisk(map.diskLine(line)) : null;
            if (hunk != null && reverted.contains(hunk)) {
                hunk = null;
            }
            if (hunk == run) {
                if (run != null) {
                    out[n - 2]++;
                }
                continue;
            }
            run = hunk;
            if (hunk != null) {
                if (n + 3 > out.length) {
                    out = java.util.Arrays.copyOf(out, Math.max(12, out.length * 2)); // a hunk split by an edit
                }
                out[n++] = line;
                out[n++] = 1;
                out[n++] = hunk.kind().ordinal();
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private void marksChanged() {
        marks = null;
        minimap.gitMarksChanged();
    }

    List<BlameInfo> blame() {
        return blame;
    }

    BlameInfo blameAt(int line) {
        int disk = map.diskLine(line + hiddenAbove);
        return blame != null && disk >= 0 && disk < blame.size() ? blame.get(disk) : null;
    }

    /** Sets the blame column; returns false when it is what was already showing (no gutter rebuild then). */
    boolean setBlame(List<BlameInfo> lines) {
        List<BlameInfo> next = lines == null || lines.isEmpty() ? null : List.copyOf(lines);
        if (Objects.equals(next, blame)) {
            return false;
        }
        blame = next;
        return true;
    }

    /**
     * Sets the change bars. Returns {@code null} when tracking was switched on or off (the gutter's reserved
     * slot changed: rebuild it), otherwise the buffer lines whose bar may have changed.
     */
    Set<Integer> setBars(Map<Integer, String> lineClasses, Map<Integer, String> hunkText) {
        boolean wasTracked = bars != null;
        Set<Integer> disk = new HashSet<>();
        if (wasTracked) {
            disk.addAll(bars.keySet());
        }
        bars = lineClasses;
        tooltips = lineClasses == null ? null : hunkText;
        setHunks(List.of()); // these belonged to the previous diff; the caller hands the new ones over next
        if (wasTracked != (bars != null)) {
            return null;
        }
        if (bars != null) {
            disk.addAll(bars.keySet());
        }
        if (map.identity() && hiddenAbove == 0) {
            return disk;
        }
        Set<Integer> lines = new HashSet<>();
        for (int line = 0, n = area.getParagraphs().size(); line < n; line++) {
            if (disk.contains(map.diskLine(line + hiddenAbove))) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** The buffer matches the disk again (loaded, saved, or edited back to the saved text). */
    void reset() {
        if (!map.identity() || !reverted.isEmpty()) {
            map.reset();
            reverted.clear();
            marksChanged();
            repaintIfShown();
        }
    }

    private void repaintIfShown() {
        if (bars != null || blame != null) {
            repaintGutter.run();
        }
    }

    /**
     * A save was acknowledged while newer edits are pending: the disk now holds a text this map was not
     * following from, so unless nothing had moved the relation is unknown until the buffer is clean again.
     */
    void savedWithPendingEdits() {
        if (dirty.get() && !map.identity() && !map.lost()) {
            map.lose();
            marksChanged();
            repaintIfShown();
        }
    }

    /**
     * Follows a batch of settled text changes, then repaints the gutter of the visible lines from the first
     * moved one down (their graphics were rebuilt by the edit itself, with the map as it was before it).
     *
     * @param split the split view's second area, or {@code null}
     */
    void edited(List<PlainTextChange> changes, IntConsumer repaintLine, CodeArea split) {
        if (swapping) {
            return;
        }
        if (cleanPending) {
            cleanPending = false;
            map.reset(); // saved, then edited within the same event: this edit starts from the disk's lines
            reverted.clear();
            marksChanged();
        }
        if (map.lost()) {
            return;
        }
        int structural = 0;
        for (PlainTextChange c : changes) {
            if (c.getInserted().indexOf('\n') >= 0 || c.getRemoved().indexOf('\n') >= 0) {
                structural++;
            }
        }
        if (structural == 0) {
            return; // typing inside a line: the per-keystroke path ends here
        }
        int firstMoved = follow(changes, structural);
        if (firstMoved >= 0) {
            marksChanged();
        }
        if (firstMoved >= 0 && (bars != null || blame != null)) {
            repaintFrom(firstMoved, area, repaintLine);
            if (split != null) {
                repaintFrom(firstMoved, split, repaintLine);
            }
        }
    }

    /** Applies the batch to the map; returns the first area line whose mapping moved, or -1. */
    private int follow(List<PlainTextChange> changes, int structural) {
        int n = changes.size();
        if (structural > MAX_FOLLOWED_BATCH) {
            map.lose();
            return 0;
        }
        int[] positions = new int[n];
        int[] lengthDelta = new int[n];
        int[] lineDelta = new int[n];
        for (int i = 0; n > 1 && i < n; i++) {
            PlainTextChange c = changes.get(i);
            positions[i] = c.getPosition();
            lengthDelta[i] = c.getInserted().length() - c.getRemoved().length();
            lineDelta[i] = LineMarks.countNewlines(c.getInserted()) - LineMarks.countNewlines(c.getRemoved());
        }
        int firstMoved = -1;
        for (int i = 0; i < n; i++) {
            PlainTextChange c = changes.get(i);
            if (c.getInserted().indexOf('\n') < 0 && c.getRemoved().indexOf('\n') < 0) {
                continue;
            }
            // Each replacement reports the position it had when it ran; read it from the final document.
            LineMarkTracker.Settled at = n == 1
                    ? new LineMarkTracker.Settled(c.getPosition(), 0, 0)
                    : LineMarkTracker.settle(i, positions, lengthDelta, lineDelta);
            var pos = area.offsetToPosition(Math.min(at.offset(), area.getLength()), Bias.Forward);
            int lineThen = pos.getMajor() - at.linesAddedAbove();
            if (map.edit(lineThen + hiddenAbove, pos.getMinor() == 0, c.getRemoved(), c.getInserted())) {
                firstMoved = firstMoved < 0 ? Math.max(0, lineThen) : Math.min(firstMoved, Math.max(0, lineThen));
            }
        }
        return firstMoved;
    }

    private static void repaintFrom(int line, CodeArea view, IntConsumer repaintLine) {
        int from = line;
        int to;
        try {
            from = Math.max(line, view.firstVisibleParToAllParIndex());
            to = view.lastVisibleParToAllParIndex();
        } catch (RuntimeException notLaidOut) {
            return; // nothing is on screen yet; the first layout builds every graphic from the current map
        }
        for (int i = from; i <= to; i++) {
            repaintLine.accept(i);
        }
    }

    @Override
    public void narrow(int start, int end, Runnable swap) {
        int above = area.offsetToPosition(Math.clamp(start, 0, area.getLength()), Bias.Forward)
                .getMajor();
        swapWith(swap);
        hiddenAbove = above;
    }

    @Override
    public void widen(Runnable swap) {
        swapWith(swap);
        hiddenAbove = 0;
    }

    private void swapWith(Runnable swap) {
        swapping = true;
        try {
            swap.run();
        } finally {
            swapping = false;
        }
    }
}
