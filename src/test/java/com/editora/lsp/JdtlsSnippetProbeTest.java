package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
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
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — what jdtls actually sends for a Java completion, and why
 * import completion looked useless.
 *
 * <p>Typing {@code import java.uti} and accepting the {@code java.util} proposal produced
 * {@code import java.util.*;} with the caret past the semicolon. The obvious readings were both wrong: the
 * server is not "only offering on-demand imports", and Editora was not picking the wrong field. jdtls sends
 * {@code textEdit.newText = "java.util.$&#123;0:*&#125;;"} with {@code insertTextFormat = Snippet} — the
 * {@code *} is a <b>placeholder to be selected</b>, exactly as VS Code shows it. Editora flattened every
 * server snippet to literal text, so the placeholder became an ordinary character with nothing selected.
 *
 * <p>Two further facts this records, because each ruled out a plausible alternative fix:
 *
 * <ul>
 *   <li>At {@code import java.uti} jdtls returns <b>packages only</b> — no types at all. So "show the
 *       classes too" is not something the client can filter its way into; the types arrive only once the
 *       trailing {@code .} is typed, which is what makes the selected {@code *} the whole flow.
 *   <li>Method proposals are snippets too ({@code add($&#123;1:e&#125;)}), so the same flattening left a
 *       literal {@code e} in the document — the same bug, on the far more common path.
 * </ul>
 *
 * <p>Run: {@code ./mvnw test -Dtest=JdtlsSnippetProbeTest -Dgroups=probe -Dlsp.probe=true}
 */
@Tag("probe")
class JdtlsSnippetProbeTest {

    private static final Path PROJECT = Path.of(System.getProperty("user.home"), "src/adl/lsp-test-fixture");
    private static final String JDTLS = "/opt/homebrew/bin/jdtls";

    @Test
    void importAndMethodProposalsAreSnippetsWithPlaceholders() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(JDTLS)), "needs a local jdtls");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(PROJECT), "needs the local fixture project");

        Path file = PROJECT.resolve("src/main/java/demo/App.java");
        String uri = file.toUri().toString();

        Path data = Files.createTempDirectory("jdtls-import-probe");
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

        // A half-typed import, then the same file with the trailing dot, then a member access. The document
        // is only ever opened in memory — the fixture on disk is never written.
        String halfImport = "package demo;\n\nimport java.util.ArrayList;\nimport java.uti\n\nclass Probe {\n}\n";
        String dotted = "package demo;\n\nimport java.util.ArrayList;\nimport java.util.\n\nclass Probe {\n}\n";
        String member = "package demo;\n\nimport java.util.ArrayList;\n\nclass Probe {\n  void go() {\n"
                + "    ArrayList<String> list = new ArrayList<>();\n    list.ad\n  }\n}\n";

        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 1, halfImport)));
        System.out.println("waiting 45s for the project import…");
        Thread.sleep(45_000);

        System.out.println("\n=== 1. `import java.uti` (line 3, char 15) ===");
        report(server, uri, halfImport, 3, 15);

        System.out.println("\n=== 2. `import java.util.` (line 3, char 17) ===");
        reopen(server, uri, dotted);
        report(server, uri, dotted, 3, 17);

        System.out.println("\n=== 3. `list.ad` (line 7, char 11) ===");
        reopen(server, uri, member);
        report(server, uri, member, 7, 11);

        proc.destroyForcibly();
    }

    /** Re-opens the document with fresh text (simpler than a versioned didChange for a one-shot probe). */
    private static void reopen(LanguageServer server, String uri, String text) throws Exception {
        server.getTextDocumentService()
                .didClose(new org.eclipse.lsp4j.DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "java", 2, text)));
        Thread.sleep(2_000);
    }

    private static void report(LanguageServer server, String uri, String text, int line, int character)
            throws Exception {
        var result = server.getTextDocumentService()
                .completion(new CompletionParams(new TextDocumentIdentifier(uri), new Position(line, character)))
                .get(60, TimeUnit.SECONDS);
        List<CompletionItem> items =
                result.isLeft() ? result.getLeft() : result.getRight().getItems();
        System.out.println("   " + items.size() + " items");
        for (CompletionItem it : items.subList(0, Math.min(items.size(), 8))) {
            String newText = it.getTextEdit() == null
                    ? null
                    : (it.getTextEdit().isLeft()
                            ? it.getTextEdit().getLeft().getNewText()
                            : it.getTextEdit().getRight().getNewText());
            System.out.println("      kind=" + it.getKind() + " format=" + it.getInsertTextFormat() + " label='"
                    + it.getLabel() + "' insertText=" + it.getInsertText() + " newText=" + newText);
        }
    }

    /** Mirrors {@code LanguageServerSession.clientCapabilities} for the completion half. */
    private static ClientCapabilities editoraCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        CompletionItemCapabilities item = new CompletionItemCapabilities(true); // snippetSupport
        item.setResolveSupport(new CompletionItemResolveSupportCapabilities(
                List.of("documentation", "detail", "additionalTextEdits")));
        td.setCompletion(new CompletionCapabilities(item));
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
