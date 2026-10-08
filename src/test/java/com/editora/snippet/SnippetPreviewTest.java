package com.editora.snippet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a snippet reads in the Insert Snippet picker (N19). */
class SnippetPreviewTest {

    @Test
    void theLabelDoesNotRepeatATriggerThatIsAlsoTheName() {
        assertEquals("fori", SnippetPreview.label(new Snippet("fori", "fori", "x", "", "java")));
        assertEquals(
                "sout — Print to standard out",
                SnippetPreview.label(new Snippet("Print to standard out", "sout", "x", "", "java")));
        assertEquals("Unnamed trigger", SnippetPreview.label(new Snippet("Unnamed trigger", "", "x", "", "java")));
    }

    @Test
    void theDetailShowsWhatTheSnippetInserts() {
        Snippet fori = new Snippet(
                "fori", "fori", "for (${1:int} ${2:i} = 0; $2 < ${3:max}; $2++) {\n\t$0\n}", "Indexed loop", "java");
        assertEquals("Indexed loop  ·  for (int i = 0; i < max; i++) { }", SnippetPreview.detail(fori));
        assertEquals(
                "for (int i = 0; i < max; i++) { }",
                SnippetPreview.detail(new Snippet("fori", "fori", fori.body(), "fori", "java")),
                "a description that only repeats the name is dropped");
        assertEquals(
                "Today: …-…-…",
                SnippetPreview.bodyLine("Today: ${CURRENT_YEAR}-${CURRENT_MONTH}-${CURRENT_DATE}"),
                "variables are not resolved for a preview");
        String longBody = SnippetPreview.bodyLine("x".repeat(300));
        assertTrue(longBody.length() == SnippetPreview.MAX_BODY && longBody.endsWith("…"));
        assertEquals("", SnippetPreview.bodyLine(null));
    }
}
