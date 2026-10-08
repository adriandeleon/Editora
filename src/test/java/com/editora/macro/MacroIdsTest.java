package com.editora.macro;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroIdsTest {

    /** An ASCII name slugs exactly as before ids were stored, so nothing about existing bindings changes. */
    @Test
    void asciiNamesSlugAsTheyAlwaysDid() {
        for (String name : List.of("Build", "my macro", "  Wrap (parens) ", "a--b", "x_y.z", "42")) {
            assertEquals(MacroIds.legacySlug(name), MacroIds.slug(name), name);
        }
        assertEquals("my-macro", MacroIds.slug("My Macro"));
        assertEquals("macro", MacroIds.slug("!!!"));
        assertEquals("macro", MacroIds.slug(null));
    }

    /** M8: every non-Latin name used to collapse to the one id {@code macro}. */
    @Test
    void namesInOtherScriptsGetDistinctStableIds() {
        String japanese = MacroIds.slug("日本語");
        String russian = MacroIds.slug("Сборка");
        assertNotEquals("macro", japanese);
        assertNotEquals(japanese, russian);
        assertEquals("u65e5-u672c-u8a9e", japanese);
        assertEquals(japanese, MacroIds.slug("日本語"), "stable");
        assertTrue(japanese.matches("[a-z0-9-]+"), japanese);
        assertEquals("cafe", MacroIds.slug("Café"), "an accent is dropped, not the letter");
        assertEquals("build-u65e5", MacroIds.slug("build 日"));
    }

    @Test
    void aTakenIdGetsASuffix() {
        Set<String> taken = new HashSet<>();
        for (String name : List.of("Build", "build", "BUILD")) {
            taken.add(MacroIds.forName(name, taken));
        }
        assertEquals(Set.of("build", "build-2", "build-3"), taken);
        assertEquals("macro", MacroIds.unique(" ", Set.of()));
    }
}
