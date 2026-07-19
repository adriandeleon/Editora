package com.editora.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrViewParserTest {

    @Test
    void parsesDetail() {
        String json = """
                {"number":42,"title":"Feature","body":"Does things","author":{"login":"adl"},
                 "baseRefName":"main","headRefName":"feat/y","state":"OPEN","url":"https://x/pull/42",
                 "additions":120,"deletions":30}
                """;
        PrViewParser.PrDetail d = PrViewParser.parse(json);
        assertEquals(42, d.number());
        assertEquals("Feature", d.title());
        assertEquals("Does things", d.body());
        assertEquals("adl", d.authorLogin());
        assertEquals("main", d.baseRefName());
        assertEquals(120, d.additions());
        assertEquals(30, d.deletions());
    }

    @Test
    void badInputYieldsNull() {
        assertNull(PrViewParser.parse(""));
        assertNull(PrViewParser.parse(null));
        assertNull(PrViewParser.parse("[]")); // array, not an object
        assertNull(PrViewParser.parse("garbage"));
    }
}
