package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
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
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — the A/B for #715. Two jdtls servers, same fixture, same
 * client capabilities; the ONLY difference is {@code initializationOptions}:
 *
 * <ul>
 *   <li><b>A</b>: none (what the original {@code JdtlsProbeTest} sent — the run that DID return hints).</li>
 *   <li><b>B</b>: {@code {settings:{java:{autobuild:{enabled:false}}}}} — exactly what
 *       {@code LspManager.javaInitOptions} sends for every Editora jdtls session.</li>
 * </ul>
 *
 * <p>If A yields hints and B does not, Editora's autobuild-off init option is what suppresses inlay hints —
 * the same shape as #468 ({@code provideFormatter}) and #674 ({@code java.signatureHelp.enabled}), where a
 * settings object the client sends changes what the server will do.
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsInlayInitOptionsProbeTest -Dgroups=probe -Dlsp.probe=true}
 */
@Tag("probe")
class JdtlsInlayInitOptionsProbeTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.home"), "src/adl/lsp-test-fixture");
    private static final String JDTLS = "/opt/homebrew/bin/jdtls";

    @Test
    void initOptionsAb() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(JDTLS)), "needs a local jdtls");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(PROJECT), "needs the local fixture project");

        System.out.println("\n##### A: NO initializationOptions (the original probe's shape) #####");
        run(null);

        System.out.println("\n##### B: Editora's initializationOptions (autobuild off) #####");
        run(Map.of("settings", Map.of("java", Map.of("autobuild", Map.of("enabled", false)))));
    }

    private static void run(Object initOptions) throws Exception {
        Path file = PROJECT.resolve("src/main/java/demo/App.java");
        String text = Files.readString(file);
        String uri = file.toUri().toString();
        String[] lines = text.split("\n", -1);

        Path data = Files.createTempDirectory("jdtls-ab");
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
        if (initOptions != null) {
            ip.setInitializationOptions(initOptions);
        }
        server.initialize(ip).get(180, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, text)));

        int callLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("greet(\"prefix\"")) {
                callLine = i;
            }
        }
        int callCol = lines[callLine].indexOf("greet(") + 2;
        Range whole = new Range(new Position(0, 0), new Position(lines.length, 0));

        // Wait for bindings (hover naming the method), then read hints — up to 3 min.
        for (int attempt = 1; attempt <= 18; attempt++) {
            Thread.sleep(10_000);
            boolean bound = hover(server, uri, callLine, callCol).contains("greet(String");
            int hints = count(server, uri, whole);
            System.out.println("  " + (attempt * 10) + "s: bindings=" + bound + " hints=" + hints);
            if (bound && hints > 0) {
                break; // got what we came for
            }
            if (bound && attempt >= 6) {
                break; // bindings resolved and hints stayed 0 for a minute — that IS the answer
            }
        }
        var hints = server.getTextDocumentService()
                .inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), whole))
                .get(30, TimeUnit.SECONDS);
        System.out.println("  FINAL: " + (hints == null ? "null" : hints.size() + " hints"));
        if (hints != null) {
            hints.forEach(h -> System.out.println("     " + h.getPosition() + " " + h.getLabel()));
        }
        proc.destroyForcibly();
    }

    private static String hover(LanguageServer server, String uri, int line, int col) {
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
            return "";
        }
    }

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

    private static ClientCapabilities capabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        td.setInlayHint(new InlayHintCapabilities());
        td.setHover(new org.eclipse.lsp4j.HoverCapabilities());
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
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
    }
}
