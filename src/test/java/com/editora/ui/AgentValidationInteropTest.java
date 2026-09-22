package com.editora.ui;

import java.nio.file.Path;

import com.editora.agent.eval.AgentEvaluationCases;
import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Production buffer/save/validation boundaries plus a real isolated Maven process; no model. */
class AgentValidationInteropTest {
    @Test
    void realEditorEditsSaveAndProduceFreshIsolatedTestEvidence(@TempDir Path root) throws Exception {
        assumeTrue(Boolean.getBoolean("agent.validation.integration"));
        assumeTrue(AgentValidationSandbox.available());
        FxTestSupport.bootToolkit();
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("ledger-tests"))
                .findFirst()
                .orElseThrow();
        AgentEvaluationCases.prepare(task, Path.of("").toAbsolutePath(), root);
        try (var window = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.invokeWith(window.controller, "openProjectRoot", Path.class, root));
            var host = FxTestSupport.callOnFx(() -> FxTestSupport.<AgentCoordinator.Ops>field(
                            FxTestSupport.field(window.controller, "agentCoordinator"), "ops")
                    .nativeDocuments());
            var workspace = new AgentWorkspace(root);
            var documents = new WindowAgentDocuments(host, workspace);
            var token = new AgentCancellation();
            var initial = documents.read(root.resolve(task.active()), token);
            var nativeTools = new NativeAgentTools(workspace, documents, p -> {});
            nativeTools.acceptance().user("Add a regression test and run tests.");
            var tools = nativeTools.registry();
            var json = new ObjectMapper();
            var edit = json.createObjectNode();
            edit.putArray("edits")
                    .addObject()
                    .put("path", task.active())
                    .put("revision", initial.revision())
                    .put("old_text", "")
                    .put("new_text", "// agent validation integration\n" + initial.text());
            tools.get("apply_edits").handler().execute(edit, token);
            var args = json.readTree("{\"type\":\"TEST\",\"module\":\".\",\"isolation\":\"ISOLATED\"}");
            AgentTools.validate(args, tools.get("run_validation").spec().inputSchema());
            assertTrue(
                    tools.get("run_validation").handler().execute(args, token).error());
            tools.get("save_files").handler().execute(json.createObjectNode(), token);
            var result = tools.get("run_validation").handler().execute(args, token);
            assertFalse(result.error(), result.text());
            var evidence = json.readTree(result.text());
            assertTrue(evidence.path("passed").asBoolean());
            assertEquals(1, evidence.path("savedRevisionCount").asInt());
            assertEquals(1, evidence.path("tests").path("tests").asInt());
            assertEquals("DENIED", evidence.path("network").asText());
            assertTrue(nativeTools.verify(token).passed());
            assertEquals(
                    1,
                    nativeTools
                            .acceptance()
                            .ledger()
                            .current(AgentEvidence.Kind.FILE_CHANGED)
                            .size());
            assertFalse(
                    nativeTools
                            .acceptance()
                            .finish("I added a regression test", token)
                            .accepted(),
                    "A comment-only test edit and green build cannot satisfy new coverage");
            var current = documents.read(root.resolve(task.active()), token);
            var unchanged = json.createObjectNode();
            unchanged
                    .putArray("edits")
                    .addObject()
                    .put("path", task.active())
                    .put("revision", current.revision())
                    .put("old_text", "")
                    .put("new_text", current.text());
            assertFalse(
                    tools.get("apply_edits").handler().execute(unchanged, token).changed());
            assertTrue(nativeTools.verify(token).passed(), "A no-op must retain current native validation");
            var added = json.createObjectNode();
            added.putArray("edits")
                    .addObject()
                    .put("path", task.active())
                    .put("revision", current.revision())
                    .put("old_text", "class LedgerTest {")
                    .put(
                            "new_text",
                            "class LedgerTest { @Test void multipliesQuantity() { assertEquals(250,new Invoice().total(List.of(new Line(125,2)))); }");
            tools.get("apply_edits").handler().execute(added, token);
            tools.get("save_files").handler().execute(json.createObjectNode(), token);
            result = tools.get("run_validation").handler().execute(args, token);
            assertFalse(result.error(), result.text());
            assertTrue(nativeTools.verify(token).passed());
            var completion = nativeTools.acceptance().finish("Added imaginaryTest", token);
            assertTrue(completion.accepted(), completion.rendered());
            assertTrue(completion.rendered().contains("LedgerTest#multipliesQuantity"));
            assertFalse(completion.rendered().contains("imaginaryTest"));
            assertTrue(completion.rendered().contains("2 passed"));
        }
    }
}
