package com.editora.snippet;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which text before the caret is a trigger, and where Tab may expand one (N5, N15). */
class TabExpansionTest {

    private static Function<String, Snippet> triggers(String... names) {
        Map<String, Snippet> map = new java.util.HashMap<>();
        for (String n : names) {
            map.put(n, new Snippet(n, n, "BODY", "", "x"));
        }
        return map::get;
    }

    private static String found(String before, String... names) {
        TabExpansion.Match m = TabExpansion.find(before, triggers(names));
        return m == null ? null : before.substring(m.start()) + "@" + m.start();
    }

    @Test
    void theIdentifierRunAndTheWholeTokenAreBothTried() {
        assertEquals("fori@4", found("    fori", "fori"));
        assertEquals("fori@1", found("(fori", "fori"), "the identifier run inside a wider token");
        assertEquals("#inc@0", found("#inc", "#inc", "inc"), "the whole token wins over the run in it");
        assertEquals("!@2", found("  !", "!"));
        assertNull(found("fori ", "fori"), "nothing was typed after the blank");
        assertNull(found("xfori", "fori"), "a trigger is not found inside a longer word");
        assertNull(found("", "fori"));
    }

    @Test
    void aTriggerOfSeveralWordsIsReachable() {
        assertEquals("else if@2", found("} else if", "else if", "if"), "the longer trigger wins");
        assertEquals("bold and italic@4", found("say bold and italic", "bold and italic", "italic"));
        assertEquals("if@7", found("} else if", "if"));
        assertEquals("if@8", found("} else  if", "else if", "if"), "two blanks are not the trigger's one");
        assertEquals("class@5", found("enum\nclass", "enum class", "class").replace("\n", " "), "never across a line");
    }

    @Test
    void tabDoesNotExpandInDelimitedFilesOrInsideCommentsAndStrings() {
        assertTrue(TabExpansion.allowed("java", false, false));
        assertFalse(TabExpansion.allowed("java", false, true), "// see the main<Tab> is a sentence");
        assertTrue(TabExpansion.allowed("markdown", true, true), "prose has no code scopes to respect");
        assertFalse(TabExpansion.allowed("csv", false, false), "Tab separates fields in a .tsv");
        assertFalse(TabExpansion.allowed("csv", true, false));
    }

    private static Snippet snippet(String body) {
        return new Snippet("n", "p", body, "", "markdown");
    }

    @Test
    void aBlockSnippetExpandsInProseOnlyAtTheStartOfItsLine() {
        Snippet note = snippet("> [!NOTE]\n> ");
        assertTrue(TabExpansion.fitsInProse("note", 0, note));
        assertTrue(TabExpansion.fitsInProse("  > note", 4, note), "indentation and quote marks do not count");
        assertFalse(
                TabExpansion.fitsInProse("take a note", 7, note), "a word in a sentence is not a request for an alert");
        assertFalse(TabExpansion.fitsInProse("- tip", 2, snippet("> [!TIP]\n> ")), "nor in a list item");
        assertFalse(TabExpansion.fitsInProse("see h2", 4, snippet("## ${0}")));
        assertTrue(
                TabExpansion.fitsInProse("see the code", 8, snippet("`${1}` $0")), "an inline snippet fits anywhere");
    }

    @Test
    void bundledMarkdownSnippetsAreWellFormed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        SnippetManager m = new SnippetManager(new com.editora.config.ConfigManager(dir));
        assertEquals("<sub>${1}</sub>${0}", m.byPrefix("markdown", "sub").body());
        assertEquals("<sup>${1}</sup>${0}", m.byPrefix("markdown", "sup").body());
        assertTrue(m.byPrefix("markdown", "horizontal rule").body().startsWith("\n---\n"), "not a Setext underline");
        assertFalse(m.byPrefix("markdown", "codeblock").body().contains("language"));
        assertTrue(TabExpansion.blockLevel(m.byPrefix("markdown", "note").body()));
        assertTrue(TabExpansion.blockLevel(m.byPrefix("markdown", "table").body()));
        assertFalse(TabExpansion.blockLevel(m.byPrefix("markdown", "link").body()));
    }

    @Test
    void anInlineWordTriggerExpandsInProseOnlyAloneOnItsLine() {
        assertTrue(TabExpansion.inlineFitsInProse("code", 0, ""));
        assertTrue(TabExpansion.inlineFitsInProse("  link", 2, "  "), "indentation does not count");
        assertFalse(TabExpansion.inlineFitsInProse("see the code", 8, ""), "a sentence, then a Tab");
        assertFalse(TabExpansion.inlineFitsInProse("code", 0, " follows"), "text after the caret");
        assertFalse(TabExpansion.inlineFitsInProse("- link", 2, ""), "a list item is not an empty line");
        assertTrue(TabExpansion.inlineFitsInProse("bold and italic", 0, ""));
        assertTrue(
                TabExpansion.inlineFitsInProse("#inc", 1, ""), "glued to what precedes it: not a word of a sentence");
        assertTrue(TabExpansion.inlineFitsInProse("see ;sig", 4, " x"), "a trigger nobody types by accident");
        assertTrue(TabExpansion.wordTrigger("bold and italic"));
        assertFalse(TabExpansion.wordTrigger("h2"));
        assertFalse(TabExpansion.wordTrigger(""));
    }
}
