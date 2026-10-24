package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.editor.lsp.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real typescript-language-server and TypeScript (review E12): a JavaScript file in a
 * small Node project gets a diagnostic from the server Editora starts the way it always does. A TypeScript
 * release without {@code tsserver.js} (TypeScript 7) broke every JavaScript file for months with nothing in the
 * suite to notice; the CI {@code node-probes} job installs {@code typescript@6} + {@code
 * typescript-language-server} and runs this with {@code -Dgroups=node -Dnode.probe=true}.
 *
 * <p>Locally: {@code ./mvnw test -Dtest=TypeScriptServerNodeProbeFxTest -Dgroups=node -Dnode.probe=true}, with
 * the two on the PATH. {@code -Dnode.probe.workspaceTs=<typescript folder>} links an SDK into the project's own
 * {@code node_modules} when the global one is unusable.
 */
@Tag("node")
@Tag("fx")
class TypeScriptServerNodeProbeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aJavaScriptFileGetsADiagnosticFromTheRealServer(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("node.probe"), "opt-in: -Dnode.probe=true");
        Path root = Files.createDirectories(dir.resolve("app"));
        Files.writeString(
                root.resolve("package.json"), "{\"name\":\"probe\",\"version\":\"1.0.0\",\"type\":\"module\"}\n");
        String workspaceTs = System.getProperty("node.probe.workspaceTs", "");
        if (!workspaceTs.isBlank()) {
            Files.createDirectories(root.resolve("node_modules"));
            Files.createSymbolicLink(root.resolve("node_modules/typescript"), Path.of(workspaceTs));
        }
        Path file = root.resolve("app.js");
        String text = "// @ts-check\nconst n = 42;\nn.nope();\n";
        Files.writeString(file, text);

        CompletableFuture<List<LspDiagnostic>> diagnostics = new CompletableFuture<>();
        StringBuffer status = new StringBuffer();
        LspManager manager = new LspManager(
                (f, d) -> {
                    if (f.equals(file) && d != null && !d.isEmpty()) {
                        diagnostics.complete(d);
                    }
                },
                (type, message) ->
                        status.append(type).append(": ").append(message).append('\n'));
        try {
            manager.configure(true, Map.of());
            CompletableFuture<String> failed = new CompletableFuture<>();
            manager.setOnStartFailed((serverId, sessionRoot) -> failed.complete("start failed: " + serverId));
            manager.setOnSessionCrashed((serverId, sessionRoot) -> failed.complete("crashed: " + serverId));
            Platform.runLater(() -> manager.openDocument(file, root, "javascript", text));

            Object first = CompletableFuture.anyOf(diagnostics, failed).get(90, TimeUnit.SECONDS);
            assertTrue(first instanceof List<?>, "the TypeScript server did not answer: " + first + "\n" + status);
            List<LspDiagnostic> got = diagnostics.get();
            assertTrue(got.stream().anyMatch(d -> d.message().contains("nope")), "a diagnostic for n.nope(): " + got);
        } finally {
            manager.close();
        }
    }
}
