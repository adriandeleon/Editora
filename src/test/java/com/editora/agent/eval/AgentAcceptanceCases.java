package com.editora.agent.eval;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import com.editora.agent.runtime.*;
import com.editora.process.ProcessRunner;

/** Acceptance adversaries stay in the evaluation layer; no task ids enter product predicates. */
public final class AgentAcceptanceCases {
    private AgentAcceptanceCases() {}

    public static List<AgentEvaluationCases.Task> tasks() {
        String source = "src/main/java/com/editora/diff/DiffText.java",
                test = "src/test/java/com/editora/diff/DiffTextTest.java";
        return List.of(
                new AgentEvaluationCases.Task(
                        "editora-diff-documentation",
                        "bug",
                        "Fix diff recomposition adding an unwanted final newline. Add a regression test, update README.md to document preserved newline behavior, and run relevant tests. A passing build alone is not the whole request.",
                        source,
                        Set.of(source, test, "README.md"),
                        Set.of(source, "README.md"),
                        false,
                        false,
                        false,
                        48),
                new AgentEvaluationCases.Task(
                        "editora-diff-test-quality",
                        "bug",
                        "Fix diff recomposition adding an unwanted final newline and add regression coverage. Preserve CRLF, CR, blank-line and empty-file behavior. Run the tests and state precisely which evidence was observed; do not claim a mutation check you did not run.",
                        source,
                        Set.of(source, test),
                        Set.of(source),
                        false,
                        false,
                        false,
                        48));
    }

    private static AgentEvaluationCases.Task original() {
        return AgentReliabilityCases.tasks().stream()
                .filter(t -> t.id().equals("editora-diff-newline"))
                .findFirst()
                .orElseThrow();
    }

    public static boolean prepare(AgentEvaluationCases.Task task, Path source, Path root) throws Exception {
        if (!task.id().equals("editora-diff-documentation") && !task.id().equals("editora-diff-test-quality"))
            return false;
        AgentReliabilityCases.prepare(original(), source, root);
        if (task.id().equals("editora-diff-documentation"))
            Files.writeString(
                    root.resolve("README.md"), "# Diff component\n\nNewline preservation is not documented yet.\n");
        return true;
    }

    public static Boolean oracle(AgentEvaluationCases.Task task, Path root, Path outside) throws Exception {
        if (!task.id().startsWith("editora-diff-")) return null;
        return AgentReliabilityCases.oracle(original(), root, outside);
    }

    /** Only an owned fixture copy is mutated, after the live agent ends. Original user work is untouched. */
    public static com.fasterxml.jackson.databind.node.ObjectNode regressionQuality(
            AgentEvaluationCases.Task task, Path root, Path outside, String oldSource, Set<String> addedTests)
            throws Exception {
        var out = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("kind", "EVALUATION_ONLY_REGRESSION_QUALITY");
        if (!task.id().startsWith("editora-diff-") || addedTests.isEmpty())
            return out.put("state", "UNVERIFIED")
                    .put("reason", "No supported new test identity or no mutation fixture");
        Path copy = outside.resolve("mutant");
        Files.createDirectories(copy);
        AgentEvaluationCases.copyTree(root.resolve("src"), copy.resolve("src"));
        Files.copy(root.resolve("pom.xml"), copy.resolve("pom.xml"));
        Files.writeString(copy.resolve(task.active()), oldSource);
        var workspace = new AgentWorkspace(copy);
        var args = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("type", "TEST")
                .put("isolation", "ISOLATED");
        var plan = AgentValidationProfile.plan(workspace, args);
        var result = ProcessRunner.runRestricted(
                copy, Duration.ofSeconds(120), AgentValidationSandbox.command(workspace, plan));
        var reports = AgentValidationReports.readDetailed(workspace, copy, Map.of());
        var failures = reports.cases().stream()
                .filter(t -> t.failed() && addedTests.contains(t.className() + "#" + t.name()))
                .map(t -> t.className() + "#" + t.name())
                .toList();
        out.put("state", !result.ok() && !failures.isEmpty() ? "TEST_PROVEN_TO_DETECT_OLD_FAILURE" : "NOT_PROVEN")
                .put("exit", result.exit());
        failures.forEach(out.putArray("newTestsFailingAgainstOldSource")::add);
        return out;
    }
}
