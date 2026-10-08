package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A code-lens request is superseded whenever the view scrolls. Its listing has usually been answered by
 * then, and what is still running are the per-lens {@code codeLens/resolve} requests — with jdtls a
 * reference search each. They were left running: the cancellation was checked on an inner stage the caller
 * never holds, so abandoning the request told the server nothing.
 */
class LspCodeLensCancelTest {

    private static final String URI = "file:///tmp/Demo.java";

    private static CodeLens unresolved(int line, int references) {
        var lens = new CodeLens(new Range(new Position(line, 0), new Position(line, 1)));
        lens.setData(references);
        return lens;
    }

    @Test
    void cancellingALensRequestCancelsTheResolvesItStarted() {
        var caps = new ServerCapabilities();
        caps.setCodeLensProvider(new CodeLensOptions(true));
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of());
        var session = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (type, message) -> {});
        var fake = new FakeLanguageServer();
        session.attachForTest(fake, caps);
        fake.codeLensResponse = List.of(unresolved(1, 7), unresolved(2, 8));
        fake.holdReplies = true; // the listing is answered below; the resolves it starts stay open

        CompletableFuture<List<CodeLens>> request = session.codeLens(URI, 0, 10);
        assertEquals(1, fake.held.size(), "the listing is on the wire");
        @SuppressWarnings("unchecked")
        CompletableFuture<List<? extends CodeLens>> listing =
                (CompletableFuture<List<? extends CodeLens>>) fake.held.get(0);
        listing.complete(fake.codeLensResponse);
        assertEquals(3, fake.held.size(), "both lenses are being resolved");
        assertFalse(request.isDone());

        request.cancel(true);

        assertTrue(fake.held.get(1).isCancelled(), "the first resolve was left running on the server");
        assertTrue(fake.held.get(2).isCancelled(), "the second resolve was left running on the server");
    }

    /** The other order: abandoned while the listing itself is still unanswered. */
    @Test
    void cancellingBeforeTheListingArrivesCancelsTheListing() {
        var caps = new ServerCapabilities();
        caps.setCodeLensProvider(new CodeLensOptions(true));
        var spec = new LspServerRegistry.ServerSpec("java", List.of("jdtls"), List.of());
        var session = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (type, message) -> {});
        var fake = new FakeLanguageServer();
        session.attachForTest(fake, caps);
        fake.holdReplies = true;

        CompletableFuture<List<CodeLens>> request = session.codeLens(URI, 0, 10);
        request.cancel(true);

        assertTrue(fake.held.get(0).isCancelled());
        assertEquals(1, fake.held.size(), "nothing was resolved for an answer that never came");
    }
}
