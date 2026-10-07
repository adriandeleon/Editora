package com.editora.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictFile;
import com.editora.diff.ConflictParser.ConflictSegment;
import com.editora.diff.ConflictParser.PlainSegment;
import com.editora.diff.ConflictParser.Segment;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;

/**
 * Ancestor-aware, line-based three-way merge. Changes are calculated independently from the common
 * ancestor to ours and theirs. Disjoint changes and overlapping changes that produce the same text are
 * merged automatically; only genuinely divergent overlapping regions become {@link ConflictSegment}s.
 *
 * <p>The result intentionally uses the same {@link ConflictFile} model as the marker parser, so Git-stage
 * merges and already marker-formatted files share one resolution UI.
 */
public final class ThreeWayMerge {

    private ThreeWayMerge() {}

    /** Merge output plus the number of side changes incorporated without user intervention. */
    public record Result(ConflictFile file, int automaticallyMergedChanges) {}

    private record Change(int start, int end, List<String> replacement) {
        boolean insertion() {
            return start == end;
        }
    }

    /** Computes a merge using {@code base} as the common ancestor. */
    public static Result merge(String base, String ours, String theirs) {
        List<String> baseLines = DiffText.parse(base).lines();
        List<String> oursLines = DiffText.parse(ours).lines();
        List<String> theirsLines = DiffText.parse(theirs).lines();
        List<Change> oursChanges = changes(baseLines, oursLines);
        List<Change> theirsChanges = changes(baseLines, theirsLines);

        List<Segment> segments = new ArrayList<>();
        int oi = 0;
        int ti = 0;
        int cursor = 0;
        int autoMerged = 0;
        while (oi < oursChanges.size() || ti < theirsChanges.size()) {
            if (ti >= theirsChanges.size()
                    || (oi < oursChanges.size() && strictlyBefore(oursChanges.get(oi), theirsChanges.get(ti)))) {
                Change change = oursChanges.get(oi++);
                addPlain(segments, baseLines.subList(cursor, change.start()));
                addPlain(segments, change.replacement());
                cursor = change.end();
                autoMerged++;
                continue;
            }
            if (oi >= oursChanges.size() || strictlyBefore(theirsChanges.get(ti), oursChanges.get(oi))) {
                Change change = theirsChanges.get(ti++);
                addPlain(segments, baseLines.subList(cursor, change.start()));
                addPlain(segments, change.replacement());
                cursor = change.end();
                autoMerged++;
                continue;
            }

            int clusterStart =
                    Math.min(oursChanges.get(oi).start(), theirsChanges.get(ti).start());
            int clusterEnd =
                    Math.max(oursChanges.get(oi).end(), theirsChanges.get(ti).end());
            int oursStart = oi;
            int theirsStart = ti;
            oi++;
            ti++;

            boolean expanded;
            do {
                expanded = false;
                while (oi < oursChanges.size() && overlapsRegion(oursChanges.get(oi), clusterStart, clusterEnd)) {
                    clusterEnd = Math.max(clusterEnd, oursChanges.get(oi).end());
                    oi++;
                    expanded = true;
                }
                while (ti < theirsChanges.size() && overlapsRegion(theirsChanges.get(ti), clusterStart, clusterEnd)) {
                    clusterEnd = Math.max(clusterEnd, theirsChanges.get(ti).end());
                    ti++;
                    expanded = true;
                }
            } while (expanded);

            addPlain(segments, baseLines.subList(cursor, clusterStart));
            List<String> oursVariant = apply(baseLines, clusterStart, clusterEnd, oursChanges.subList(oursStart, oi));
            List<String> theirsVariant =
                    apply(baseLines, clusterStart, clusterEnd, theirsChanges.subList(theirsStart, ti));
            if (oursVariant.equals(theirsVariant)) {
                addPlain(segments, oursVariant);
                autoMerged += (oi - oursStart) + (ti - theirsStart);
            } else {
                List<String> baseVariant = List.copyOf(baseLines.subList(clusterStart, clusterEnd));
                segments.add(new ConflictSegment(new Conflict(
                        "ours", oursVariant, "common ancestor", baseVariant, "theirs", theirsVariant, true)));
            }
            cursor = clusterEnd;
        }
        addPlain(segments, baseLines.subList(cursor, baseLines.size()));
        return new Result(new ConflictFile(List.copyOf(segments)), autoMerged);
    }

    /**
     * Whether {@code written} — the conflict-marked text the file holds — is still the merge {@code merged}
     * describes: taking "ours" everywhere gives the same lines in both, and so does taking "theirs". It is
     * not once someone has resolved a conflict by hand or edited the file since Git wrote it, and a
     * resolution built from {@code merged} would then replace that work. Where the two only draw a conflict's
     * boundaries differently, both tests still pass.
     */
    public static boolean agreesWith(ConflictFile merged, ConflictFile written) {
        for (ConflictParser.Choice side : List.of(ConflictParser.Choice.OURS, ConflictParser.Choice.THEIRS)) {
            List<String> fromMerge =
                    ConflictParser.resolve(merged, java.util.Collections.nCopies(merged.conflictCount(), side));
            List<String> fromFile =
                    ConflictParser.resolve(written, java.util.Collections.nCopies(written.conflictCount(), side));
            if (!fromMerge.equals(fromFile)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One side's changes against the base, in a canonical position.
     *
     * <p>The raw deltas of a line differ are not unique where lines repeat: a line replaced inside a run of
     * identical lines comes back as an insertion plus a deletion of the run's <em>last</em> line. Merged as
     * they are, that detached deletion looked identical to the other side's real deletion of a line of the
     * run, the two were taken for one change and an edit was dropped (or, with two replacements, a line was
     * duplicated). The deltas are therefore compacted the way Git's xdiff does before a merge: every run of
     * changed lines is slid across the equal lines next to it until it joins a neighbouring run or lines up
     * with the other file's change, so a replaced line is one change at the line it replaced.
     */
    private static List<Change> changes(List<String> base, List<String> side) {
        // Index i + 1 holds line i; the two extra slots are sentinels that are never "changed".
        boolean[] baseChanged = new boolean[base.size() + 2];
        boolean[] sideChanged = new boolean[side.size() + 2];
        for (AbstractDelta<String> delta : DiffUtils.diff(base, side).getDeltas()) {
            int start = delta.getSource().getPosition();
            Arrays.fill(baseChanged, start + 1, start + delta.getSource().size() + 1, true);
            int targetStart = delta.getTarget().getPosition();
            Arrays.fill(
                    sideChanged,
                    targetStart + 1,
                    targetStart + delta.getTarget().size() + 1,
                    true);
        }
        compact(base, baseChanged, sideChanged);
        compact(side, sideChanged, baseChanged);

        List<Change> changes = new ArrayList<>();
        int b = 0;
        int s = 0;
        while (b < base.size() || s < side.size()) {
            if (!baseChanged[b + 1] && !sideChanged[s + 1]) {
                b++;
                s++;
                continue;
            }
            int start = b;
            int targetStart = s;
            while (baseChanged[b + 1]) {
                b++;
            }
            while (sideChanged[s + 1]) {
                s++;
            }
            changes.add(new Change(start, b, List.copyOf(side.subList(targetStart, s))));
        }
        return changes;
    }

    /** A run of changed lines {@code [start, end)} between two unchanged lines; empty when nothing changed there. */
    private static final class Group {
        int start;
        int end;

        boolean isEmpty() {
            return start == end;
        }
    }

    /**
     * Slides each run of changed lines of {@code lines} to its canonical position (xdiff's
     * {@code xdl_change_compact} without the indent heuristic): as far down as it goes, merging with the
     * runs it meets, unless on the way it can sit opposite a run of the other file, in which case it is
     * aligned with the last such run. Both files have the same number of unchanged lines, so their runs
     * pair up and {@code other} is walked in step.
     */
    private static void compact(List<String> lines, boolean[] changed, boolean[] other) {
        int count = lines.size();
        int otherCount = other.length - 2;
        Group group = new Group();
        Group otherGroup = new Group();
        first(changed, group);
        first(other, otherGroup);
        while (true) {
            if (!group.isEmpty()) {
                int size;
                int earliestEnd;
                int endMatchingOther;
                do {
                    size = group.end - group.start;
                    endMatchingOther = -1;
                    while (slideUp(lines, changed, group)) {
                        previous(other, otherGroup);
                    }
                    earliestEnd = group.end;
                    if (!otherGroup.isEmpty()) {
                        endMatchingOther = group.end;
                    }
                    while (slideDown(lines, changed, count, group)) {
                        next(other, otherCount, otherGroup);
                        if (!otherGroup.isEmpty()) {
                            endMatchingOther = group.end;
                        }
                    }
                } while (size != group.end - group.start); // it absorbed a neighbour: slide the merged run again
                if (group.end != earliestEnd && endMatchingOther != -1) {
                    while (otherGroup.isEmpty()) {
                        slideUp(lines, changed, group);
                        previous(other, otherGroup);
                    }
                }
            }
            if (!next(changed, count, group)) {
                return;
            }
            next(other, otherCount, otherGroup);
        }
    }

    private static void first(boolean[] changed, Group group) {
        group.start = 0;
        group.end = 0;
        while (changed[group.end + 1]) {
            group.end++;
        }
    }

    private static boolean next(boolean[] changed, int count, Group group) {
        if (group.end == count) {
            return false;
        }
        group.start = group.end + 1;
        group.end = group.start;
        while (changed[group.end + 1]) {
            group.end++;
        }
        return true;
    }

    private static void previous(boolean[] changed, Group group) {
        group.end = group.start - 1;
        group.start = group.end;
        while (changed[group.start]) {
            group.start--;
        }
    }

    private static boolean slideDown(List<String> lines, boolean[] changed, int count, Group group) {
        if (group.end >= count || !lines.get(group.start).equals(lines.get(group.end))) {
            return false;
        }
        changed[group.start + 1] = false;
        group.start++;
        changed[group.end + 1] = true;
        group.end++;
        while (changed[group.end + 1]) {
            group.end++;
        }
        return true;
    }

    private static boolean slideUp(List<String> lines, boolean[] changed, Group group) {
        if (group.start <= 0 || !lines.get(group.start - 1).equals(lines.get(group.end - 1))) {
            return false;
        }
        group.start--;
        changed[group.start + 1] = true;
        group.end--;
        changed[group.end + 1] = false;
        while (changed[group.start]) {
            group.start--;
        }
        return true;
    }

    /**
     * Whether {@code first} ends with at least one untouched base line before {@code second} starts. Changes
     * that merely touch are one region, as in Git: merged independently, a region Git had reported as a
     * conflict was resolved without ever being shown, and when each side deleted a different one of two
     * identical neighbouring lines both were gone.
     */
    private static boolean strictlyBefore(Change first, Change second) {
        return first.end() < second.start();
    }

    private static boolean overlapsRegion(Change change, int start, int end) {
        return change.start() <= end && change.end() >= start;
    }

    private static List<String> apply(List<String> base, int start, int end, List<Change> changes) {
        List<String> out = new ArrayList<>();
        int cursor = start;
        for (Change change : changes) {
            out.addAll(base.subList(cursor, change.start()));
            out.addAll(change.replacement());
            cursor = change.end();
        }
        out.addAll(base.subList(cursor, end));
        return List.copyOf(out);
    }

    private static void addPlain(List<Segment> segments, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        if (!segments.isEmpty() && segments.get(segments.size() - 1) instanceof PlainSegment previous) {
            List<String> joined = new ArrayList<>(previous.lines().size() + lines.size());
            joined.addAll(previous.lines());
            joined.addAll(lines);
            segments.set(segments.size() - 1, new PlainSegment(List.copyOf(joined)));
        } else {
            segments.add(new PlainSegment(List.copyOf(lines)));
        }
    }
}
