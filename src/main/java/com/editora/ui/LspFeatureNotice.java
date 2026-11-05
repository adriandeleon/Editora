package com.editora.ui;

import java.nio.file.Path;
import java.util.Locale;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the status line says when a feature is on but the active file's language server does not give it: Pyright
 * has no inlay hints, semantic tokens or code lenses, and turning one on in a Python file showed nothing and said
 * nothing; the inlay-hint filter offered its choices there too (Python review 2 C107 / E108).
 */
final class LspFeatureNotice {

    /** The features a toggle can name. */
    enum Feature {
        INLAY_HINTS("status.lsp.featureMissing.inlayHints"),
        SEMANTIC_TOKENS("status.lsp.featureMissing.semanticTokens"),
        CODE_LENS("status.lsp.featureMissing.codeLens"),
        /** The inlay-hint filter picker: it has nothing to filter. */
        INLAY_HINT_FILTER("status.lsp.featureMissing.inlayHintFilter");

        final String key;

        Feature(String key) {
            this.key = key;
        }
    }

    private LspFeatureNotice() {}

    /** A file's managing server: its manager, the file, and the program it runs ({@code pyright-langserver}). */
    record Server(LspManager lsp, Path path, String program) {

        /** The name a user knows it by ("Pyright"). */
        String name() {
            return serverName(program);
        }
    }

    /** {@code server}'s name ("Pyright"), or null for none. */
    static @Nullable String nameOf(@Nullable Server server) {
        return server == null ? null : server.name();
    }

    /**
     * The notice for {@code server}'s file, or null when there is nothing to say: no server manages it, the server
     * is still starting (its capabilities are not known yet), or it gives {@code feature}.
     */
    static @Nullable String forServer(@Nullable Server server, Feature feature) {
        if (server == null
                || !server.lsp().isManaged(server.path())
                || !server.lsp().isReady(server.path())) {
            return null;
        }
        LspManager lsp = server.lsp();
        boolean has = switch (feature) {
            case INLAY_HINTS, INLAY_HINT_FILTER -> lsp.supportsInlayHints(server.path());
            case SEMANTIC_TOKENS -> lsp.supportsSemanticTokens(server.path());
            case CODE_LENS -> lsp.supportsCodeLens(server.path());
        };
        return message(feature, has, server.program());
    }

    /** Pure: the notice when a server named by {@code serverProgram} lacks {@code feature}, else null. */
    static @Nullable String message(Feature feature, boolean serverHasIt, String serverProgram) {
        return serverHasIt ? null : tr(feature.key, serverName(serverProgram));
    }

    /**
     * Pure: the name a user knows a server by. Pyright's program is {@code pyright-langserver} (basedpyright's
     * {@code basedpyright-langserver}); every other server is named by its program, as the status bar does.
     */
    static String serverName(String serverProgram) {
        return switch (serverProgram.toLowerCase(Locale.ROOT)) {
            case "pyright-langserver" -> "Pyright";
            case "basedpyright-langserver" -> "basedpyright";
            default -> serverProgram;
        };
    }
}
