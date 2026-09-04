package com.editora.diff;

import java.util.ArrayList;
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

    private static List<Change> changes(List<String> base, List<String> side) {
        List<Change> changes = new ArrayList<>();
        for (AbstractDelta<String> delta : DiffUtils.diff(base, side).getDeltas()) {
            int start = delta.getSource().getPosition();
            int end = start + delta.getSource().size();
            int targetStart = delta.getTarget().getPosition();
            int targetEnd = targetStart + delta.getTarget().size();
            changes.add(new Change(start, end, List.copyOf(side.subList(targetStart, targetEnd))));
        }
        return changes;
    }

    /** Adjacent replacements are independent; insertions at a replacement boundary are kept together. */
    private static boolean strictlyBefore(Change first, Change second) {
        return first.end() < second.start()
                || (first.end() == second.start() && !first.insertion() && !second.insertion());
    }

    private static boolean overlapsRegion(Change change, int start, int end) {
        if (change.insertion()) {
            return change.start() >= start && change.start() <= end;
        }
        if (start == end) {
            return change.start() <= start && change.end() >= end;
        }
        return change.start() < end && change.end() > start;
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
