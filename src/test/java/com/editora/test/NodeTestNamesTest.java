package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeTestNamesTest {

    @Test
    void aRowNameBecomesAnExactAnchoredPattern() {
        assertEquals("^adds$", NodeTestNames.pattern("adds"));
        assertEquals("^group inner bad$", NodeTestNames.pattern("group › inner bad"));
        assertEquals("^same$", NodeTestNames.pattern("same (2)"));
        assertEquals("^a\\.b \\(x\\) \\[1\\]\\?$", NodeTestNames.pattern("a.b (x) [1]?"));
    }

    @Test
    void npmPassesThePatternsAfterADoubleDashOthersDirectly() {
        assertEquals(
                List.of("run", "test", "--", "--test-name-pattern=^fails$"),
                NodeTestNames.npmTask(List.of("run", "test"), List.of("fails"), "/usr/bin/npm"));
        assertEquals(
                List.of("run", "test", "--test-name-pattern=^a$", "--test-name-pattern=^b$"),
                NodeTestNames.npmTask(List.of("run", "test"), List.of("a", "b"), "pnpm"));
        assertEquals(
                List.of("run", "test", "--", "--test-name-pattern=^b$"),
                NodeTestNames.npmTask(List.of("run", "test", "--", "--test-name-pattern=^a$"), List.of("b"), "npm"),
                "a rerun of a rerun replaces the earlier filter");
        assertEquals(List.of("run", "test"), NodeTestNames.npmTask(List.of("run", "test"), List.of(), "npm"));
    }
}
