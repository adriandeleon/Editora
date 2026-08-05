package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (not part of the suite — {@code @Tag("probe")}, run explicitly). Answers the one question a
 * unit test cannot: what does a REAL jdtls, loaded with the java-debug bundle exactly as Editora loads it,
 * hand back for {@code vscode.java.resolveMainClass} on a plain Maven project — and, just as important,
 * <em>as which Java type</em>.
 *
 * <p>Why the type matters: lsp4j deserializes an untyped {@code workspace/executeCommand} result to whatever
 * the payload maps to, which is not always a gson {@code JsonElement}. {@code DapManager.parseMainClasses}
 * reads it through {@code asJson}, whose fallback is {@code JsonParser.parseString(String.valueOf(res))} —
 * a round trip through Java's {@code toString()}. {@code parseClasspath} was already fixed to read a
 * {@code List} directly for that reason; {@code parseMainClasses} was not.
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsResolveMainClassProbeTest -Dgroups=probe -Dlsp.probe=true}
 * (optionally {@code -Dlsp.probe.project=/path/to/a/maven/project}). Self-skips without the flag.
 */
@Tag("probe")
class JdtlsResolveMainClassProbeTest {

    private static final Path JDTLS = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");

    private static final Path DEBUG_PLUGIN_DIR = Path.of(System.getProperty("user.home"), ".editora/plugins/dap/java");

    @Test
    void probe() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: pass -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(JDTLS), "needs a local jdtls at " + JDTLS);

        Path project = Path.of(System.getProperty(
                "lsp.probe.project",
                Path.of(System.getProperty("user.home"), "src/adl/adltest").toString()));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(project), "needs a Maven project: " + project);

        Path file;
        try (var walk = Files.walk(project.resolve("src/main/java"))) {
            file = walk.filter(p -> p.toString().endsWith(".java")).findFirst().orElseThrow();
        }
        String bundle;
        try (var jars = Files.list(DEBUG_PLUGIN_DIR)) {
            bundle = jars.filter(p -> p.toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow()
                    .toString();
        }
        System.out.println("project = " + project);
        System.out.println("file    = " + file);
        System.out.println("bundle  = " + bundle);

        Path data = Files.createTempDirectory("jdtls-mainclass-probe");
        ProcessBuilder pb = new ProcessBuilder(JDTLS.toString(), "-data", data.toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process proc = pb.start();
        try {
            LanguageClient client = new ProbeClient();
            Launcher<LanguageServer> launcher =
                    LSPLauncher.createClientLauncher(client, proc.getInputStream(), proc.getOutputStream());
            LanguageServer server = launcher.getRemoteProxy();
            launcher.startListening();

            InitializeParams ip = new InitializeParams();
            ip.setProcessId((int) ProcessHandle.current().pid());
            ip.setRootUri(project.toUri().toString());
            ip.setWorkspaceFolders(List.of(new WorkspaceFolder(
                    project.toUri().toString(), project.getFileName().toString())));
            // Exactly what LspManager.initOptionsFor sends for jdtls with debugging on.
            ip.setInitializationOptions(Map.of("bundles", List.of(bundle)));
            InitializeResult init = server.initialize(ip).get(180, TimeUnit.SECONDS);
            server.initialized(new InitializedParams());
            System.out.println("executeCommandProvider = "
                    + (init.getCapabilities().getExecuteCommandProvider() == null
                            ? "none"
                            : init.getCapabilities().getExecuteCommandProvider().getCommands().stream()
                                    .filter(c -> c.contains("resolveMainClass") || c.contains("debug"))
                                    .toList()));

            server.getTextDocumentService()
                    .didOpen(new DidOpenTextDocumentParams(
                            new TextDocumentItem(file.toUri().toString(), "java", 1, Files.readString(file))));

            // The project import is asynchronous; ask repeatedly rather than sleeping one arbitrary interval.
            for (int attempt = 1; attempt <= 12; attempt++) {
                Thread.sleep(10_000);
                Object res;
                String error = null;
                try {
                    res = server.getWorkspaceService()
                            .executeCommand(new ExecuteCommandParams("vscode.java.resolveMainClass", List.of()))
                            .get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    res = null;
                    error = String.valueOf(e.getCause() == null ? e : e.getCause());
                }
                System.out.println("--- attempt " + attempt + " (" + attempt * 10 + "s) ---");
                System.out.println("  error = " + error);
                System.out.println(
                        "  type  = " + (res == null ? "null" : res.getClass().getName()));
                System.out.println("  value = " + res);
                if (res != null && !String.valueOf(res).equals("[]")) {
                    // Close the loop: run OUR parser on the server's own object, not on a transcription of it.
                    System.out.println("  >>> DapManager.parseMainClasses = " + parseMainClasses(res));
                    break;
                }
            }
        } finally {
            proc.destroyForcibly();
        }
    }

    /** {@code DapManager.parseMainClasses} is private and in another package; this probe is a diagnostic. */
    private static Object parseMainClasses(Object res) throws Exception {
        var m = Class.forName("com.editora.dap.DapManager").getDeclaredMethod("parseMainClasses", Object.class);
        m.setAccessible(true);
        return m.invoke(null, res);
    }

    /** Minimal client: jdtls talks a lot during an import and every notification needs somewhere to go. */
    private static final class ProbeClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {}

        @Override
        public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {}

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {
            System.out.println("[showMessage] " + messageParams.getMessage());
        }

        @Override
        public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
                org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return java.util.concurrent.CompletableFuture.completedFuture(new org.eclipse.lsp4j.MessageActionItem());
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {}
    }
}
