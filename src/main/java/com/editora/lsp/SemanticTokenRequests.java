package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * Semantic tokens for {@link LspManager}: range requests over the visible lines where the server has them, else the
 * whole document — as a delta spliced onto the last answer when the server offers one (#679) — with the cached
 * state that makes a delta possible and a re-request after scrolling unnecessary. Tokens reach their callbacks on
 * the FX thread.
 */
final class SemanticTokenRequests {

    private final LspManager m;

    SemanticTokenRequests(LspManager m) {
        this.m = m;
    }

    /** True if {@code file}'s server is ready and advertises range semantic tokens (viewport highlighting). */
    public boolean supportsSemanticTokens(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && semanticTokensProvider(s.capabilities()) != null;
    }

    /**
     * Pure: the server's semantic-tokens registration options if it supports range <em>or</em> full
     * requests (and carries a legend), else {@code null}. Null-safe — gates {@link #requestSemanticTokens},
     * which prefers range (viewport) and falls back to full for range-less servers.
     */
    static org.eclipse.lsp4j.@Nullable SemanticTokensWithRegistrationOptions semanticTokensProvider(
            org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return null;
        }
        var p = caps.getSemanticTokensProvider();
        if (p == null || p.getLegend() == null) {
            return null;
        }
        return (LspManager.eitherTrue(p.getRange()) || LspManager.eitherTrue(p.getFull())) ? p : null;
    }

    /** The cached whole-document token state for delta requests (#679): the server's {@code resultId} +
     *  the full data array it identifies. Keyed by document URI; dropped on close/shutdown/error. */
    private record TokenState(LanguageServerSession session, String resultId, List<Integer> data) {}

    final Map<String, TokenState> semanticTokenState = new ConcurrentHashMap<>();

    final Map<String, Long> semanticRequestGeneration = new ConcurrentHashMap<>();

    /** The document version a whole-document token set was last delivered for, per URI. */
    private record TokenMark(
            LanguageServerSession session, @Nullable Integer version) {}

    final Map<String, TokenMark> wholeDocumentTokens = new ConcurrentHashMap<>();

    /**
     * Whether the server would answer a semantic-tokens request for {@code file} with what it last sent:
     * it has no range requests (so every request covers the whole document) and the document has not
     * changed on the server since that answer. Scrolling such a document shows nothing new, so the
     * scroll-settle re-request — a whole-document transfer and decode per scroll — can be skipped.
     */
    public boolean wholeDocumentTokensCurrent(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        var prov = s == null ? null : semanticTokensProvider(s.capabilities());
        if (prov == null || LspManager.eitherTrue(prov.getRange())) {
            return false;
        }
        String uri = LspManager.uri(file);
        TokenMark mark = wholeDocumentTokens.get(uri);
        return mark != null && mark.session() == s && java.util.Objects.equals(mark.version(), s.documentVersion(uri));
    }

    /**
     * Requests semantic tokens over the line window {@code [startLine..endLine]} (inclusive) and delivers
     * the decoded, CSS-classed {@link com.editora.text.SemanticToken}s to {@code cb} on the FX thread
     * (empty when unsupported / on error). The window bounds cost on large files — the caller passes the
     * visible paragraph range. Decoding uses the server's effective legend, never hardcoded indices.
     *
     * <p>Range-less servers (jdtls advertises {@code range=false}) take the whole-document path, which —
     * when the server supports {@code full.delta} and we hold its previous {@code resultId} — asks for a
     * <b>delta</b> and splices it onto the cached array ({@link SemanticTokensSplice}) instead of
     * re-transferring every token on each 300 ms edit pulse (#679). Any error/stale delta falls back to a
     * plain full request.
     */
    public void requestSemanticTokens(
            Path file,
            int startLine,
            int endLine,
            int lineCount,
            int lastLineLength,
            Consumer<List<com.editora.text.SemanticToken>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        var prov = s == null ? null : semanticTokensProvider(s.capabilities());
        if (s == null || prov == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var legend = prov.getLegend();
        String uri = LspManager.uri(file);
        long requestGeneration = semanticRequestGeneration.merge(uri, 1L, Long::sum);
        // Prefer a range (viewport) request to bound cost; fall back to a whole-document request for a
        // server that advertises only `full` (no range).
        if (LspManager.eitherTrue(prov.getRange())) {
            // Clamped to the document — a range past the last line makes a server return nothing (#715).
            var range = LspInlineHints.inclusiveLineRange(startLine, endLine, lineCount, lastLineLength);
            m.latest.issue(
                    uri,
                    SEMANTIC,
                    LspManager.stamp(s, uri, range),
                    true,
                    () -> s.semanticTokensRange(uri, range),
                    (tokens, error) -> {
                        if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                            return;
                        }
                        List<com.editora.text.SemanticToken> out = (error != null || tokens == null)
                                ? List.of()
                                : SemanticTokensDecoder.decode(
                                        tokens.getData(), legend.getTokenTypes(), legend.getTokenModifiers());
                        Platform.runLater(() -> cb.accept(out));
                    });
            return;
        }
        boolean deltaSupported = fullDeltaSupported(prov);
        TokenState prev = deltaSupported ? semanticTokenState.get(uri) : null;
        if (prev != null && prev.session() != s) {
            semanticTokenState.remove(uri, prev);
            prev = null;
        }
        if (prev != null) {
            TokenState deltaBase = prev;
            Integer version = s.documentVersion(uri);
            var delta = LspManager.stamp(s, uri, "delta:" + deltaBase.resultId());
            m.latest.issue(
                    uri,
                    SEMANTIC,
                    delta,
                    true,
                    () -> s.semanticTokensFullDelta(uri, deltaBase.resultId()),
                    (either, error) -> {
                        if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                            return;
                        }
                        List<Integer> data = null;
                        String resultId = null;
                        if (error == null && either != null) {
                            if (either.isLeft() && either.getLeft() != null) {
                                data = either.getLeft().getData(); // server chose to answer with a fresh full set
                                resultId = either.getLeft().getResultId();
                            } else if (either.isRight() && either.getRight() != null) {
                                data = SemanticTokensSplice.apply(
                                        deltaBase.data(), either.getRight().getEdits());
                                resultId = either.getRight().getResultId();
                            }
                        }
                        if (data == null) {
                            semanticTokenState.remove(uri); // stale/failed delta — re-request full
                            requestSemanticTokensFull(s, file, legend, requestGeneration, cb);
                            return;
                        }
                        rememberTokenState(s, uri, resultId, data);
                        wholeDocumentTokens.put(uri, new TokenMark(s, version));
                        List<Integer> decoded = data;
                        List<com.editora.text.SemanticToken> out = SemanticTokensDecoder.decode(
                                decoded, legend.getTokenTypes(), legend.getTokenModifiers());
                        Platform.runLater(() -> cb.accept(out));
                    });
            return;
        }
        requestSemanticTokensFull(s, file, legend, requestGeneration, cb);
    }

    /** Plain whole-document request; caches {@code resultId}+data when the server supplies one (#679). */
    private void requestSemanticTokensFull(
            LanguageServerSession s,
            Path file,
            org.eclipse.lsp4j.SemanticTokensLegend legend,
            long requestGeneration,
            Consumer<List<com.editora.text.SemanticToken>> cb) {
        String uri = LspManager.uri(file);
        Integer version = s.documentVersion(uri);
        m.latest.issue(
                uri,
                SEMANTIC,
                LspManager.stamp(s, uri, "full"),
                true,
                () -> s.semanticTokensFull(uri),
                (tokens, error) -> {
                    if (!currentSemanticRequest(file, s, uri, requestGeneration)) {
                        return;
                    }
                    if (error != null || tokens == null) {
                        wholeDocumentTokens.remove(uri);
                        Platform.runLater(() -> cb.accept(List.of()));
                        return;
                    }
                    rememberTokenState(s, uri, tokens.getResultId(), tokens.getData());
                    wholeDocumentTokens.put(uri, new TokenMark(s, version));
                    List<com.editora.text.SemanticToken> out = SemanticTokensDecoder.decode(
                            tokens.getData(), legend.getTokenTypes(), legend.getTokenModifiers());
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    private boolean currentSemanticRequest(
            Path file, @Nullable LanguageServerSession session, String uri, long requestGeneration) {
        return m.sessionFor(file) == session
                && java.util.Objects.equals(semanticRequestGeneration.get(uri), requestGeneration);
    }

    private void rememberTokenState(
            LanguageServerSession session, String uri, @Nullable String resultId, List<Integer> data) {
        if (resultId != null && data != null) {
            semanticTokenState.put(uri, new TokenState(session, resultId, List.copyOf(data)));
        } else {
            semanticTokenState.remove(uri); // no resultId ⇒ the server can't delta from this response
        }
    }

    /** Pure: whether the provider's {@code full} form advertises delta support (null-safe). */
    static boolean fullDeltaSupported(org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions prov) {
        var full = prov == null ? null : prov.getFull();
        return full != null
                && full.isRight()
                && full.getRight() != null
                && Boolean.TRUE.equals(full.getRight().getDelta());
    }

    /** The {@link LatestRequests} kind shared by the range, full and delta forms: they answer one question. */
    private static final String SEMANTIC = "semanticTokens";
}
