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

    // --- one pass over a line (S1, S3, S6, S13) ---

    private static List<String> checkable(String line, SpellChecker.Syntax syntax) {
        return SpellChecker.checkableWords(line, syntax).stream()
                .map(s -> word(line, s))
                .toList();
    }

    private static List<String> checkable(String line) {
        return checkable(line, SpellChecker.Syntax.PLAIN);
    }

    @Test
    void checkableWordsLeavesOutEveryWordOfAStructuredToken() {
        assertEquals(
                List.of("see", "or", "the", "mvnw", "script"), checkable("see https://example.com or the mvnw script"));
        assertEquals(List.of("run", "then", "open"), checkable("run ./mvnw javafx:run then open target/app-1.0.jar"));
        assertEquals(List.of("well", "known", "don't", "word"), checkable("well-known don't (word)."));
    }

    @Test
    void invertedMarksAndLowQuotesAreWrappingPunctuation() {
        // "¿Qeu pasa? ¡Holaa!" flagged nothing: ¿ and ¡ were not edge punctuation, so the first word of every
        // Spanish question or exclamation sat in a "structured" token.
        assertEquals(List.of("Qeu", "pasa", "Holaa"), checkable("¿Qeu pasa? ¡Holaa!"));
        assertEquals(List.of("Wortt", "mott", "parola"), checkable("„Wortt“ ‚mott‘ ‹parola›"));
    }

    @Test
    void markdownEmphasisMarkersAndAMissingSpaceAfterACommaDoNotHideWords() {
        assertEquals(
                List.of("an", "emphasised", "mispeled", "word", "and", "boldd", "and", "strikke", "and", "okk"),
                checkable("an _emphasised mispeled_ word and __boldd__ and ~~strikke~~ and *okk*"));
        assertEquals(List.of("recieve", "seperate"), checkable("recieve,seperate"));
        // …while an underscore or a tilde inside the token still makes it an identifier or a path.
        assertTrue(checkable("snake_case ~/notes __init__.py").isEmpty());
    }

    @Test
    void optionFlagsAndDocumentationTagNamesAreNotProse() {
        assertEquals(List.of("grep", "foo"), checkable("grep -rn --colour 'foo'"));
        assertEquals(List.of("the", "user", "name"), checkable("@param nme the user name"));
        assertEquals(List.of("the", "user", "name"), checkable(" * @param {string} nme the user name"));
        assertEquals(List.of("see", "and", "too"), checkable("see {@link Fooo} and {@code codde} too"));
        assertEquals(List.of("when", "it", "breaks"), checkable("@throws Excption when it breaks"));
    }

    @Test
    void htmlTextBetweenTagsIsItsOwnToken() {
        String line = "<p>Paragraf with <b>boldd</b> text &amp; more</p>";
        // Plain tokenization glues the text to the tag (">Paragraf" trims to "p>Paragraf": structured).
        assertFalse(checkable(line).contains("Paragraf"));
        assertFalse(checkable(line).contains("boldd"));
        List<String> html = checkable(line, SpellChecker.Syntax.HTML);
        assertTrue(html.containsAll(List.of("Paragraf", "with", "boldd", "text", "more")), html.toString());
        assertFalse(html.contains("amp"), "an entity is not a word");
        assertFalse(
                checkable("<a href=\"/abuot\">Abuot</a>", SpellChecker.Syntax.HTML)
                        .contains("abuot"),
                "an attribute value with a path in it stays structured");
    }

    @Test
    void typstLabelsAndReferencesAreNotProse() {
        assertEquals(
                List.of("See", "the", "figur", "and", "emph"),
                checkable("See the figur @fig-one <fig-one> and _emph_", SpellChecker.Syntax.TYPST));
    }

    @Test
    void aTokenTooLongToBeAWordIsSkippedWhole() {
        String blob = "x".repeat(SpellChecker.MAX_TOKEN + 1);
        assertTrue(checkable(blob + "abc").isEmpty());
        assertEquals(List.of("after"), checkable(blob + " after"));
    }

    /**
     * The 430 ms keystroke: a 60 KB line with no whitespace (minified JSON). Each of its misspelled words used
     * to rescan the whole token, so the cost grew with words × token length. One pass is linear; this bound
     * is loose enough for a loaded CI machine and still two orders of magnitude under the old cost.
     */
    @Test
    void aSixtyKilobyteLineWithNoWhitespaceIsScannedInLinearTime() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; sb.length() < 60_000; i++) {
            sb.append("\"kee").append(i).append("\":\"valu wrold recieve\",".replace(' ', '_'));
        }
        String line = sb.append('}').toString();
        SpellChecker.checkableWords(line, SpellChecker.Syntax.PLAIN); // warm up
        long t0 = System.nanoTime();
        int words = 0;
        for (int i = 0; i < 20; i++) {
            words +=
                    SpellChecker.checkableWords(line, SpellChecker.Syntax.PLAIN).size();
        }
        long perScanMicros = (System.nanoTime() - t0) / 20 / 1000;
        System.out.println("[spell-perf] 60 KB no-whitespace line: " + perScanMicros + " µs per scan, " + words / 20
                + " checkable words");
        assertTrue(perScanMicros < 20_000, "one scan took " + perScanMicros + " µs");
    }

    // --- what counts as a word, and as the same word (S2, S7, S9) ---

    @Test
    void theStoredFormOfAWordIsTheFormItIsLookedUpBy() {
        assertEquals("zzq'abc", SpellChecker.canonical("zzq’abc"));
        assertEquals("editora", SpellChecker.canonical("Editora’s"));
        assertEquals("editora", SpellChecker.canonical(" Editora's "));
        assertEquals("café", SpellChecker.canonical("café"), "a decomposed accent is composed");
        assertEquals("", SpellChecker.canonical("  "));
    }

    @Test
    void ignoringAWordWithATypographicApostropheStopsFlaggingIt() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", new java.util.HashSet<>(), new java.util.HashSet<>());
        assertTrue(c.isMisspelled("zzq’abc"));
        assertTrue(c.ignore("zzq’abc"));
        assertFalse(c.isMisspelled("zzq’abc"), "the ignore set stores the form the lookup uses");
        assertFalse(c.isMisspelled("zzq'abc"));
        assertFalse(c.ignore("ZZQ'abc"), "already ignored");
        // …and so does the user dictionary, once the word is stored in that form.
        SpellChecker d =
                new SpellChecker("en_US", Set.of(SpellChecker.canonical("qqz’def")), new java.util.HashSet<>());
        assertFalse(d.isMisspelled("qqz’def"));
    }

    @Test
    void ignoreHoldsInEveryCheckerForTheSession() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker oneBuffer = new SpellChecker("en_US", Set.of());
        SpellChecker anotherBuffer = new SpellChecker("en_US", Set.of());
        assertTrue(anotherBuffer.isMisspelled("zzsessionwordq"));
        oneBuffer.ignore("zzsessionwordq");
        assertFalse(anotherBuffer.isMisspelled("zzsessionwordq"), "Ignore used to apply to one buffer only");
    }

    @Test
    void possessivesAndPluralsOfKnownWordsAreAccepted() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of("adrianx"), new java.util.HashSet<>());
        assertFalse(c.isMisspelled("adrianx's"));
        assertFalse(c.isMisspelled("adrianx’s"));
        assertFalse(c.isMisspelled("adrianxs"));
        assertFalse(c.isMisspelled("kubernetes's"));
        assertFalse(c.isMisspelled("middlewares"));
        assertTrue(c.isMisspelled("adrianxx"), "a different word is still flagged");
        assertTrue(c.isMisspelled("wrolds"), "the plural of an unknown word is still flagged");
        c.setUserWordsEnabled(false);
        assertTrue(c.isMisspelled("adrianx's"), "the possessive follows the personal-dictionary switch");
        assertEquals("class", SpellChecker.stem("class's"));
        assertEquals(null, SpellChecker.stem("class"));
        assertEquals(null, SpellChecker.stem("its"));
    }

    @Test
    void textInAScriptTheDictionaryDoesNotCoverIsNotFlagged() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of(), new java.util.HashSet<>());
        for (String foreign : List.of("这是一个测试", "Привет", "こんにちは", "Ελληνικά", "مرحبا")) {
            assertTrue(SpellChecker.foreignScript(foreign), foreign);
            assertFalse(c.isMisspelled(foreign), foreign + " is another language, not a misspelling");
        }
        assertFalse(SpellChecker.foreignScript("naïve"));
        assertFalse(SpellChecker.foreignScript("Łódź"));
        assertTrue(c.isMisspelled("wrold"));
    }

    @Test
    void accentedLoanWordsAndDecomposedAccentsAreHandled() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker en = new SpellChecker("en_US", Set.of(), new java.util.HashSet<>());
        for (String loan : List.of("café", "résumé", "naïve")) {
            assertFalse(en.isMisspelled(loan), loan + " is English with its accent kept");
        }
        assertTrue(en.isMisspelled("cäfé") || !en.isMisspelled("cafe"), "only a word whose plain form is correct");
        assertTrue(en.isMisspelled("wróld"), "an accent does not excuse a misspelling");

        // NFD "café" (e + combining acute): one word, not "cafe" plus a stray mark that hid it from checking.
        String nfd = "un café solo y un cafeé";
        assertEquals(List.of("un", "café", "solo", "y", "un", "cafeé"), checkable(nfd));
        assumeTrue(SpellDictionaries.buildBlocking("es").isPresent(), "es dictionary should build");
        SpellChecker es = new SpellChecker("es", Set.of(), new java.util.HashSet<>());
        assertFalse(es.isMisspelled("café"));
        assertTrue(es.isMisspelled("cafeé"), "a decomposed word is checked, where it used to be skipped");
    }

    // --- suggestions (S20) ---

    @Test
    void splitWordJunkIsDroppedAndTheCommonSlipLeads() {
        assertEquals(
                List.of("until", "untie"),
                SpellChecker.rankSuggestions("untill", List.of("until l", "until", "untie", "un till"), 8));
        assertEquals(
                List.of("occurred", "occur"),
                SpellChecker.rankSuggestions("occured", List.of("occur ed", "occur", "occurred"), 8));
        assertEquals(List.of("allot", "a lot"), SpellChecker.rankSuggestions("alot", List.of("a lot", "allot"), 8));
        // A doubled letter left out, or two neighbours swapped, outranks an unrelated one-letter change.
        assertEquals(
                List.of("comment", "cement", "moment", "foment"),
                SpellChecker.rankSuggestions("coment", List.of("cement", "moment", "foment", "comment"), 8));
        assertEquals(
                List.of("the", "tech", "ten"), SpellChecker.rankSuggestions("teh", List.of("tech", "ten", "the"), 8));
        assertEquals(
                2,
                SpellChecker.rankSuggestions("x", List.of("a", "b", "c", "a"), 2)
                        .size());
    }

    @Test
    void realSuggestionsHaveNoSplitJunk() {
        assumeTrue(SpellDictionaries.buildBlocking("en_US").isPresent(), "en_US dictionary should build");
        SpellChecker c = new SpellChecker("en_US", Set.of(), new java.util.HashSet<>());
        assertFalse(c.suggest("untill").contains("until l"), c.suggest("untill").toString());
        assertFalse(
                c.suggest("occured").contains("occur ed"), c.suggest("occured").toString());
        assertEquals("comment", c.suggest("coment").get(0), c.suggest("coment").toString());
        assertTrue(c.suggest("teh").contains("the"));
    }
}
