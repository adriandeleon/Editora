package com.editora.diff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Quality;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import com.editora.diff.DiffModels.UnifiedRow;
import com.editora.diff.DiffModels.UnifiedType;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/**
 * Computes an aligned diff between two texts using java-diff-utils' Myers line diff. Pure and
 * unit-tested. {@link #compute} walks the {@link Patch} deltas, emitting an {@link DiffModels.Row} per
 * screen line — equal stretches first, then each delta as ADD/REMOVE rows, and a CHANGE delta paired
 * positionally or by bounded similarity alignment into MODIFIED rows (with {@link InlineDiff} word ranges)
 * plus filler for the longer side.
 */
public final class DiffEngine {

    private DiffEngine() {}

    /**
     * Splits {@code text} into lines for diffing: CRLF/CR are normalized to LF, and a single trailing
     * newline is dropped so {@code "a\nb\n"} → {@code [a, b]} (not a spurious trailing empty line).
     */
    public static List<String> lines(String text) {
        return DiffText.parse(text).lines();
    }

    /**
     * Diff options. Whitespace and case rules affect comparison keys only; rows retain both original texts.
     * {@code wordLevel} enables intra-line {@link InlineDiff} ranges, while {@code smartAlignment} uses a
     * bounded similarity pass to pair related lines inside replacement blocks.
     */
    public enum WhitespaceMode {
        NONE,
        TRIM,
        ALL
    }

    public record DiffOptions(
            WhitespaceMode whitespace, boolean wordLevel, boolean ignoreCase, boolean smartAlignment) {
        public static final DiffOptions DEFAULT = new DiffOptions(WhitespaceMode.NONE, true, false, true);

        public DiffOptions {
            whitespace = whitespace == null ? WhitespaceMode.NONE : whitespace;
        }

        public DiffOptions(WhitespaceMode whitespace, boolean wordLevel) {
            this(whitespace, wordLevel, false, true);
        }

        public DiffOptions(boolean ignoreWhitespace, boolean wordLevel) {
            this(ignoreWhitespace ? WhitespaceMode.ALL : WhitespaceMode.NONE, wordLevel, false, true);
        }

        public boolean ignoreWhitespace() {
            return whitespace != WhitespaceMode.NONE;
        }

        public DiffOptions withWhitespace(WhitespaceMode value) {
            return new DiffOptions(value, wordLevel, ignoreCase, smartAlignment);
        }

        public DiffOptions withWordLevel(boolean value) {
            return new DiffOptions(whitespace, value, ignoreCase, smartAlignment);
        }

        public DiffOptions withIgnoreCase(boolean value) {
            return new DiffOptions(whitespace, wordLevel, value, smartAlignment);
        }

        public DiffOptions withSmartAlignment(boolean value) {
            return new DiffOptions(whitespace, wordLevel, ignoreCase, value);
        }
    }

    private static final InlineDiff.Spans NO_SPANS = new InlineDiff.Spans(new int[0][], new int[0][]);
    private static final int SMART_ALIGNMENT_MAX_LINES = 200;
    private static final int SMART_ALIGNMENT_MAX_CELLS = 10_000;
    private static final double GAP_COST = 0.55;
    private static final double MIN_PAIR_SIMILARITY = 0.35;

    /** Computes the diff of two already-split line lists (default options). */
    public static DiffModel compute(List<String> left, List<String> right) {
        return compute(left, right, DiffOptions.DEFAULT);
    }

    /**
     * Computes the diff with {@code opts}. Comparison normalization is strictly one-to-one per line, so
     * delta positions continue to index the original lists and every emitted {@link Row} carries each side's
     * <em>original</em> text. Smart alignment is bounded by both line and matrix-cell limits; larger change
     * blocks use deterministic positional pairing. {@code wordLevel} gates the per-line {@link InlineDiff}.
     */
    public static DiffModel compute(List<String> left, List<String> right, DiffOptions opts) {
        DiffOptions o = opts == null ? DiffOptions.DEFAULT : opts;
        List<String> dl = normalizeAll(left, o);
        List<String> dr = normalizeAll(right, o);
        Patch<String> patch = DiffUtils.diff(dl, dr);
        List<Row> rows = new ArrayList<>();
        int li = 0; // 0-based pointer into left (original)
        int ri = 0; // 0-based pointer into right (original)
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int srcPos = delta.getSource().getPosition();
            while (li < srcPos) { // equal stretch before this delta
                rows.add(Row.equal(left.get(li), li + 1, right.get(ri), ri + 1));
                li++;
                ri++;
            }
            // Slice text from the ORIGINAL lists by the delta's positions/sizes (not delta.getLines(),
            // which would be the normalized text when ignoring whitespace).
            int srcSize = delta.getSource().size();
            int tgtSize = delta.getTarget().size();
            switch (delta.getType()) {
                case INSERT -> {
                    for (int k = 0; k < tgtSize; k++) {
                        rows.add(Row.added(right.get(ri), ri + 1));
                        ri++;
                    }
                }
                case DELETE -> {
                    for (int k = 0; k < srcSize; k++) {
                        rows.add(Row.removed(left.get(li), li + 1));
                        li++;
                    }
                }
                case CHANGE -> {
                    if (shouldSmartAlign(srcSize, tgtSize, o)) {
                        appendSmartChange(rows, left, right, dl, dr, li, ri, srcSize, tgtSize, o.wordLevel());
                    } else {
                        appendPositionalChange(rows, left, right, li, ri, srcSize, tgtSize, o.wordLevel());
                    }
                    li += srcSize;
                    ri += tgtSize;
                }
                default -> {
                    // java-diff-utils never emits EQUAL deltas; equal lines are filled above.
                }
            }
        }
        while (li < left.size()) { // trailing equal stretch
            rows.add(Row.equal(left.get(li), li + 1, right.get(ri), ri + 1));
            li++;
            ri++;
        }
        return finish(rows, false, false, Quality.FULL);
    }

    /** Loss-aware entry point used by the UI. */
    public static DiffModel compute(String left, String right, DiffOptions opts) {
        DiffText l = DiffText.parse(left);
        DiffText r = DiffText.parse(right);
        DiffModel model = compute(l.lines(), r.lines(), opts);
        return withMetadata(model, l.finalNewline(), r.finalNewline(), Quality.FULL);
    }

    /**
     * Linear-time fallback for very large files: retain the common prefix/suffix and align the changed
     * middle without an expensive Myers search or word diff.
     */
    public static DiffModel computeCoarse(String left, String right) {
        DiffText l = DiffText.parse(left);
        DiffText r = DiffText.parse(right);
        List<String> a = l.lines();
        List<String> b = r.lines();
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix
                && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            rows.add(Row.equal(a.get(i), i + 1, i + 1));
        }
        int alen = a.size() - prefix - suffix;
        int blen = b.size() - prefix - suffix;
        for (int i = 0; i < Math.max(alen, blen); i++) {
            if (i < alen && i < blen) {
                rows.add(Row.modified(
                        a.get(prefix + i),
                        prefix + i + 1,
                        b.get(prefix + i),
                        prefix + i + 1,
                        new int[0][],
                        new int[0][]));
            } else if (i < alen) {
                rows.add(Row.removed(a.get(prefix + i), prefix + i + 1));
            } else {
                rows.add(Row.added(b.get(prefix + i), prefix + i + 1));
            }
        }
        for (int i = suffix; i > 0; i--) {
            int ai = a.size() - i;
            int bi = b.size() - i;
            rows.add(Row.equal(a.get(ai), ai + 1, bi + 1));
        }
        return finish(rows, l.finalNewline(), r.finalNewline(), Quality.LINE_ONLY);
    }

    /** Bounded fallback when even materializing every aligned row would be unreasonable. */
    public static DiffModel metadataOnly(String left, String right) {
        DiffText l = DiffText.parse(left);
        DiffText r = DiffText.parse(right);
        String ls = "Binary/large content: " + left.length() + " chars, "
                + l.lines().size() + " lines";
        String rs = "Binary/large content: " + right.length() + " chars, "
                + r.lines().size() + " lines";
        List<Row> rows = List.of(Row.modified(ls, 1, rs, 1, new int[0][], new int[0][]));
        return finish(rows, l.finalNewline(), r.finalNewline(), Quality.METADATA_ONLY);
    }

    private static List<String> normalizeAll(List<String> lines, DiffOptions options) {
        WhitespaceMode mode = options.whitespace();
        if (mode == WhitespaceMode.NONE && !options.ignoreCase()) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            String normalized =
                    switch (mode) {
                        case NONE -> s;
                        case TRIM -> s.strip();
                        case ALL -> normalize(s);
                    };
            out.add(options.ignoreCase() ? normalized.toLowerCase(Locale.ROOT) : normalized);
        }
        return out;
    }

    private static boolean shouldSmartAlign(int leftSize, int rightSize, DiffOptions options) {
        return options.smartAlignment()
                && leftSize > 1
                && rightSize > 1
                && leftSize <= SMART_ALIGNMENT_MAX_LINES
                && rightSize <= SMART_ALIGNMENT_MAX_LINES
                && (long) leftSize * rightSize <= SMART_ALIGNMENT_MAX_CELLS;
    }

    private static void appendPositionalChange(
            List<Row> rows,
            List<String> left,
            List<String> right,
            int leftStart,
            int rightStart,
            int leftSize,
            int rightSize,
            boolean wordLevel) {
        int paired = Math.min(leftSize, rightSize);
        for (int k = 0; k < paired; k++) {
            rows.add(modified(left, right, leftStart + k, rightStart + k, wordLevel));
        }
        for (int k = paired; k < leftSize; k++) {
            rows.add(Row.removed(left.get(leftStart + k), leftStart + k + 1));
        }
        for (int k = paired; k < rightSize; k++) {
            rows.add(Row.added(right.get(rightStart + k), rightStart + k + 1));
        }
    }

    private static void appendSmartChange(
            List<Row> rows,
            List<String> left,
            List<String> right,
            List<String> normalizedLeft,
            List<String> normalizedRight,
            int leftStart,
            int rightStart,
            int leftSize,
            int rightSize,
            boolean wordLevel) {
        double[][] costs = new double[leftSize + 1][rightSize + 1];
        byte[][] steps = new byte[leftSize + 1][rightSize + 1];
        for (int i = 1; i <= leftSize; i++) {
            costs[i][0] = i * GAP_COST;
            steps[i][0] = 1; // remove
        }
        for (int j = 1; j <= rightSize; j++) {
            costs[0][j] = j * GAP_COST;
            steps[0][j] = 2; // add
        }
        for (int i = 1; i <= leftSize; i++) {
            for (int j = 1; j <= rightSize; j++) {
                double remove = costs[i - 1][j] + GAP_COST;
                double add = costs[i][j - 1] + GAP_COST;
                costs[i][j] = remove;
                steps[i][j] = 1;
                if (add < costs[i][j]) {
                    costs[i][j] = add;
                    steps[i][j] = 2;
                }
                double similarity =
                        lineSimilarity(normalizedLeft.get(leftStart + i - 1), normalizedRight.get(rightStart + j - 1));
                double pair = costs[i - 1][j - 1] + 1.0 - similarity;
                if (similarity >= MIN_PAIR_SIMILARITY && pair <= costs[i][j]) {
                    costs[i][j] = pair;
                    steps[i][j] = 3;
                }
            }
        }

        List<Byte> alignment = new ArrayList<>(leftSize + rightSize);
        int i = leftSize;
        int j = rightSize;
        while (i > 0 || j > 0) {
            byte step = steps[i][j];
            alignment.add(step);
            if (step == 3) {
                i--;
                j--;
            } else if (step == 1) {
                i--;
            } else {
                j--;
            }
        }
        java.util.Collections.reverse(alignment);
        i = 0;
        j = 0;
        for (byte step : alignment) {
            if (step == 3) {
                rows.add(modified(left, right, leftStart + i, rightStart + j, wordLevel));
                i++;
                j++;
            } else if (step == 1) {
                rows.add(Row.removed(left.get(leftStart + i), leftStart + i + 1));
                i++;
            } else {
                rows.add(Row.added(right.get(rightStart + j), rightStart + j + 1));
                j++;
            }
        }
    }

    private static Row modified(
            List<String> left, List<String> right, int leftIndex, int rightIndex, boolean wordLevel) {
        InlineDiff.Spans spans = wordLevel ? InlineDiff.compute(left.get(leftIndex), right.get(rightIndex)) : NO_SPANS;
        return Row.modified(
                left.get(leftIndex), leftIndex + 1, right.get(rightIndex), rightIndex + 1, spans.left(), spans.right());
    }

    /** Multiset Dice similarity over non-whitespace word and punctuation tokens. */
    private static double lineSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        List<String> leftTokens = significantTokens(left);
        List<String> rightTokens = significantTokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String token : leftTokens) {
            counts.merge(token, 1, Integer::sum);
        }
        int common = 0;
        for (String token : rightTokens) {
            Integer count = counts.get(token);
            if (count != null && count > 0) {
                common++;
                counts.put(token, count - 1);
            }
        }
        return 2.0 * common / (leftTokens.size() + rightTokens.size());
    }

    private static List<String> significantTokens(String line) {
        List<String> tokens = InlineDiff.tokenize(line);
        tokens.removeIf(String::isBlank);
        return tokens;
    }

    /** Leading/trailing whitespace stripped and internal whitespace runs collapsed to one space. */
    static String normalize(String line) {
        return line.strip().replaceAll("\\s+", " ");
    }

    private static DiffModel finish(
            List<Row> rows, boolean leftFinalNewline, boolean rightFinalNewline, Quality quality) {
        int added = 0;
        int removed = 0;
        List<Integer> changeStarts = new ArrayList<>();
        List<UnifiedRow> unified = new ArrayList<>();
        boolean prevChanged = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean changed = r.type() != RowType.EQUAL;
            if (changed && !prevChanged) {
                changeStarts.add(i);
            }
            prevChanged = changed;
            switch (r.type()) {
                case EQUAL ->
                    unified.add(new UnifiedRow(UnifiedType.CONTEXT, r.left(), r.leftLine(), r.rightLine(), null));
                case ADDED -> {
                    added++;
                    unified.add(new UnifiedRow(UnifiedType.ADD, r.right(), -1, r.rightLine(), null));
                }
                case REMOVED -> {
                    removed++;
                    unified.add(new UnifiedRow(UnifiedType.REMOVE, r.left(), r.leftLine(), -1, null));
                }
                case MODIFIED -> {
                    added++;
                    removed++;
                    unified.add(new UnifiedRow(UnifiedType.REMOVE, r.left(), r.leftLine(), -1, r.leftWordRanges()));
                    unified.add(new UnifiedRow(UnifiedType.ADD, r.right(), -1, r.rightLine(), r.rightWordRanges()));
                }
                default -> {}
            }
        }
        return new DiffModel(rows, unified, added, removed, changeStarts, leftFinalNewline, rightFinalNewline, quality);
    }

    private static DiffModel withMetadata(
            DiffModel model, boolean leftFinalNewline, boolean rightFinalNewline, Quality quality) {
        return new DiffModel(
                model.rows(),
                model.unified(),
                model.added(),
                model.removed(),
                model.changeBlockStarts(),
                leftFinalNewline,
                rightFinalNewline,
                quality);
    }
}
