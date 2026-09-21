package com.editora.agent.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentEvaluationTest {
    @Test
    void modelCompletionIsNotTaskSuccessAndUnrelatedEditsFail() {
        var eval = new AgentEvaluation(Path.of(".").toAbsolutePath());
        var done = new AgentRuntime.Outcome(AgentRuntime.State.COMPLETED, "claimed complete");
        assertEquals(
                "FAIL",
                eval.report(
                                "case",
                                "provider",
                                "model",
                                32768,
                                done,
                                Set.of(),
                                Set.of("A"),
                                Set.of("A"),
                                true,
                                false,
                                20)
                        .path("taskSuccess")
                        .asText());
        assertEquals(
                "FAIL",
                eval.report(
                                "case",
                                "provider",
                                "model",
                                32768,
                                done,
                                Set.of("A", "secret"),
                                Set.of("A"),
                                Set.of("A"),
                                true,
                                false,
                                20)
                        .path("taskSuccess")
                        .asText());
        assertEquals(
                "PASS",
                eval.report(
                                "case",
                                "provider",
                                "model",
                                32768,
                                done,
                                Set.of("A"),
                                Set.of("A"),
                                Set.of("A"),
                                true,
                                false,
                                20)
                        .path("taskSuccess")
                        .asText());
        assertEquals(
                "REVIEW_REQUIRED",
                eval.report("case", "provider", "model", 32768, done, Set.of(), Set.of(), Set.of(), true, true, 20)
                        .path("taskSuccess")
                        .asText());
    }

    @Test
    void reportsNeverCopyArgumentsSourceErrorsOrPromptText() throws Exception {
        var json = new ObjectMapper();
        var eval = new AgentEvaluation(Path.of(".").toAbsolutePath());
        var tools = new AgentTools()
                .register(new AgentTool(
                        new AgentTool.Spec(
                                "read_file",
                                "description",
                                json.createObjectNode().put("type", "object"),
                                null,
                                AgentTool.Effect.READ,
                                Duration.ofSeconds(1),
                                true,
                                "test"),
                        (a, c) -> AgentTool.Result.failure("secret-source PASSWORD-EXAMPLE stale document")));
        var args = json.createObjectNode().put("path", "Code.java").put("credential", "PRIVATE-ARGUMENT");
        eval.tools(tools).get("read_file").handler().execute(args, new AgentCancellation());
        String report = eval.report(
                        "case",
                        "provider",
                        "model",
                        32768,
                        new AgentRuntime.Outcome(AgentRuntime.State.FAILED, "SECRET-ERROR"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        false,
                        false,
                        10)
                .toString();
        assertFalse(report.contains("PASSWORD-EXAMPLE"));
        assertFalse(report.contains("PRIVATE-ARGUMENT"));
        assertFalse(report.contains("SECRET-ERROR"));
        assertTrue(report.contains("STALE_CONTEXT"));
    }

    @Test
    void harnessConsentNeverAcceptsArbitraryCommandsOrMavenOptions() throws Exception {
        var eval = new AgentEvaluation(Path.of(".").toAbsolutePath());
        var json = new ObjectMapper();
        var spec = new AgentTool.Spec(
                "run_command",
                "test",
                json.createObjectNode().put("type", "object"),
                null,
                AgentTool.Effect.EXTERNAL,
                Duration.ofSeconds(1),
                true,
                "editora");
        assertTrue(eval.approval(
                spec, "{\"argv\":[\"mvn\",\"test\",\"-q\"],\"cwd\":\".\"}", new AgentCancellation(), true));
        var absolute = json.createObjectNode()
                .put("cwd", Path.of(".").toAbsolutePath().normalize().toString());
        absolute.putArray("argv").add("mvn").add("test");
        assertTrue(eval.approval(spec, absolute.toString(), new AgentCancellation(), true));
        for (String args : List.of(
                "{\"argv\":[\"sh\",\"-c\",\"mvn test\"]}",
                "{\"argv\":[\"mvn\",\"test\",\"-Dmaven.ext.class.path=evil.jar\"]}",
                "{\"argv\":[\"mvn\",\"test\"],\"cwd\":\"..\"}"))
            assertFalse(eval.approval(spec, args, new AgentCancellation(), true));
        assertFalse(eval.approval(spec, "{\"argv\":[\"mvn\",\"test\"]}", new AgentCancellation(), false));
    }

    @Test
    void newFocusedTestsAreAllowedWithoutRequiringAnUnrelatedExistingTestEdit() {
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("ledger-feature"))
                .findFirst()
                .orElseThrow();
        var changed = Set.of("src/test/java/demo/InvoiceTest.java", "pom.xml");
        assertTrue(AgentEvaluationCases.allowedChanges(task, changed).contains("src/test/java/demo/InvoiceTest.java"));
        assertFalse(AgentEvaluationCases.allowedChanges(task, changed).contains("pom.xml"));
        assertTrue(AgentEvaluationCases.requiredTestsChanged(task, changed));
        assertFalse(AgentEvaluationCases.requiredTestsChanged(task, Set.of("src/main/java/demo/Invoice.java")));
    }

    @Test
    void benchmarkCoversAllCategoriesAndDetectsDeletedFiles() {
        assertEquals(
                Set.of("understanding", "bug", "refactor", "feature", "testing", "maintenance"),
                new HashSet<>(AgentEvaluationCases.tasks().stream()
                        .map(AgentEvaluationCases.Task::category)
                        .toList()));
        assertEquals(
                Set.of("A", "B"), AgentEvaluationCases.changed(Map.of("A", "old", "B", "old"), Map.of("A", "new")));
        assertEquals("CONTEXT_EXHAUSTED", AgentEvaluation.classify("Context budget exhausted"));
    }
}
