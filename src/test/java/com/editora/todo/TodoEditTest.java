package com.editora.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TodoEditTest {

    private static TodoComment parse(String line, String keyword) {
        int s = line.indexOf(keyword);
        return TodoComment.parse(line, s, s + keyword.length());
    }

    private static int kwStart(String line, String keyword) {
        return line.indexOf(keyword);
    }

    @Test
    void setPriorityInsertsWhenAbsent() {
        String line = "// TODO [auth] fix it";
        assertEquals(
                "// TODO [auth] (high) fix it",
                TodoEdit.withPriority(line, kwStart(line, "TODO"), parse(line, "TODO"), "high"));
    }

    @Test
    void setPriorityReplacesExisting() {
        String line = "  // TODO (low) fix it";
        assertEquals(
                "  // TODO (critical) fix it",
                TodoEdit.withPriority(line, kwStart(line, "TODO"), parse(line, "TODO"), "critical"));
    }

    @Test
    void clearPriorityRemovesIt() {
        String line = "# FIXME (medium) do the thing";
        assertEquals(
                "# FIXME do the thing",
                TodoEdit.withPriority(line, kwStart(line, "FIXME"), parse(line, "FIXME"), null));
    }

    @Test
    void markDoneRewritesKeywordPreservingTagPriorityDesc() {
        String line = "// TODO [auth] (high) fix token refresh";
        assertEquals(
                "// DONE [auth] (high) fix token refresh",
                TodoEdit.withKeyword(line, kwStart(line, "TODO"), parse(line, "TODO"), "DONE"));
    }

    @Test
    void editDescriptionReplacesTrailingText() {
        String line = "// TODO [ui] (low) old text";
        assertEquals(
                "// TODO [ui] (low) new text here",
                TodoEdit.withDescription(line, kwStart(line, "TODO"), parse(line, "TODO"), "  new text here  "));
    }

    @Test
    void keywordPrefixIndentationAndCommentOpenerPreserved() {
        String line = "\t    #   XXX   hmm"; // odd spacing collapses to canonical form after the keyword
        assertEquals(
                "\t    #   XXX (high) hmm",
                TodoEdit.withPriority(line, kwStart(line, "XXX"), parse(line, "XXX"), "high"));
    }

    /**
     * A block comment's terminator is part of the comment, not of the TODO's text. It used to be swept into
     * the description, so editing the description re-emitted the line without it — leaving the block comment
     * unterminated, which silently commented out everything below it.
     */
    @Test
    void aBlockCommentTerminatorSurvivesEveryEdit() {
        String line = "/* TODO: x */";
        TodoComment p = TodoComment.parse(line, 3, 7);
        assertEquals("x", p.description(), "the */ is not description text");
        assertEquals("/* TODO handle nulls */", TodoEdit.withDescription(line, 3, p, "handle nulls"));
        assertEquals("/* DONE x */", TodoEdit.withKeyword(line, 3, p, "DONE"));
        assertEquals("/* TODO (high) x */", TodoEdit.withPriority(line, 3, p, "high"));
        assertEquals("/* TODO */", TodoEdit.withDescription(line, 3, p, ""), "clearing the text keeps the closer");
    }

    @Test
    void anHtmlCommentTerminatorSurvivesToo() {
        String line = "<!-- TODO [ui] (low) polish -->";
        TodoComment p = TodoComment.parse(line, 5, 9);
        assertEquals("polish", p.description());
        assertEquals("<!-- DONE [ui] (low) polish -->", TodoEdit.withKeyword(line, 5, p, "DONE"));
    }

    /**
     * A Markdown link is not a tag. Reading {@code [label]} as one and re-emitting canonically injected a
     * space — {@code [label] (url)} — which breaks the link. A TODO.md is exactly where this lives.
     */
    @Test
    void aMarkdownLinkIsNotSplitByAnEdit() {
        String line = "TODO [see the docs](https://example.com) for details";
        TodoComment p = TodoComment.parse(line, 0, 4);
        assertNull(p.tag(), "[label](url) is a link, not a tag");
        assertEquals("DONE [see the docs](https://example.com) for details", TodoEdit.withKeyword(line, 0, p, "DONE"));
    }

    /** A genuine [tag] — one not followed by "(" — still parses and is re-emitted. */
    @Test
    void arealTagStillWorks() {
        String line = "// TODO [auth] (high) token refresh";
        TodoComment p = TodoComment.parse(line, 3, 7);
        assertEquals("auth", p.tag());
        assertEquals("high", p.priority());
        assertEquals("// DONE [auth] (high) token refresh", TodoEdit.withKeyword(line, 3, p, "DONE"));
    }

    /** A block-comment terminator in the middle of the text is not a terminator; leave it in place. */
    @Test
    void aMidLineTerminatorIsLeftAlone() {
        String line = "// TODO handle the */ token here";
        TodoComment p = TodoComment.parse(line, 3, 7);
        assertEquals("handle the */ token here", p.description());
        assertEquals("// DONE handle the */ token here", TodoEdit.withKeyword(line, 3, p, "DONE"));
    }
}
