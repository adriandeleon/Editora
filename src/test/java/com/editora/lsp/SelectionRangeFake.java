package com.editora.lsp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** Test helper: the stock {@link FakeLanguageServer} plus a scripted {@code textDocument/selectionRange}. */
public final class SelectionRangeFake {
    private SelectionRangeFake() {}

    public static void install(LspManager manager, Function<Position, List<SelectionRange>> answer) {
        ServerCapabilities caps = LspTestHooks.caps();
        caps.setSelectionRangeProvider(true);
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            Object proxy = Proxy.newProxyInstance(
                    SelectionRangeFake.class.getClassLoader(),
                    new Class<?>[] {LanguageServer.class, TextDocumentService.class, WorkspaceService.class},
                    (p, m, args) -> {
                        if (m.getName().equals("selectionRange")) {
                            SelectionRangeParams prm = (SelectionRangeParams) args[0];
                            return CompletableFuture.completedFuture(
                                    answer.apply(prm.getPositions().get(0)));
                        }
                        if (m.getName().equals("getTextDocumentService")
                                || m.getName().equals("getWorkspaceService")) {
                            return p;
                        }
                        try {
                            return m.invoke(fake, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            session.attachForTest((LanguageServer) proxy, caps);
        });
    }
}
