package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoldRegionsTest {

    private static boolean hasRegion(List<Region> regions, int start, int end) {
        return regions.contains(new Region(start, end));
    }

    @Test
    void emptyTextHasNoRegions() {
        assertTrue(FoldRegions.detect("", "java").isEmpty());
        assertTrue(FoldRegions.detect(null, "java").isEmpty());
    }

    @Test
    void plaintextNeverFolds() {
        assertTrue(FoldRegions.detect("{\n}\n", "plaintext").isEmpty());
    }

    @Test
    void bracesFoldFromOpenToCloseLine() {
        String java = "class A {\n    void m() {\n        x();\n    }\n}\n";
        List<Region> regions = FoldRegions.detect(java, "java");
        assertTrue(hasRegion(regions, 0, 4), "outer class braces");
        assertTrue(hasRegion(regions, 1, 3), "inner method braces");
    }

    @Test
    void bracesOnSameLineDoNotFold() {
        List<Region> regions = FoldRegions.detect("int[] a = {1, 2, 3};\n", "java");
        assertTrue(regions.isEmpty());
    }

    @Test
    void markwhenFoldsHeaderSectionsByLevel() {
        //  0: # Outer     1: 2023: a    2: ## Inner   3: 2024: b   4: # Sibling  5: 2025: c
        String mw = "# Outer\n2023: a\n## Inner\n2024: b\n# Sibling\n2025: c\n";
        List<Region> r = FoldRegions.detect(mw, "markwhen");
        assertTrue(hasRegion(r, 0, 3), "Outer spans to just before the next same-level header");
        assertTrue(hasRegion(r, 2, 3), "Inner spans its nested block");
        assertTrue(hasRegion(r, 4, 5), "Sibling spans to EOF");
    }

    @Test
    void markwhenTagDeclIsNotAFoldableHeader() {
        // "#Travel:" (no space after #) is a tag color, not a section — must not fold.
        assertTrue(FoldRegions.detect("#Travel: blue\n2023: a\n", "markwhen").isEmpty());
    }

    @Test
    void bracesInStringsAndCommentsAreIgnored() {
        String java = "String s = \"{\";\n// }\nint x = 1;\n";
        assertTrue(FoldRegions.detect(java, "java").isEmpty());
    }

    @Test
    void jsonBracketsFold() {
        String json = "{\n  \"a\": [\n    1,\n    2\n  ]\n}\n";
        List<Region> regions = FoldRegions.detect(json, "json");
        assertTrue(hasRegion(regions, 0, 5), "object braces");
        assertTrue(hasRegion(regions, 1, 4), "array brackets");
    }

    @Test
    void xmlFoldsMatchingElements() {
        String xml = "<root>\n  <child>\n    text\n  </child>\n</root>\n";
        List<Region> regions = FoldRegions.detect(xml, "xml");
        assertTrue(hasRegion(regions, 0, 4), "root element");
        assertTrue(hasRegion(regions, 1, 3), "child element");
    }

    @Test
    void xmlSelfClosingTagsDoNotFold() {
        String xml = "<root>\n  <item/>\n</root>\n";
        List<Region> regions = FoldRegions.detect(xml, "xml");
        assertTrue(hasRegion(regions, 0, 2));
        assertFalse(hasRegion(regions, 1, 1));
    }

    @Test
    void markdownHeadingsFoldTheirSections() {
        String md = "# Title\n\nintro\n\n## Section\n\nbody\n";
        List<Region> regions = FoldRegions.detect(md, "markdown");
        // "# Title" (level 1) encompasses the nested "## Section" down to the last non-blank line (6).
        assertTrue(hasRegion(regions, 0, 6), "top heading section");
        // "## Section" (line 4) folds its own subsection to the last non-blank line (6).
        assertTrue(hasRegion(regions, 4, 6), "subsection");
    }

    @Test
    void markdownFencedCodeBlocksFold() {
        String md = "text\n```\ncode line\nmore code\n```\n";
        List<Region> regions = FoldRegions.detect(md, "markdown");
        assertTrue(hasRegion(regions, 1, 4), "fenced code block");
    }

    // --- Block comments (#727) ---

    @Test
    void multiLineBlockCommentsFold() {
        String java = "int a;\n/*\n * doc\n */\nint b;\n";
        assertTrue(hasRegion(FoldRegions.blockComments(java, "java"), 1, 3), "the /* */ span folds");
    }

    @Test
    void singleLineBlockCommentsDoNotFold() {
        assertTrue(FoldRegions.blockComments("/* one line */\n", "java").isEmpty());
    }

    @Test
    void commentMarkersInsideStringsAreNotComments() {
        String java = "String s = \"/*\";\nint a;\nString e = \"*/\";\n";
        assertTrue(FoldRegions.blockComments(java, "java").isEmpty(), "quoted /* */ is content");
    }

    @Test
    void xmlCommentsFold() {
        String xml = "<a>\n<!-- one\n two -->\n</a>\n";
        assertTrue(hasRegion(FoldRegions.blockComments(xml, "xml"), 1, 2));
        assertTrue(FoldRegions.blockComments("<a><!-- inline --></a>\n", "xml").isEmpty());
    }

    @Test
    void blockCommentsNeedALanguageWithThem() {
        assertTrue(FoldRegions.blockComments("/*\nx\n*/\n", "python").isEmpty());
        assertTrue(FoldRegions.blockComments(null, "java").isEmpty());
        assertTrue(FoldRegions.blockComments("/*\nx\n*/\n", null).isEmpty());
    }

    // --- #region markers (#727) ---

    @Test
    void markerFamiliesPairUp() {
        // Every comment style's spelling, each on its own pair of lines.
        assertTrue(hasRegion(FoldRegions.markers("//#region a\nx\n//#endregion\n", "typescript"), 0, 2), "//#region");
        assertTrue(hasRegion(FoldRegions.markers("//region a\nx\n//endregion\n", "java"), 0, 2), "//region");
        assertTrue(hasRegion(FoldRegions.markers("#region a\nx\n#endregion\n", "csharp"), 0, 2), "#region");
        assertTrue(hasRegion(FoldRegions.markers("# region a\nx\n# endregion\n", "python"), 0, 2), "# region");
        assertTrue(
                hasRegion(FoldRegions.markers("#pragma region a\nx\n#pragma endregion\n", "c"), 0, 2),
                "#pragma region");
        assertTrue(
                hasRegion(FoldRegions.markers("<!-- #region a -->\nx\n<!-- #endregion -->\n", "html"), 0, 2),
                "<!-- #region -->");
        assertTrue(hasRegion(FoldRegions.markers("--region a\nx\n--endregion\n", "sql"), 0, 2), "--region");
    }

    @Test
    void markersNestByStack() {
        String src = "//#region outer\n//#region inner\nx\n//#endregion\n//#endregion\n";
        List<Region> m = FoldRegions.markers(src, "typescript");
        assertTrue(hasRegion(m, 1, 3), "inner pairs with the nearer end");
        assertTrue(hasRegion(m, 0, 4), "outer pairs with the farther end");
    }

    @Test
    void unmatchedMarkersAreIgnored() {
        assertTrue(FoldRegions.markers("//#region only\nx\n", "typescript").isEmpty());
        assertTrue(FoldRegions.markers("x\n//#endregion only\n", "typescript").isEmpty());
    }

    @Test
    void markersAreExcludedWhereTheyCollideOrSurprise() {
        // "# region" is a legal Markdown heading named "region" — the marker reading must lose there.
        assertTrue(FoldRegions.markers("# region\nx\n# endregion\n", "markdown").isEmpty());
        assertTrue(
                FoldRegions.markers("# region\nx\n# endregion\n", "plaintext").isEmpty());
    }

    @Test
    void indentedMarkersStillMatch() {
        assertTrue(hasRegion(FoldRegions.markers("    //#region a\nx\n    //#endregion\n", "java"), 0, 2));
    }

    // --- canonical order (#727: several detectors merge) ---

    @Test
    void canonicalOrderIsInnermostFirstAndDeduped() {
        // Inner closes on the same line as outer → inner (later start) first; duplicates collapse.
        List<Region> merged = List.of(new Region(0, 5), new Region(2, 5), new Region(0, 5), new Region(1, 3));
        assertEquals(List.of(new Region(1, 3), new Region(2, 5), new Region(0, 5)), FoldRegions.canonicalOrder(merged));
    }

    @Test
    void bracesNaturalOrderIsAlreadyCanonical() {
        // The convention canonicalOrder makes explicit is what braces() has always emitted —
        // foldRecursivelyAtCaret depends on it, so the two must agree.
        String java = "class A {\n  void m() {\n    x();\n  }\n}\n";
        List<Region> natural = FoldRegions.detect(java, "java");
        assertEquals(FoldRegions.canonicalOrder(natural), natural);
    }

    // --- Typst: heading sections as well as the brace pairs of its code mode ------------------------

    /**
     * Typst folded on braces only, so a document folded at {@code #align(center)[…]} but not at a single one
     * of its sections — the structure a reader navigates by.
     */
    @Test
    void typstFoldsHeadingSections() {
        String text = "= Introduction\nprose\nmore\n\n== A list\n- one\n- two\n\n= Second\ntail\n";
        List<FoldRegions.Region> regions = FoldRegions.detect(text, "typst");

        // "= Introduction" runs to the line before "= Second", with the blank line before it trimmed off.
        assertTrue(regions.contains(new FoldRegions.Region(0, 6)), "= Introduction should fold to line 7: " + regions);
        // "== A list" nests inside it and ends at the same place.
        assertTrue(regions.contains(new FoldRegions.Region(4, 6)), "== A list should fold: " + regions);
        // The last section runs to the end of the document.
        assertTrue(regions.contains(new FoldRegions.Region(8, 9)), "= Second should fold to EOF: " + regions);
    }

    /** The brace/bracket folding Typst already had must survive alongside the heading sections. */
    @Test
    void typstStillFoldsItsDelimiterPairs() {
        String text = "#align(center)[\n  hello\n]\n\n= Section\nbody\n";
        List<FoldRegions.Region> regions = FoldRegions.detect(text, "typst");
        assertTrue(regions.contains(new FoldRegions.Region(0, 2)), "the #align bracket pair should fold: " + regions);
        assertTrue(regions.contains(new FoldRegions.Region(4, 5)), "the heading should fold too: " + regions);
    }

    /** A one-line section has nothing to hide, so it must not offer a chevron. */
    @Test
    void typstDoesNotFoldASectionWithNoBody() {
        assertTrue(FoldRegions.detect("= One\n= Two\n", "typst").isEmpty());
    }

    /** Folding and the outline share one heading scan, so a raw block cannot fold as a section either. */
    @Test
    void typstIgnoresHeadingsInsideRawBlocks() {
        String text = "```\n= Not a heading\nstill code\n```\n";
        assertTrue(
                FoldRegions.detect(text, "typst").stream().noneMatch(r -> r.startLine() == 1),
                "a heading inside a raw block must not fold");
    }
}
