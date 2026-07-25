package com.editora.lsp;

import org.eclipse.lsp4j.ServerCapabilities;

/**
 * Test-only bridge exposing {@code com.editora.lsp}'s package-private test seams to tests in other packages
 * (notably {@code com.editora.ui}, where {@code LspCoordinator} lives). Test sources only — no production
 * class references it.
 */
public final class LspTestHooks {

    private LspTestHooks() {}

    /**
     * Makes {@code manager} attach an in-process fake to every session it creates instead of forking a real
     * language server. Without this a coordinator test would fork whatever happens to be installed on the
     * machine running it.
     */
    public static java.util.List<FakeLanguageServer> useFakeSessions(LspManager manager) {
        java.util.List<FakeLanguageServer> created = new java.util.concurrent.CopyOnWriteArrayList<>();
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            created.add(fake);
            session.attachForTest(fake, caps());
        });
        return created; // grows as sessions are created, so a test can set canned responses on one
    }

    /** Server capabilities advertising the providers the coordinator gates features on. */
    public static ServerCapabilities caps() {
        var caps = new ServerCapabilities();
        caps.setDocumentFormattingProvider(true);
        caps.setDocumentRangeFormattingProvider(true);
        caps.setCodeActionProvider(true);
        caps.setRenameProvider(true);
        caps.setDocumentHighlightProvider(true);
        caps.setDocumentSymbolProvider(true);
        return caps;
    }
}
