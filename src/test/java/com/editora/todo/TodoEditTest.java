package com.editora.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
