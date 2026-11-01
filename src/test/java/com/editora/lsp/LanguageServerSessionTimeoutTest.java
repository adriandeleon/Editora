package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML review A11: a hung server is reported. Two requests in a row that run out of time say "not responding" once,
 * with Restart Language Servers; the next answer takes it back. One slow request says nothing.
 */
class LanguageServerSessionTimeoutTest {

    private final List<String> said = new CopyOnWriteArrayList<>();

    private LanguageServerSession session() {
        return session("html");
    }

    private LanguageServerSession session(String id) {
        var spec = new LspServerRegistry.ServerSpec(id, List.of(id + "-language-server"), List.of());
        LanguageServerSession s = new LanguageServerSession(
                spec, Path.of("/tmp"), d -> {}, (type, message) -> said.add(type + ": " + message), null);
        s.initializedForTest();
        return s;
    }

    private static org.eclipse.lsp4j.ProgressParams progress(
            String token, org.eclipse.lsp4j.WorkDoneProgressNotification n) {
        return new org.eclipse.lsp4j.ProgressParams(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(token),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(n));
    }

    private List<String> notResponding() {
        return said.stream()
                .filter(m -> m.equals("Message: " + tr("lsp.status.notResponding")))
                .toList();
    }

    private static void timeOut(LanguageServerSession s) throws InterruptedException {
        CompletableFuture<Object> request = new CompletableFuture<>();
        LanguageServerSession.bounded(request, java.time.Duration.ofMillis(20), s);
        long until = System.currentTimeMillis() + 5_000;
        while (!request.isDone() && System.currentTimeMillis() < until) {
            Thread.sleep(5);
        }
        assertTrue(request.isCancelled());
    }

    @Test
    void twoTimeoutsInARowSayNotRespondingOnceAndAnAnswerTakesItBack() throws Exception {
        LanguageServerSession s = session();
        timeOut(s);
        assertEquals(List.of(), said, "one slow request is not a hung server");
        assertFalse(s.notResponding());

        timeOut(s);
        timeOut(s);
        assertEquals(List.of("Message: " + tr("lsp.status.notResponding")), said, "said once");
        assertTrue(s.notResponding());

        CompletableFuture<Object> answered = new CompletableFuture<>();
        LanguageServerSession.bounded(answered, java.time.Duration.ofSeconds(30), s);
        answered.complete("ok");
        assertEquals(
                List.of("Message: " + tr("lsp.status.notResponding"), "Message: " + tr("lsp.status.respondingAgain")),
                said);
        assertFalse(s.notResponding());

        timeOut(s);
        assertEquals(2, said.size(), "the count starts again after an answer");
    }

    @Test
    void anErrorAnswerIsAnAnswerButALostSessionIsNot() throws Exception {
        LanguageServerSession s = session();
        timeOut(s);
        CompletableFuture<Object> refused = new CompletableFuture<>();
        LanguageServerSession.bounded(refused, java.time.Duration.ofSeconds(30), s);
        refused.completeExceptionally(new RuntimeException("internal error"));
        timeOut(s);
        assertEquals(List.of(), said, "the error answer between reset the count");

        CompletableFuture<Object> lost = new CompletableFuture<>();
        LanguageServerSession.bounded(lost, java.time.Duration.ofSeconds(30), s);
        lost.completeExceptionally(new IllegalStateException("language server not available"));
        timeOut(s);
        assertEquals(List.of("Message: " + tr("lsp.status.notResponding")), said);
    }

    @Test
    void aServerReportingProgressIsBusyNotHung() throws Exception {
        // HTML review 2 A104: gopls loading, Metals importing — requests wait behind work the server reports.
        LanguageServerSession s = session("gopls");
        var begin = new org.eclipse.lsp4j.WorkDoneProgressBegin();
        begin.setTitle("Loading packages");
        s.notifyProgress(progress("load", begin));
        assertTrue(s.busy());
        timeOut(s);
        timeOut(s);
        timeOut(s);
        assertEquals(List.of(), notResponding(), "busy, not hung");

        s.notifyProgress(progress("load", new org.eclipse.lsp4j.WorkDoneProgressEnd()));
        assertFalse(s.busy());
        timeOut(s);
        timeOut(s);
        assertEquals(1, notResponding().size(), "once the work has ended, timeouts count again");
    }

    @Test
    void jdtlsStillImportingIsNotCalledNotResponding() throws Exception {
        LanguageServerSession s = session(LspServerRegistry.JAVA_SERVER_ID);
        assertTrue(s.stillImporting());
        timeOut(s);
        timeOut(s);
        assertEquals(List.of(), notResponding(), "Restart would only start the import over");
        assertFalse(s.notResponding());
    }

    @Test
    void requestsSentBeforeTheHandshakeDoNotCount() throws Exception {
        var spec = new LspServerRegistry.ServerSpec("html", List.of("vscode-html-language-server"), List.of());
        LanguageServerSession s = new LanguageServerSession(
                spec, Path.of("/tmp"), d -> {}, (type, message) -> said.add(type + ": " + message), null);
        timeOut(s);
        timeOut(s);
        assertEquals(List.of(), said, "queued behind initialization");
        s.initializedForTest();
        timeOut(s);
        timeOut(s);
        assertEquals(1, notResponding().size());
    }
}
