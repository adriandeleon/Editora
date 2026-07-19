package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueListParserTest {

    @Test
    void parsesIssuesFlatteningLabels() {
        String json = """
                [
                  {"number":5,"title":"Bug","author":{"login":"adl"},"state":"OPEN",
                   "labels":[{"name":"bug","color":"d73a4a"},{"name":"p1","color":"000000"}],
                   "updatedAt":"2026-07-19T10:00:00Z","url":"https://x/issues/5"}
                ]
                """;
        List<IssueListParser.Issue> issues = IssueListParser.parse(json);
        assertEquals(1, issues.size());
        assertEquals(5, issues.get(0).number());
        assertEquals("adl", issues.get(0).authorLogin());
        assertEquals(List.of("bug", "p1"), issues.get(0).labels());
    }

    @Test
    void toleratesMissingLabelsAndAuthor() {
        List<IssueListParser.Issue> issues = IssueListParser.parse("[{\"number\":9,\"title\":\"t\"}]");
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).labels().isEmpty());
        assertEquals("", issues.get(0).authorLogin());
    }

    @Test
    void malformedYieldsEmpty() {
        assertTrue(IssueListParser.parse("nope").isEmpty());
        assertTrue(IssueListParser.parse(null).isEmpty());
    }
}
