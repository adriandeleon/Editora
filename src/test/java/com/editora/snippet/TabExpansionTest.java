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
}
