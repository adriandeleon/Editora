package com.editora.typst;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TypstOutline} — the heading scan behind both Typst folding and the Structure outline.
 *
 * <p>Its whole job is telling a section marker apart from the many other uses of {@code =} in a Typst
 * document: an assignment, an equality inside math, a rule of {@code =} characters. Each of those is a case
 * here, because a false positive puts a fold and an outline entry somewhere the reader cannot use them.
 */
class TypstOutlineTest {

    private static List<String> titles(String text) {
        return TypstOutline.headings(text).stream()
                .map(TypstOutline.Heading::title)
                .toList();
    }

    @Test
    void readsLevelsFromTheMarkerRun() {
        List<TypstOutline.Heading> h = TypstOutline.headings("= One\ntext\n== Two\n=== Three\n");
        assertEquals(3, h.size());
        assertEquals(1, h.get(0).level());
        assertEquals("One", h.get(0).title());
        assertEquals(0, h.get(0).line());
        assertEquals(2, h.get(1).level());
        assertEquals(2, h.get(1).line());
        assertEquals(3, h.get(2).level());
    }

    @Test
    void aMarkerRunMustBeFollowedBySpace() {
        // A rule of '=' characters (and a bare '=', a plausible fragment of half-typed text) is not a section.
        assertTrue(titles("=====\n").isEmpty());
        assertTrue(titles("=\n").isEmpty());
        assertTrue(titles("=Tight\n").isEmpty(), "Typst itself requires the space");
    }

    @Test
    void sevenMarkersIsNotAHeading() {
        assertEquals(List.of("Six"), titles("====== Six\n"));
        assertTrue(titles("======= Seven\n").isEmpty(), "Typst tops out at six levels");
    }

    @Test
    void anAssignmentIsNotAHeading() {
        // The '=' of a binding always follows a name, so the line-start rule excludes code without a parser.
        assertTrue(titles("#let x = 1\n#set page(margin: 2cm)\n").isEmpty());
    }

    @Test
    void mathIsNotAHeading() {
        assertTrue(titles("Inline $a^2 + b^2 = c^2$ renders.\n$ sum_(k=1)^n k = n / 2 $\n")
                .isEmpty());
    }

    @Test
    void headingsInsideARawBlockAreSkipped() {
        String text = "= Real\n\n```\n= Not a heading\n```\n\n= Also real\n";
        assertEquals(List.of("Real", "Also real"), titles(text));
    }

    @Test
    void aLongerClosingRunStillClosesTheRawBlock() {
        assertEquals(List.of("After"), titles("```\n= hidden\n````\n= After\n"));
    }

    @Test
    void headingsInsideBlockCommentsAreSkipped() {
        assertEquals(List.of("Real"), titles("/*\n= Commented\n*/\n= Real\n"));
    }

    @Test
    void blockCommentsNest() {
        // Typst nests /* */, so an inner close must not re-open the document early.
        String text = "/*\n= a\n/*\n= b\n*/\n= c\n*/\n= Real\n";
        assertEquals(List.of("Real"), titles(text));
    }

    @Test
    void aCommentOpenedAndClosedOnOneLineDoesNotSwallowTheRest() {
        assertEquals(List.of("Real"), titles("/* aside */\n= Real\n"));
    }

    @Test
    void aLineCommentCannotOpenABlock() {
        // "// /*" is inside a line comment, so it must not put the scan into a block-comment state.
        assertEquals(List.of("Real"), titles("// /* not opened\n= Real\n"));
    }

    @Test
    void indentedHeadingsCount() {
        assertEquals(List.of("Indented"), titles("  == Indented\n"));
    }

    @Test
    void titleMarkersAndSurroundingSpaceAreStripped() {
        assertEquals(List.of("Spaced Out"), titles("==   Spaced Out   \n"));
    }

    @Test
    void carriageReturnsAreToleratedAndKeptOutOfTheTitle() {
        assertEquals(List.of("Windows"), titles("= Windows\r\n"));
    }

    @Test
    void emptyAndNullInputAreEmptyOutlines() {
        assertTrue(TypstOutline.headings(null).isEmpty());
        assertTrue(TypstOutline.headings("").isEmpty());
    }

    @Test
    void levelIsUsableOnItsOwn() {
        assertEquals(0, TypstOutline.level(null));
        assertEquals(0, TypstOutline.level("plain text"));
        assertEquals(1, TypstOutline.level("= x"));
        assertEquals(TypstOutline.MAX_LEVEL, TypstOutline.level("====== x"));
    }

    // --- #let / #show bindings ----------------------------------------------------------------------

    private static List<String> bindingNames(String text) {
        return TypstOutline.bindings(text).stream()
                .map(TypstOutline.Binding::name)
                .toList();
    }

    @Test
    void readsTopLevelLetAndShowBindings() {
        List<TypstOutline.Binding> b = TypstOutline.bindings("#let title = \"x\"\n#show heading: it => it\n");
        assertEquals(2, b.size());
        assertEquals("let", b.get(0).kind());
        assertEquals("title", b.get(0).name());
        assertEquals(0, b.get(0).line());
        assertEquals("show", b.get(1).kind());
        assertEquals("heading", b.get(1).name());
    }

    @Test
    void setIsNotABinding() {
        // #set configures the document rather than defining anything; a run of them tops most files.
        assertTrue(
                bindingNames("#set page(margin: 2cm)\n#set text(size: 11pt)\n").isEmpty());
    }

    @Test
    void anIndentedBindingIsLocalAndNotListed() {
        // Inside a code block or a function body — a name that means nothing outside it.
        assertTrue(bindingNames("#let outer = {\n  let inner = 1\n  #let nested = 2\n}\n")
                        .isEmpty()
                || bindingNames("  #let nested = 2\n").isEmpty());
        assertTrue(bindingNames("  #let nested = 2\n").isEmpty());
    }

    @Test
    void aWordStartingWithLetIsNotABinding() {
        assertTrue(bindingNames("#letter\n").isEmpty(), "the keyword needs a space after it");
    }

    @Test
    void destructuringAndBareShowNameNothingSoAreSkipped() {
        assertTrue(bindingNames("#let (a, b) = pair\n").isEmpty());
        assertTrue(bindingNames("#show: template\n").isEmpty());
    }

    @Test
    void bindingNamesMayCarryHyphensAndUnderscores() {
        assertEquals(List.of("my-style_2"), bindingNames("#let my-style_2 = 1\n"));
    }

    @Test
    void bindingsInsideRawBlocksAndCommentsAreSkipped() {
        assertTrue(bindingNames("```\n#let hidden = 1\n```\n").isEmpty());
        assertTrue(bindingNames("/*\n#let hidden = 1\n*/\n").isEmpty());
    }

    // --- raw blocks ---------------------------------------------------------------------------------

    @Test
    void reportsRawBlockSpansIncludingTheFenceLines() {
        List<TypstOutline.RawBlock> raw = TypstOutline.rawBlocks("a\n```\ncode\n```\nb\n");
        assertEquals(1, raw.size());
        assertEquals(1, raw.get(0).startLine());
        assertEquals(3, raw.get(0).endLine());
    }

    @Test
    void anUnterminatedFenceRunsToTheEndOfTheDocument() {
        // What the editor shows, so it is what folding should offer.
        List<TypstOutline.RawBlock> raw = TypstOutline.rawBlocks("```\nstill code\nand more\n");
        assertEquals(1, raw.size());
        assertEquals(0, raw.get(0).startLine());
        assertEquals(3, raw.get(0).endLine());
    }

    @Test
    void oneScanAnswersAllThree() {
        var o = TypstOutline.scan("= H\n#let x = 1\n```\nc\n```\n");
        assertEquals(1, o.headings().size());
        assertEquals(1, o.bindings().size());
        assertEquals(1, o.rawBlocks().size());
    }
}
