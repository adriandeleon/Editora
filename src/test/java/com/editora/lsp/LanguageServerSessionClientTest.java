package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LanguageServerSession} as the <b>client</b> half of the protocol: what it does with the requests
 * and notifications a server sends it (progress, messages, {@code workspace/applyEdit}, capability
 * registrations, {@code workspace/configuration}), and how each of its own requests behaves when there is no
 * server to send it to.
 *
 * <p>The second half matters more than it looks. Every editor feature calls these methods without first
 * asking whether the server is up — a server takes seconds to start and can die at any time — so "not ready"
 * has to be an ordinary empty answer, never an exception and never a request put on a wire that is not there.
 */
class LanguageServerSessionClientTest {

    private static final String URI = "file:///tmp/Demo.java";
    private static final Position ORIGIN = new Position(0, 0);

    private final List<String> statuses = new ArrayList<>();
    private FakeLanguageServer fake;

    private LanguageServerSession unattached(String serverId) {
        var spec = new LspServerRegistry.ServerSpec(serverId, List.of(serverId + "-ls"), List.of());
        return new LanguageServerSession(
                spec, Path.of("/tmp"), d -> {}, (type, message) -> statuses.add(type + ":" + message));
    }

    private LanguageServerSession attached(String serverId, ServerCapabilities caps) {
        LanguageServerSession s = unattached(serverId);
        fake = new FakeLanguageServer();
        s.attachForTest(fake, caps);
        return s;
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    // --- no server to ask ----------------------------------------------------------------------------

    @Test
    void everyRequestAnswersEmptyWhileThereIsNoServer() throws Exception {
        LanguageServerSession s = unattached("java");
        var action = new CodeAction("fix");
        var item = new CompletionItem("x");

        assertEquals(List.of(), s.codeAction(URI, range(0, 0, 1), List.of()).get());
        assertSame(action, s.resolveCodeAction(action).get(), "an unresolvable action stays as it was listed");
        assertNull(s.signatureHelp(URI, ORIGIN, "(", false).get());
        assertEquals(List.of(), s.documentHighlight(URI, ORIGIN).get());
        assertNull(s.prepareRename(URI, ORIGIN).get());
        assertNull(s.rename(URI, ORIGIN, "y").get());
        assertEquals(List.of(), s.inlayHint(URI, range(0, 0, 1)).get());
        assertEquals(List.of(), s.codeLens(URI, 0, 10).get());
        assertEquals(List.of(), s.prepareCallHierarchy(URI, ORIGIN).get());
        assertEquals(
                List.of(),
                s.incomingCalls(new org.eclipse.lsp4j.CallHierarchyItem()).get());
        assertEquals(
                List.of(),
                s.outgoingCalls(new org.eclipse.lsp4j.CallHierarchyItem()).get());
        assertEquals(List.of(), s.prepareTypeHierarchy(URI, ORIGIN).get());
        var type = new org.eclipse.lsp4j.TypeHierarchyItem(
                "T", org.eclipse.lsp4j.SymbolKind.Class, URI, range(0, 0, 1), range(0, 0, 1));
        assertEquals(List.of(), s.supertypes(type).get());
        assertEquals(List.of(), s.subtypes(type).get());
        assertEquals(List.of(), s.completion(URI, ORIGIN).get().getLeft());
        assertEquals(
                List.of(), s.formatting(URI, new FormattingOptions(4, true)).get());
        assertEquals(
                List.of(),
                s.rangeFormatting(URI, range(0, 0, 1), new FormattingOptions(4, true))
                        .get());
        assertSame(item, s.resolveCompletion(item).get());
        assertNull(s.hover(URI, ORIGIN).get());
        assertEquals(List.of(), s.definition(URI, ORIGIN).get().getLeft());
        assertEquals(
                List.of(),
                s.onTypeFormatting(URI, ORIGIN, ";", new FormattingOptions(4, true))
                        .get());
        assertEquals(List.of(), s.implementation(URI, ORIGIN).get().getLeft());
        assertEquals(List.of(), s.typeDefinition(URI, ORIGIN).get().getLeft());
        assertEquals(List.of(), s.declaration(URI, ORIGIN).get().getLeft());
        assertEquals(List.of(), s.foldingRange(URI).get());
        assertEquals(List.of(), s.selectionRange(URI, List.of(ORIGIN)).get());
        assertEquals(List.of(), s.references(URI, ORIGIN).get());
        assertEquals(List.of(), s.workspaceSymbol("q").get().getLeft());
        assertEquals(List.of(), s.documentSymbol(URI).get());
        assertNull(s.diagnostic(URI).get());
        assertNull(s.semanticTokensRange(URI, range(0, 0, 1)).get());
        assertNull(s.semanticTokensFullDelta(URI, "r1").get());
        assertNull(s.semanticTokensFull(URI).get());
        s.didChangeWatchedFiles(List.of(new FileEvent(URI, FileChangeType.Changed))); // dropped, not queued
        assertFalse(s.isInitialized());
    }

    @Test
    void aDisposedSessionRefusesToRunCommandsOrRawRequests() {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        s.setRawSinkForTest(null);
        s.dispose();

        assertTrue(s.executeCommand("java.x", List.of()).isCompletedExceptionally());
        assertTrue(s.rawRequest("java/x", null).isCompletedExceptionally());
        s.rawNotify("java/y", null); // nothing to send to, nothing thrown
        assertTrue(fake.executedCommands.isEmpty());
    }

    /** A session whose launcher never came up (the in-process fake has none) cannot carry a custom request:
     *  it fails the future instead of throwing at the caller. */
    @Test
    void aRawRequestWithoutATransportFailsItsFuture() {
        LanguageServerSession s = attached("java", new ServerCapabilities());

        CompletableFuture<Object> request = s.rawRequest("java/classFileContents", Map.of());
        s.rawNotify("java/projectConfigurationUpdate", Map.of());

        assertTrue(request.isCompletedExceptionally());
    }

    @Test
    void anExecuteCommandThatThrowsFailsItsFuture() {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        fake.executeCommandHandler = params -> {
            throw new IllegalStateException("the transport is gone");
        };

        assertTrue(s.executeCommand("java.x", null).isCompletedExceptionally());
    }

    @Test
    void executeCommandSendsAnEmptyArgumentListForNull() throws Exception {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        fake.executeCommandResponse = "done";

        assertEquals("done", s.executeCommand("java.x", null).get());
        assertEquals(List.of(), fake.executedCommands.get(0).getArguments());
    }

    // --- progress and messages -----------------------------------------------------------------------

    @Test
    void workDoneProgressStartsAndStopsTheStatusAndIgnoresPerStepReports() {
        LanguageServerSession s = unattached("java");
        var begin = new WorkDoneProgressBegin();
        begin.setTitle(" Indexing ");
        begin.setMessage("demo");
        begin.setPercentage(40);
        var end = new WorkDoneProgressEnd();
        end.setMessage("done");

        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forLeft(begin)));
        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forLeft(new WorkDoneProgressReport())));
        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forLeft(end)));
        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forRight("a partial result, not progress")));
        s.notifyProgress(null);
        assertNull(s.createProgress(new org.eclipse.lsp4j.WorkDoneProgressCreateParams())
                .join());

        assertEquals(List.of("Progress:Indexing — demo (40%)", "ProgressEnd:done"), statuses);
    }

    @Test
    void progressTextKeepsOnlyThePartsItWasGiven() {
        assertEquals("…", LanguageServerSession.progressText(null, null, null));
        assertEquals("…", LanguageServerSession.progressText("  ", " ", null));
        assertEquals("Build", LanguageServerSession.progressText("Build", "", null));
        assertEquals("Build (0%)", LanguageServerSession.progressText("Build", null, 0));
        assertEquals("Build — step", LanguageServerSession.progressText("Build", " step ", null));
    }

    @Test
    void theJdtProgressChannelReportsOncePerTaskAndDropsARedundantPercentage() {
        LanguageServerSession s = unattached("java");
        var report = new LanguageServerSession.LanguageProgressReport();
        report.id = "import";
        report.task = "Importing";
        report.status = " 25% ";
        s.languageProgressReport(report);
        s.languageProgressReport(report); // the same task again: already announced
        s.languageProgressReport(null);

        var stray = new LanguageServerSession.LanguageProgressReport();
        stray.id = "never-started";
        stray.complete = true;
        s.languageProgressReport(stray); // an end for a task that was never begun stops nothing

        report.complete = true;
        report.status = "Imported";
        s.languageProgressReport(report);

        assertEquals(
                List.of("Progress:Importing", "ProgressEnd:Imported"),
                statuses,
                "no total: no percentage; a status that is only a percentage is not repeated as detail");
    }

    @Test
    void jdtStatusAndActionableMessagesReachTheStatusLine() {
        LanguageServerSession s = unattached("java");
        var status = new LanguageServerSession.LanguageStatus();
        s.languageStatus(status); // neither type nor message: still a (blank) status, not an exception
        status.type = "Starting";
        status.message = "  12% Starting Java Language Server ";
        s.languageStatus(status);
        s.languageStatus(null);

        var actionable = new LanguageServerSession.LanguageActionableNotification();
        s.languageActionableNotification(actionable);
        actionable.message = "  ";
        s.languageActionableNotification(actionable);
        actionable.message = " The project needs an update ";
        s.languageActionableNotification(actionable);
        s.languageActionableNotification(null);
        s.languageEventNotification(new LanguageServerSession.LanguageEventNotification());

        s.showMessage(new MessageParams(MessageType.Warning, "Low memory"));
        s.logMessage(new MessageParams(MessageType.Log, "not for the user"));
        assertNull(s.showMessageRequest(new org.eclipse.lsp4j.ShowMessageRequestParams())
                .join());

        assertEquals(
                List.of(
                        ":",
                        "Starting:12% Starting Java Language Server",
                        "Message:The project needs an update",
                        "Message:Low memory"),
                statuses);
    }

    /** A session being shut down keeps reading while its server exits; what the server says then may stop
     *  a loading bar but must never start one or speak. */
    @Test
    void aDisposedSessionMayOnlyStopTheLoadingBar() {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        s.dispose();
        statuses.clear();
        var begin = new WorkDoneProgressBegin();
        begin.setTitle("Shutting down");
        var end = new WorkDoneProgressEnd();
        end.setMessage("bye");

        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forLeft(begin)));
        s.showMessage(new MessageParams(MessageType.Info, "Shutting down"));
        s.notifyProgress(new ProgressParams(Either.forLeft("t"), Either.forLeft(end)));

        assertEquals(List.of("ProgressEnd:null"), statuses);
    }

    // --- workspace/applyEdit -------------------------------------------------------------------------

    @Test
    void aServerInitiatedEditIsHandedToTheHandlerAndItsAnswerReturned() throws Exception {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        var edit = new WorkspaceEdit();
        var seen = new AtomicReference<WorkspaceEdit>();
        var respond = new AtomicReference<Consumer<Boolean>>();
        s.setOnApplyEdit((e, r) -> {
            seen.set(e);
            respond.set(r);
        });

        var pending = s.applyEdit(new ApplyWorkspaceEditParams(edit));
        assertFalse(pending.isDone(), "the server waits for the editor to have applied it");
        respond.get().accept(true);
        assertTrue(pending.get().isApplied());
        assertSame(edit, seen.get());

        s.setOnApplyEdit((e, r) -> r.accept(null));
        assertFalse(s.applyEdit(null).get().isApplied(), "no answer is a no");
        s.setOnApplyEdit((e, r) -> {
            throw new IllegalStateException("the editor failed");
        });
        assertFalse(s.applyEdit(new ApplyWorkspaceEditParams(edit)).get().isApplied());
        s.setOnApplyEdit(null);
        assertFalse(s.applyEdit(new ApplyWorkspaceEditParams(edit)).get().isApplied(), "no handler refuses");
    }

    @Test
    void aSessionBeingShutDownMayNotEditTheWorkspace() throws Exception {
        LanguageServerSession s = attached("java", new ServerCapabilities());
        var called = new AtomicReference<>(false);
        s.setOnApplyEdit((e, r) -> {
            called.set(true);
            r.accept(true);
        });
        s.dispose();

        assertFalse(s.applyEdit(new ApplyWorkspaceEditParams(new WorkspaceEdit()))
                .get()
                .isApplied());
        assertFalse(called.get());
    }

    // --- dynamic capability registration -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "textDocument/completion",
                "textDocument/signatureHelp",
                "textDocument/hover",
                "textDocument/definition",
                "textDocument/implementation",
                "textDocument/typeDefinition",
                "textDocument/declaration",
                "textDocument/references",
                "textDocument/documentHighlight",
                "textDocument/documentSymbol",
                "textDocument/codeAction",
                "textDocument/formatting",
                "textDocument/rangeFormatting",
                "textDocument/onTypeFormatting",
                "textDocument/rename",
                "textDocument/foldingRange",
                "textDocument/selectionRange",
                "textDocument/prepareCallHierarchy",
                "textDocument/prepareTypeHierarchy",
                "textDocument/inlayHint",
                "textDocument/semanticTokens",
                "textDocument/diagnostic",
                "workspace/symbol",
                "workspace/executeCommand"
            })
    void aRegisteredCapabilityIsOnUntilItIsUnregistered(String method) {
        LanguageServerSession s = attached("typst", new ServerCapabilities());
        List<String> refreshed = new ArrayList<>();
        s.setOnRefresh(refreshed::add);
        String before = s.capabilities().toString();

        s.registerCapability(new RegistrationParams(List.of(new Registration("id-1", method))));
        String registered = s.capabilities().toString();
        assertFalse(before.equals(registered), method + " changed nothing");

        // Switched off again (a boolean capability reads false rather than absent, so the comparison is
        // with the registered state, and with what registering it once more gives).
        s.unregisterCapability(new UnregistrationParams(List.of(new Unregistration("id-1", method))));
        assertFalse(registered.equals(s.capabilities().toString()), method + " was not switched back off");
        s.registerCapability(new RegistrationParams(List.of(new Registration("id-2", method))));
        assertEquals(registered, s.capabilities().toString());
        assertEquals(
                List.of("capabilities", "capabilities", "capabilities"),
                refreshed,
                "each change re-gates the open buffers");
    }

    @Test
    void registrationsThatCannotBeUsedAreIgnored() {
        LanguageServerSession s = attached("typst", new ServerCapabilities());
        List<String> refreshed = new ArrayList<>();
        s.setOnRefresh(refreshed::add);
        String before = s.capabilities().toString();
        var entries = new ArrayList<Registration>();
        entries.add(null);
        entries.add(new Registration("w", "workspace/didChangeWatchedFiles")); // not a feature gate
        entries.add(LanguageServerSession.LSP_GSON.fromJson("{\"method\":\"textDocument/hover\"}", Registration.class));
        entries.add(LanguageServerSession.LSP_GSON.fromJson("{\"id\":\"x\"}", Registration.class));

        s.registerCapability(new RegistrationParams(entries));
        s.registerCapability(null);
        s.registerCapability(LanguageServerSession.LSP_GSON.fromJson("{}", RegistrationParams.class));
        var removals = new ArrayList<Unregistration>();
        removals.add(null);
        removals.add(new Unregistration("unknown", "workspace/didChangeWatchedFiles"));
        s.unregisterCapability(new UnregistrationParams(removals));
        s.unregisterCapability(null);
        s.unregisterCapability(LanguageServerSession.LSP_GSON.fromJson("{}", UnregistrationParams.class));

        assertEquals(before, s.capabilities().toString());
        assertEquals(List.of(), refreshed, "nothing changed, so nothing is re-gated");
    }

    /** Two registrations of one method: removing one leaves the feature on, with the other's options. */
    @Test
    void unregisteringOneOfTwoRegistrationsKeepsTheOther() {
        LanguageServerSession s = attached("typst", new ServerCapabilities());
        var first = new CompletionOptions(false, List.of("."));
        var second = new CompletionOptions(false, List.of("#"));
        s.registerCapability(new RegistrationParams(List.of(
                new Registration("a", "textDocument/completion", first),
                new Registration("b", "textDocument/completion", second))));
        assertEquals(List.of("#"), s.capabilities().getCompletionProvider().getTriggerCharacters());

        s.unregisterCapability(new UnregistrationParams(List.of(new Unregistration("b", "textDocument/completion"))));

        assertNotNull(s.capabilities().getCompletionProvider(), "one registration is still in force");
        assertEquals(List.of("."), s.capabilities().getCompletionProvider().getTriggerCharacters());
    }

    /** What the server declared in {@code initialize} is not undone by unregistering a dynamic duplicate. */
    @Test
    void aStaticCapabilitySurvivesTheRemovalOfADynamicRegistration() {
        var caps = new ServerCapabilities();
        caps.setHoverProvider(true);
        LanguageServerSession s = attached("typst", caps);

        s.registerCapability(new RegistrationParams(List.of(new Registration("h", "textDocument/hover"))));
        s.unregisterCapability(new UnregistrationParams(List.of(new Unregistration("h", "textDocument/hover"))));

        assertEquals(Either.forLeft(true), s.capabilities().getHoverProvider());
    }

    @Test
    void aRegistrationBeforeInitializeHasNothingToChange() {
        LanguageServerSession s = unattached("typst");
        List<String> refreshed = new ArrayList<>();
        s.setOnRefresh(refreshed::add);

        s.registerCapability(new RegistrationParams(List.of(new Registration("h", "textDocument/hover"))));

        assertNull(s.capabilities());
        s.setOnRefresh(null); // a missing hook is a no-op, not a null to trip over
        s.refreshDiagnostics();
        s.setOnDead(null);
        s.simulateServerDeathForTest();
    }

    // --- configuration and save ----------------------------------------------------------------------

    @Test
    void workspaceConfigurationIsAnsweredPerSection() throws Exception {
        LanguageServerSession s = unattached("css");
        var params = new ConfigurationParams(List.of(new ConfigurationItem(), new ConfigurationItem()));
        params.getItems().get(0).setSection("css");
        params.getItems().get(1).setSection("something.else");

        List<Object> answer = s.configuration(params).get();

        assertEquals(2, answer.size(), "one answer per item, in order");
        assertNotNull(answer.get(0), "the CSS server throws on null for its own section");
        assertNull(answer.get(1), "an unknown section keeps the server's default");
        assertEquals(List.of(), s.configuration(null).get());
        assertEquals(List.of(), s.configuration(new ConfigurationParams()).get());
    }

    @Test
    void theJavaOnTypePreferenceIsPushedToARunningJavaServerOnlyWhenItChanges() {
        LanguageServerSession java = attached("java", new ServerCapabilities());
        java.setJavaOnTypeFormatting(true);
        java.setJavaOnTypeFormatting(true);
        assertEquals(1, fake.configurations.size(), "an unchanged preference is not re-sent");
        java.setJavaOnTypeFormatting(false);
        assertEquals(2, fake.configurations.size());

        LanguageServerSession other = attached("typst", new ServerCapabilities());
        other.setJavaOnTypeFormatting(true);
        assertTrue(fake.configurations.isEmpty(), "it is jdtls's preference and nobody else's");

        LanguageServerSession starting = unattached("java");
        starting.setJavaOnTypeFormatting(true); // not ready: it goes out with initialize instead
    }

    @Test
    void theSyncKindIsReadFromEitherCapabilityForm() {
        var caps = new ServerCapabilities();
        assertNull(LanguageServerSession.changeSyncKind(null));
        assertNull(LanguageServerSession.changeSyncKind(caps));
        caps.setTextDocumentSync(TextDocumentSyncKind.Full);
        assertEquals(TextDocumentSyncKind.Full, LanguageServerSession.changeSyncKind(caps));
        caps.setTextDocumentSync(new TextDocumentSyncOptions());
        assertNull(LanguageServerSession.changeSyncKind(caps), "options that name no kind");
        caps.setTextDocumentSync(Either.forRight(null));
        assertNull(LanguageServerSession.changeSyncKind(caps));
    }

    @Test
    void saveTextIsIncludedOnlyWhenTheSaveOptionsAskForIt() {
        var options = new TextDocumentSyncOptions();
        var caps = new ServerCapabilities();
        caps.setTextDocumentSync(options);

        options.setSave(true); // the boolean form says "notify me", not "send the text"
        LanguageServerSession s = attached("java", caps);
        s.didSave(URI, "text");
        assertNull(fake.saved.get(0).getText());

        options.setSave(new SaveOptions(false));
        s.didSave(URI, "text");
        assertNull(fake.saved.get(1).getText());

        options.setSave(Either.forRight(null));
        s.didSave(URI, "text");
        assertNull(fake.saved.get(2).getText());

        options.setSave(new SaveOptions(true));
        s.didSave(URI, "text");
        assertEquals("text", fake.saved.get(3).getText());

        caps.setTextDocumentSync(TextDocumentSyncKind.Full); // no options at all
        s.didSave(URI, "text");
        assertNull(fake.saved.get(4).getText());
    }

    // --- code lenses ---------------------------------------------------------------------------------

    private static CodeLens lens(int line, String title, Object data) {
        var lens = new CodeLens(range(line, 0, 1));
        if (title != null) {
            lens.setCommand(new Command(title, "java.show.references"));
        }
        lens.setData(data);
        return lens;
    }

    @Test
    void onlyTheLensesInViewAreResolved() throws Exception {
        var caps = new ServerCapabilities();
        caps.setCodeLensProvider(new CodeLensOptions(true));
        LanguageServerSession s = attached("java", caps);
        var above = lens(1, null, 7);
        var inView = lens(12, null, 3);
        var ready = lens(13, "5 references", null);
        var below = lens(90, null, 9);
        fake.codeLensResponse = List.of(above, inView, ready, below);

        List<CodeLens> lenses = s.codeLens(URI, 10, 20).get();

        assertEquals(
                List.of("3 references", "5 references"),
                lenses.stream().map(l -> l.getCommand().getTitle()).toList());
        assertEquals(List.of(inView), fake.resolvedCodeLenses, "a lens nobody is looking at costs no search");
    }

    @Test
    void aServerThatCannotResolveLensesIsNotAskedTo() throws Exception {
        var caps = new ServerCapabilities();
        caps.setCodeLensProvider(new CodeLensOptions(false));
        LanguageServerSession s = attached("java", caps);
        fake.codeLensResponse = List.of(lens(1, null, 7), lens(2, "1 reference", null));

        List<CodeLens> lenses = s.codeLens(URI, 0, 10).get();

        assertEquals(1, lenses.size(), "a lens with no command and no way to get one is not shown");
        assertTrue(fake.resolvedCodeLenses.isEmpty());

        fake.codeLensResponse = null;
        assertEquals(List.of(), s.codeLens(URI, 0, 10).get());
        s.capabilities().setCodeLensProvider(null);
        fake.codeLensResponse = List.of(lens(2, "1 reference", null));
        assertEquals(1, s.codeLens(URI, 0, 10).get().size());
    }
}
