package com.editora.agent.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.agent.runtime.AgentSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Frozen task definitions and independent oracle code, independent of runtime/tool prompts. */
public final class AgentExecutionCorpus {
    public static final String VERSION = "phase7-v1";
    public static final List<String> TASKS = List.of(
            "ledger-bug",
            "editora-diff-newline",
            "ledger-refactor",
            "ledger-tests",
            "editora-diff-documentation",
            "editora-save-understanding",
            "editora-save-cancellation");
    public static final Path MANIFEST = Path.of("docs/evaluations/phase7/corpus-v1.json");

    public static ObjectNode manifest(Path source) throws Exception {
        var json = new ObjectMapper();
        var out = json.createObjectNode().put("version", VERSION);
        var tasks = out.putArray("tasks");
        for (String id : TASKS) {
            var task = AgentEvaluationCases.tasks().stream()
                    .filter(t -> t.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            var value = json.valueToTree(task);
            ((ObjectNode) value)
                    .set(
                            "allowed",
                            json.valueToTree(task.allowed().stream().sorted().toList()));
            ((ObjectNode) value)
                    .set(
                            "required",
                            json.valueToTree(task.required().stream().sorted().toList()));
            tasks.add(value);
        }
        var hashes = out.putObject("fixtureAndOracleSources");
        for (String name :
                List.of("AgentEvaluationCases.java", "AgentReliabilityCases.java", "AgentAcceptanceCases.java")) {
            String path = "src/test/java/com/editora/agent/eval/" + name;
            hashes.put(path, AgentSessionStore.hash(Files.readAllBytes(source.resolve(path))));
        }
        return out;
    }

    public static String verify(Path source) throws Exception {
        var expected = new ObjectMapper().readTree(Files.readString(source.resolve(MANIFEST)));
        var actual = manifest(source);
        if (!actual.equals(expected))
            throw new IllegalStateException("Phase 7 corpus changed; version tasks/oracles before comparison");
        return AgentSessionStore.hash(actual.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
