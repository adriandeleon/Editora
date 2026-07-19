package com.editora.github;

import java.util.List;

import com.editora.github.RunListParser.RunState;
import com.editora.github.RunListParser.WorkflowRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunListParserTest {

    @Test
    void parsesRunsIncludingLargeIds() {
        String json = """
                [
                  {"databaseId":9876543210,"displayTitle":"Fix the thing","workflowName":"CI",
                   "headBranch":"feat/x","status":"completed","conclusion":"failure","event":"push",
                   "createdAt":"2026-07-19T10:00:00Z","url":"https://github.com/o/r/actions/runs/9876543210"},
                  {"databaseId":12,"displayTitle":"Docs","workflowName":"Pages","headBranch":"main",
                   "status":"completed","conclusion":"success","event":"schedule",
                   "createdAt":"2026-07-18T10:00:00Z","url":"https://x/2"}
                ]
                """;
        List<WorkflowRun> runs = RunListParser.parse(json);
        assertEquals(2, runs.size());
        // A run id past Integer.MAX_VALUE must survive as a long.
        assertEquals(9876543210L, runs.get(0).databaseId());
        assertEquals("CI", runs.get(0).workflowName());
        assertEquals("feat/x", runs.get(0).headBranch());
        assertEquals(RunState.FAILURE, runs.get(0).state());
        assertEquals(RunState.SUCCESS, runs.get(1).state());
    }

    @Test
    void inProgressRunHasNoConclusion() {
        String json = "[{\"databaseId\":5,\"status\":\"in_progress\",\"conclusion\":\"\"}]";
        List<WorkflowRun> runs = RunListParser.parse(json);
        assertEquals(1, runs.size());
        assertEquals(RunState.RUNNING, runs.get(0).state());
        assertTrue(runs.get(0).state().active());
        assertFalse(runs.get(0).state().failed());
    }

    @Test
    void missingFieldsGetDefaults() {
        List<WorkflowRun> runs = RunListParser.parse("[{}]");
        assertEquals(1, runs.size());
        assertEquals(0L, runs.get(0).databaseId());
        assertEquals("", runs.get(0).workflowName());
        assertEquals(RunState.UNKNOWN, runs.get(0).state());
    }

    @Test
    void malformedInputYieldsEmptyList() {
        assertTrue(RunListParser.parse("nope").isEmpty());
        assertTrue(RunListParser.parse(null).isEmpty());
        assertTrue(RunListParser.parse("").isEmpty());
        assertTrue(RunListParser.parse("{\"a\":1}").isEmpty()); // object, not an array
        assertTrue(RunListParser.parse("[]").isEmpty());
    }

    @Test
    void stateMappingTable() {
        assertEquals(RunState.QUEUED, RunState.of("queued", ""));
        assertEquals(RunState.QUEUED, RunState.of("waiting", ""));
        assertEquals(RunState.QUEUED, RunState.of("requested", ""));
        assertEquals(RunState.QUEUED, RunState.of("pending", ""));
        assertEquals(RunState.RUNNING, RunState.of("in_progress", ""));
        assertEquals(RunState.SUCCESS, RunState.of("completed", "success"));
        assertEquals(RunState.FAILURE, RunState.of("completed", "failure"));
        // The conclusions people forget — all must read as a failure.
        assertEquals(RunState.FAILURE, RunState.of("completed", "timed_out"));
        assertEquals(RunState.FAILURE, RunState.of("completed", "startup_failure"));
        assertEquals(RunState.FAILURE, RunState.of("completed", "action_required"));
        assertEquals(RunState.CANCELLED, RunState.of("completed", "cancelled"));
        assertEquals(RunState.CANCELLED, RunState.of("completed", "stale"));
        assertEquals(RunState.SKIPPED, RunState.of("completed", "skipped"));
        assertEquals(RunState.SKIPPED, RunState.of("completed", "neutral"));
        assertEquals(RunState.UNKNOWN, RunState.of("completed", ""));
    }

    @Test
    void stateMappingIsCaseInsensitiveAndNullSafe() {
        assertEquals(RunState.FAILURE, RunState.of("COMPLETED", "FAILURE"));
        assertEquals(RunState.RUNNING, RunState.of("IN_PROGRESS", null));
        assertEquals(RunState.UNKNOWN, RunState.of(null, null));
    }

    @Test
    void statePredicatesAndClasses() {
        assertTrue(RunState.FAILURE.failed());
        assertFalse(RunState.CANCELLED.failed()); // a cancelled run has no failure log to show
        assertTrue(RunState.QUEUED.active());
        assertTrue(RunState.RUNNING.active());
        assertFalse(RunState.SUCCESS.active());
        assertEquals("github-run-failure", RunState.FAILURE.cssClass());
    }
}
