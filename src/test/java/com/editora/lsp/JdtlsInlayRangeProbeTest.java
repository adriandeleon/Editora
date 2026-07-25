package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) for #715 — why inlay hints never appear.
 *
 * <p>Establishes ground truth in one run:
 * <ol>
 *   <li><b>Control</b>: hover + completion prove the project imported and bindings resolve — without this
 *       a "0 hints" result only measures an unfinished import.</li>
 *   <li><b>Inlay hints with the config Editora actually sends</b> (its {@code didChangeConfiguration} pushes
 *       only {@code java.signatureHelp}) — and with none at all.</li>
 *   <li><b>Inlay hints after explicitly enabling the jdtls preference</b>
 *       ({@code java.inlayHints.parameterNames.enabled = "all"}) — the same class of server-side default as
 *       #468's {@code provideFormatter} and #674's {@code java.signatureHelp.enabled}.</li>
 *   <li><b>Range shape</b>: exact document end vs. the padded, past-the-end range the app sends.</li>
 * </ol>
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsInlayRangeProbeTest -Dgroups=probe -Dlsp.probe=true}
 */
@Tag("probe")
class JdtlsInlayRangeProbeTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.home"), "src/adl/lsp-test-fixture");
    private static final String JDTLS = "/opt/homebrew/bin/jdtls";

    /** What LspCoordinator adds to the visible window on each side. */
    private static final int SEMANTIC_WINDOW_PAD = 200;

    @Test
    void inlayHints() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(JDTLS)), "needs a local jdtls");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(PROJECT), "needs the local fixture project");

        Path file = PROJECT.resolve("src/main/java/demo/App.java");
        String text = Files.readString(file);
        String uri = file.toUri().toString();
        String[] lines = text.split("\n", -1);
        int lineCount = lines.length;

        Path data = Files.createTempDirectory("jdtls-inlay-probe");
        ProcessBuilder pb = new ProcessBuilder(JDTLS, "-data", data.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();

        var launcher =
                LSPLauncher.createClientLauncher(new ProbeClient(), proc.getInputStream(), proc.getOutputStream());
        launcher.startListening();
        LanguageServer server = launcher.getRemoteProxy();

        InitializeParams ip = new InitializeParams();
        ip.setProcessId((int) ProcessHandle.current().pid());
        ip.setRootUri(PROJECT.toUri().toString());
        ip.setWorkspaceFolders(List.of(new WorkspaceFolder(PROJECT.toUri().toString(), "fixture")));
        ip.setCapabilities(capabilities());
        // Exactly what Editora sends (LspManager.javaInitOptions): autobuild off, no debug bundles.
        ip.setInitializationOptions(Map.of("settings", Map.of("java", Map.of("autobuild", Map.of("enabled", false)))));
        InitializeResult init = server.initialize(ip).get(180, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        System.out.println("inlayHintProvider = " + init.getCapabilities().getInlayHintProvider());

        // What Editora's pushConfiguration() actually sends: a java object carrying ONLY signatureHelp.
        server.getWorkspaceService()
                .didChangeConfiguration(new DidChangeConfigurationParams(
                        Map.of("java", Map.of("signatureHelp", Map.of("enabled", true, "description", true)))));

        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, text)));

        // The greet("prefix", 2) call — both arguments are literals, so "literals" mode must hint here.
        int callLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("greet(\"prefix\"")) {
                callLine = i;
            }
        }
        int callCol = lines[callLine].indexOf("greet(") + 2;
        System.out.println("document has " + lineCount + " lines; greet call at line " + callLine);

        Range whole = new Range(new Position(0, 0), new Position(lineCount, 0));

        // CONTROL: poll hover until bindings resolve, so a later "0 hints" cannot be blamed on the import.
        boolean resolved = false;
        for (int attempt = 1; attempt <= 18 && !resolved; attempt++) {
            Thread.sleep(10_000);
            String hover = hoverText(server, uri, callLine, callCol);
            resolved = hover.contains("greet");
            System.out.println("poll " + attempt + " (" + (attempt * 10) + "s): hover=<"
                    + hover.replace("\n", " ").trim() + ">  inlay=" + count(server, uri, whole));
        }
        System.out.println("\nBINDINGS RESOLVED (hover names the method): " + resolved);

        System.out.println("\n=== COMPLETION after `greeter.` (also shows the insertText format) ===");
        int dotLine = callLine;
        int dotCol = lines[dotLine].indexOf("greeter.") + "greeter.".length();
        var comp = server.getTextDocumentService()
                .completion(new CompletionParams(new TextDocumentIdentifier(uri), new Position(dotLine, dotCol)))
                .get(30, TimeUnit.SECONDS);
        var items = comp.isLeft() ? comp.getLeft() : comp.getRight().getItems();
        System.out.println("items: " + items.size());
        items.stream()
                .filter(i -> i.getLabel().startsWith("greet"))
                .limit(4)
                .forEach(i -> System.out.println("   label=<"
                        + i.getLabel() + ">  insertTextFormat=" + i.getInsertTextFormat() + "  insertText=<"
                        + i.getInsertText()
                        + ">  textEdit.newText=<"
                        + (i.getTextEdit() == null
                                ? "null"
                                : (i.getTextEdit().isLeft()
                                        ? i.getTextEdit().getLeft().getNewText()
                                        : i.getTextEdit().getRight().getNewText()))
                        + ">"));

        probe(server, uri, "A  Editora config, exact doc end (0,0)..(" + lineCount + ",0)", whole);
        int appEnd = (lineCount - 1) + SEMANTIC_WINDOW_PAD + 1;
        probe(
                server,
                uri,
                "B  Editora config, APP RANGE (0,0)..(" + appEnd + ",0)",
                new Range(new Position(0, 0), new Position(appEnd, 0)));

        System.out.println("\n=== pushing java.inlayHints.parameterNames.enabled = \"all\" ===");
        Map<String, Object> inlay = new HashMap<>();
        inlay.put("parameterNames", Map.of("enabled", "all"));
        Map<String, Object> java_ = new HashMap<>();
        java_.put("inlayHints", inlay);
        java_.put("inlayhints", inlay); // both casings — jdtls key spelling differs across versions
        java_.put("signatureHelp", Map.of("enabled", true, "description", true));
        server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(Map.of("java", java_)));
        Thread.sleep(5_000);

        probe(server, uri, "C  AFTER enabling the preference, exact doc end", whole);
        probe(
                server,
                uri,
                "D  AFTER enabling the preference, APP RANGE",
                new Range(new Position(0, 0), new Position(appEnd, 0)));

        proc.destroyForcibly();
    }

    private static String hoverText(LanguageServer server, String uri, int line, int col) {
        try {
            var h = server.getTextDocumentService()
                    .hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(line, col)))
                    .get(30, TimeUnit.SECONDS);
            if (h == null || h.getContents() == null) {
                return "";
            }
            var c = h.getContents();
            if (c.isRight()) {
                return c.getRight() == null ? "" : String.valueOf(c.getRight().getValue());
            }
            StringBuilder sb = new StringBuilder();
            c.getLeft()
                    .forEach(e ->
                            sb.append(e.isLeft() ? e.getLeft() : e.getRight().getValue()));
            return sb.toString();
        } catch (Exception e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    /** Hint count for {@code range}, or -1 on error. */
    private static int count(LanguageServer server, String uri, Range range) {
        try {
            var hints = server.getTextDocumentService()
                    .inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), range))
                    .get(30, TimeUnit.SECONDS);
            return hints == null ? 0 : hints.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void probe(LanguageServer server, String uri, String label, Range range) {
        System.out.println("\n=== " + label + " ===");
        try {
            var hints = server.getTextDocumentService()
                    .inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), range))
                    .get(30, TimeUnit.SECONDS);
            System.out.println("RESULT: " + (hints == null ? "null" : hints.size() + " hints"));
            if (hints != null) {
                hints.forEach(h -> System.out.println("   " + h.getPosition() + " " + h.getLabel()));
            }
        } catch (Exception e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            System.out.println("ERROR: " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    private static ClientCapabilities capabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        td.setInlayHint(new InlayHintCapabilities());
        td.setHover(new org.eclipse.lsp4j.HoverCapabilities());
        td.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true))); // snippetSupport
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        var ws = new org.eclipse.lsp4j.WorkspaceClientCapabilities();
        ws.setDidChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationCapabilities());
        ws.setConfiguration(true);
        cc.setWorkspace(ws);
        return cc;
    }

    private static final class ProbeClient implements LanguageClient {
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

        @Override
        public java.util.concurrent.CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams p) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void notifyProgress(ProgressParams p) {}

        @Override
        public java.util.concurrent.CompletableFuture<List<Object>> configuration(
                org.eclipse.lsp4j.ConfigurationParams params) {
            List<Object> out = new java.util.ArrayList<>();
            if (params != null && params.getItems() != null) {
                params.getItems().forEach(i -> out.add(null));
            }
            return java.util.concurrent.CompletableFuture.completedFuture(out);
        }
    }
}
