package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksParserTest {

    @Test
    void parsesAllBucketsAndRollsUp() {
        String json = """
                [
                  {"name":"build","workflow":"CI","bucket":"pass","link":"https://x/1"},
                  {"name":"test","workflow":"CI","bucket":"fail","link":"https://x/2"},
                  {"name":"lint","workflow":"CI","bucket":"pending","link":"https://x/3"},
                  {"name":"docs","workflow":"CI","bucket":"skipping","link":"https://x/4"}
                ]
                """;
        List<ChecksParser.CheckRun> runs = ChecksParser.parse(json);
        assertEquals(4, runs.size());
        ChecksParser.ChecksSummary s = ChecksParser.ChecksSummary.of(runs);
        assertEquals(1, s.pass());
        assertEquals(1, s.fail());
        assertEquals(1, s.pending());
        assertEquals(1, s.skipped());
    }

    @Test
    void overallPrioritizesFailThenPending() {
        assertEquals(
                ChecksParser.Overall.FAIL,
                ChecksParser.ChecksSummary.of(runs("pass", "fail", "pending")).overall());
        assertEquals(
                ChecksParser.Overall.PENDING,
                ChecksParser.ChecksSummary.of(runs("pass", "pending")).overall());
        assertEquals(
                ChecksParser.Overall.PASS,
                ChecksParser.ChecksSummary.of(runs("pass", "pass")).overall());
        assertEquals(
                ChecksParser.Overall.NONE,
                ChecksParser.ChecksSummary.of(List.of()).overall());
        assertEquals(
                ChecksParser.Overall.FAIL,
                ChecksParser.ChecksSummary.of(runs("cancel")).overall());
    }

    @Test
    void malformedYieldsEmpty() {
        assertTrue(ChecksParser.parse("boom").isEmpty());
        assertTrue(ChecksParser.parse(null).isEmpty());
    }

    private static List<ChecksParser.CheckRun> runs(String... buckets) {
        return List.of(buckets).stream()
                .map(b -> new ChecksParser.CheckRun("n", "CI", b, "l"))
                .toList();
    }
}
