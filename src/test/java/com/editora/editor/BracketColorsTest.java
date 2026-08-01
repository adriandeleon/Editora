package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the pure bracket-depth pass behind bracket-pair colorization (no toolkit). */
class BracketColorsTest {

    private static final int C = BracketColors.COLORS;

    /** "offset:code" per mark, so a failure reads as positions rather than as arrays. */
    private static List<String> marks(BracketColors.Analysis a) {
        List<String> out = new ArrayList<>();
        for (int[] m : a.marks()) {
            out.add(m[0] + ":" + m[1]);
        }
        return out;
    }

    private static BracketColors.Analysis analyze(String text) {
        return BracketColors.analyze(text, 0, 0, List.of(), C);
    }

    @Test
    void nestingIncreasesDepthAndClosersMatchTheirOpener() {
        //                       0123456789
        BracketColors.Analysis a = analyze("a(b[c]d)e");
        assertEquals(List.of("1:0", "3:1", "5:1", "7:0"), marks(a), "the pair at each level shares a depth");
    }

    @Test
    void siblingsAtTheSameLevelShareADepth() {
        assertEquals(List.of("0:0", "1:0", "2:0", "3:0"), marks(analyze("()()")));
    }

    @Test
    void allThreeKindsShareOneDepthCounter() {
        // VS Code's default independentColorPoolPerBracketType: false.
        assertEquals(List.of("0:0", "1:1", "2:2", "3:2", "4:1", "5:0"), marks(analyze("([{}])")));
    }

    @Test
    void depthCyclesAtTheColorCount() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < C + 2; i++) {
            deep.append('(');
        }
        List<String> m = marks(BracketColors.analyze(deep.toString(), 0, 0, List.of(), C));
        assertEquals(C + 2, m.size());
        assertEquals("0:0", m.get(0));
        assertEquals(C + ":0", m.get(C), "depth " + C + " wraps back to the first color");
        assertEquals((C + 1) + ":1", m.get(C + 1));
    }

    @Test
    void aCloserWithNothingOpenIsUnmatched() {
        assertEquals(List.of("0:" + BracketColors.UNMATCHED), marks(analyze(")")));
        // ...and it does not drag the depth negative for what follows.
        assertEquals(List.of("0:" + BracketColors.UNMATCHED, "1:0", "2:0"), marks(analyze(")()")));
    }

    @Test
    void bracketsInsideSkippedRangesAreIgnoredEntirelyIncludingTheirDepthEffect() {
        // A "{" in a string must not shift the color of every bracket below it — the whole reason the
        // token spans are consulted here when BraceMatcher cannot.
        String text = "(\"{\")";
        List<int[]> skip = List.of(new int[] {1, 4}); // the "{" string literal, quotes included
        BracketColors.Analysis a = BracketColors.analyze(text, 0, 0, skip, C);
        assertEquals(List.of("0:0", "4:0"), marks(a), "the pair stays at depth 0; the quoted brace is not a bracket");
    }

    @Test
    void skipRangesAreConsumedInOrderAcrossLines() {
        String text = "(\n// )\n)"; // 0='(' 1='\n' 2..5='// )' 6='\n' 7=')'
        List<int[]> skip = List.of(new int[] {2, 6}); // the "// )" comment run
        assertEquals(List.of("0:0", "7:0"), marks(BracketColors.analyze(text, 0, 0, skip, C)));
    }

    @Test
    void startDepthContinuesAnEarlierPass() {
        // The incremental case: re-highlighting from a line already two levels deep.
        assertEquals(List.of("0:2", "1:2"), marks(BracketColors.analyze("()", 0, 2, List.of(), C)));
        // A closer at the carried depth returns to the level below, not to unmatched.
        assertEquals(List.of("0:1"), marks(BracketColors.analyze(")", 0, 2, List.of(), C)));
    }

    @Test
    void marksAreRelativeToFromNotToTheDocument() {
        // They have to line up with the token spans, which start at `from`.
        BracketColors.Analysis a = BracketColors.analyze("xxxx(y)", 4, 0, List.of(), C);
        assertEquals(List.of("0:0", "2:0"), marks(a), "offsets are measured from `from`");
    }

    @Test
    void oneLineEndDepthPerLineMatchingTheTokenizersLineAccounting() {
        // Must equal the number of entries TextMateHighlighter.analyzeFrom produces, or the two lists
        // splice out of step and the next incremental pass starts from another line's depth.
        assertEquals(List.of(0), BracketColors.analyze("", 0, 0, List.of(), C).lineEndDepths());
        assertEquals(
                List.of(0), BracketColors.analyze("abc", 0, 0, List.of(), C).lineEndDepths());
        assertEquals(
                List.of(0, 0), BracketColors.analyze("a\n", 0, 0, List.of(), C).lineEndDepths());
        assertEquals(
                List.of(0, 0, 0),
                BracketColors.analyze("a\nb\n", 0, 0, List.of(), C).lineEndDepths());
        // Depth carried across lines is what the next pass resumes from.
        assertEquals(
                List.of(1, 2, 0),
                BracketColors.analyze("{\n{\n}}", 0, 0, List.of(), C).lineEndDepths());
    }

    @Test
    void lineEndDepthsStartFromFromsLineOnly() {
        // from is a line start; the entries cover that line to the last, like the tokenizer's end-states.
        assertEquals(
                List.of(2, 0),
                BracketColors.analyze("{\n{\n}}", 2, 1, List.of(), C).lineEndDepths());
    }

    @Test
    void emptyAndNullTextAreSafe() {
        assertTrue(BracketColors.analyze(null, 0, 0, List.of(), C).marks().isEmpty());
        assertTrue(
                BracketColors.analyze(null, 0, 0, List.of(), C).lineEndDepths().isEmpty());
        assertTrue(analyze("no brackets here").marks().isEmpty());
    }

    @Test
    void aNegativeOrZeroColorCountCannotDivideByZero() {
        assertEquals(List.of("0:0"), marks(BracketColors.analyze("(", 0, 0, List.of(), 0)));
    }

    @Test
    void classNames() {
        assertEquals("bracket-depth-0", BracketColors.classFor(0));
        assertEquals("bracket-depth-5", BracketColors.classFor(5));
        assertEquals("bracket-unmatched", BracketColors.classFor(BracketColors.UNMATCHED));
    }

    @Test
    void spansCoverTheWholeRangeExactlyOrAreNull() {
        // RichTextFX requires the spans to cover their range exactly; a short or long build would throw
        // on apply, so the total length is the contract worth pinning.
        var spans = BracketColors.buildSpans(9, analyze("a(b[c]d)e").marks());
        assertEquals(9, spans.length());
        assertNull(BracketColors.buildSpans(0, List.of()), "nothing to color → no overlay");
        assertNull(BracketColors.buildSpans(10, List.of()));
    }

    @Test
    void aMarkPastTheRangeIsDroppedRatherThanBreakingTheCover() {
        var spans = BracketColors.buildSpans(3, List.of(new int[] {0, 0}, new int[] {99, 1}));
        assertEquals(3, spans.length());
    }

    private static int spanCount(org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> spans) {
        int n = 0;
        for (var ignored : spans) {
            n++;
        }
        return n;
    }

    @Test
    void adjacentBracketsOfTheSameDepthMergeIntoOneSpan() {
        // A Text node per span is the cost that matters, so the empty pairs that pepper real code must not
        // each cost two of them. Depth still separates what genuinely differs.
        assertEquals(1, spanCount(BracketColors.buildSpans(2, analyze("()").marks())), "() is one span");
        assertEquals(2, spanCount(BracketColors.buildSpans(2, analyze("((").marks())), "different depths stay apart");
        // A merged run must still cover exactly its range, or the apply throws.
        assertEquals(4, BracketColors.buildSpans(4, analyze("{}{}").marks()).length());
        assertEquals(
                9, BracketColors.buildSpans(9, analyze("a(b[c]d)e").marks()).length());
    }

    @Test
    void everyDepthClassTheCycleCanProduceExistsInTheStylesheet() throws Exception {
        // COLORS and syntax.css have to agree: a depth with no rule renders as unstyled text, which
        // looks like colorization silently not working for deeply nested code.
        String css = new String(
                getClass().getResourceAsStream("/com/editora/styles/syntax.css").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < BracketColors.COLORS; i++) {
            assertTrue(css.contains(".text." + BracketColors.classFor(i) + " "), "syntax.css defines depth " + i);
        }
        assertTrue(css.contains(".text.bracket-unmatched "), "syntax.css defines the unmatched class");
    }
}
