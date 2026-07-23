package com.editora.editops;

import java.util.Map;

import com.editora.editops.Abbrev.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure abbreviation expander (no toolkit). */
class AbbrevTest {

    private static final Map<String, String> TABLE = Map.of("btw", "by the way", "afaik", "as far as i know");

    /** Expands the word ending at the {@code |} marker; returns the resulting text (or null). */
    private static String expand(String textWithCaret) {
        int point = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        Edit e = Abbrev.expand(text, point, TABLE);
        if (e == null) {
            return null;
        }
        return text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }

    @Test
    void expandsAKnownAbbreviation() {
        assertEquals("by the way", expand("btw|"));
    }

    @Test
    void expandsTheWordEndingAtThePointLeavingTheRestAlone() {
        assertEquals("well, by the way, ...", expand("well, btw|, ..."));
    }

    @Test
    void unknownWordDoesNotExpand() {
        assertNull(expand("hello|"));
    }

    @Test
    void noWordBeforeThePointDoesNotExpand() {
        assertNull(expand("btw |"), "the word ends at a space, not the point");
        assertNull(expand("|"));
    }

    // --- case adaptation -------------------------------------------------------------------------

    @Test
    void carriesTheTypedCaseOntoTheExpansion() {
        assertEquals("By the way", expand("Btw|"), "a capitalized abbrev capitalizes the expansion");
        assertEquals("BY THE WAY", expand("BTW|"), "an all-caps abbrev upper-cases the expansion");
    }

    @Test
    void lowercaseAbbrevExpandsVerbatim() {
        assertEquals("as far as i know", expand("afaik|"));
    }

    @Test
    void aLowercaseAbbrevKeepsCapitalsInsideItsExpansion() {
        // Not PreserveCase: a lower-case abbrev must NOT lower-case a stored capital in the expansion.
        assertEquals(
                "HyperText Markup Language",
                Abbrev.expand("html", 4, Map.of("html", "HyperText Markup Language"))
                        .replacement());
    }

    // --- lookup is case-insensitive --------------------------------------------------------------

    @Test
    void lookupIsCaseInsensitiveAgainstTheLowerCasedTable() {
        assertEquals("by the way", expand("btw|"));
        assertEquals("By the way", expand("Btw|"));
    }

    // --- edges -----------------------------------------------------------------------------------

    @Test
    void anEmptyTableExpandsNothing() {
        assertNull(Abbrev.expand("btw", 3, Map.of()));
        assertNull(Abbrev.expand("btw", 3, null));
    }

    @Test
    void anAbbrevThatMapsToItselfIsANoOp() {
        assertNull(Abbrev.expand("x", 1, Map.of("x", "x")));
    }

    @Test
    void wordStartFindsTheRunOfWordCharacters() {
        assertEquals(6, Abbrev.wordStart("hello world", 11), "'world' starts at index 6");
        assertEquals(
                12,
                Abbrev.wordStart("hello world ", 12),
                "a trailing space means the word run is empty (start == point)");
    }

    @Test
    void aPointPastTheEndIsClampedNotThrown() {
        assertEquals("by the way", expandAt("btw", 99));
    }

    private static String expandAt(String text, int point) {
        Edit e = Abbrev.expand(text, point, TABLE);
        return e == null ? null : text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
    }
}
