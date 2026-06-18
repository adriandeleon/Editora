package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Small Git display formatters: short hash + compact (first-name) author. */
class GitFormatTest {

    @Test
    void shortHashTruncatesToSevenAndGuardsNullAndShort() {
        assertEquals("abcdef1", GitFormat.shortHash("abcdef1234567890"));
        assertEquals("abc", GitFormat.shortHash("abc")); // shorter than 7 → unchanged
        assertEquals("", GitFormat.shortHash(""));
        assertEquals("", GitFormat.shortHash(null));
    }

    @Test
    void shortAuthorTakesTheFirstNameAndGuardsNull() {
        assertEquals("Ada", GitFormat.shortAuthor("Ada Lovelace"));
        assertEquals("Ada", GitFormat.shortAuthor("  Ada Lovelace  ")); // stripped first
        assertEquals("Cher", GitFormat.shortAuthor("Cher")); // no space → whole string
        assertEquals("", GitFormat.shortAuthor(""));
        assertEquals("", GitFormat.shortAuthor(null));
    }
}
