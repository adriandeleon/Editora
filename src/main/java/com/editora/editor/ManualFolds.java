package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.FoldRegions.Region;

/**
 * Line arithmetic for <b>manual fold ranges</b> (VS Code's {@code createFoldingRangeFromSelection}):
 * user-defined {@link Region}s with no syntactic basis, held per buffer by {@link FoldManager} and merged
 * into the detected set. Unlike detected regions — recomputed from the text on every pulse — a manual
 * region is anchored to nothing, so it must be <b>shifted through edits</b> or it drifts off the lines the
 * user folded (the {@code BookmarkManager.shift} problem, in line units rather than offsets).
 *
 * <p>Pure; unit-tested. {@code FoldManager} feeds it each {@code PlainTextChange}'s line geometry.
 */
public final class ManualFolds {

    private ManualFolds() {}

    /**
     * Shifts every region through one edit. {@code changeStartLine} is the line the change begins on;
     * {@code removedLines}/{@code insertedLines} are how many line <em>breaks</em> the removed/inserted
     * text contained (0 for an edit within one line, which never moves a region).
     *
     * <ul>
     *   <li>A region entirely above the change is untouched; entirely below moves by the delta.
     *   <li>A change inside a region grows/shrinks its end.
     *   <li>A deletion overlapping a region's lines clamps it; a region squeezed below two lines is
     *       <b>dropped</b> — the lines the user folded no longer exist, and keeping a phantom
     *       one-line region would grow a chevron on an arbitrary surviving line.
     * </ul>
     */
    public static List<Region> shift(List<Region> regions, int changeStartLine, int removedLines, int insertedLines) {
        if (regions == null || regions.isEmpty()) {
            return List.of();
        }
        int delta = insertedLines - removedLines;
        if (delta == 0 && removedLines == 0) {
            return regions; // single-line edit: no line moved, nothing to do (the hot path)
        }
        int removedEnd = changeStartLine + removedLines; // last line touched by the removal
        List<Region> out = new ArrayList<>(regions.size());
        for (Region r : regions) {
            int start = r.startLine();
            int end = r.endLine();
            if (end < changeStartLine) {
                out.add(r); // wholly above
                continue;
            }
            if (start > removedEnd) {
                out.add(new Region(start + delta, end + delta)); // wholly below
                continue;
            }
            // Overlapping. Move each boundary independently: a boundary above the change keeps its
            // line; one below the removed span shifts by the delta; one INSIDE the removed span no
            // longer exists and clamps to the change-start line.
            int newStart = start <= changeStartLine ? start : Math.max(changeStartLine, start + delta);
            int newEnd = end >= removedEnd ? end + delta : changeStartLine;
            if (newEnd > newStart) {
                out.add(new Region(newStart, newEnd));
            }
            // else: squeezed to under two lines — dropped
        }
        return out;
    }

    /** The number of line breaks in {@code s} ({@code null} counts 0) — the shift's removed/inserted units. */
    public static int lineBreaks(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * Flattens regions to the persisted form — {@code [s1, e1, s2, e2, …]}, the shape stored per file in
     * {@code WorkspaceState.manualFoldRegions} (a flat int list keeps the Jackson mapping as simple as the
     * existing collapsed-fold line list beside it).
     */
    public static List<Integer> toFlat(List<Region> regions) {
        List<Integer> out = new ArrayList<>();
        if (regions != null) {
            for (Region r : regions) {
                out.add(r.startLine());
                out.add(r.endLine());
            }
        }
        return out;
    }

    /** Inverse of {@link #toFlat}; tolerant of a malformed odd-length or inverted-pair list (skips them). */
    public static List<Region> fromFlat(List<Integer> flat) {
        List<Region> out = new ArrayList<>();
        if (flat != null) {
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                Integer s = flat.get(i);
                Integer e = flat.get(i + 1);
                if (s != null && e != null && s >= 0 && e > s) {
                    out.add(new Region(s, e));
                }
            }
        }
        return out;
    }
}
