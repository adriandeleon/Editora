package com.editora.editor.fold;

import java.util.Collection;
import java.util.List;

import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.text.FoldRegions.Region;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.SegmentOps;
import org.fxmisc.richtext.model.StyledDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The line arithmetic that carries symbols and fold regions across a narrow/widen swap. */
class NarrowCarryTest {

    private static final StyledDocument<Collection<String>, String, Collection<String>> EMPTY = doc("");

    private static final String REGION = "a\nb\nc\nd";

    /** The document narrowed to lines 10–13: a class 2–30, a method 10–13 with an `if` 11–12, another 20–25. */
    private static NarrowCarry.Held held() {
        return new NarrowCarry.Held(
                "",
                "",
                EMPTY,
                doc(REGION),
                EMPTY,
                List.of(
                        new Symbol(2, "Demo", "class"),
                        new Symbol(10, "greet", "method"),
                        new Symbol(20, "count", "method")),
                List.of(new Region(11, 12), new Region(10, 13), new Region(20, 25), new Region(2, 30)),
                10,
                13);
    }

    private static StyledDocument<Collection<String>, String, Collection<String>> doc(String text) {
        return ReadOnlyStyledDocument.fromString(text, List.of(), List.of(), SegmentOps.styledTextOps());
    }

    @Test
    void narrowingKeepsWhatIsOnTheRegionsLinesRegionRelative() {
        assertEquals(List.of(new Symbol(0, "greet", "method")), NarrowCarry.narrowSymbols(held()));
        assertEquals(List.of(new Region(1, 2), new Region(0, 3)), NarrowCarry.narrowRegions(held()));
    }

    @Test
    void wideningAnUneditedRegionGivesBackExactlyWhatWasHeld() {
        List<Symbol> regionPass = List.of(new Symbol(0, "greet(String)", "method")); // the region seen alone
        assertEquals(held().symbols(), NarrowCarry.widenSymbols(held(), REGION, regionPass));
        assertEquals(held().regions(), NarrowCarry.widenRegions(held(), REGION, List.of(new Region(0, 3))));
    }

    @Test
    void wideningAnEditedRegionSplicesItsOwnBetweenTheHeldOnesMovedByTheLinesItGained() {
        String edited = "a\nb\nc\nx\ny\nd"; // two lines more
        List<Symbol> region = List.of(new Symbol(0, "greet", "method"), new Symbol(3, "x", "field"));
        assertEquals(
                List.of(
                        new Symbol(2, "Demo", "class"),
                        new Symbol(10, "greet", "method"),
                        new Symbol(13, "x", "field"),
                        new Symbol(22, "count", "method")),
                NarrowCarry.widenSymbols(held(), edited, region));
        assertEquals(
                List.of(new Region(11, 12), new Region(10, 15), new Region(20 + 2, 25 + 2), new Region(2, 32)),
                NarrowCarry.widenRegions(held(), edited, List.of(new Region(1, 2), new Region(0, 5))));
    }

    @Test
    void aBlockEndingInsideAnEditedRegionThatShrankEndsAtItsLastLine() {
        NarrowCarry.Held held = new NarrowCarry.Held(
                "", "", EMPTY, doc(REGION), EMPTY, List.of(), List.of(new Region(5, 12), new Region(12, 20)), 10, 13);
        // Lines 10–13 became one line (10): a block from 5 ending at 12 now ends on 10; one starting at 12 does
        // too, and ends 3 lines earlier.
        assertEquals(List.of(new Region(5, 10), new Region(10, 17)), NarrowCarry.widenRegions(held, "abcd", List.of()));
    }
}
