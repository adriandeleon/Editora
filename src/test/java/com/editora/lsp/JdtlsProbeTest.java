package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (not part of the suite — @Tag("probe"), run explicitly). Drives a REAL jdtls with the
 * exact capabilities Editora declares and asks it the three questions device-testing left open:
 * signature help, inlay hints, and rename-with-file-move. Prints the raw answers so we can tell a
 * client bug from server behavior.
 */
@Tag("probe")
class JdtlsProbeTest {

    /** Opt-in: {@code ./mvnw test -Dtest=JdtlsProbeTest -Dgroups=probe -Dlsp.probe=true}. Without the flag
     *  (and without a local jdtls + fixture) it self-skips, so CI and a normal {@code verify} never run it. */
    private static final Path PROJECT = Path.of(System.getProperty("user.home"), "src/adl/lsp-test-fixture");

    private static final String JDTLS = "/opt/homebrew/bin/jdtls";

    @Test
    void probe() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: pass -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(JDTLS)), "needs a local jdtls");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(PROJECT), "needs the local fixture project");
        Path file = PROJECT.resolve("src/main/java/demo/App.java");
        String text = Files.readString(file);
        String uri = file.toUri().toString();

        Path data = Files.createTempDirectory("jdtls-probe");
        ProcessBuilder pb = new ProcessBuilder(JDTLS, "-data", data.toString());
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();

        LanguageClient client = new ProbeClient();
        Launcher<LanguageServer> launcher =
                LSPLauncher.createClientLauncher(client, proc.getInputStream(), proc.getOutputStream());
        LanguageServer server = launcher.getRemoteProxy();
        launcher.startListening();

        InitializeParams ip = new InitializeParams();
        ip.setProcessId((int) ProcessHandle.current().pid());
        ip.setRootUri(PROJECT.toUri().toString());
        ip.setWorkspaceFolders(List.of(new WorkspaceFolder(PROJECT.toUri().toString(), "fixture")));
        ip.setCapabilities(editoraCapabilities());
        InitializeResult init = server.initialize(ip).get(120, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());

        var caps = init.getCapabilities();
        System.out.println("=== CAPABILITIES ===");
        System.out.println("signatureHelpProvider = " + caps.getSignatureHelpProvider());
        System.out.println("inlayHintProvider     = " + caps.getInlayHintProvider());
        System.out.println("renameProvider        = " + caps.getRenameProvider());

        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, text)));

        System.out.println("\n=== waiting 45s for the project import ===");
        Thread.sleep(45_000);

        // Line 15 (0-based) = "        System.out.println(greeter.greet(\"prefix\", 2));"; col 41 = after greet(
        int line = 15;
        int col = 41;
        System.out.println("probing line[" + line + "] = <" + text.split("\n")[line] + ">");
        System.out.println("char at col " + col + " onwards: <"
                + text.split("\n")[line].substring(Math.min(col, text.split("\n")[line].length())) + ">");

        System.out.println("\n=== SIGNATURE HELP (no context) ===");
        var p1 = new SignatureHelpParams(new TextDocumentIdentifier(uri), new Position(line, col));
        System.out.println(server.getTextDocumentService().signatureHelp(p1).get(30, TimeUnit.SECONDS));

        System.out.println("\n=== enabling java.signatureHelp.enabled via didChangeConfiguration ===");
        java.util.Map<String, Object> sig = new java.util.HashMap<>();
        sig.put("enabled", true);
        sig.put("description", true);
        java.util.Map<String, Object> java_ = new java.util.HashMap<>();
        java_.put("signatureHelp", sig);
        java.util.Map<String, Object> settings = new java.util.HashMap<>();
        settings.put("java", java_);
        server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams(settings));
        Thread.sleep(3000);

        System.out.println("\n=== SIGNATURE HELP (after enabling the preference) ===");
        var p2 = new SignatureHelpParams(new TextDocumentIdentifier(uri), new Position(line, col));
        var ctx = new SignatureHelpContext();
        ctx.setTriggerKind(SignatureHelpTriggerKind.Invoked);
        ctx.setIsRetrigger(false);
        p2.setContext(ctx);
        System.out.println(server.getTextDocumentService().signatureHelp(p2).get(30, TimeUnit.SECONDS));

        System.out.println("\n=== INLAY HINTS (whole file) ===");
        var ih = new InlayHintParams(
                new TextDocumentIdentifier(uri),
                new Range(new Position(0, 0), new Position(text.split("\n").length, 0)));
        System.out.println(server.getTextDocumentService().inlayHint(ih).get(30, TimeUnit.SECONDS));

        // OldName is declared in OldName.java; rename it there.
        Path oldFile = PROJECT.resolve("src/main/java/demo/OldName.java");
        String oldText = Files.readString(oldFile);
        String oldUri = oldFile.toUri().toString();
        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(oldUri, "java", 1, oldText)));
        Thread.sleep(3000);
        int classLine = -1;
        String[] oldLines = oldText.split("\n");
        for (int i = 0; i < oldLines.length; i++) {
            if (oldLines[i].startsWith("public class OldName")) {
                classLine = i;
            }
        }
        int nameCol = oldLines[classLine].indexOf("OldName") + 2;
        System.out.println("\n=== RENAME at OldName.java " + classLine + ":" + nameCol + " ===");
        var rp = new RenameParams(new TextDocumentIdentifier(oldUri), new Position(classLine, nameCol), "NewName");
        WorkspaceEdit we = server.getTextDocumentService().rename(rp).get(60, TimeUnit.SECONDS);
        System.out.println("documentChanges = "
                + (we == null
                        ? "null"
                        : (we.getDocumentChanges() == null
                                ? "NULL"
                                : we.getDocumentChanges().size())));
        if (we != null && we.getDocumentChanges() != null) {
            for (var c : we.getDocumentChanges()) {
                System.out.println("  "
                        + (c.isLeft()
                                ? ("TextDocumentEdit "
                                        + c.getLeft().getTextDocument().getUri())
                                : ("RESOURCE-OP " + c.getRight().getClass().getSimpleName() + " " + c.getRight())));
            }
        }
        proc.destroyForcibly();
    }

    /** Exactly what Editora declares (mirrors LanguageServerSession.clientCapabilities). */
    private static ClientCapabilities editoraCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        var sigInfo = new SignatureInformationCapabilities(List.of("markdown", "plaintext"));
        sigInfo.setParameterInformation(new ParameterInformationCapabilities(true));
        sigInfo.setActiveParameterSupport(true);
        var sigCaps = new SignatureHelpCapabilities(sigInfo, false); // 2nd arg = dynamicRegistration!
        sigCaps.setContextSupport(true);
        td.setSignatureHelp(sigCaps);
        td.setInlayHint(new InlayHintCapabilities());
        var rename = new RenameCapabilities();
        rename.setPrepareSupport(true);
        td.setRename(rename);
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        WorkspaceClientCapabilities ws = new WorkspaceClientCapabilities();
        ws.setApplyEdit(true);
        var wsEdit = new WorkspaceEditCapabilities();
        wsEdit.setDocumentChanges(true);
        wsEdit.setResourceOperations(
                List.of(ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete));
        ws.setWorkspaceEdit(wsEdit);
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
    }
}
