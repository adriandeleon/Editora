package com.editora.completion;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Methods and common headers are offered while typing a request (C16). */
class HttpKeywordsTest {

    @Test
    void headerNamesMethodsAndValuesAreOffered() {
        List<String> cont = HttpKeywords.startingWith("Cont", "Cont", 10);
        assertTrue(cont.contains("Content-Type") && cont.contains("Content-Length"), cont.toString());
        assertEquals(List.of("POST", "PUT", "PATCH"), HttpKeywords.startingWith("p", "p", 3));
        assertTrue(HttpKeywords.startingWith("appl", "appl", 10).contains("application/json"));
    }

    @Test
    void aHalfTypedHeaderOrAnExactWordIsLeftAlone() {
        assertEquals(
                List.of(), HttpKeywords.startingWith("T", "Content-T", 10), "only the run after - would be replaced");
        assertTrue(
                HttpKeywords.startingWith("Accept", "Accept", 10).stream().noneMatch("Accept"::equals),
                "a word typed out in full is not offered again");
        assertEquals(List.of(), HttpKeywords.startingWith("", "", 10));
    }
}
