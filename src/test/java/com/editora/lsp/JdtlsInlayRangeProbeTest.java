package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ParameterInformationCapabilities;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SignatureInformationCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — the root cause of #715, and the fix, against a real jdtls.
 *
 * <p>jdtls answers a {@code textDocument/inlayHint} whose range reaches <b>past the last line</b> with an
 * empty list rather than an error — indistinguishable from "this file has no hints". Editora's padded window
 * always did exactly that, so inlay hints silently never appeared.
 *
 * <p>The off-by-one is subtle enough that an earlier pass wrongly ruled the range out: its "in-range control"
 * asked for {@code Position(lineCount, 0)} believing that to be the document end, when a document of
 * {@code lineCount} lines ends at line {@code lineCount - 1}. Both sides of that comparison were out of
 * range, so both read 0 and the hypothesis looked dead. Hence the explicit three-way here.
 *
 * <ol>
 *   <li><b>BROKEN</b> — {@code Position(lineCount, 0)}, the old behaviour (one past the last line)</li>
 *   <li><b>FIXED</b> — the range {@link LspManager#inclusiveLineRange} produces, i.e. the production path</li>
 *   <li><b>FIXED, padded</b> — the same through the coordinator's over-scan, which is what actually runs
 *       (the clamp has to survive the padding)</li>
 * </ol>
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsInlayRangeProbeTest -Dgroups=probe -Dlsp.probe=true}
 */
@Tag("probe")
class JdtlsInlayRangeProbeTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.home"), "src/adl/lsp-test-fixture");
    private static final String JDTLS = "/opt/homebrew/bin/jdtls";

    /** LspCoordinator.SEMANTIC_WINDOW_PAD. */
    private static final int PAD = 200;

    @Test
    void outOfRangeEndYieldsNothingWhileTheClampedRangeWorks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(JDTLS)), "needs a local jdtls");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(PROJECT), "needs the local fixture project");

        Path file = PROJECT.resolve("src/main/java/demo/App.java");
        String text = Files.readString(file);
        String uri = file.toUri().toString();
        String[] lines = text.split("\n", -1);
        int lineCount = lines.length;
        int lastLine = lineCount - 1;
        int lastLineLength = lines[lastLine].length();

        Path data = Files.createTempDirectory("jdtls-715");
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
        ip.setCapabilities(editoraCapabilities());
        ip.setInitializationOptions(LspManager.javaInitOptions(List.of())); // exactly what Editora sends
        server.initialize(ip).get(180, TimeUnit.SECONDS);
        server.initialized(new InitializedParams());
        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, text)));

        System.out.println("waiting 45s for the project import…");
        Thread.sleep(45_000);
        server.getWorkspaceService()
                .didChangeConfiguration(new DidChangeConfigurationParams(
                        Map.of("java", Map.of("signatureHelp", Map.of("enabled", true, "description", true)))));
        Thread.sleep(3_000);

        System.out.println("\ndocument: " + lineCount + " lines ⇒ last line index " + lastLine + ", last line "
                + lastLineLength + " chars");

        System.out.println("\n=== 1. BROKEN (pre-fix): end = Position(" + lineCount + ",0) ===");
        int broken = report(server, uri, new Range(new Position(0, 0), new Position(lineCount, 0)));

        System.out.println("\n=== 2. FIXED: LspManager.inclusiveLineRange(0, " + lastLine + ", …) ===");
        int fixed = report(server, uri, LspManager.inclusiveLineRange(0, lastLine, lineCount, lastLineLength));

        System.out.println("\n=== 3. FIXED with the coordinator's +" + PAD + " over-scan ===");
        int padded =
                report(server, uri, LspManager.inclusiveLineRange(-PAD, lastLine + PAD, lineCount, lastLineLength));

        System.out.println("\n##### VERDICT: broken=" + broken + "  fixed=" + fixed + "  fixed+padded=" + padded);
        proc.destroyForcibly();
    }

    private static int report(LanguageServer server, String uri, Range range) throws Exception {
        System.out.println("   range " + range.getStart().getLine() + ":"
                + range.getStart().getCharacter() + " .. "
                + range.getEnd().getLine() + ":" + range.getEnd().getCharacter());
        var hints = server.getTextDocumentService()
                .inlayHint(new InlayHintParams(new TextDocumentIdentifier(uri), range))
                .get(30, TimeUnit.SECONDS);
        int n = hints == null ? 0 : hints.size();
        System.out.println("   RESULT: " + n + " hints");
        if (hints != null) {
            hints.forEach(h -> System.out.println("      line "
                    + h.getPosition().getLine() + " char "
                    + h.getPosition().getCharacter() + "  "
                    + (h.getLabel().isLeft()
                            ? h.getLabel().getLeft()
                            : h.getLabel().getRight())));
        }
        return n;
    }

    /** Mirrors {@code LanguageServerSession.clientCapabilities} (as {@code JdtlsProbeTest} does). */
    private static ClientCapabilities editoraCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        var sigInfo = new SignatureInformationCapabilities(List.of("markdown", "plaintext"));
        sigInfo.setParameterInformation(new ParameterInformationCapabilities(true));
        sigInfo.setActiveParameterSupport(true);
        var sigCaps = new SignatureHelpCapabilities(sigInfo, false);
        sigCaps.setContextSupport(true);
        td.setSignatureHelp(sigCaps);
        td.setInlayHint(new InlayHintCapabilities());
        td.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true)));
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
