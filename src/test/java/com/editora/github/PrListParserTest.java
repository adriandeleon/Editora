package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrListParserTest {

    @Test
    void parsesFullArray() {
        String json = """
                [
                  {"number":123,"title":"Add feature","author":{"login":"adl"},"headRefName":"feat/x",
                   "baseRefName":"main","state":"OPEN","isDraft":false,"updatedAt":"2026-07-19T10:00:00Z",
                   "url":"https://github.com/o/r/pull/123"},
                  {"number":124,"title":"WIP","author":{"login":"bob"},"headRefName":"wip","baseRefName":"main",
                   "state":"OPEN","isDraft":true,"updatedAt":"2026-07-18T10:00:00Z","url":"https://x/pull/124"}
                ]
                """;
        List<PrListParser.PullRequest> prs = PrListParser.parse(json);
        assertEquals(2, prs.size());
        assertEquals(123, prs.get(0).number());
        assertEquals("Add feature", prs.get(0).title());
        assertEquals("adl", prs.get(0).authorLogin());
        assertEquals("feat/x", prs.get(0).headRefName());
        assertFalse(prs.get(0).draft());
        assertTrue(prs.get(1).draft());
    }

    @Test
    void toleratesMissingAuthorAndFields() {
        List<PrListParser.PullRequest> prs = PrListParser.parse("[{\"number\":7,\"title\":\"t\"}]");
        assertEquals(1, prs.size());
        assertEquals(7, prs.get(0).number());
        assertEquals("", prs.get(0).authorLogin());
        assertEquals("", prs.get(0).headRefName());
        assertFalse(prs.get(0).draft());
    }

    @Test
    void emptyAndMalformedYieldEmptyList() {
        assertTrue(PrListParser.parse("[]").isEmpty());
        assertTrue(PrListParser.parse("").isEmpty());
        assertTrue(PrListParser.parse(null).isEmpty());
        assertTrue(PrListParser.parse("not json").isEmpty());
        assertTrue(PrListParser.parse("{\"number\":1}").isEmpty()); // object, not an array
    }
}
