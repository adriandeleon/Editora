package com.editora.ui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.editora.agent.eval.AgentEvaluationCases;
import com.editora.agent.runtime.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real server contract; semantic operations pass through production editor buffers and leases. */
@Tag("lsp-interop")
class AgentLspInteropTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentCancellation token = new AgentCancellation();
    private WindowAgentDocuments documents;
    private AgentSemantics semantics;
    private final List<String> passed = new ArrayList<>();

    @Test
    void coldWarmUnsavedAndRestartedJavaServer() throws Exception {
        assumeTrue(Boolean.getBoolean("agent.lsp.integration"), "opt-in installed language server");
        String command = System.getProperty("agent.lsp.command", "");
        assertFalse(command.isBlank(), "Set -Dagent.lsp.command to an installed JDT LS");
        Path scratch = Files.createTempDirectory("editora-lsp-interop-");
        Path root = scratch.resolve("workspace");
        var task = AgentEvaluationCases.tasks().stream()
                .filter(t -> t.id().equals("ledger-tests"))
                .findFirst()
                .orElseThrow();
        AgentEvaluationCases.prepare(task, Path.of(".").toAbsolutePath(), root);
        Files.writeString(
                root.resolve("src/main/java/demo/Price.java"),
                "package demo;\npublic interface Price { long value(); }\n");
        Files.writeString(
                root.resolve("src/main/java/demo/Quote.java"),
                "package demo;\npublic class Quote implements Price { public long value(){return 1L;} }\n");
        Files.writeString(
                root.resolve("src/main/java/demo/Use.java"),
                "package demo;\npublic class Use { public long total(Price p) { return p.value(); } }\n");
        FxTestSupport.bootToolkit();
        long start = System.nanoTime();
        try (var window = FxWindowFixture.create()) {
            var coordinator = FxTestSupport.<LspCoordinator>field(window.controller, "lspCoordinator");
            FxTestSupport.runOnFx(() -> {
                window.shared.getSettings().setLspSupport(true);
                window.shared.getSettings().setJavaLspCommand(command);
                coordinator.applySupport();
                FxTestSupport.invokeWith(window.controller, "openProjectRoot", Path.class, root);
            });
            var host = FxTestSupport.callOnFx(() -> FxTestSupport.<AgentCoordinator.Ops>field(
                            FxTestSupport.field(window.controller, "agentCoordinator"), "ops")
                    .nativeDocuments());
            var workspace = new AgentWorkspace(root);
            documents = new WindowAgentDocuments(host, workspace);
            semantics = documents.semantics();
            var price = documents.read(root.resolve("src/main/java/demo/Price.java"), token);
            var quote = documents.read(root.resolve("src/main/java/demo/Quote.java"), token);
            var use = documents.read(root.resolve("src/main/java/demo/Use.java"), token);
            await(() -> supports(price, "symbols"), 90);
            passed.add("cold_initialization_and_agent_only_documents");
            JsonNode symbols = query("symbols", price, 0, 0);
            JsonNode value = null;
            for (var item : symbols.path("items"))
                if (item.path("symbol").asText().startsWith("value")) value = item;
            assertNotNull(value, symbols.toString());
            passed.add("symbols");
            int line = value.path("range").path("start").path("line").asInt();
            int character = value.path("range").path("start").path("character").asInt();
            JsonNode references = query("references", price, line, character);
            assertTrue(references.path("items").size() >= 2, references.toString());
            passed.add("references");
            var implementations = query("implementation", price, line, character);
            assertFalse(implementations.path("items").isEmpty(), implementations.toString());
            passed.add("implementations");
            int pos = use.text().indexOf("p.value") + 2;
            var definition = query("definition", use, 1, pos - use.text().indexOf('\n') - 1);
            assertFalse(definition.path("items").isEmpty());
            passed.add("definition");
            var actions = query("code_actions", quote, 1, 0);
            assertTrue(actions.has("actions"));
            passed.add("code_actions");
            var natives = new NativeAgentTools(workspace, documents, p -> {});
            var tools = natives.registry();
            new AgentSemanticTools(workspace, documents, semantics, natives::applySemanticEdits).register(tools);
            var rename = json.createObjectNode()
                    .put("path", "src/main/java/demo/Price.java")
                    .put("revision", price.revision())
                    .put("operation", "rename")
                    .put("new_name", "amount")
                    .put("line", line)
                    .put("character", character);
            var proposal = json.readTree(tools.get("semantic_prepare")
                    .handler()
                    .execute(rename, token)
                    .text());
            assertEquals(3, proposal.path("files").size());
            var applied = tools.get("semantic_apply")
                    .handler()
                    .execute(
                            json.createObjectNode()
                                    .put("proposal", proposal.path("proposal").asText()),
                            token);
            assertTrue(applied.changed());
            passed.add("rename_preview_and_apply");
            var unsaved = documents.read(quote.path(), token);
            assertTrue(unsaved.dirty());
            assertTrue(unsaved.text().contains("amount"));
            assertTrue(Files.readString(quote.path()).contains("value"));
            await(() -> query("symbols", unsaved, 0, 0).toString().contains("amount"), 30);
            passed.add("unsaved_didChange");
            var format = json.createObjectNode()
                    .put("path", "src/main/java/demo/Quote.java")
                    .put("revision", unsaved.revision())
                    .put("operation", "format");
            var formatting = tools.get("semantic_prepare").handler().execute(format, token);
            assertFalse(formatting.error());
            passed.add("format_preview");
            var invalid = documents
                    .apply(
                            List.of(new AgentDocuments.Edit(
                                    quote.path(),
                                    unsaved.revision(),
                                    "",
                                    "package demo; public class Quote { MissingType broken; }")),
                            token)
                    .getFirst();
            await(() -> documents.diagnostics(quote.path(), token).errors() > 0, 60);
            passed.add("unsaved_diagnostics");
            documents.apply(
                    List.of(new AgentDocuments.Edit(quote.path(), invalid.revision(), "", unsaved.text())), token);
            await(() -> documents.diagnostics(quote.path(), token).errors() == 0, 60);
            passed.add("repair_during_diagnostics");
            var old = FxTestSupport.callOnFx(() -> host.lsp().agentEndpoint(price.path()));
            FxTestSupport.runOnFx(coordinator::restartServers);
            assertThrows(
                    Exception.class,
                    () -> old.request("symbols", price.path().toUri().toString(), json.createObjectNode())
                            .get(2, TimeUnit.SECONDS));
            var restored = documents.read(price.path(), token);
            await(() -> supports(restored, "symbols"), 90);
            assertTrue(query("symbols", restored, 0, 0).toString().contains("amount"));
            passed.add("restart_preserves_unsaved_buffers");
        } finally {
            var report = json.createObjectNode()
                    .put("kind", "REAL_LSP_INTEROPERABILITY")
                    .put("server", "JDT_LS")
                    .put("elapsedMs", (System.nanoTime() - start) / 1_000_000);
            report.set("passedOperations", json.valueToTree(passed));
            Path output = Path.of("target/lsp-interoperability.json");
            Files.createDirectories(output.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("AGENT_LSP_INTEROP " + report);
        }
    }

    private boolean supports(AgentDocuments.Snapshot source, String operation) throws Exception {
        for (var item : semantics.capabilities(source, token).path("operations"))
            if (operation.equals(item.asText())) return true;
        return false;
    }

    private JsonNode query(String op, AgentDocuments.Snapshot source, int line, int character) throws Exception {
        var args = json.createObjectNode().put("line", line).put("character", character);
        return semantics.request(op, args, source, documents.open(token), token);
    }

    @FunctionalInterface
    interface Check {
        boolean get() throws Exception;
    }

    private static void await(Check check, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (check.get()) return;
            Thread.sleep(100);
        }
        fail("Language server condition did not become ready within " + seconds + " seconds");
    }
}
