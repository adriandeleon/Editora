package com.editora.diff;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
import com.editora.diff.DiffModels.UnifiedType;
import com.editora.perf.BlockingSites;
import org.jspecify.annotations.Nullable;

/**
 * What a diff viewer shows for a {@link DiffModel}, worked out where the model is made, off the JavaFX thread
 * (PERF-01, Pauta 6.2.0): the rows on screen (unchanged runs folded to a marker row when context is collapsed), the
 * whole text of each pane joined from them, each row's line number and the changed words as offsets in that text. The
 * viewer only puts these into its text areas.
 *
 * <p>{@link DiffService} works out the four layouts of each model it makes (side by side and unified, context
 * collapsed or not) on its own thread and keeps the last few here for the viewer to {@linkplain #of take}. A model made
 * elsewhere (a test's) is laid out when first asked; {@link #SITE} names that work so a test can tell on which thread
 * it ran.
 */
public final class DiffLayout {

    /** Where a layout is worked out from a model's rows. */
    public static final String SITE = "DiffLayout.build";

    /** Unchanged lines kept around a change when context is collapsed. */
    public static final int CONTEXT_LINES = 3;

    /** How many models' layouts are kept for their viewers to take: a viewer takes its model's as it is made. */
    private static final int KEPT = 16;

    private static final ArrayDeque<Kept> recent = new ArrayDeque<>();

    private record Kept(WeakReference<DiffModel> model, Layouts layouts) {}

    private DiffLayout() {}

    /** The side-by-side panes: the rows shown, the source row each starts at, and their texts. */
    public record Side(
            List<Row> rows,
            int[] sourceStarts,
            boolean[] collapsed,
            String left,
            String right,
            int[] leftNos,
            int[] rightNos,
            List<int[]> leftWords,
            List<int[]> rightWords) {}

    /** The unified pane: the rows shown, the source row each starts at, its text, numbers and signs. */
    public record Unified(
            List<UnifiedRow> rows,
            int[] sourceStarts,
            boolean[] collapsed,
            String text,
            List<int[]> words,
            int[] nos,
            String[] signs) {}

    /** A model's four layouts; one not worked out yet is worked out when first asked. */
    public static final class Layouts {
        private final DiffModel model;
        private final IntFunction<String> marker;
        private final @Nullable Side[] sides = new Side[2];
        private final @Nullable Unified[] unifieds = new Unified[2];

        Layouts(DiffModel model, IntFunction<String> marker) {
            this.model = model;
            this.marker = marker;
        }

        /** The side-by-side layout, context collapsed or not. */
        public synchronized Side side(boolean collapse) {
            Side s = sides[collapse ? 1 : 0];
            if (s == null) {
                s = DiffLayout.side(model, collapse, marker);
                sides[collapse ? 1 : 0] = s;
            }
            return s;
        }

        /** The unified layout, context collapsed or not. */
        public synchronized Unified unified(boolean collapse) {
            Unified u = unifieds[collapse ? 1 : 0];
            if (u == null) {
                u = DiffLayout.unified(model, collapse, marker);
                unifieds[collapse ? 1 : 0] = u;
            }
            return u;
        }

        /** Whether the layout of that view (unified or side-by-side), context collapsed or not, is worked out. */
        public synchronized boolean has(boolean unified, boolean collapse) {
            return unified ? unifieds[collapse ? 1 : 0] != null : sides[collapse ? 1 : 0] != null;
        }

        void warm() {
            side(false);
            side(true);
            unified(false);
            unified(true);
        }
    }

    /**
     * Works out every layout of {@code model} now (on the thread that made it) and keeps them for its viewer.
     * {@code marker} words a folded run of n unchanged lines.
     */
    public static void warm(DiffModel model, IntFunction<String> marker) {
        Layouts layouts = new Layouts(model, marker);
        layouts.warm();
        synchronized (recent) {
            recent.addFirst(new Kept(new WeakReference<>(model), layouts));
            while (recent.size() > KEPT) {
                recent.removeLast();
            }
        }
    }

    /** The layouts kept for {@code model} (the same object), or new ones, worked out as they are asked for. */
    public static Layouts of(DiffModel model, IntFunction<String> marker) {
        synchronized (recent) {
            for (Iterator<Kept> it = recent.iterator(); it.hasNext(); ) {
                Kept kept = it.next();
                DiffModel m = kept.model().get();
                if (m == null) {
                    it.remove();
                } else if (m == model) {
                    return kept.layouts();
                }
            }
        }
        return new Layouts(model, marker);
    }

    /** Pure: the side-by-side layout of {@code model}. */
    public static Side side(DiffModel model, boolean collapse, IntFunction<String> marker) {
        BlockingSites.enter(SITE);
        List<Row> all = model.rows();
        List<Row> rows = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Boolean> folded = new ArrayList<>();
        int i = 0;
        boolean fold = collapse && all.size() > CONTEXT_LINES * 2 + 2;
        while (i < all.size()) {
            if (!fold || all.get(i).type() != RowType.EQUAL) {
                rows.add(all.get(i));
                starts.add(i);
                folded.add(false);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < all.size() && all.get(end).type() == RowType.EQUAL) {
                end++;
            }
            int[] keep = keep(i, end, all.size());
            if (keep == null) {
                for (int j = i; j < end; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
            } else {
                for (int j = i; j < keep[0]; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
                Row first = all.get(keep[0]);
                rows.add(Row.equal(marker.apply(keep[1] - keep[0]), first.leftLine(), first.rightLine()));
                starts.add(keep[0]);
                folded.add(true);
                for (int j = keep[1]; j < end; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
            }
            i = end;
        }
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        int[] leftNos = new int[rows.size()];
        int[] rightNos = new int[rows.size()];
        List<int[]> leftWords = new ArrayList<>();
        List<int[]> rightWords = new ArrayList<>();
        boolean[] collapsed = new boolean[rows.size()];
        for (int k = 0; k < rows.size(); k++) {
            Row r = rows.get(k);
            String l = r.left() == null ? "" : r.left();
            String rt = r.right() == null ? "" : r.right();
            if (k > 0) {
                left.append('\n');
                right.append('\n');
            }
            leftNos[k] = r.leftLine();
            rightNos[k] = r.rightLine();
            collapsed[k] = folded.get(k);
            if (!collapsed[k] && r.type() == RowType.MODIFIED) {
                addAbs(leftWords, left.length(), r.leftWordRanges());
                addAbs(rightWords, right.length(), r.rightWordRanges());
            }
            left.append(l);
            right.append(rt);
        }
        return new Side(
                List.copyOf(rows),
                starts.stream().mapToInt(Integer::intValue).toArray(),
                collapsed,
                left.toString(),
                right.toString(),
                leftNos,
                rightNos,
                leftWords,
                rightWords);
    }

    /** Pure: the unified layout of {@code model}. */
    public static Unified unified(DiffModel model, boolean collapse, IntFunction<String> marker) {
        BlockingSites.enter(SITE);
        List<UnifiedRow> all = model.unified();
        List<UnifiedRow> rows = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Boolean> folded = new ArrayList<>();
        int i = 0;
        boolean fold = collapse && all.size() > CONTEXT_LINES * 2 + 2;
        while (i < all.size()) {
            if (!fold || all.get(i).type() != UnifiedType.CONTEXT) {
                rows.add(all.get(i));
                starts.add(i);
                folded.add(false);
                i++;
                continue;
            }
            int end = i + 1;
            while (end < all.size() && all.get(end).type() == UnifiedType.CONTEXT) {
                end++;
            }
            int[] keep = keep(i, end, all.size());
            if (keep == null) {
                for (int j = i; j < end; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
            } else {
                for (int j = i; j < keep[0]; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
                UnifiedRow first = all.get(keep[0]);
                rows.add(new UnifiedRow(
                        UnifiedType.CONTEXT,
                        marker.apply(keep[1] - keep[0]),
                        first.leftLine(),
                        first.rightLine(),
                        null));
                starts.add(keep[0]);
                folded.add(true);
                for (int j = keep[1]; j < end; j++) {
                    rows.add(all.get(j));
                    starts.add(j);
                    folded.add(false);
                }
            }
            i = end;
        }
        StringBuilder text = new StringBuilder();
        List<int[]> words = new ArrayList<>();
        int[] nos = new int[rows.size()];
        String[] signs = new String[rows.size()];
        boolean[] collapsed = new boolean[rows.size()];
        for (int k = 0; k < rows.size(); k++) {
            UnifiedRow r = rows.get(k);
            if (k > 0) {
                text.append('\n');
            }
            addAbs(words, text.length(), r.wordRanges());
            text.append(r.text() == null ? "" : r.text());
            collapsed[k] = folded.get(k);
            if (collapsed[k]) {
                nos[k] = r.rightLine();
                signs[k] = "⋯";
            } else {
                switch (r.type()) {
                    case ADD -> {
                        nos[k] = r.rightLine();
                        signs[k] = "+";
                    }
                    case REMOVE -> {
                        nos[k] = r.leftLine();
                        signs[k] = "-";
                    }
                    default -> {
                        nos[k] = r.rightLine();
                        signs[k] = " ";
                    }
                }
            }
        }
        return new Unified(
                List.copyOf(rows),
                starts.stream().mapToInt(Integer::intValue).toArray(),
                collapsed,
                text.toString(),
                words,
                nos,
                signs);
    }

    /**
     * The hidden part {@code [from, to)} of the unchanged run {@code [start, end)} of a list of {@code size} rows, or
     * null when the run is kept whole: {@link #CONTEXT_LINES} kept on each side that has a change.
     */
    private static int @Nullable [] keep(int start, int end, int size) {
        int leftKeep = start == 0 ? 0 : Math.min(CONTEXT_LINES, end - start);
        int rightKeep = end == size ? 0 : Math.min(CONTEXT_LINES, end - start - leftKeep);
        if (end - start <= leftKeep + rightKeep + 1) {
            return null;
        }
        return new int[] {start + leftKeep, end - rightKeep};
    }

    private static void addAbs(List<int[]> out, int lineOffset, int @Nullable [][] ranges) {
        if (ranges == null) {
            return;
        }
        for (int[] r : ranges) {
            out.add(new int[] {lineOffset + r[0], lineOffset + r[1]});
        }
    }
}
