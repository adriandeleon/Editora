package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where {@link AgentStreamSplit} lets a streaming reply be cut into "final" and "may still change". */
class AgentStreamSplitTest {

    private static String settled(String markdown) {
        return markdown.substring(0, AgentStreamSplit.settledEnd(markdown, 0));
    }

    @Test
    void aParagraphFollowedByAnotherIsSettled() {
        assertEquals("First paragraph.\n\n", settled("First paragraph.\n\nSecond one,\nstill arriving"));
    }

    @Test
    void nothingIsSettledWithinASingleBlock() {
        assertEquals("", settled("Only one paragraph\nso far"));
        assertEquals("", settled(""));
    }

    /** The line being received could still become a list item or a fence: wait for its newline. */
    @Test
    void anUnfinishedLastLineIsNotABoundary() {
        assertEquals("", settled("Intro.\n\n1"));
        assertEquals("Intro.\n\n", settled("Intro.\n\n1 is a number\n"));
    }

    @Test
    void theLastBoundaryWins() {
        assertEquals("A.\n\nB.\n\n", settled("A.\n\nB.\n\nC.\n"));
    }

    /** A blank line inside a code fence is part of the code, not a block boundary. */
    @Test
    void blankLinesInsideAFenceDoNotSplit() {
        String md = "Intro.\n\n```java\nclass A {\n\nint f;\n\n}\n";
        assertEquals("Intro.\n\n", settled(md), "the fence is still open");

        String closed = md + "```\n\nAfter.\n";
        assertEquals(md + "```\n\n", settled(closed));
    }

    @Test
    void aFenceIsOnlyClosedByItsOwnMarker() {
        String md = "````\n```\n\nstill code\n\n````\n\nDone.\n";
        assertEquals("````\n```\n\nstill code\n\n````\n\n", settled(md));

        String tilde = "~~~\n```\n\nstill code\n";
        assertEquals("", settled(tilde));
    }

    /** Items of one list (and its numbering) must stay in one render. */
    @Test
    void aLooseListIsNotSplitBetweenItems() {
        assertEquals("Intro.\n\n", settled("Intro.\n\n- one\n\n- two\n\n- three\n"));
        assertEquals("Intro.\n\n", settled("Intro.\n\n1. one\n\n2. two\n"));
        assertEquals("Intro.\n\n", settled("Intro.\n\n1) one\n\n2) two\n"));
    }

    @Test
    void textAfterAListIsANewBlock() {
        assertEquals("- one\n- two\n\n", settled("- one\n- two\n\nAnd then prose.\n"));
    }

    /** An indented line after a blank one continues a list item, or is indented code. */
    @Test
    void indentedContinuationsAreNotBoundaries() {
        assertEquals("", settled("- item\n\n  continued paragraph\n"));
        assertEquals("", settled("Para.\n\n    indented code\n"));
    }

    @Test
    void quotesAndHtmlAreLeftWhole() {
        assertEquals("", settled("> quoted\n\n> more quote\n"));
        assertEquals("", settled("<details>\n\n<summary>x</summary>\n"));
    }

    /** The first item of a list, or the first line of a quote, does start a new block. */
    @Test
    void aListOrQuoteAfterProseIsABoundary() {
        assertEquals("Intro.\n\n", settled("Intro.\n\n- one\n- two\n"));
        assertEquals("Intro.\n\n", settled("Intro.\n\n> quoted\n"));
        assertEquals("- one\n\n", settled("- one\n\n> quoted\n\n> more\n"), "a quote after a list");
    }

    /** A lazy continuation line (unindented, no blank before it) leaves the list a list. */
    @Test
    void aLazyContinuationDoesNotEndTheList() {
        assertEquals("", settled("- one\nstill item one\n\n- two\n"));
    }

    @Test
    void emphasisAndRulesAtLineStartAreNotMistakenForBullets() {
        assertEquals("A.\n\n", settled("A.\n\n**Bold** start.\n"));
        assertEquals("A.\n\n", settled("A.\n\n---\n"));
    }

    @Test
    void headingsStartBlocks() {
        assertEquals("# One\n\ntext\n\n", settled("# One\n\ntext\n\n## Two\n"));
    }

    /** The second argument resumes the scan where the previous one stopped. */
    @Test
    void scanningResumesFromAnEarlierBoundary() {
        String md = "A.\n\nB.\n\n```\ncode\n\nmore\n";
        int first = AgentStreamSplit.settledEnd(md, 0);
        assertEquals("A.\n\nB.\n\n", md.substring(0, first));
        assertEquals(first, AgentStreamSplit.settledEnd(md, first), "nothing further is settled yet");

        String grown = md + "```\n\nC.\n";
        assertEquals(grown.length() - "C.\n".length(), AgentStreamSplit.settledEnd(grown, first));
    }
}
