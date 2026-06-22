package com.editora.editor;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the bundled technical-terms dictionary loads and is clean (lowercase, sane sample coverage). */
class TechnicalDictionaryTest {

    @Test
    void loadsNonEmpty() {
        Set<String> words = TechnicalDictionary.words();
        assertTrue(words.size() > 100, "expected a substantial bundled list, got " + words.size());
    }

    @Test
    void containsCommonTechnicalTerms() {
        for (String w : new String[] {"config", "async", "boolean", "middleware", "kubernetes", "stdin", "json"}) {
            assertTrue(TechnicalDictionary.contains(w), "should contain '" + w + "'");
        }
    }

    @Test
    void containsExpandedCategories() {
        // companies/products, languages, networking, cloud, and AI/ML terms.
        for (String w : new String[] {
            "github", "kubernetes", "pytorch", "golang", "rust", "subnet", "grpc", "azure", "llm", "embeddings"
        }) {
            assertTrue(TechnicalDictionary.contains(w), "should contain '" + w + "'");
        }
    }

    @Test
    void containsIsCaseSensitiveOnLowercasedInput() {
        // The store is lowercase; callers lowercase first. A non-lowercased query must not match.
        assertTrue(TechnicalDictionary.contains("config"));
        assertFalse(TechnicalDictionary.contains("Config"));
    }

    @Test
    void everyEntryIsLowercaseAndTrimmed() {
        for (String w : TechnicalDictionary.words()) {
            assertFalse(w.isBlank(), "no blank entries");
            assertTrue(w.equals(w.toLowerCase(Locale.ROOT)), "entry not lowercase: " + w);
            assertTrue(w.equals(w.strip()), "entry not trimmed: " + w);
        }
    }

    @Test
    void doesNotContainCommentOrPlainProse() {
        assertFalse(TechnicalDictionary.contains("#")); // comment lines are skipped
        assertFalse(TechnicalDictionary.contains("the")); // not a technical term
    }
}
