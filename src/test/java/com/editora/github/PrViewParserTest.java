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
                 "additions":120,"deletions":30,"headRefOid":"0123abc"}
                """;
        PrViewParser.PrDetail d = PrViewParser.parse(json);
        assertEquals(42, d.number());
        assertEquals("Feature", d.title());
        assertEquals("Does things", d.body());
        assertEquals("adl", d.authorLogin());
        assertEquals("main", d.baseRefName());
        assertEquals(120, d.additions());
        assertEquals(30, d.deletions());
        assertEquals("0123abc", d.headRefOid(), "G7: the head commit a review is pinned to");
    }

    @Test
    void badInputYieldsNull() {
        assertNull(PrViewParser.parse(""));
        assertNull(PrViewParser.parse(null));
        assertNull(PrViewParser.parse("[]")); // array, not an object
        assertNull(PrViewParser.parse("garbage"));
    }

    /** R4-G3: the head asked for again after a review's diff arrived. */
    @Test
    void readsTheHeadCommitAlone() {
        assertEquals("abc123", PrViewParser.headRefOid("{\"headRefOid\":\" abc123 \"}"));
        assertNull(PrViewParser.headRefOid("{\"headRefOid\":\"\"}"));
        assertNull(PrViewParser.headRefOid("{}"));
        assertNull(PrViewParser.headRefOid("not json"));
        assertNull(PrViewParser.headRefOid(""));
    }
}
