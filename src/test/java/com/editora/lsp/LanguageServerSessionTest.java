package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonParser;
import org.eclipse.lsp4j.Color;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkOptions;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.FileOperationFilter;
import org.eclipse.lsp4j.FileOperationOptions;
import org.eclipse.lsp4j.FileOperationPattern;
import org.eclipse.lsp4j.FileOperationsServerCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFilesParams;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a session asks its server for and makes of the answers, against an in-process server: the requests a
 * capability gates, the empty answer a missing, failed or null reply becomes, and the settings each setter pushes to
 * its own server only.
 */
class LanguageServerSessionTest {

    private static final String URI = "file:///p/page.html";

    /** A server whose answers a test sets: each request is recorded by name and answered by its future. */
    private static final class Answers implements LanguageServer, TextDocumentService, WorkspaceService {
        final List<String> asked = new ArrayList<>();
        final List<DidChangeConfigurationParams> configurations = new ArrayList<>();
        CompletableFuture<List<ColorInformation>> colors = CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<DocumentLink>> links = CompletableFuture.completedFuture(List.of());
        CompletableFuture<DocumentLink> resolved = CompletableFuture.completedFuture(null);
        CompletableFuture<WorkspaceEdit> willRename = CompletableFuture.completedFuture(null);
        CompletableFuture<List<SelectionRange>> selections = CompletableFuture.completedFuture(null);

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return this;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return this;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {}

        @Override
        public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}

        @Override
        public void didClose(DidCloseTextDocumentParams params) {}

        @Override
        public void didSave(DidSaveTextDocumentParams params) {}

        @Override
        public void didChangeConfiguration(DidChangeConfigurationParams params) {
            configurations.add(params);
        }

        @Override
        public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

        @Override
        public CompletableFuture<List<ColorInformation>> documentColor(DocumentColorParams params) {
            asked.add("documentColor");
            return colors;
        }

        @Override
        public CompletableFuture<List<DocumentLink>> documentLink(DocumentLinkParams params) {
            asked.add("documentLink");
            return links;
        }

        @Override
        public CompletableFuture<DocumentLink> documentLinkResolve(DocumentLink params) {
            asked.add("documentLinkResolve");
            return resolved;
        }

        @Override
        public CompletableFuture<WorkspaceEdit> willRenameFiles(RenameFilesParams params) {
            asked.add("willRenameFiles");
            return willRename;
        }

        @Override
        public CompletableFuture<List<SelectionRange>> selectionRange(SelectionRangeParams params) {
            asked.add("selectionRange");
            return selections;
        }
    }

    private static LanguageServerSession session(String serverId, Path root) {
        var spec = new LspServerRegistry.ServerSpec(serverId, List.of(serverId + "-server"), List.of());
        return new LanguageServerSession(spec, root, d -> {}, (type, message) -> {});
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(30, TimeUnit.SECONDS);
    }

    private static Range range(int line) {
        return new Range(new Position(line, 0), new Position(line, 4));
    }

    @Test
    void aSessionNotYetStartedAnswersEveryRequestEmpty() throws Exception {
        LanguageServerSession s = session("html", Path.of("/p"));
        DocumentLink link = new DocumentLink(range(0));
        assertEquals(List.of(), get(s.documentColor(URI)));
        assertEquals(List.of(), get(s.documentLinks(URI)));
        assertSame(link, get(s.resolveDocumentLink(link)));
        assertNull(get(s.willRenameFiles("file:///p/a.html", "file:///p/b.html")), "it cannot say yet");
        assertEquals(List.of(), get(s.selectionRange(URI, List.of(new Position(0, 0)))));
        assertEquals(List.of(), get(s.foldingRange(URI)));
        assertTrue(get(s.typeDefinition(URI, new Position(0, 0))).getLeft().isEmpty());
        assertTrue(get(s.declaration(URI, new Position(0, 0))).getLeft().isEmpty());
        assertTrue(get(s.findLinks("superImplementation", URI, new Position(0, 0)))
                .getLeft()
                .isEmpty());
        CompletionItem item = new CompletionItem("x");
        assertSame(item, get(s.resolveCompletion(item)));
    }

    @Test
    void coloursAreAskedOnlyOfAServerThatHasThem() throws Exception {
        LanguageServerSession s = session("html", Path.of("/p"));
        Answers server = new Answers();
        s.attachForTest(server, new ServerCapabilities());
        assertEquals(List.of(), get(s.documentColor(URI)));
        assertEquals(List.of(), server.asked, "no colour provider: not asked");

        var caps = new ServerCapabilities();
        caps.setColorProvider(Boolean.TRUE);
        LanguageServerSession withColours = session("html", Path.of("/p"));
        withColours.attachForTest(server, caps);
        ColorInformation red = new ColorInformation(range(2), new Color(1, 0, 0, 1));
        server.colors = CompletableFuture.completedFuture(List.of(red));
        assertEquals(List.of(red), get(withColours.documentColor(URI)));
        server.colors = CompletableFuture.completedFuture(null);
        assertEquals(List.of(), get(withColours.documentColor(URI)), "a null answer is none");
        server.colors = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertEquals(List.of(), get(withColours.documentColor(URI)), "a failure is none");
        assertEquals(3, server.asked.size());
    }

    @Test
    void documentLinksAndTheirResolveFollowTheProvider() throws Exception {
        Answers server = new Answers();
        LanguageServerSession none = session("xml", Path.of("/p"));
        none.attachForTest(server, new ServerCapabilities());
        DocumentLink link = new DocumentLink(range(1));
        assertEquals(List.of(), get(none.documentLinks(URI)));
        assertSame(link, get(none.resolveDocumentLink(link)), "no provider: the link as it is");

        var plain = new ServerCapabilities();
        plain.setDocumentLinkProvider(new DocumentLinkOptions(false));
        LanguageServerSession s = session("xml", Path.of("/p"));
        s.attachForTest(server, plain);
        DocumentLink target = new DocumentLink(range(1), "file:///p/schema.xsd");
        server.links = CompletableFuture.completedFuture(List.of(target));
        assertEquals(List.of(target), get(s.documentLinks(URI)));
        server.links = CompletableFuture.completedFuture(null);
        assertEquals(List.of(), get(s.documentLinks(URI)));
        server.links = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertEquals(List.of(), get(s.documentLinks(URI)));
        assertSame(link, get(s.resolveDocumentLink(link)), "a provider that does not resolve");
        assertEquals(List.of("documentLink", "documentLink", "documentLink"), server.asked);

        var resolving = new ServerCapabilities();
        resolving.setDocumentLinkProvider(new DocumentLinkOptions(true));
        LanguageServerSession r = session("xml", Path.of("/p"));
        r.attachForTest(server, resolving);
        server.resolved = CompletableFuture.completedFuture(target);
        assertSame(target, get(r.resolveDocumentLink(link)));
        server.resolved = CompletableFuture.completedFuture(null);
        assertSame(link, get(r.resolveDocumentLink(link)), "resolved to nothing: the link as it was");
        server.resolved = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertSame(link, get(r.resolveDocumentLink(link)), "a failed resolve: the link as it was");
    }

    @Test
    void aFileRenameAsksForTheServersEditOnlyWhenItTakesThem() throws Exception {
        Answers server = new Answers();
        LanguageServerSession plain = session("typescript", Path.of("/p"));
        plain.attachForTest(server, new ServerCapabilities());
        assertEquals(new WorkspaceEdit(), get(plain.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")));

        var noOperations = new ServerCapabilities();
        noOperations.setWorkspace(new WorkspaceServerCapabilities());
        LanguageServerSession w = session("typescript", Path.of("/p"));
        w.attachForTest(server, noOperations);
        assertEquals(new WorkspaceEdit(), get(w.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")));
        var noWillRename = new WorkspaceServerCapabilities();
        noWillRename.setFileOperations(new FileOperationsServerCapabilities());
        var caps = new ServerCapabilities();
        caps.setWorkspace(noWillRename);
        LanguageServerSession o = session("typescript", Path.of("/p"));
        o.attachForTest(server, caps);
        assertEquals(new WorkspaceEdit(), get(o.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")));
        assertEquals(List.of(), server.asked, "none of them is asked");

        var operations = new FileOperationsServerCapabilities();
        operations.setWillRename(
                new FileOperationOptions(List.of(new FileOperationFilter(new FileOperationPattern("**/*.ts")))));
        var workspace = new WorkspaceServerCapabilities();
        workspace.setFileOperations(operations);
        var renaming = new ServerCapabilities();
        renaming.setWorkspace(workspace);
        LanguageServerSession s = session("typescript", Path.of("/p"));
        s.attachForTest(server, renaming);
        WorkspaceEdit edit = new WorkspaceEdit(
                Map.of("file:///p/main.ts", List.of(new TextEdit(range(0), "import { b } from './b';"))));
        server.willRename = CompletableFuture.completedFuture(edit);
        assertEquals(edit, get(s.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")));
        server.willRename = CompletableFuture.completedFuture(null);
        assertEquals(new WorkspaceEdit(), get(s.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")));
        server.willRename = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertNull(get(s.willRenameFiles("file:///p/a.ts", "file:///p/b.ts")), "a failure cannot say");
    }

    @Test
    void aNullOrFailedSelectionRangeIsNone() throws Exception {
        Answers server = new Answers();
        LanguageServerSession s = session("html", Path.of("/p"));
        s.attachForTest(server, new ServerCapabilities());
        assertEquals(List.of(), get(s.selectionRange(URI, List.of(new Position(0, 0)))));
        server.selections = CompletableFuture.failedFuture(new IllegalStateException("boom"));
        assertEquals(List.of(), get(s.selectionRange(URI, List.of(new Position(0, 0)))));
        SelectionRange word = new SelectionRange(range(0), null);
        server.selections = CompletableFuture.completedFuture(List.of(word));
        assertEquals(List.of(word), get(s.selectionRange(URI, List.of(new Position(0, 0)))));
    }

    @Test
    void jdtlsLinksKeepOnlyWholeLocations() throws Exception {
        LanguageServerSession s = session("java", Path.of("/p"));
        FakeLanguageServer fake = new FakeLanguageServer();
        s.setRawSinkForTest(fake.rawSink());
        s.attachForTest(fake, new ServerCapabilities());
        fake.rawResponse = JsonParser.parseString("""
                [
                  {"uri": "file:///p/Base.java",
                   "range": {"start": {"line": 3, "character": 4}, "end": {"line": 3, "character": 9}}},
                  {"uri": "file:///p/NoRange.java"},
                  {"range": {"start": {"line": 1, "character": 0}, "end": {"line": 1, "character": 1}}},
                  null
                ]
                """);
        var found = get(s.findLinks("superImplementation", "file:///p/Impl.java", new Position(5, 9)))
                .getLeft();
        assertEquals(1, found.size());
        assertEquals("file:///p/Base.java", found.get(0).getUri());
        assertEquals("java/findLinks", fake.rawRequests.get(0).method());

        fake.rawResponse = JsonParser.parseString("{\"not\": \"a list\"}");
        assertTrue(get(s.findLinks("superImplementation", "file:///p/Impl.java", new Position(5, 9)))
                .getLeft()
                .isEmpty());
        fake.rawResponse = null;
        assertTrue(get(s.findLinks("superImplementation", "file:///p/Impl.java", new Position(5, 9)))
                .getLeft()
                .isEmpty());
    }

    @Test
    void aCompletionIsResolvedOnlyByAServerThatResolves() throws Exception {
        FakeLanguageServer fake = new FakeLanguageServer();
        fake.completionResolver = c -> {
            c.setDetail("resolved");
            return c;
        };
        LanguageServerSession none = session("typescript", Path.of("/p"));
        none.attachForTest(fake, new ServerCapabilities());
        CompletionItem item = new CompletionItem("x");
        assertNull(get(none.resolveCompletion(item)).getDetail(), "no completion provider");
        var noResolve = new ServerCapabilities();
        noResolve.setCompletionProvider(new CompletionOptions(false, List.of()));
        LanguageServerSession plain = session("typescript", Path.of("/p"));
        plain.attachForTest(fake, noResolve);
        assertNull(get(plain.resolveCompletion(item)).getDetail(), "a provider that does not resolve");
        assertNull(get(plain.resolveCompletion(null)));
        assertTrue(fake.resolvedCompletions.isEmpty());

        var resolving = new ServerCapabilities();
        resolving.setCompletionProvider(new CompletionOptions(true, List.of()));
        LanguageServerSession s = session("typescript", Path.of("/p"));
        s.attachForTest(fake, resolving);
        assertEquals("resolved", get(s.resolveCompletion(item)).getDetail());
        fake.failEverything = true;
        CompletionItem other = new CompletionItem("y");
        assertSame(other, get(s.resolveCompletion(other)), "a failed resolve keeps the item");
    }

    // --- settings pushed to a running server --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(DidChangeConfigurationParams params, String name) {
        return (Map<String, Object>) ((Map<String, Object>) params.getSettings()).get(name);
    }

    @Test
    void thePythonInterpreterAndScopeArePushedToAPythonServerWhenTheyChange(@TempDir Path root) {
        LanguageServerSession s = session("python", root);
        FakeLanguageServer fake = new FakeLanguageServer();
        s.setPythonPath("/before/start/python"); // not running yet: kept for the start, nothing pushed
        s.attachForTest(fake, new ServerCapabilities());
        assertEquals("/before/start/python", s.pythonPath());

        s.setPythonPath("/venv/bin/python");
        assertEquals(1, fake.configurations.size());
        assertEquals(
                "/venv/bin/python",
                section(fake.configurations.get(0), "python").get("pythonPath"));
        s.setPythonPath("/venv/bin/python");
        assertEquals(1, fake.configurations.size(), "the same interpreter again: nothing to push");
        s.setPythonPath("  ");
        assertNull(s.pythonPath(), "blank is the server's own choice");
        assertEquals(2, fake.configurations.size());

        s.setPythonWorkspaceDiagnostics(true);
        assertEquals(3, fake.configurations.size());
        s.setPythonWorkspaceDiagnostics(true);
        assertEquals(3, fake.configurations.size());

        LanguageServerSession other = session("bash", root);
        FakeLanguageServer otherFake = new FakeLanguageServer();
        other.attachForTest(otherFake, new ServerCapabilities());
        other.setPythonPath("/venv/bin/python");
        other.setPythonWorkspaceDiagnostics(true);
        other.setJavaCleanups(List.of("qualifyMembers"));
        other.setJavaFilteredTypes(List.of("java.awt.*"));
        other.setJavaRuntimes(List.of(Map.of("name", "JavaSE-21")));
        other.setJavaDownloadSources(true);
        other.setJavaOnTypeFormatting(true);
        other.refreshProjectSettings();
        assertEquals(List.of(), otherFake.configurations, "none of these is the Bash server's");
    }

    @Test
    void jdtlsGetsEachChangedJavaSettingPushedWithTheRest(@TempDir Path root) {
        LanguageServerSession s = session(LspServerRegistry.JAVA_SERVER_ID, root);
        FakeLanguageServer fake = new FakeLanguageServer();
        s.attachForTest(fake, new ServerCapabilities());

        s.setJavaCleanups(List.of("qualifyMembers", "addOverride"));
        assertEquals(1, fake.configurations.size());
        Map<String, Object> java = section(fake.configurations.get(0), "java");
        assertEquals(Map.of("actions", List.of("qualifyMembers", "addOverride")), java.get("cleanup"));
        s.setJavaCleanups(List.of("qualifyMembers", "addOverride"));
        assertEquals(1, fake.configurations.size(), "unchanged");
        s.setJavaCleanups(null);
        assertEquals(2, fake.configurations.size());

        s.setJavaFilteredTypes(List.of("java.awt.*"));
        Map<?, ?> completion = (Map<?, ?>)
                section(FakeLanguageServer.last(fake.configurations), "java").get("completion");
        assertEquals(List.of("java.awt.*"), completion.get("filteredTypes"));
        s.setJavaFilteredTypes(List.of("java.awt.*"));
        s.setJavaFilteredTypes(null);
        assertEquals(4, fake.configurations.size());

        s.setJavaRuntimes(List.of(Map.of("name", "JavaSE-21", "path", "/jdk/21", "default", true)));
        assertEquals(List.of(Map.of("name", "JavaSE-21", "path", "/jdk/21", "default", true)), s.javaRuntimes());
        s.setJavaRuntimes(List.of(Map.of("name", "JavaSE-21", "path", "/jdk/21", "default", true)));
        assertEquals(5, fake.configurations.size());
        s.setJavaRuntimes(null);
        assertEquals(List.of(), s.javaRuntimes());

        s.setJavaDownloadSources(true);
        s.setJavaDownloadSources(true);
        s.setJavaOnTypeFormatting(true);
        s.setJavaOnTypeFormatting(true);
        s.refreshProjectSettings();
        assertEquals(9, fake.configurations.size());
    }

    @Test
    void anUntrustedJdtlsKeepsItsBuildImportsOffInEveryPush(@TempDir Path root) {
        LanguageServerSession s = session(LspServerRegistry.JAVA_SERVER_ID, root);
        s.setStartTrust(false, true, true);
        assertTrue(s.buildImportsOff());
        assertTrue(s.buildNotLoaded());
        assertTrue(!s.startedTrusted());
        FakeLanguageServer fake = new FakeLanguageServer();
        s.attachForTest(fake, new ServerCapabilities());

        s.refreshProjectSettings();

        Map<String, Object> java = section(fake.configurations.get(0), "java");
        Map<?, ?> imports = (Map<?, ?>) java.get("import");
        assertNotNull(imports, "the import section names the builds left off");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) imports.get("maven")).get("enabled"));
    }

    // --- progress, watched files, PATH --------------------------------------------------------------------

    private static org.eclipse.lsp4j.ProgressParams progress(
            String token, org.eclipse.lsp4j.WorkDoneProgressNotification value) {
        return new org.eclipse.lsp4j.ProgressParams(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(token),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(value));
    }

    @Test
    void eachProgressTokenIsBegunAndEndedOnce() {
        List<String> said = new ArrayList<>();
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of());
        var s = new LanguageServerSession(
                spec, Path.of("/p"), d -> {}, (type, message) -> said.add(type + ":" + message));
        var begin = new org.eclipse.lsp4j.WorkDoneProgressBegin();
        begin.setTitle("Importing");
        var end = new org.eclipse.lsp4j.WorkDoneProgressEnd();
        end.setMessage("Done");

        s.notifyProgress(null);
        s.notifyProgress(new org.eclipse.lsp4j.ProgressParams());
        s.notifyProgress(new org.eclipse.lsp4j.ProgressParams(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft("t"),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(new Object())));
        s.notifyProgress(progress("other", new org.eclipse.lsp4j.WorkDoneProgressReport()));
        s.notifyProgress(progress("other", end));
        assertEquals(List.of(), said, "nothing begun: a report or an end says nothing");

        s.notifyProgress(progress("t", begin));
        s.notifyProgress(progress("t", begin));
        s.notifyProgress(progress("t", new org.eclipse.lsp4j.WorkDoneProgressReport()));
        s.notifyProgress(progress("t", end));
        s.notifyProgress(progress("t", end));
        assertEquals("Progress:Importing", said.get(0));
        assertEquals(
                List.of("Progress", "ProgressReport", "ProgressEnd"),
                said.stream().map(x -> x.substring(0, x.indexOf(':'))).toList());
        assertEquals("ProgressEnd:Done", said.get(2));
        assertNull(LanguageServerSession.endText("50%"));
        assertNull(LanguageServerSession.endText(" "));
        assertEquals("Built", LanguageServerSession.endText(" Built "));
    }

    @Test
    void watchedFileEventsGoOnlyToARunningServer() {
        FakeLanguageServer fake = new FakeLanguageServer();
        LanguageServerSession s = session("java", Path.of("/p"));
        var event = new org.eclipse.lsp4j.FileEvent("file:///p/pom.xml", org.eclipse.lsp4j.FileChangeType.Changed);
        s.didChangeWatchedFiles(List.of(event));
        s.attachForTest(fake, new ServerCapabilities());
        s.didChangeWatchedFiles(List.of());
        s.didChangeWatchedFiles(null);
        assertEquals(List.of(), fake.watchedFiles);
        s.didChangeWatchedFiles(List.of(event));
        assertEquals(List.of(event), fake.watchedFiles.get(0).getChanges());
    }

    @Test
    void aFolderIsPutInFrontOfThePath() {
        assertEquals("/tools:/usr/bin", LanguageServerSession.prependedPath("/usr/bin", "/tools", ":"));
        assertEquals("/tools", LanguageServerSession.prependedPath(" ", "/tools", ":"));
        assertEquals("/tools", LanguageServerSession.prependedPath(null, "/tools", ":"));
        assertEquals("/usr/bin", LanguageServerSession.prependedPath("/usr/bin", " ", ":"));
    }
}
