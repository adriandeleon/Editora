package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.editora.config.Settings;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bash session puts on the wire to work around bash-language-server (Bash review B1, B2, B7, B8, C4), asserted
 * against an in-process fake: the protocol shims live in the session, so no real server is needed.
 */
class BashSessionShimsTest {

    private static final String MAIN = "file:///srv/proj/main.sh";
    private static final String TOOL = "file:///srv/proj/tool";
    private static final String UTIL = "file:///srv/proj/lib/util.sh";

    private final List<PublishDiagnosticsParams> published = new CopyOnWriteArrayList<>();

    private LanguageServerSession session(String serverId) {
        var spec = new LspServerRegistry.ServerSpec(serverId, List.of(serverId), List.of());
        return new LanguageServerSession(spec, Path.of("/srv/proj"), published::add, (t, m) -> {});
    }

    private static Range range(int l1, int c1, int l2, int c2) {
        return new Range(new Position(l1, c1), new Position(l2, c2));
    }

    /**
     * Two scripts opened while the server starts: both queue behind {@code initialize}. Once its own {@code
     * workspace/configuration} has been answered each is sent again, whole, with a new version — the server linted
     * only the last one before (B1, D1). Only the first answer does it.
     */
    @Test
    void scriptsOpenedWhileTheServerStartsAreSentAgainOnceItIsConfigured() {
        LanguageServerSession session = session("bash");
        session.didOpen(MAIN, "shellscript", "echo $1\n");
        session.didOpen(TOOL, "shellscript", "ls $(ls)\n");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        assertEquals(2, fake.opened.size());
        assertTrue(fake.changed.isEmpty(), "nothing re-sent before the server is configured");

        session.configurationAnswered();

        assertEquals(2, fake.changed.size(), "every open script again");
        for (var change : fake.changed) {
            assertEquals(2, change.getTextDocument().getVersion());
            assertEquals(1, change.getContentChanges().size());
            assertTrue(change.getContentChanges().get(0).getRange() == null, "the whole text");
        }
        assertEquals(
                java.util.Set.of(MAIN, TOOL),
                fake.changed.stream()
                        .map(c -> c.getTextDocument().getUri())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, session.documentVersion(MAIN));

        session.configurationAnswered();
        assertEquals(2, fake.changed.size(), "a later configuration request re-sends nothing");
    }

    @Test
    void otherServersAreNotResentAfterTheirConfiguration() {
        LanguageServerSession session = session("python");
        session.didOpen("file:///srv/proj/a.py", "python", "x = 1\n");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        session.configurationAnswered();
        assertTrue(fake.changed.isEmpty());
    }

    /** The re-sync runs where every other document sync is sent from (the FX thread in the app). */
    @Test
    void theResyncIsHandedToTheClientThread() {
        LanguageServerSession session = session("bash");
        session.didOpen(MAIN, "shellscript", "echo $1\n");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        List<Runnable> handed = new ArrayList<>();
        session.setClientThread(handed::add);
        session.configurationAnswered();
        assertTrue(fake.changed.isEmpty(), "not on the reader thread");
        assertEquals(1, handed.size());
        handed.get(0).run();
        assertEquals(1, fake.changed.size());
    }

    /** The configuration answer is on its way out before the re-sync is started: the hook runs after the consumer. */
    @Test
    void theHookRunsAfterTheRequestHasBeenHandledAndOnlyForItsMethod() {
        List<String> order = new ArrayList<>();
        var consumer = new AfterRequestConsumer(
                m -> order.add("handled " + (m instanceof RequestMessage r ? r.getMethod() : "note")),
                "workspace/configuration",
                () -> order.add("after"));
        RequestMessage configuration = new RequestMessage();
        configuration.setMethod("workspace/configuration");
        RequestMessage other = new RequestMessage();
        other.setMethod("window/workDoneProgress/create");
        NotificationMessage note = new NotificationMessage();
        note.setMethod("workspace/configuration");
        consumer.consume(other);
        consumer.consume(note);
        consumer.consume(configuration);
        assertEquals(
                List.of(
                        "handled window/workDoneProgress/create",
                        "handled note",
                        "handled workspace/configuration",
                        "after"),
                order);
    }

    /** 5.6 re-lints only the last-touched script on a settings push; every open one is sent again (B8, D3). */
    @Test
    void aSettingsChangeSendsEveryOpenScriptAgain() {
        LanguageServerSession session = session("bash");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        session.setBashIde(BashIdeConfig.of(new Settings()));
        session.didOpen(MAIN, "shellscript", "echo $1\n");
        session.didOpen(TOOL, "shellscript", "ls $(ls)\n");
        int pushes = fake.configurations.size();
        Settings s = new Settings();
        s.setBashShellcheckArguments("--severity=warning");

        session.setBashIde(BashIdeConfig.of(s));

        assertEquals(pushes + 1, fake.configurations.size());
        assertEquals(2, fake.changed.size(), "both scripts re-linted with the new arguments");
        session.setBashIde(BashIdeConfig.of(s));
        assertEquals(2, fake.changed.size(), "an unchanged configuration re-sends nothing");
    }

    /**
     * Find References from a call: asked with the declaration included, as the server reads {@code false} as
     * "without the occurrence under the caret", and the declaration — where the definition starts — left out (C4).
     */
    @Test
    void referencesIncludeTheCallUnderTheCaretAndNotTheDeclaration() throws Exception {
        LanguageServerSession session = session("bash");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        Location call = new Location(MAIN, range(7, 2, 7, 10));
        Location declaration = new Location(UTIL, range(4, 0, 4, 8));
        fake.referenceResponse = List.of(call, declaration);
        fake.definitionResponse = List.of(new Location(UTIL, range(4, 0, 6, 1)));

        var refs = session.references(MAIN, new Position(7, 4)).get(5, TimeUnit.SECONDS);

        assertTrue(FakeLanguageServer.last(fake.references).getContext().isIncludeDeclaration());
        assertEquals(List.of(call), refs);
    }

    @Test
    void otherServersAreAskedForUsagesOnly() throws Exception {
        LanguageServerSession session = session("python");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        session.references("file:///srv/proj/a.py", new Position(0, 0)).get(5, TimeUnit.SECONDS);
        assertFalse(FakeLanguageServer.last(fake.references).getContext().isIncludeDeclaration());
        assertTrue(fake.definitions.isEmpty());
    }

    /** ShellCheck's code-point columns are made UTF-16 against the text the server holds (B2). */
    @Test
    void shellCheckDiagnosticsAndFixesAreMeasuredInUtf16() throws Exception {
        LanguageServerSession session = session("bash");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        String uri = "file:///srv/proj/utf.sh";
        session.didOpen(uri, "shellscript", "echo \"😀😀\" $1 end\n");
        Diagnostic d = new Diagnostic(range(0, 10, 0, 12), "Double quote to prevent globbing");
        d.setSource("shellcheck");

        session.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<>(List.of(d)), 1));

        assertEquals(
                range(0, 12, 0, 14), published.get(0).getDiagnostics().get(0).getRange());

        Diagnostic fixed = new Diagnostic(range(0, 10, 0, 12), "Double quote to prevent globbing");
        fixed.setSource("shellcheck");
        CodeAction fix = new CodeAction("Apply fix for SC2086");
        fix.setDiagnostics(List.of(fixed));
        fix.setEdit(new WorkspaceEdit(Map.of(
                uri, List.of(new TextEdit(range(0, 12, 0, 12), "\""), new TextEdit(range(0, 10, 0, 10), "\"")))));
        fake.codeActionResponse = List.of(Either.forRight(fix));

        var actions = session.codeAction(uri, range(0, 12, 0, 12), List.of()).get(5, TimeUnit.SECONDS);

        var edits = actions.get(0).getRight().getEdit().getChanges().get(uri);
        assertEquals(range(0, 14, 0, 14), edits.get(0).getRange());
        assertEquals(range(0, 12, 0, 12), edits.get(1).getRange());
    }

    /** A script changed on disk and not open is opened and closed again, so the server reads it afresh (B7). */
    @Test
    void aClosedScriptChangedOnDiskIsOpenedAndClosedAgain() {
        LanguageServerSession session = session("bash");
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        session.didOpen(MAIN, "shellscript", "echo $1\n");

        session.reanalyze(UTIL, "new_helper() { :; }\n");
        session.reanalyze(MAIN, "changed on disk\n");

        assertEquals(2, fake.opened.size(), "the open script is left to its window");
        var reopened = fake.opened.get(1).getTextDocument();
        assertEquals(UTIL, reopened.getUri());
        assertEquals("shellscript", reopened.getLanguageId());
        assertEquals("new_helper() { :; }\n", reopened.getText());
        assertEquals(
                UTIL, FakeLanguageServer.last(fake.closed).getTextDocument().getUri());
        assertFalse(session.isOpen(UTIL), "nothing is left open");
    }
}
