package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

/**
 * Diagnostics on their way from the servers to the editor, for {@link LspManager}: published ones (dropped when they
 * are older than the document, or for a closed file nobody shows) and pulled ones, the HTML markup check merged in,
 * and the raw diagnostics of open documents kept as the context a code-action request sends back. Delivered on the
 * FX thread through the manager's diagnostics callback.
 */
final class LspDiagnostics {

    private final LspManager m;

    LspDiagnostics(LspManager m) {
        this.m = m;
    }

    /** Whether Editora's own markup check joins the HTML server's diagnostics (HTML review D5); read off the FX thread. */
    private volatile java.util.function.BooleanSupplier htmlMarkupChecks = () -> true;

    /** Sets what says whether the markup check runs (Settings → Editor → HTML markup checks). */
    public void setHtmlMarkupChecks(java.util.function.BooleanSupplier on) {
        htmlMarkupChecks = on == null ? () -> true : on;
    }

    /** {@code mapped} with the markup check's diagnostics added for an HTML page (D5); null stays null. */
    private @Nullable List<LspDiagnostic> withMarkupChecks(
            LanguageServerSession s, @Nullable String documentUri, Path file, @Nullable List<LspDiagnostic> mapped) {
        String text = documentUri == null || mapped == null
                ? null
                : s.documentSnapshots().get(documentUri);
        return HtmlMarkupLint.merged(
                s.serverId(), file, text, s.root(), mapped, text != null && htmlMarkupChecks.getAsBoolean());
    }

    final Map<String, Long> diagnosticRequestGeneration = new ConcurrentHashMap<>();

    /** Source of {@link #diagnosticRequestGeneration} values. One counter for every document, so a value is
     *  never reused: an entry can then be removed when its document closes without a late reply for the
     *  closed document ever matching the generation of a reopened one. */
    private final java.util.concurrent.atomic.AtomicLong diagnosticGenerations =
            new java.util.concurrent.atomic.AtomicLong();

    long nextDiagnosticGeneration(String uri) {
        long generation = diagnosticGenerations.incrementAndGet();
        diagnosticRequestGeneration.put(uri, generation);
        return generation;
    }

    /** Tracked documents — package-private so a test can show closing one releases its entry. */
    int diagnosticGenerationEntries() {
        return diagnosticRequestGeneration.size();
    }

    /**
     * Last raw published diagnostics per <b>managed document URI</b> — the spelling this client opened the
     * document under, never the one the server reported it under (open documents only — see
     * {@link #onPublishDiagnostics}); the context a code-action request sends back to the server.
     *
     * <p>Keying by the server's string lost them whenever the server spelled the URI differently
     * ({@code file:///c%3A/…} for our {@code file:///C:/…}, or a symlink-resolved path): the lookup below
     * missed, the request went out with empty {@code context.diagnostics}, and every quick fix that keys off
     * a diagnostic silently disappeared.
     */
    final Map<String, List<org.eclipse.lsp4j.Diagnostic>> rawDiagnostics = new ConcurrentHashMap<>();

    /** The retained raw diagnostics for {@code file}. */
    List<org.eclipse.lsp4j.Diagnostic> rawDiagnosticsFor(Path file) {
        return rawDiagnostics.getOrDefault(LspManager.uri(file), List.of());
    }

    /** Told (FX thread) that a document's diagnostics are confirmed unchanged by its server. */
    private volatile Consumer<Path> onDiagnosticsUnchanged = file -> {};

    public void setOnDiagnosticsUnchanged(Consumer<Path> handler) {
        onDiagnosticsUnchanged = handler == null ? file -> {} : handler;
    }

    /**
     * Requests <em>pull</em> diagnostics ({@code textDocument/diagnostic}) for {@code file} and routes the
     * result through the same diagnostics callback as pushed {@code publishDiagnostics}. A no-op unless the
     * file's server advertises a {@code diagnosticProvider} (so push-only servers like jdtls/pyright/tsserver
     * are untouched). An "unchanged" report leaves the current diagnostics in place.
     */
    public void pullDiagnostics(Path file) {
        if (file == null) {
            return;
        }
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || s.capabilities() == null || s.capabilities().getDiagnosticProvider() == null) {
            return;
        }
        String documentUri = LspManager.uri(file);
        Integer requestedVersion = s.documentVersion(documentUri);
        // The generation is taken when the request is really sent: a call that finds the same pull still
        // unanswered sends nothing and must not outdate the answer it is waiting for along with it.
        long[] sent = new long[1];
        java.util.function.Supplier<CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport>> send = () -> {
            sent[0] = nextDiagnosticGeneration(documentUri);
            return s.diagnostic(documentUri);
        };
        m.latest.issue(
                documentUri, "diagnostic", LspManager.stamp(s, documentUri, null), false, send, (report, error) -> {
                    long generation = sent[0];
                    if (error != null) {
                        return;
                    }
                    List<LspDiagnostic> mapped =
                            withMarkupChecks(s, documentUri, file, DiagnosticMapper.mapReport(report));
                    if (mapped == null) {
                        // An "unchanged" report: what was shown before the edit is still right. The editor cleared
                        // its marks on the edit, so say so rather than leave it showing a clean file.
                        Platform.runLater(() -> {
                            if (m.sessionFor(file) == s
                                    && java.util.Objects.equals(
                                            diagnosticRequestGeneration.get(documentUri), generation)) {
                                onDiagnosticsUnchanged.accept(file);
                            }
                        });
                        return;
                    }
                    var full = report == null ? null : report.getRelatedFullDocumentDiagnosticReport();
                    List<org.eclipse.lsp4j.Diagnostic> raw =
                            full == null || full.getItems() == null ? List.of() : List.copyOf(full.getItems());
                    Platform.runLater(() -> {
                        if (m.sessionFor(file) == s
                                && java.util.Objects.equals(diagnosticRequestGeneration.get(documentUri), generation)
                                && java.util.Objects.equals(s.documentVersion(documentUri), requestedVersion)) {
                            // The code-action context is built from these. A pull-only server (vscode-css, ruby-lsp)
                            // never publishes, so without this it was always asked with an empty context and offered
                            // no quick fix for a diagnostic the editor was showing.
                            rawDiagnostics.put(documentUri, raw);
                            m.onDiagnostics.accept(file, mapped);
                        }
                    });
                });
    }

    /** Whether diagnostics published for a file that is not open on the publishing server are dropped. */
    volatile boolean openDocumentDiagnosticsOnly;

    void onPublishDiagnostics(LanguageServerSession source, org.eclipse.lsp4j.PublishDiagnosticsParams params) {
        if (source == null || source.isDisposed()) {
            return;
        }
        String reported = source.uris().toKey(params.getUri());
        Path file = DocumentUris.toPath(reported);
        if (file == null) {
            return;
        }
        params.setDiagnostics(BashLsp.diagnostics(source.serverId(), file, params.getDiagnostics())); // D2, A15
        String sourceUri = managedDocumentUri(source, file, reported);
        if (params.getVersion() != null
                && (sourceUri == null
                        || !java.util.Objects.equals(params.getVersion(), source.documentVersion(sourceUri)))) {
            return; // ranges from an older server snapshot must never replace current diagnostics
        }
        if (!m.builds.buildCaptures.isEmpty()) {
            m.builds.captureBuildErrors(source, file, DiagnosticMapper.map(params.getDiagnostics()));
        }
        if (sourceUri == null && openDocumentDiagnosticsOnly && !LspManager.projectLevel(file)) {
            // Nobody shows a file that is not open here, and a server publishes for its whole project
            // (jdtls: once per file on import). Dropped on this, the reader, thread: each one used to
            // cost the FX thread a queued task, a search of the tabs and a path resolution to learn that.
            return;
        }
        // Only an open document is tracked: successive publishes for any other file are applied in the
        // order they were queued, and one entry per file a server ever mentioned was never released.
        long generation = sourceUri == null ? 0 : nextDiagnosticGeneration(sourceUri);
        // Retain the RAW lsp4j diagnostics for open documents: a code-action request must send the
        // originals as context (their code/source/data are what a quick fix keys off — the mapped neutral
        // LspDiagnostic loses them). Open-documents-only, so a server's project-wide publishes don't
        // accumulate (#670).
        Integer acceptedVersion = params.getVersion();
        List<org.eclipse.lsp4j.Diagnostic> raw =
                params.getDiagnostics() == null ? List.of() : List.copyOf(params.getDiagnostics());
        List<LspDiagnostic> mapped = withMarkupChecks(
                source,
                sourceUri,
                file,
                MavenPomServer.adjustDiagnostics(
                        serverDisplayName(source.serverId()), DiagnosticMapper.map(params.getDiagnostics())));
        Platform.runLater(() -> {
            if (source.isDisposed()
                    || !m.sessionsByRoot.containsValue(source)
                    || sourceUri != null
                            && !java.util.Objects.equals(diagnosticRequestGeneration.get(sourceUri), generation)
                    || sourceUri != null && m.sessionByDocUri.get(sourceUri) != source
                    || acceptedVersion != null
                            && !java.util.Objects.equals(acceptedVersion, source.documentVersion(sourceUri))) {
                return;
            }
            if (sourceUri != null) {
                rawDiagnostics.put(sourceUri, raw);
            }
            m.onDiagnostics.accept(file, mapped);
        });
    }

    /** The name a diagnostic without a {@code source} is attributed to: the server's display name, else its id. */
    private static String serverDisplayName(String serverId) {
        String name = LspServerRegistry.displayNameFor(serverId);
        return name != null ? name : serverId;
    }

    private @Nullable String managedDocumentUri(LanguageServerSession source, Path file, String reportedUri) {
        if (m.sessionByDocUri.get(reportedUri) == source) {
            return reportedUri;
        }
        Path canonical = null;
        for (var entry : m.sessionByDocUri.entrySet()) {
            if (entry.getValue() == source) {
                Path openPath = DocumentUris.toPath(entry.getKey());
                if (openPath == null) {
                    continue;
                }
                if (com.editora.config.PathKeys.sameNormalized(openPath, file)) {
                    return entry.getKey();
                }
                // A server may report the symlink-resolved path (/private/tmp for /tmp). Off the FX thread
                // (the reader thread), and the resolution is cached.
                if (canonical == null) {
                    canonical = com.editora.config.PathKeys.canonical(file);
                }
                if (canonical.equals(com.editora.config.PathKeys.canonical(openPath))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
