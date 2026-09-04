package com.editora.diff;

import java.util.List;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    @Test
    void linesNormalizesEolAndDropsSingleTrailingNewline() {
        assertEquals(List.of("a", "b"), DiffEngine.lines("a\nb\n"));
        assertEquals(List.of("a", "b"), DiffEngine.lines("a\r\nb"));
        assertEquals(List.of("a", "", "b"), DiffEngine.lines("a\n\nb")); // interior blank kept
        assertEquals(List.of(), DiffEngine.lines(""));
        assertEquals(List.of(), DiffEngine.lines(null));
    }

    @Test
    void identicalContentIsAllEqual() {
        DiffModel m = DiffEngine.compute(List.of("a", "b", "c"), List.of("a", "b", "c"));
        assertEquals(3, m.rows().size());
        assertTrue(m.rows().stream().allMatch(r -> r.type() == RowType.EQUAL));
        assertEquals(0, m.added());
        assertEquals(0, m.removed());
        assertTrue(m.isEmpty());
        assertTrue(m.changeBlockStarts().isEmpty());
    }

    @Test
    void pureInsertionAndDeletionAlignWithFiller() {
        // Insert "x" between a and b.
        DiffModel ins = DiffEngine.compute(List.of("a", "b"), List.of("a", "x", "b"));
        assertEquals(3, ins.rows().size());
        assertEquals(RowType.ADDED, ins.rows().get(1).type());
        assertEquals("x", ins.rows().get(1).right());
        assertEquals(-1, ins.rows().get(1).leftLine()); // filler on the left
        assertEquals(1, ins.added());
        assertEquals(0, ins.removed());

        DiffModel del = DiffEngine.compute(List.of("a", "x", "b"), List.of("a", "b"));
        assertEquals(RowType.REMOVED, del.rows().get(1).type());
        assertEquals(-1, del.rows().get(1).rightLine());
        assertEquals(1, del.removed());
    }

    @Test
    void changedLinePairsIntoModifiedWithWordRanges() {
        DiffModel m = DiffEngine.compute(List.of("the quick fox"), List.of("the slow fox"));
        assertEquals(1, m.rows().size());
        Row r = m.rows().get(0);
        assertEquals(RowType.MODIFIED, r.type());
        assertEquals(1, m.added());
        assertEquals(1, m.removed());
        // "quick" → "slow" is the only changed word; one range per side.
        assertEquals(1, r.leftWordRanges().length);
        assertEquals(1, r.rightWordRanges().length);
        assertEquals("quick", "the quick fox".substring(r.leftWordRanges()[0][0], r.leftWordRanges()[0][1]));
        assertEquals("slow", "the slow fox".substring(r.rightWordRanges()[0][0], r.rightWordRanges()[0][1]));
    }

    @Test
    void ignoreWhitespaceTreatsWhitespaceOnlyDiffsAsEqualButRendersOriginalText() {
        List<String> left = List.of("  int  x = 1;", "y = 2;");
        List<String> right = List.of("int x = 1;", "y = 2;"); // line 1 differs only in whitespace amount/indent
        // Default: line 1 is a real change.
        DiffModel def = DiffEngine.compute(left, right);
        assertEquals(RowType.MODIFIED, def.rows().get(0).type());
        // Ignore whitespace: line 1 counts as EQUAL, and the row still shows the original (left) text.
        DiffModel ign = DiffEngine.compute(left, right, new DiffEngine.DiffOptions(true, true));
        assertTrue(ign.isEmpty());
        assertEquals(RowType.EQUAL, ign.rows().get(0).type());
        assertEquals("  int  x = 1;", ign.rows().get(0).left()); // original spacing preserved in the render
    }

    @Test
    void wordLevelOffProducesNoInlineRanges() {
        DiffModel on = DiffEngine.compute(List.of("the quick fox"), List.of("the slow fox"));
        assertEquals(1, on.rows().get(0).leftWordRanges().length); // word-level default: one changed word
        DiffModel off = DiffEngine.compute(
                List.of("the quick fox"), List.of("the slow fox"), new DiffEngine.DiffOptions(false, false));
        assertEquals(RowType.MODIFIED, off.rows().get(0).type());
        assertEquals(0, off.rows().get(0).leftWordRanges().length); // whole-line highlight (no word ranges)
        assertEquals(0, off.rows().get(0).rightWordRanges().length);
    }

    @Test
    void changeBlockStartsMarkEachContiguousRun() {
        // equal, change, equal, add → two change blocks at rows 1 and 3.
        DiffModel m = DiffEngine.compute(List.of("a", "B", "c"), List.of("a", "b", "c", "d"));
        assertEquals(List.of(1, 3), m.changeBlockStarts());
    }

    @Test
    void unifiedExpandsModifiedToRemoveThenAdd() {
        DiffModel m = DiffEngine.compute(List.of("a", "B"), List.of("a", "b"));
        // context "a", then remove "B", then add "b".
        assertEquals(DiffModels.UnifiedType.CONTEXT, m.unified().get(0).type());
        assertEquals(DiffModels.UnifiedType.REMOVE, m.unified().get(1).type());
        assertEquals(DiffModels.UnifiedType.ADD, m.unified().get(2).type());
        assertEquals("B", m.unified().get(1).text());
        assertEquals("b", m.unified().get(2).text());
        assertEquals(1, m.unified().get(1).wordRanges().length);
        assertEquals(1, m.unified().get(2).wordRanges().length);
    }

    @Test
    void finalNewlineIsARealDifference() {
        DiffModel model = DiffEngine.compute("same\n", "same", DiffEngine.DiffOptions.DEFAULT);
        assertTrue(model.finalNewlineDiffers());
        assertEquals(0, model.added());
        assertEquals(0, model.removed());
        assertTrue(!model.isEmpty());
    }

    @Test
    void trimWhitespaceKeepsInternalRunsSignificant() {
        var trim = new DiffEngine.DiffOptions(DiffEngine.WhitespaceMode.TRIM, true);
        assertTrue(DiffEngine.compute(List.of("  a b  "), List.of("a b"), trim).isEmpty());
        assertTrue(!DiffEngine.compute(List.of("a  b"), List.of("a b"), trim).isEmpty());
    }

    @Test
    void ignoreCaseChangesMatchingButRetainsBothOriginalLines() {
        var options = DiffEngine.DiffOptions.DEFAULT.withIgnoreCase(true);
        DiffModel model = DiffEngine.compute(List.of("Return VALUE;"), List.of("return value;"), options);

        assertTrue(model.isEmpty());
        assertEquals(RowType.EQUAL, model.rows().get(0).type());
        assertEquals("Return VALUE;", model.rows().get(0).left());
        assertEquals("return value;", model.rows().get(0).right());
    }

    @Test
    void smartAlignmentLeavesInsertedLineUnpairedAndMatchesRelatedLines() {
        List<String> left = List.of("start", "int alpha = loadAlpha();", "int beta = loadBeta();", "finish");
        List<String> right = List.of(
                "start", "log.debug(\"loading\");", "int alpha = readAlpha();", "int beta = readBeta();", "finish");

        DiffModel positional =
                DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT.withSmartAlignment(false));
        assertEquals("log.debug(\"loading\");", positional.rows().get(1).right());
        assertEquals("int alpha = loadAlpha();", positional.rows().get(1).left());

        DiffModel smart = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        assertEquals(RowType.ADDED, smart.rows().get(1).type());
        assertEquals("log.debug(\"loading\");", smart.rows().get(1).right());
        assertEquals(RowType.MODIFIED, smart.rows().get(2).type());
        assertEquals("int alpha = loadAlpha();", smart.rows().get(2).left());
        assertEquals("int alpha = readAlpha();", smart.rows().get(2).right());
        assertEquals("int beta = loadBeta();", smart.rows().get(3).left());
        assertEquals("int beta = readBeta();", smart.rows().get(3).right());
    }

    @Test
    void coarseFallbackRetainsCommonEdges() {
        DiffModel model = DiffEngine.computeCoarse("a\nold\nz\n", "a\nnew\nz\n");
        assertEquals(DiffModels.Quality.LINE_ONLY, model.quality());
        assertEquals(RowType.EQUAL, model.rows().get(0).type());
        assertEquals(RowType.MODIFIED, model.rows().get(1).type());
        assertEquals(RowType.EQUAL, model.rows().get(2).type());
    }
}
