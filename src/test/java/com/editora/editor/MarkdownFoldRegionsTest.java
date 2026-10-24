package com.editora.editor;

import java.util.List;

import com.editora.editor.fold.FoldRegions;
import com.editora.editor.fold.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown folds on the document's real blocks: what the outline and the linter take for a fence, a comment
 * or a heading is what folds. (The fold scan used to read fences its own way — any line starting with three
 * backticks toggled — so a tilde fence did not fold and a {@code #} line in one became a foldable heading.)
 */
class MarkdownFoldRegionsTest {

    private static List<Region> folds(String md) {
        return FoldRegions.detect(md, "markdown");
    }

    private static Region r(int start, int end) {
        return new Region(start, end);
    }

    @Test
    void aTildeFenceFoldsAndAHashLineInItIsNotAHeading() {
        String md = "# Title\n\n~~~sh\n# a shell comment\necho hi\n~~~\n\ntext\n";
        List<Region> folds = folds(md);
        assertTrue(folds.contains(r(2, 5)), "the ~~~ fence: " + folds);
        assertTrue(folds.contains(r(0, 7)), "the section: " + folds);
        assertEquals(2, folds.size(), "and nothing for the comment line: " + folds);
    }

    @Test
    void aFenceInsideALongerFenceIsItsContent() {
        String md = "````md\n```\n# not a heading\n```\n````\n\nafter\n";
        assertEquals(List.of(r(0, 4)), folds(md));
    }

    @Test
    void anUnterminatedFenceFoldsToTheEnd() {
        assertEquals(List.of(r(1, 3)), folds("text\n```\ncode\nmore"));
    }

    @Test
    void frontMatterFolds() {
        List<Region> folds = folds("---\ntitle: x\ntags: [a]\n---\n\n# H\n\ntext\n");
        assertTrue(folds.contains(r(0, 3)), folds.toString());
        assertTrue(folds.contains(r(5, 7)), folds.toString());
        assertEquals(
                List.of(r(3, 5)),
                folds("intro\n\n---\nnot: front matter\n---\ntext\n"),
                "only at the very top; further down it is a rule, then a setext heading and its section");
    }

    @Test
    void aSetextHeadingOpensASectionLikeAnAtxOne() {
        String md = "Title\n=====\n\nintro\n\nSub\n---\n\nbody\n\n## Atx sub\n\nmore\n\nOther\n=====\n\nend\n";
        List<Region> folds = folds(md);
        assertTrue(folds.contains(r(0, 12)), "the H1 section ends before the next H1: " + folds);
        assertTrue(folds.contains(r(5, 8)), "the setext H2 section: " + folds);
        assertTrue(folds.contains(r(10, 12)), folds.toString());
        assertTrue(folds.contains(r(14, 17)), folds.toString());
    }

    @Test
    void aMultiLineCommentFoldsAndItsHeadingsOpenNothing() {
        String md = "# Real\n\n<!--\n# commented out\n\ntext\n-->\n\n<!-- one line -->\n\nend\n";
        List<Region> folds = folds(md);
        assertEquals(List.of(r(2, 6), r(0, 10)), folds);
    }

    @Test
    void headingsThatTheOutlineListsAreTheOnesThatFold() {
        String md = "   ## Indented three spaces\n\ntext\n\n#\n\nunder an empty heading\n\n    # indented code\n";
        List<Region> folds = folds(md);
        assertTrue(folds.contains(r(0, 2)), "up to three spaces of indent is still a heading: " + folds);
        assertTrue(folds.contains(r(4, 8)), "a bare # is an (empty) heading: " + folds);
        assertEquals(2, folds.size(), folds.toString());
    }

    @Test
    void blockQuotesAndMultiLineListItemsFold() {
        String text =
                "> one\n> two\n> three\n\n> single\n\n- a\n  wrapped\n- b\n  - b1\n    more\n  - b2\n- c\n\n1. x\n\n   para\n\n2. y\n\ntext\n";
        var regions = FoldRegions.detect(text, "markdown");
        assertTrue(regions.contains(new FoldRegions.Region(0, 2)), regions.toString());
        assertFalse(regions.stream().anyMatch(r -> r.startLine() == 4), "a one-line quote has nothing to fold");
        assertTrue(regions.contains(new FoldRegions.Region(6, 7)), regions.toString());
        assertTrue(regions.contains(new FoldRegions.Region(8, 11)), regions.toString());
        assertTrue(regions.contains(new FoldRegions.Region(9, 10)), regions.toString());
        assertFalse(regions.stream().anyMatch(r -> r.startLine() == 11 || r.startLine() == 12), regions.toString());
        assertTrue(regions.contains(new FoldRegions.Region(14, 16)), "trailing blank lines stay out: " + regions);
        assertFalse(regions.stream().anyMatch(r -> r.startLine() == 18), regions.toString());
    }

    @Test
    void listLookalikesInCodeDoNotFold() {
        String text = "```\n- a\n  b\n> q\n> r\n```\n";
        assertEquals(java.util.List.of(new FoldRegions.Region(0, 5)), FoldRegions.detect(text, "markdown"));
    }
}
