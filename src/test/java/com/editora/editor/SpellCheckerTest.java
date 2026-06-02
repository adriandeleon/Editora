package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pure word-splitting / skip rules, plus spelling against the bundled en_US dictionary (built headless
 * via Lucene Hunspell — no JavaFX toolkit needed).
 */
class SpellCheckerTest {

    // --- pure word splitting ---

    private static String word(String line, int[] span) {
        return line.substring(span[0], span[1]);
    }

    @Test
    void wordSpansSplitsLettersKeepingApostrophes() {
        String line = "  don't  recieve, the  ";
        List<int[]> spans = SpellChecker.wordSpans(line);
        assertEquals(List.of("don't", "recieve", "the"), spans.stream().map(s -> word(line, s)).toList());
    }

    @Test
    void wordSpansHandlesPunctuationAndEmptyLines() {
        assertTrue(SpellChecker.wordSpans("").isEmpty());
        assertTrue(SpellChecker.wordSpans("12 + 34 = 46").isEmpty()); // no letters
        // Trailing apostrophe / quotes are trimmed.
        String q = "'quoted'";
        assertEquals(List.of("quoted"), SpellChecker.wordSpans(q).stream().map(s -> word(q, s)).toList());
    }

    @Test
    void skipsIdentifiersAcronymsNumbersAndShortWords() {
        assertTrue(SpellChecker.skip("a"));        // too short
        assertTrue(SpellChecker.skip("getName"));  // camelCase
        assertTrue(SpellChecker.skip("HttpClient")); // PascalCase
        assertTrue(SpellChecker.skip("HTTP"));     // acronym
        assertTrue(SpellChecker.skip("md5"));      // has a digit
        assertFalse(SpellChecker.skip("the"));     // normal word
        assertFalse(SpellChecker.skip("Hello"));   // capitalized prose word
    }

    // --- spelling against the bundled dictionary ---

    @Test
    void flagsMisspellingsAgainstBundledEnUs() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of("editora", "hunspell"));

        assertTrue(c.isMisspelled("teh"));
        assertTrue(c.isMisspelled("recieve"));
        assertFalse(c.isMisspelled("the"));
        assertFalse(c.isMisspelled("receive"));
        assertFalse(c.isMisspelled("editora"));   // user word
        assertFalse(c.isMisspelled("getName"));   // skipped (camelCase)

        c.ignore("zzx");
        assertFalse(c.isMisspelled("zzx"));        // ignored this session

        assertTrue(c.suggest("teh").contains("the"));
    }

    @Test
    void buildsBundledSpanishAndFrench() {
        assumeTrue(SpellDictionaries.buildBlocking("es").isPresent(), "es dictionary should build");
        SpellChecker es = new SpellChecker("es", Set.of());
        assertFalse(es.isMisspelled("hola"));
        assertTrue(es.isMisspelled("holaa"));

        assumeTrue(SpellDictionaries.buildBlocking("fr").isPresent(), "fr dictionary should build");
        SpellChecker fr = new SpellChecker("fr", Set.of());
        assertFalse(fr.isMisspelled("bonjour"));
        assertTrue(fr.isMisspelled("bonjouur"));
    }
}
