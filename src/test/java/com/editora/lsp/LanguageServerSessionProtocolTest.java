package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpTriggerKind;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code LanguageServerSession} actually puts on the wire, asserted against a recording
 * {@link FakeLanguageServer} attached through {@code attachForTest} — no subprocess.
 *
 * <p>This class was 3.2%-covered before these tests, and it is where the request-shape bugs live: nothing
 * checked the params, only that the code compiled. Two of this cycle's four defects are pinned here as
 * regressions (#725 signature-help trigger kind, #715 out-of-range inlay range); the rest cover the document
 * lifecycle and the incremental-sync contract (#678), whose invariant — "the shadow always equals what the
 * server holds" — is otherwise only checked end-to-end by hand.
 */
class LanguageServerSessionProtocolTest {

    private static final String URI = "file:///tmp/Demo.java";

    private FakeLanguageServer fake;

    /** A session wired to a fresh fake, as if {@code initialize} had completed with {@code caps}. */
    private LanguageServerSession session(ServerCapabilities caps) {
        fake = new FakeLanguageServer();
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of("pom.xml"));
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);
        s.attachForTest(fake, caps);
        return s;
    }

    private static ServerCapabilities caps() {
        return new ServerCapabilities();
    }

    private static ServerCapabilities incrementalSyncCaps() {
        var c = new ServerCapabilities();
        var sync = new TextDocumentSyncOptions();
        sync.setChange(TextDocumentSyncKind.Incremental);
        c.setTextDocumentSync(sync);
        return c;
    }

    // --- #725: signature help must report the trigger character ------------------------------------

    /**
     * The auto-trigger path passes the typed character, so the server sees {@code TriggerCharacter} with it.
     * Before #725 the {@code TriggerCharacter} branch was unreachable — every request said {@code Invoked}.
     */
    @Test
    void signatureHelpReportsTheTriggerCharacter() {
        var s = session(caps());
        s.signatureHelp(URI, new Position(3, 10), "(", false);

        var p = FakeLanguageServer.last(fake.signatureHelps);
        assertNotNull(p, "no signatureHelp request was sent");
        assertEquals(SignatureHelpTriggerKind.TriggerCharacter, p.getContext().getTriggerKind());
        assertEquals("(", p.getContext().getTriggerCharacter());
        assertFalse(p.getContext().isRetrigger());
    }

    /** The explicit command (no character) stays {@code Invoked} and carries no trigger character. */
    @Test
    void manualSignatureHelpIsInvoked() {
        var s = session(caps());
        s.signatureHelp(URI, new Position(3, 10), null, false);

        var p = FakeLanguageServer.last(fake.signatureHelps);
        assertEquals(SignatureHelpTriggerKind.Invoked, p.getContext().getTriggerKind());
        assertNull(p.getContext().getTriggerCharacter());
        assertFalse(p.getContext().isRetrigger());
    }

    /** Refreshing an open popup is a re-trigger from a content change, not a fresh invocation — that is
     *  what lets a server keep the active overload stable while arguments are typed. */
    @Test
    void refreshingAnOpenPopupIsAContentChangeRetrigger() {
        var s = session(caps());
        s.signatureHelp(URI, new Position(3, 12), null, true);

        var p = FakeLanguageServer.last(fake.signatureHelps);
        assertEquals(SignatureHelpTriggerKind.ContentChange, p.getContext().getTriggerKind());
        assertTrue(p.getContext().isRetrigger());
    }

    // --- #715: the inlay-hint range must stay inside the document ----------------------------------

    /**
     * The whole-document window of a 27-line file must end at line 26, not 27. jdtls answers an out-of-range
     * range with an empty list rather than an error, so this off-by-one silently disabled inlay hints
     * entirely — and an empty response is indistinguishable from "this file has no hints".
     */
    @Test
    void inlayHintRangeNeverLeavesTheDocument() {
        var s = session(caps());
        s.inlayHint(URI, LspManager.inclusiveLineRange(0, 26, 27, 0));

        var p = FakeLanguageServer.last(fake.inlayHints);
        assertNotNull(p, "no inlayHint request was sent");
        assertEquals(26, p.getRange().getEnd().getLine(), "asked past the last line — the server returns nothing");
        assertEquals(0, p.getRange().getEnd().getCharacter());
    }

    /** A last line with content must still be covered to its end. */
    @Test
    void inlayHintRangeCoversALastLineWithContent() {
        var s = session(caps());
        s.inlayHint(URI, LspManager.inclusiveLineRange(0, 9, 10, 42));

        var p = FakeLanguageServer.last(fake.inlayHints);
        assertEquals(9, p.getRange().getEnd().getLine());
        assertEquals(42, p.getRange().getEnd().getCharacter());
    }

    // --- document lifecycle ------------------------------------------------------------------------

    @Test
    void didOpenCarriesTheLanguageIdVersionAndText() {
        var s = session(caps());
        s.didOpen(URI, "java", "class A {}");

        var p = FakeLanguageServer.last(fake.opened);
        assertEquals(URI, p.getTextDocument().getUri());
        assertEquals("java", p.getTextDocument().getLanguageId());
        assertEquals(1, p.getTextDocument().getVersion());
        assertEquals("class A {}", p.getTextDocument().getText());
    }

    @Test
    void didSaveAndDidCloseAddressTheDocument() {
        var s = session(caps());
        s.didOpen(URI, "java", "class A {}");
        s.didSave(URI);
        s.didClose(URI);

        assertEquals(URI, FakeLanguageServer.last(fake.saved).getTextDocument().getUri());
        assertEquals(URI, FakeLanguageServer.last(fake.closed).getTextDocument().getUri());
    }

    /** Document versions must increase — a server that sees a stale/repeated version may ignore the change. */
    @Test
    void documentVersionsIncreaseMonotonically() {
        var s = session(caps());
        s.didOpen(URI, "java", "a");
        s.didChange(URI, "ab");
        s.didChange(URI, "abc");

        assertEquals(2, fake.changed.size());
        int first = fake.changed.get(0).getTextDocument().getVersion();
        int second = fake.changed.get(1).getTextDocument().getVersion();
        assertTrue(second > first, "version went " + first + " → " + second);
        assertTrue(first > 1, "the didOpen version (1) must be superseded");
    }

    // --- #678: incremental sync --------------------------------------------------------------------

    /** Without an Incremental capability the whole text is sent (the conservative default). */
    @Test
    void fullSyncSendsTheWholeText() {
        var s = session(caps());
        s.didOpen(URI, "java", "hello");
        s.didChange(URI, "hello world");

        var events = FakeLanguageServer.last(fake.changed).getContentChanges();
        assertEquals(1, events.size());
        assertNull(events.get(0).getRange(), "full sync must not carry a range");
        assertEquals("hello world", events.get(0).getText());
    }

    /** Under Incremental sync only the minimal splice goes out, with the range in OLD-text coordinates. */
    @Test
    void incrementalSyncSendsOnlyTheSplice() {
        var s = session(incrementalSyncCaps());
        s.didOpen(URI, "java", "line one\nline two\n");
        s.didChange(URI, "line one\nline TWO\n");

        var events = FakeLanguageServer.last(fake.changed).getContentChanges();
        assertEquals(1, events.size());
        Range r = events.get(0).getRange();
        assertNotNull(r, "incremental sync must carry a range");
        assertEquals(1, r.getStart().getLine(), "the edit is on line 1");
        assertEquals("TWO", events.get(0).getText());
        assertTrue(
                events.get(0).getText().length() < "line one\nline TWO\n".length(),
                "a splice must be smaller than the whole document");
    }

    /** Re-sending identical text is skipped entirely — the server already holds it. */
    @Test
    void anIdenticalResyncSendsNothing() {
        var s = session(incrementalSyncCaps());
        s.didOpen(URI, "java", "same");
        s.didChange(URI, "same");

        assertTrue(fake.changed.isEmpty(), "no change event should be sent for identical content");
    }

    /** A server declaring sync kind None gets no didChange traffic at all. */
    @Test
    void syncKindNoneSuppressesChangeNotifications() {
        var c = new ServerCapabilities();
        var sync = new TextDocumentSyncOptions();
        sync.setChange(TextDocumentSyncKind.None);
        c.setTextDocumentSync(sync);

        var s = session(c);
        s.didOpen(URI, "java", "a");
        s.didChange(URI, "ab");

        assertTrue(fake.changed.isEmpty(), "sync kind None must suppress didChange");
    }

    // --- queue-until-ready -------------------------------------------------------------------------

    /**
     * Calls made before the handshake completes are queued and flushed in order once the server is attached
     * — and a queued {@code didChange} is collapsed to the latest text, so a long-initializing server does
     * not retain one full copy of the document per typing pause.
     */
    @Test
    void callsBeforeReadyAreQueuedAndTheLatestChangeWins() {
        fake = new FakeLanguageServer();
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of("pom.xml"));
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);

        s.didOpen(URI, "java", "v1");
        s.didChange(URI, "v2");
        s.didChange(URI, "v3");
        assertTrue(fake.opened.isEmpty(), "nothing may reach the server before it is ready");

        s.attachForTest(fake, caps());

        assertEquals(1, fake.opened.size(), "the queued didOpen must be flushed");
        assertEquals(1, fake.changed.size(), "superseded changes must collapse to one");
        assertEquals(
                "v3",
                FakeLanguageServer.last(fake.changed).getContentChanges().get(0).getText(),
                "the latest text must win");
    }

    /** A disposed session is inert: no traffic reaches the server. */
    @Test
    void aDisposedSessionSendsNothing() {
        var s = session(caps());
        s.dispose();
        s.didOpen(URI, "java", "a");
        s.didChange(URI, "b");
        s.didSave(URI);

        assertTrue(fake.opened.isEmpty() && fake.changed.isEmpty() && fake.saved.isEmpty());
    }

    // --- watched files (#677) ----------------------------------------------------------------------

    @Test
    void watchedFileEventsAreForwarded() {
        var s = session(caps());
        s.didChangeWatchedFiles(List.of(
                new org.eclipse.lsp4j.FileEvent("file:///tmp/Other.java", org.eclipse.lsp4j.FileChangeType.Changed)));

        var p = FakeLanguageServer.last(fake.watchedFiles);
        assertNotNull(p);
        assertEquals(1, p.getChanges().size());
        assertEquals("file:///tmp/Other.java", p.getChanges().get(0).getUri());
    }
}
