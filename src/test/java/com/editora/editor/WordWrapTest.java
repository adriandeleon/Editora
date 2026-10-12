package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordWrapTest {

    @Test
    void aBufferNobodyConfiguredDoesNotWrapWhateverItsLanguage() {
        List<Boolean> applied = new ArrayList<>();
        WordWrap wrap = new WordWrap(applied::add);
        wrap.languageChanged("markdown");
        wrap.languageChanged("java");
        assertEquals(List.of(), applied, "nothing to decide before a preference exists");
        assertFalse(wrap.preferred());
    }

    @Test
    void thePreferenceForTheBuffersKindIsAppliedAndFollowsItsLanguage() {
        List<Boolean> applied = new ArrayList<>();
        WordWrap wrap = new WordWrap(applied::add);
        wrap.setPreference(false, true);
        assertEquals(List.of(true), applied, "no language yet: plain text, which is prose");
        wrap.languageChanged("java");
        wrap.languageChanged("markdown");
        wrap.languageChanged(LanguageRegistry.PLAINTEXT);
        wrap.languageChanged("typst");
        assertEquals(List.of(true, false, true, true, false), applied);
        assertFalse(wrap.preferred());
    }

    @Test
    void aLongLineHoldKeepsWrappingOffUntilItIsLifted() {
        List<Boolean> applied = new ArrayList<>();
        WordWrap wrap = new WordWrap(applied::add);
        wrap.setSuppressed(true);
        wrap.setPreference(true, true);
        wrap.languageChanged("markdown");
        assertEquals(List.of(false), applied, "both preferences agree: a language change decides nothing");
        assertTrue(wrap.preferred(), "the preference itself is still on");
        assertTrue(wrap.isSuppressed());
        wrap.setSuppressed(false);
        wrap.setPreference(true, true);
        assertEquals(List.of(false, true), applied);
    }

    @Test
    void whichLanguagesAreProse() {
        assertTrue(WordWrap.isProse(null));
        assertTrue(WordWrap.isProse("plaintext"));
        assertTrue(WordWrap.isProse("markdown"));
        for (String code : List.of("java", "html", "json", "yaml", "typst", "log", "csv")) {
            assertFalse(WordWrap.isProse(code), code);
        }
    }

    @Test
    void aGiantUnbrokenTokenHoldsWrappingOffAndOrdinaryTextDoesNot() {
        List<Boolean> applied = new ArrayList<>();
        WordWrap wrap = new WordWrap(applied::add);
        wrap.setPreference(true, true);
        wrap.inserted("x");
        wrap.inserted(("word ".repeat(1_500) + "\n").repeat(40));
        wrap.inserted("x".repeat(WordWrap.MAX_UNBROKEN_RUN - 1) + " " + "y".repeat(WordWrap.MAX_UNBROKEN_RUN - 1));
        assertEquals(List.of(true), applied, "prose, however long, wraps");
        assertFalse(wrap.isSuppressed());

        wrap.inserted("![shot](data:image/png;base64," + "A".repeat(50_000) + ")");
        assertEquals(List.of(true, false), applied, "switched off before the next layout");
        assertTrue(wrap.isSuppressed());
        wrap.setPreference(true, true);
        assertEquals(List.of(true, false, false), applied, "and a settings re-apply keeps it off");
    }

    @Test
    void whatIsTooCostlyToWrap() {
        assertFalse(WordWrap.tooCostlyToWrap(""));
        assertFalse(WordWrap.tooCostlyToWrap("a".repeat(WordWrap.MAX_UNBROKEN_RUN - 1)));
        assertTrue(WordWrap.tooCostlyToWrap("a".repeat(WordWrap.MAX_UNBROKEN_RUN)));
        assertTrue(WordWrap.tooCostlyToWrap("lead\n" + "a".repeat(WordWrap.MAX_UNBROKEN_RUN) + "\ntail"));
        assertFalse(WordWrap.tooCostlyToWrap(("a".repeat(100) + "\t").repeat(90)), "many short tokens");
        assertTrue(WordWrap.tooCostlyToWrap("word ".repeat(WordWrap.MAX_WRAPPED_LINE / 5)), "one very long line");
        assertFalse(WordWrap.tooCostlyToWrap(("word ".repeat(1_000) + "\n").repeat(50)), "many long paragraphs");
    }
}
