package com.editora.editor;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assertEquals(
                List.of("don't", "recieve", "the"),
                spans.stream().map(s -> word(line, s)).toList());
    }

    @Test
    void wordSpansHandlesPunctuationAndEmptyLines() {
        assertTrue(SpellChecker.wordSpans("").isEmpty());
        assertTrue(SpellChecker.wordSpans("12 + 34 = 46").isEmpty()); // no letters
        // Trailing apostrophe / quotes are trimmed.
        String q = "'quoted'";
        assertEquals(
                List.of("quoted"),
                SpellChecker.wordSpans(q).stream().map(s -> word(q, s)).toList());
    }

    /** Is the first letter run of {@code token} part of a structured (URL/path/identifier) token? */
    private static boolean structural(String token) {
        List<int[]> spans = SpellChecker.wordSpans(token);
        return !spans.isEmpty() && SpellChecker.partOfStructuredToken(token, spans.get(0)[0], spans.get(0)[1]);
    }

    @Test
    void structuredTokensAreSkipped() {
        assertTrue(structural("./mvnw")); // command / path
        assertTrue(structural("mvnw.cmd")); // dotted
        assertTrue(structural("javafx:run")); // colon-joined
        assertTrue(structural("https://github.com/adriandeleon/Editora")); // URL
        assertTrue(structural("user@example.com")); // e-mail
        assertTrue(structural("snake_case_name")); // underscore
        assertTrue(structural("path/to/file.txt")); // path
        assertTrue(structural("target/Editora-1.0.jar")); // path + version
    }

    @Test
    void plainWordsAreNotSkipped() {
        assertFalse(structural("hello"));
        assertFalse(structural("well-known")); // hyphenated word
        assertFalse(structural("state-of-the-art"));
        assertFalse(structural("don't")); // apostrophe
        assertFalse(structural("sentence.")); // trailing period trimmed
        assertFalse(structural("(word),")); // wrapping punctuation trimmed
        assertFalse(structural("\"quoted\"")); // quotes trimmed
        assertFalse(structural("word"));
    }

    @Test
    void structuredCheckIsContextAwareWithinALine() {
        String line = "see https://example.com or the mvnw script";
        for (int[] s : SpellChecker.wordSpans(line)) {
            String w = word(line, s);
            boolean st = SpellChecker.partOfStructuredToken(line, s[0], s[1]);
            if (w.equals("https") || w.equals("example") || w.equals("com")) {
                assertTrue(st, w + " should be structural (part of the URL)");
            }
            if (w.equals("see") || w.equals("or") || w.equals("the") || w.equals("mvnw") || w.equals("script")) {
                assertFalse(st, w + " should be a plain word");
            }
        }
    }

    @Test
    void typographicPunctuationDoesNotExemptWordsFromChecking() {
        // macOS/iOS auto-substitute "--"→"—" and "'"→"’". Those chars used to fail the letters-only token
        // test, marking the WHOLE token structural — so every word joined by them was silently unchecked.
        String em = "The results—surprising—showed up.";
        for (int[] s : SpellChecker.wordSpans(em)) {
            assertFalse(
                    SpellChecker.partOfStructuredToken(em, s[0], s[1]),
                    word(em, s) + " is prose joined by an em dash, not a structured token");
        }
        String smart = "The world’s problem";
        for (int[] s : SpellChecker.wordSpans(smart)) {
            assertFalse(
                    SpellChecker.partOfStructuredToken(smart, s[0], s[1]),
                    word(smart, s) + " is prose with a typographic apostrophe");
        }
        // …while real structured tokens are still skipped.
        assertTrue(structural("https://x/y"));
        assertTrue(structural("snake_case"));
    }

    @Test
    void nonBreakingSpaceSeparatesTokens() {
        // Character.isWhitespace(U+00A0) is false, so the token walk ran straight through an NBSP and merged
        // the words either side into one "structural" token — pervasive in pasted text and French typography.
        String line = "hello\u00A0world"; // a non-breaking space, not a regular one
        for (int[] s : SpellChecker.wordSpans(line)) {
            assertFalse(
                    SpellChecker.partOfStructuredToken(line, s[0], s[1]),
                    word(line, s) + " is a plain word (NBSP is a token break)");
        }
    }

    @Test
    void typographicApostropheStaysInsideTheWordAndNormalizes() {
        String line = "isn’t";
        List<int[]> spans = SpellChecker.wordSpans(line);
        assertEquals(1, spans.size(), "isn’t is ONE word, not isn + t");
        assertEquals("isn’t", word(line, spans.get(0)));
        // …and the lookup normalizes it to the ASCII form the dictionaries actually contain.
        assertEquals("isn't", SpellChecker.normalizeApostrophes("isn’t"));
        assertEquals("don't", SpellChecker.normalizeApostrophes("don‘t"));
        assertEquals("plain", SpellChecker.normalizeApostrophes("plain"));
    }

    @Test
    void skipsIdentifiersAcronymsNumbersAndShortWords() {
        assertTrue(SpellChecker.skip("a")); // too short
        assertTrue(SpellChecker.skip("getName")); // camelCase
        assertTrue(SpellChecker.skip("HttpClient")); // PascalCase
        assertTrue(SpellChecker.skip("HTTP")); // acronym
        assertTrue(SpellChecker.skip("md5")); // has a digit
        assertFalse(SpellChecker.skip("the")); // normal word
        assertFalse(SpellChecker.skip("Hello")); // capitalized prose word
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
        assertFalse(c.isMisspelled("editora")); // user word
        assertFalse(c.isMisspelled("getName")); // skipped (camelCase)

        c.ignore("zzx");
        assertFalse(c.isMisspelled("zzx")); // ignored this session

        assertTrue(c.suggest("teh").contains("the"));
    }

    @Test
    void typographicApostropheIsNotFlaggedAgainstTheRealDictionary() {
        // The .dic spells contractions with an ASCII "'", but editors auto-substitute U+2019 — without
        // normalization "don’t" would be reported misspelled.
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of());
        assertFalse(c.isMisspelled("don’t"), "don’t normalizes to don't");
        assertFalse(c.isMisspelled("isn’t"));
        assertTrue(c.isMisspelled("wrold’s"), "a genuine misspelling is still caught with a smart apostrophe");
    }

    @Test
    void personalDictionaryToggleGatesUserWords() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of("editora"));

        assertTrue(c.isUserWordsEnabled());
        assertFalse(c.isMisspelled("editora")); // honored by default

        c.setUserWordsEnabled(false);
        assertTrue(c.isMisspelled("editora")); // off → the user word is flagged again

        c.setUserWordsEnabled(true);
        assertFalse(c.isMisspelled("editora")); // back on → honored
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

    @Test
    void buildsBundledMexicanSpanish() {
        assertTrue(SpellDictionaries.available().contains("es_MX"), "es_MX should be registered");
        assumeTrue(SpellDictionaries.buildBlocking("es_MX").isPresent(), "es_MX dictionary should build");
        SpellChecker es = new SpellChecker("es_MX", Set.of());
        assertFalse(es.isMisspelled("hola"));
        assertTrue(es.isMisspelled("holaa"));
    }
}
