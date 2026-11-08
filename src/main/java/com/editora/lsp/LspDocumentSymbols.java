package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.WorkspaceSymbolMatch;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.jspecify.annotations.Nullable;

/**
 * A document's structure as its server sees it, for {@link LspManager}: the symbol outline (also of a file no window
 * has open), member extents, folding and selection ranges, colours, and workspace and library symbol search. Results
 * reach their callbacks on the FX thread.
 */
final class LspDocumentSymbols {

    private final LspManager m;

    LspDocumentSymbols(LspManager m) {
        this.m = m;
    }

    /** True if {@code file}'s server is ready and advertises the document-symbol outline (Structure window). */
    public boolean supportsDocumentSymbols(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && documentSymbolProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentSymbolProvider} (null-safe). */
    static boolean documentSymbolProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentSymbolProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * Requests the document-symbol outline ({@code textDocument/documentSymbol}) for {@code file} and delivers
     * the mapped {@link SymbolNode} tree to {@code cb} on the FX thread (empty when unsupported / on error).
     * Powers the Structure tool window for LSP-served files.
     */
    public void documentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.documentSymbol(LspManager.uri(file)).whenComplete((result, error) -> {
            List<SymbolNode> symbols = error == null && result != null ? DocumentSymbolMapper.map(result) : List.of();
            Platform.runLater(() -> cb.accept(symbols));
        });
    }

    /** How long the Project Map waits for a running server's outline of a file with no tab. */
    static final java.time.Duration CLOSED_FILE_OUTLINE_TIMEOUT = java.time.Duration.ofSeconds(8);

    /**
     * Whether the server that already runs in this window for {@code file} ({@link LspManager#runsFor}) answers the
     * document-symbol outline: the Project Map's Structure columns for a file with no tab (Map explore round, position
     * 10). Never starts a server.
     */
    public boolean outlinesClosedFile(@Nullable Path file) {
        if (m.isClosed() || !m.isEnabled() || file == null || !LspManager.servable(file)) {
            return false;
        }
        LanguageServerSession s = m.runningSessionFor(file);
        return s != null && documentSymbolProvider(s.capabilities());
    }

    /**
     * The document-symbol outline of {@code text}, the content of {@code file}, from the server {@link
     * #outlinesClosedFile} names. A file no window has open is lent to it for this request ({@link LspManager#lendDocument}) and
     * the loan ends when the answer is in; a file another window has open on that session is asked about as it is, so
     * its unsaved text is never replaced by the disk's. {@code cb} gets the symbols on the FX thread, or null when no
     * such server runs, it fails or it takes longer than {@link #CLOSED_FILE_OUTLINE_TIMEOUT}. Never starts a server.
     * FX thread.
     */
    public void closedFileSymbols(Path file, String text, Consumer<@Nullable List<SymbolNode>> cb) {
        LanguageServerSession s = outlinesClosedFile(file) ? m.runningSessionFor(file) : null;
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String uri = LspManager.uri(file);
        Runnable loan = s.isOpen(uri) ? () -> {} : m.lendDocument(file, text);
        if (loan == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.documentSymbol(uri)
                .orTimeout(CLOSED_FILE_OUTLINE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((result, error) -> {
                    List<SymbolNode> symbols =
                            error == null && result != null ? DocumentSymbolMapper.map(result) : null;
                    Platform.runLater(() -> {
                        loan.run(); // didClose once nothing else holds the document
                        cb.accept(symbols);
                    });
                });
    }

    /**
     * The members of {@code libraryUri} (a {@code jdt://} class file), else of {@code file}, else of {@code anchor},
     * with their whole extents, asked on {@code anchor}'s session (Go to Super Method, eighth review BQ1).
     */
    public void memberSpans(
            Path anchor, @Nullable Path file, @Nullable String libraryUri, Consumer<List<MemberSpan>> cb) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String document = libraryUri != null ? libraryUri : LspManager.uri(file != null ? file : anchor);
        Map<String, List<MemberSpan>> cache = libraryUri == null || !jdkClassFile(libraryUri)
                ? null
                : libraryOutlines.computeIfAbsent(s, k -> new ConcurrentHashMap<>());
        List<MemberSpan> cached = cache == null ? null : cache.get(libraryUri);
        if (cached != null) {
            Platform.runLater(() -> cb.accept(cached));
            return;
        }
        s.documentSymbol(document).whenComplete((result, error) -> {
            List<MemberSpan> members =
                    error == null && result != null ? DocumentSymbolMapper.members(result) : List.of();
            if (cache != null && !members.isEmpty()) {
                cache.put(libraryUri, members); // a JDK class does not change while its server runs (BP5)
            }
            Platform.runLater(() -> cb.accept(members));
        });
    }

    /**
     * Whether {@code libraryUri} is a JDK class ({@code jdt://contents/java.base/…jrt-fs.jar…}): a dependency's jar can
     * be rebuilt in place ({@code mvn install} of a SNAPSHOT) under the same URI (tenth review DO4).
     */
    public static boolean jdkClassFile(String libraryUri) {
        return libraryUri.contains("jrt-fs.jar");
    }

    /** JDK class files' outlines per session, read once: a Go to Super Method walk reads many (ninth review BP5). */
    private final Map<LanguageServerSession, Map<String, List<MemberSpan>>> libraryOutlines =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * {@link #documentSymbols} for a caller that only ever wants the latest outline (the Structure window,
     * re-asked on every typing pause): a request for an older document version is cancelled, one already
     * running for this version is not repeated, and a superseded request never calls {@code cb}. A caller
     * that waits for its own answer must use {@link #documentSymbols}.
     */
    public void latestDocumentSymbols(Path file, Consumer<List<SymbolNode>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = LspManager.uri(file);
        m.latest.issue(
                uri,
                "documentSymbol",
                LspManager.stamp(s, uri, null),
                true,
                () -> s.documentSymbol(uri),
                (result, error) -> {
                    List<SymbolNode> symbols =
                            error == null && result != null ? DocumentSymbolMapper.map(result) : List.of();
                    Platform.runLater(() -> cb.accept(symbols));
                });
    }

    /** Pure: whether a server's capabilities include {@code foldingRangeProvider} (null-safe). */
    static boolean foldingRangeProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getFoldingRangeProvider());
    }

    /** Pure: whether a server's capabilities include {@code selectionRangeProvider} (null-safe). */
    static boolean selectionRangeProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getSelectionRangeProvider());
    }

    /** True if {@code file}'s server is ready and advertises folding ranges (#738). */
    public boolean supportsFoldingRanges(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && foldingRangeProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises selection ranges (#739). */
    public boolean supportsSelectionRanges(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && selectionRangeProvider(s.capabilities());
    }

    /**
     * Requests the document's foldable regions and delivers them on the FX thread as editor
     * {@link com.editora.text.FoldRegions.Region}s, ordered the way the built-in detector emits them
     * (see {@link LspFolding#toRegions}). Empty when the server has no folding provider — the caller then
     * keeps the heuristic regions rather than un-folding the file.
     */
    public void foldingRanges(Path file, Consumer<List<com.editora.text.FoldRegions.Region>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || !foldingRangeProvider(s.capabilities())) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = LspManager.uri(file);
        m.latest.issue(
                uri,
                "foldingRange",
                LspManager.stamp(s, uri, null),
                true,
                () -> s.foldingRange(uri),
                (ranges, error) -> {
                    List<com.editora.text.FoldRegions.Region> regions =
                            error == null ? LspFolding.toRegions(ranges) : List.of();
                    Platform.runLater(() -> cb.accept(regions));
                });
    }

    /** True if {@code file}'s server is ready and advertises {@code colorProvider} (HTML review D9). */
    public boolean supportsDocumentColor(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        var caps = s == null ? null : s.capabilities();
        return caps != null && LspManager.eitherTrue(caps.getColorProvider());
    }

    /**
     * Requests the document's colour literals and delivers them on the FX thread, in document order (D9). Empty
     * when the server has none or no colour provider.
     */
    public void documentColors(Path file, Consumer<List<com.editora.text.ColorSwatch>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || !supportsDocumentColor(file)) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        String uri = LspManager.uri(file);
        m.latest.issue(
                uri,
                "documentColor",
                LspManager.stamp(s, uri, null),
                true,
                () -> s.documentColor(uri),
                (colors, error) -> {
                    List<com.editora.text.ColorSwatch> out = error == null ? swatches(colors) : List.of();
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** Pure: the server's colour informations as swatches, in document order (null-safe). */
    static List<com.editora.text.ColorSwatch> swatches(@Nullable List<org.eclipse.lsp4j.ColorInformation> colors) {
        if (colors == null || colors.isEmpty()) {
            return List.of();
        }
        List<com.editora.text.ColorSwatch> out = new ArrayList<>(colors.size());
        for (org.eclipse.lsp4j.ColorInformation c : colors) {
            if (c == null || c.getRange() == null || c.getRange().getStart() == null || c.getColor() == null) {
                continue;
            }
            Position at = c.getRange().getStart();
            var color = c.getColor();
            out.add(new com.editora.text.ColorSwatch(
                    at.getLine(),
                    at.getCharacter(),
                    color.getRed(),
                    color.getGreen(),
                    color.getBlue(),
                    color.getAlpha()));
        }
        out.sort(java.util.Comparator.comparingInt(com.editora.text.ColorSwatch::line)
                .thenComparingInt(com.editora.text.ColorSwatch::column));
        return List.copyOf(out);
    }

    /**
     * Requests the nested selection-range chain at one position and delivers it on the FX thread as
     * {@code [start, end]} offset pairs, innermost first (see {@link LspSelection#toOffsetChain}). Empty when
     * the server has no provider or answers nothing, so the caller falls back to the local ladder.
     *
     * <p>{@code lineLengths} maps the document's lines to their lengths — the response is in LSP
     * line/character coordinates and the caller works in offsets, and doing the conversion here keeps the
     * editor free of {@code lsp4j}.
     */
    public void selectionRanges(Path file, int line, int character, int[] lineStarts, Consumer<List<int[]>> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null || !selectionRangeProvider(s.capabilities())) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.selectionRange(LspManager.uri(file), List.of(new Position(line, character)))
                .whenComplete((ranges, error) -> {
                    List<int[]> chain = error == null ? LspSelection.toOffsetChain(ranges, lineStarts) : List.of();
                    Platform.runLater(() -> cb.accept(chain));
                });
    }

    /** True if {@code file}'s server is ready and advertises {@code workspace/symbol} (Go to Symbol). */
    public boolean supportsWorkspaceSymbols(Path file) {
        LanguageServerSession s = workspaceSession(file);
        return s != null && workspaceSymbolProvider(s.capabilities());
    }

    /**
     * The session {@code workspace/symbol} goes to for {@code file}: the one serving it, else — {@code file} in no
     * editor, a Project Map card's — the running one that would serve it, since the request names no document.
     */
    private @Nullable LanguageServerSession workspaceSession(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null ? s : m.runningSessionFor(file);
    }

    /** Pure: whether the server advertises {@code workspace/symbol} (Boolean or options form). Null-safe. */
    static boolean workspaceSymbolProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getWorkspaceSymbolProvider());
    }

    /**
     * Runs {@code workspace/symbol} on {@code anchorFile}'s server for {@code query} and delivers the matches
     * on the FX thread. Empty when no server serves the file (see {@link #workspaceSession}). Handles both the
     * legacy flat {@code SymbolInformation} and the modern {@code WorkspaceSymbol} response shapes (the latter may
     * carry a range-less URI-only location, mapped to line 0).
     */
    public void workspaceSymbols(Path anchorFile, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        workspaceSymbols(workspaceSession(anchorFile), query, cb);
    }

    private static void workspaceSymbols(
            @Nullable LanguageServerSession s, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.workspaceSymbol(query).whenComplete((result, error) -> {
            List<WorkspaceSymbolMatch> out = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (org.eclipse.lsp4j.SymbolInformation si : result.getLeft()) {
                        addSymbol(out, si.getName(), si.getContainerName(), si.getKind(), si.getLocation(), s.uris());
                    }
                } else if (result.getRight() != null) {
                    for (org.eclipse.lsp4j.WorkspaceSymbol ws : result.getRight()) {
                        addSymbol(out, ws.getName(), ws.getContainerName(), ws.getKind(), ws.getLocation(), s.uris());
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    /**
     * The JDK and dependency types ({@code jdt://}) matching {@code query}, from the Java server of the project
     * holding {@code underRoot} — the classes the server-free index cannot see (Java review E8). Empty without
     * one. Delivered on the FX thread.
     */
    public void javaLibraryTypes(@Nullable Path underRoot, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        LanguageServerSession s = m.javaSessionFor(underRoot);
        if (s == null || s.capabilities() == null) { // none, or not initialized yet
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        workspaceSymbols(s, query, all -> {
            List<WorkspaceSymbolMatch> types = new ArrayList<>();
            for (WorkspaceSymbolMatch m : all) {
                if (m.libraryUri() != null && LIBRARY_TYPE_KINDS.contains(m.kind())) {
                    types.add(m);
                }
            }
            cb.accept(types);
        });
    }

    /** Adds a {@code SymbolInformation} match (its location always carries a range). */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            Location loc,
            WireUris uris) {
        if (loc == null) {
            return;
        }
        Path p = uris.toPath(loc.getUri());
        Position start = loc.getRange() != null ? loc.getRange().getStart() : new Position(0, 0);
        if (p == null) {
            // A JDK or dependency symbol (jdtls's jdt:// class-file URI): kept, opened as library source.
            if (loc.getUri() != null && loc.getUri().startsWith(LspManager.JDT_SCHEME)) {
                out.add(new WorkspaceSymbolMatch(
                        name, container, kindName(kind), null, start.getLine(), start.getCharacter(), loc.getUri()));
            }
            return;
        }
        out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, start.getLine(), start.getCharacter()));
    }

    /** Adds a {@code WorkspaceSymbol} match whose location is {@code Either<Location, URI-only>}. */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            org.eclipse.lsp4j.jsonrpc.messages.Either<Location, org.eclipse.lsp4j.WorkspaceSymbolLocation> loc,
            WireUris uris) {
        if (loc == null) {
            return;
        }
        if (loc.isLeft()) {
            addSymbol(out, name, container, kind, loc.getLeft(), uris);
        } else if (loc.getRight() != null) {
            Path p = uris.toPath(loc.getRight().getUri());
            if (p != null) {
                out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, 0, 0));
            }
        }
    }

    private static String kindName(org.eclipse.lsp4j.SymbolKind k) {
        return k == null ? "" : k.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static final java.util.Set<String> LIBRARY_TYPE_KINDS =
            java.util.Set.of("class", "interface", "enum", "struct");
}
