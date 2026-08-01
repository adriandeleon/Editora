package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (not part of the suite — @Tag("probe"), opt-in via {@code -Dlsp.probe=true}). Establishes
 * the exact wire shape of jdtls's {@code java.edit.handlePasteEvent} (#742) before wiring it into the
 * paste path: what the params look like, what comes back, and whether it needs an extended capability.
 * Self-contained — builds its own throwaway project, so it only needs a local jdtls.
 */
@Tag("probe")
class JdtlsPasteProbeTest {

    private static final String[] JDTLS_CANDIDATES = {
        System.getProperty("user.home") + "/.editora/plugins/lsp/java/bin/jdtls",
        System.getProperty("user.home") + "/.editora-dev/plugins/lsp/java/bin/jdtls",
        "/opt/homebrew/bin/jdtls",
        "/usr/local/bin/jdtls",
    };

    private static String jdtls() {
        for (String c : JDTLS_CANDIDATES) {
            if (Files.isExecutable(Path.of(c))) {
                return c;
            }
        }
        return null;
    }

    static final class ProbeClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object o) {}

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams p) {}

        @Override
        public void showMessage(MessageParams p) {}

        @Override
        public java.util.concurrent.CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams p) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams p) {}
    }

    @Test
    void probePasteEvent() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: pass -Dlsp.probe=true");
        String jdtls = jdtls();
        org.junit.jupiter.api.Assumptions.assumeTrue(jdtls != null, "needs a local jdtls");

        // Throwaway project: a bare source tree, no build file (jdtls's invisible-project mode).
        Path project = Files.createTempDirectory("jdtls-paste-probe");
        Path src = project.resolve("src/demo");
        Files.createDirectories(src);
        Path file = src.resolve("App.java");
        String original =
                "package demo;\n\npublic class App {\n    public static void main(String[] args) {\n" + "    }\n}\n";
        Files.writeString(file, original);
        String uri = file.toUri().toString();

        Path data = Files.createTempDirectory("jdtls-paste-data");
        ProcessBuilder pb = new ProcessBuilder(jdtls, "-data", data.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        try {
            Launcher<LanguageServer> launcher =
                    LSPLauncher.createClientLauncher(new ProbeClient(), proc.getInputStream(), proc.getOutputStream());
            LanguageServer server = launcher.getRemoteProxy();
            launcher.startListening();

            InitializeParams ip = new InitializeParams();
            ip.setProcessId((int) ProcessHandle.current().pid());
            ip.setRootUri(project.toUri().toString());
            ip.setWorkspaceFolders(List.of(new WorkspaceFolder(project.toUri().toString(), "probe")));
            ip.setCapabilities(new ClientCapabilities());
            // Mirror production: Editora sends extendedClientCapabilities (see LspManager.initOptionsFor).
            ip.setInitializationOptions(Map.of("extendedClientCapabilities", Map.of("classFileContentsSupport", true)));
            InitializeResult init = server.initialize(ip).get(120, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());

            var cmds = init.getCapabilities().getExecuteCommandProvider();
            boolean advertised = cmds != null && cmds.getCommands().contains("java.edit.handlePasteEvent");
            System.out.println("=== handlePasteEvent advertised: " + advertised);
            System.out.println("=== all java.edit.* commands: "
                    + (cmds == null
                            ? "none"
                            : cmds.getCommands().stream()
                                    .filter(c -> c.startsWith("java.edit"))
                                    .toList()));

            server.getTextDocumentService()
                    .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, original)));
            System.out.println("=== waiting 30s for the (invisible) project to settle ===");
            Thread.sleep(30_000);

            // Simulate the paste: insert a line that needs java.util imports into main()'s body.
            String pasted = "        List<String> xs = new ArrayList<>();\n";
            String after = original.replace(
                    "    public static void main(String[] args) {\n", // splice below the brace
                    "    public static void main(String[] args) {\n" + pasted);
            var change = new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(uri, 2), List.of(new TextDocumentContentChangeEvent(after)));
            server.getTextDocumentService().didChange(change);
            Thread.sleep(2_000);

            // The pasted text spans line 4 col 0 .. line 5 col 0 (it ends in \n).
            com.google.gson.JsonObject range = new com.google.gson.JsonObject();
            com.google.gson.JsonObject start = new com.google.gson.JsonObject();
            start.addProperty("line", 4);
            start.addProperty("character", 0);
            com.google.gson.JsonObject end = new com.google.gson.JsonObject();
            end.addProperty("line", 5);
            end.addProperty("character", 0);
            range.add("start", start);
            range.add("end", end);
            com.google.gson.JsonObject location = new com.google.gson.JsonObject();
            location.addProperty("uri", uri);
            location.add("range", range);
            com.google.gson.JsonObject fmt = new com.google.gson.JsonObject();
            fmt.addProperty("tabSize", 4);
            fmt.addProperty("insertSpaces", true);
            com.google.gson.JsonObject params = new com.google.gson.JsonObject();
            params.add("location", location);
            params.addProperty("text", pasted);
            params.add("formattingOptions", fmt);

            // Shape A: the params object itself. Shape B: the object as a JSON string (several jdt.ls
            // delegate commands take stringified json). Shape C: a lsp4j-typed Location + separate args.
            List<List<Object>> attempts = List.of(
                    List.of(params),
                    List.of(params.toString()),
                    List.of(new Location(uri, new Range(new Position(4, 0), new Position(5, 0))), pasted));
            String[] names = {"A: JsonObject", "B: json string", "C: positional"};
            for (int i = 0; i < attempts.size(); i++) {
                System.out.println("=== attempt " + names[i] + " ===");
                try {
                    Object r = server.getWorkspaceService()
                            .executeCommand(new ExecuteCommandParams("java.edit.handlePasteEvent", attempts.get(i)))
                            .get(30, TimeUnit.SECONDS);
                    System.out.println("RESULT: " + r);
                    break;
                } catch (Exception e) {
                    System.out.println("THREW: " + String.valueOf(e).replace('\n', ' '));
                }
            }

            server.shutdown().get(20, TimeUnit.SECONDS);
            server.exit();
        } finally {
            proc.destroyForcibly();
        }
    }
}
