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

    /**
     * The two siblings #746 lists as pairing with the paste path. Round two, narrowed by round one:
     * stringFormatting reads TWO arguments (arg0 a String, arg1 an object — arg0-as-object threw a
     * cast error and a lone arg threw index-1-out-of-bounds), and smartSemicolonDetection accepted every
     * shape but answered null, which in vscode-java means its preference is off by default.
     */
    private static void probeSiblings(LanguageServer server, String uri, String original) throws Exception {
        String text = "package demo;\n\npublic class App {\n    public static void main(String[] args) {\n"
                + "        String s = \"hello\";\n        int n = compute(1, 2)\n    }\n"
                + "    static int compute(int a, int b) { return a + b; }\n}\n";
        server.getTextDocumentService()
                .didChange(new DidChangeTextDocumentParams(
                        new VersionedTextDocumentIdentifier(uri, 3),
                        List.of(new TextDocumentContentChangeEvent(text))));
        Thread.sleep(3_000);

        // Turn on both features' preferences the way vscode-java does, then re-ask.
        java.util.Map<String, Object> javaCfg = new java.util.HashMap<>();
        javaCfg.put(
                "edit",
                java.util.Map.of(
                        "smartSemicolonDetection",
                        java.util.Map.of("enabled", true),
                        "validateAllOpenBuffersOnChanges",
                        false));
        server.getWorkspaceService()
                .didChangeConfiguration(new DidChangeConfigurationParams(java.util.Map.of("java", javaCfg)));
        Thread.sleep(3_000);

        com.google.gson.JsonObject pos = new com.google.gson.JsonObject();
        pos.addProperty("line", 5);
        pos.addProperty("character", 28); // just after the '2', before the ')'
        com.google.gson.JsonObject semi = new com.google.gson.JsonObject();
        semi.addProperty("uri", uri);
        semi.add("position", pos);
        tryShapes(
                server,
                "java.edit.smartSemicolonDetection",
                List.of(List.of(semi.toString()), List.of(uri, pos.toString()), List.of(uri, pos)));

        // stringFormatting: arg0 String, arg1 object. Try the plausible splits of (uri, text, position).
        com.google.gson.JsonObject spos = new com.google.gson.JsonObject();
        spos.addProperty("line", 4);
        spos.addProperty("character", 26); // between the quotes of "hello"
        com.google.gson.JsonObject fmt2 = new com.google.gson.JsonObject();
        fmt2.addProperty("tabSize", 4);
        fmt2.addProperty("insertSpaces", true);
        String pasted = "line one\nline \"two\"\n";

        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("text", pasted);
        payload.add("position", spos);
        payload.add("formattingOptions", fmt2);

        com.google.gson.JsonObject withRange = new com.google.gson.JsonObject();
        com.google.gson.JsonObject r2 = new com.google.gson.JsonObject();
        r2.add("start", spos);
        r2.add("end", spos);
        withRange.add("range", r2);
        withRange.addProperty("text", pasted);

        // line 4 is `        String s = "hello";` — the quotes are at 19 and 25, so 22 is really inside.
        com.google.gson.JsonObject inLiteral = new com.google.gson.JsonObject();
        inLiteral.addProperty("line", 4);
        inLiteral.addProperty("character", 22);
        com.google.gson.JsonObject inLiteralRange = new com.google.gson.JsonObject();
        com.google.gson.JsonObject lr = new com.google.gson.JsonObject();
        lr.add("start", inLiteral);
        lr.add("end", inLiteral);
        inLiteralRange.add("range", lr);
        inLiteralRange.addProperty("uri", uri);

        com.google.gson.JsonObject uriAndPos = new com.google.gson.JsonObject();
        uriAndPos.addProperty("uri", uri);
        uriAndPos.add("position", inLiteral);

        tryShapes(
                server,
                "java.edit.stringFormatting",
                List.of(
                        List.of(pasted, inLiteral, "4"),
                        List.of(pasted, uriAndPos, "4"),
                        List.of(pasted, inLiteral, "0"),
                        List.of(uri, inLiteral, "4")));
    }

    /** Sends {@code command} once per candidate argument list, printing what each answers. */
    private static void tryShapes(LanguageServer server, String command, List<List<Object>> shapes) {
        System.out.println("\n########## " + command + " ##########");
        for (int i = 0; i < shapes.size(); i++) {
            System.out.println("--- shape " + i + ": " + shapes.get(i));
            try {
                Object r = server.getWorkspaceService()
                        .executeCommand(new ExecuteCommandParams(command, shapes.get(i)))
                        .get(30, TimeUnit.SECONDS);
                System.out.println("RESULT[" + i + "] ("
                        + (r == null ? "null" : r.getClass().getSimpleName()) + "): " + r);
            } catch (Exception e) {
                System.out.println("THREW[" + i + "]: " + String.valueOf(e).replace('\n', ' '));
            }
        }
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

            probeSiblings(server, uri, original);

            server.shutdown().get(20, TimeUnit.SECONDS);
            server.exit();
        } finally {
            proc.destroyForcibly();
        }
    }
}
