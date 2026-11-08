package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.lsp.LspManager.HierarchyNode;
import com.editora.lsp.LspManager.Target;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jspecify.annotations.Nullable;

/**
 * Where a symbol leads, for {@link LspManager}: definition, implementation, type definition, declaration, super
 * method, references, document links, and the call and type hierarchies. Every answer reaches its callback on the
 * FX thread; an unserved file answers empty.
 */
final class LspNavigationRequests {

    private final LspManager m;

    LspNavigationRequests(LspManager m) {
        this.m = m;
    }

    /** True if {@code file}'s server is ready and answers {@code textDocument/references}. */
    public boolean supportsReferences(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        var caps = s == null ? null : s.capabilities();
        var provider = caps == null ? null : caps.getReferencesProvider();
        return provider != null && (provider.isRight() || Boolean.TRUE.equals(provider.getLeft()));
    }

    /** True if {@code file}'s server is ready and advertises call hierarchy (#682). */
    public boolean supportsCallHierarchy(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && callHierarchyProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises type hierarchy (#682). */
    public boolean supportsTypeHierarchy(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && typeHierarchyProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code callHierarchyProvider} (null-safe). */
    static boolean callHierarchyProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getCallHierarchyProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Pure: whether a server's capabilities include {@code typeHierarchyProvider} (null-safe). */
    static boolean typeHierarchyProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getTypeHierarchyProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** The call-hierarchy anchor item(s) at a 0-based position, delivered on the FX thread (#682). */
    public void prepareCallHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        prepareCallHierarchyAt(file, null, line, character, cb);
    }

    /**
     * As above; {@code onError} (when given) hears a failed or timed-out request instead of {@code cb} hearing an
     * empty list, which read as "No callers found" (Map review 4, C6; as {@link #references} has since Java B13).
     */
    public void prepareCallHierarchy(
            Path file, int line, int character, Consumer<List<HierarchyNode>> cb, @Nullable Consumer<String> onError) {
        prepareCallHierarchyAt(file, null, line, character, cb, onError);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void prepareCallHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        prepareCallHierarchyAt(anchor, documentUri, line, character, cb, null);
    }

    private void prepareCallHierarchyAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<HierarchyNode>> cb,
            @Nullable Consumer<String> onError) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.prepareCallHierarchy(
                        documentUri != null ? documentUri : LspManager.uri(anchor), new Position(line, character))
                .whenComplete((items, e) -> {
                    if (e != null && onError != null) {
                        String why = LspManager.failureReason(e);
                        Platform.runLater(() -> onError.accept(why));
                        return;
                    }
                    List<HierarchyNode> out = new ArrayList<>();
                    if (e == null && items != null) {
                        for (var i : items) {
                            if (i != null) {
                                out.add(callNode(i, s.uris()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /** The callers ({@code incoming=true}) or callees of a call-hierarchy node's {@code raw} item. */
    public void callHierarchyChildren(Path anchor, Object raw, boolean incoming, Consumer<List<HierarchyNode>> cb) {
        callHierarchyChildren(anchor, raw, incoming, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Map review 4, C6). */
    public void callHierarchyChildren(
            Path anchor,
            Object raw,
            boolean incoming,
            Consumer<List<HierarchyNode>> cb,
            @Nullable Consumer<String> onError) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null || !(raw instanceof org.eclipse.lsp4j.CallHierarchyItem item)) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        if (incoming) {
            s.incomingCalls(item).whenComplete((calls, e) -> {
                if (e != null && onError != null) {
                    String why = LspManager.failureReason(e);
                    Platform.runLater(() -> onError.accept(why));
                    return;
                }
                List<HierarchyNode> out = new ArrayList<>();
                if (e == null && calls != null) {
                    for (var c : calls) {
                        if (c != null && c.getFrom() != null) {
                            out.add(callNode(c.getFrom(), s.uris()).withCalls(callSites(c.getFromRanges())));
                        }
                    }
                }
                List<HierarchyNode> merged = mergeCalls(out);
                Platform.runLater(() -> cb.accept(merged));
            });
        } else {
            s.outgoingCalls(item).whenComplete((calls, e) -> {
                List<HierarchyNode> out = new ArrayList<>();
                if (e == null && calls != null) {
                    for (var c : calls) {
                        if (c != null && c.getTo() != null) {
                            out.add(callNode(c.getTo(), s.uris()).withCalls(callSites(c.getFromRanges())));
                        }
                    }
                }
                List<HierarchyNode> merged = mergeCalls(out);
                Platform.runLater(() -> cb.accept(merged));
            });
        }
    }

    private static int callSites(@Nullable List<?> ranges) {
        return ranges == null || ranges.isEmpty() ? 1 : ranges.size();
    }

    /**
     * One row per called (or calling) member, its call sites added up, in first-seen order. jdtls lists a member
     * once per call, so {@code append(String)} called twice was two rows that went to the same place. Pure.
     */
    static List<HierarchyNode> mergeCalls(List<HierarchyNode> nodes) {
        Map<String, HierarchyNode> byTarget = new java.util.LinkedHashMap<>();
        for (HierarchyNode n : nodes) {
            String key = n.name() + "\u0000" + n.detail() + "\u0000" + n.file() + "\u0000" + n.libraryUri() + "\u0000"
                    + n.line() + ":" + n.col();
            HierarchyNode seen = byTarget.get(key);
            byTarget.put(key, seen == null ? n : seen.withCalls(seen.calls() + n.calls()));
        }
        return List.copyOf(byTarget.values());
    }

    /** The type-hierarchy anchor item(s) at a 0-based position, delivered on the FX thread (#682). */
    public void prepareTypeHierarchy(Path file, int line, int character, Consumer<List<HierarchyNode>> cb) {
        prepareTypeHierarchyAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void prepareTypeHierarchyAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.prepareTypeHierarchy(
                        documentUri != null ? documentUri : LspManager.uri(anchor), new Position(line, character))
                .whenComplete((items, e) -> {
                    List<HierarchyNode> out = new ArrayList<>();
                    if (e == null && items != null) {
                        for (var i : items) {
                            if (i != null) {
                                out.add(typeNode(i, s.uris()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(out));
                });
    }

    /**
     * As above at a position in {@code file} (another file of {@code anchor}'s session), or in {@code libraryUri} (a
     * class file's), as {@link LspManager#memberSpans} reads one.
     */
    public void prepareTypeHierarchyAt(
            Path anchor,
            @Nullable Path file,
            @Nullable String libraryUri,
            int line,
            int character,
            Consumer<List<HierarchyNode>> cb) {
        prepareTypeHierarchyAt(
                anchor,
                libraryUri != null ? libraryUri : file != null ? LspManager.uri(file) : null,
                line,
                character,
                cb);
    }

    /** The supertypes ({@code supertypes=true}) or subtypes of a type-hierarchy node's {@code raw} item. */
    public void typeHierarchyChildren(Path anchor, Object raw, boolean supertypes, Consumer<List<HierarchyNode>> cb) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null || !(raw instanceof org.eclipse.lsp4j.TypeHierarchyItem item)) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var fut = supertypes ? s.supertypes(item) : s.subtypes(item);
        fut.whenComplete((items, e) -> {
            List<HierarchyNode> out = new ArrayList<>();
            if (e == null && items != null) {
                for (var i : items) {
                    if (i != null) {
                        out.add(typeNode(i, s.uris()));
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    private static HierarchyNode callNode(org.eclipse.lsp4j.CallHierarchyItem i, WireUris uris) {
        var pos = i.getSelectionRange() != null
                ? i.getSelectionRange().getStart()
                : i.getRange() != null ? i.getRange().getStart() : new Position(0, 0);
        return new HierarchyNode(
                i.getName() == null ? "" : i.getName(),
                i.getDetail() == null ? "" : i.getDetail(),
                i.getKind() == null ? "" : i.getKind().toString().toLowerCase(java.util.Locale.ROOT),
                uris.toPath(i.getUri()),
                pos.getLine(),
                pos.getCharacter(),
                i);
    }

    private static HierarchyNode typeNode(org.eclipse.lsp4j.TypeHierarchyItem i, WireUris uris) {
        var pos = i.getSelectionRange() != null
                ? i.getSelectionRange().getStart()
                : i.getRange() != null ? i.getRange().getStart() : new Position(0, 0);
        return new HierarchyNode(
                i.getName() == null ? "" : i.getName(),
                i.getDetail() == null ? "" : i.getDetail(),
                i.getKind() == null ? "" : i.getKind().toString().toLowerCase(java.util.Locale.ROOT),
                uris.toPath(i.getUri()),
                pos.getLine(),
                pos.getCharacter(),
                i);
    }

    public void definition(Path file, int line, int character, Consumer<List<Target>> cb) {
        definition(file, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (the Project Map's cards). */
    public void definition(
            Path file, int line, int character, Consumer<List<Target>> cb, @Nullable Consumer<String> onError) {
        requestDefinition(m.sessionFor(file), file == null ? null : LspManager.uri(file), line, character, cb, onError);
    }

    /**
     * Definition at an explicit document URI on {@code anchorFile}'s session — how navigation chains
     * <b>inside</b> an opened {@code jdt://} library source (#684): the library tab has no filesystem path,
     * but jdtls resolves positions in the jdt documents it has served. {@code anchorFile} is the original
     * workspace file the library source was reached from.
     */
    public void definitionAt(
            Path anchorFile, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        requestDefinition(
                m.sessionFor(anchorFile),
                documentUri != null ? documentUri : anchorFile == null ? null : LspManager.uri(anchorFile),
                line,
                character,
                cb,
                null);
    }

    /**
     * Go to Implementation ({@code textDocument/implementation}, #735) — the concrete overrides of the
     * member at the caret. Delivers targets on the FX thread; empty when unsupported / on error.
     */
    public void implementation(Path file, int line, int character, Consumer<List<Target>> cb) {
        implementationAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void implementationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        implementationAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void implementationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                m.sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : LspManager.uri(anchor),
                line,
                character,
                LanguageServerSession::implementation,
                cb,
                onError);
    }

    /** Go to Type Definition ({@code textDocument/typeDefinition}, #736) — from a symbol to its type. */
    public void typeDefinition(Path file, int line, int character, Consumer<List<Target>> cb) {
        typeDefinitionAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void typeDefinitionAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        typeDefinitionAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void typeDefinitionAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                m.sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : LspManager.uri(anchor),
                line,
                character,
                LanguageServerSession::typeDefinition,
                cb,
                onError);
    }

    /**
     * Go to Super Method (IntelliJ's Ctrl+U): the method the one at the caret overrides, or the class a type extends,
     * through jdtls's {@code java/findLinks} {@code superImplementation} (sixth review E1).
     */
    public void superMethodAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                m.javaSessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : LspManager.uri(anchor),
                line,
                character,
                (s, u, pos) -> s.findLinks("superImplementation", u, pos),
                cb,
                onError);
    }

    /** Whether {@code file}'s server is jdtls, which answers Go to Super Method. */
    public boolean supportsSuperMethod(Path file) {
        return m.javaSessionFor(file) != null;
    }

    /** Go to Declaration ({@code textDocument/declaration}, #736). */
    public void declaration(Path file, int line, int character, Consumer<List<Target>> cb) {
        declarationAt(file, null, line, character, cb);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void declarationAt(
            Path anchor, @Nullable String documentUri, int line, int character, Consumer<List<Target>> cb) {
        declarationAt(anchor, documentUri, line, character, cb, null);
    }

    /** As above; {@code onError} (when given) hears a failed or timed-out request (Java review B13). */
    public void declarationAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        requestLocations(
                m.sessionFor(anchor),
                documentUri != null ? documentUri : anchor == null ? null : LspManager.uri(anchor),
                line,
                character,
                LanguageServerSession::declaration,
                cb,
                onError);
    }

    /** True if {@code file}'s server is ready and advertises {@code implementationProvider} (#735). */
    public boolean supportsImplementation(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && implementationProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises {@code typeDefinitionProvider} (#736). */
    public boolean supportsTypeDefinition(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && typeDefinitionProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises {@code declarationProvider} (#736). */
    public boolean supportsDeclaration(Path file) {
        LanguageServerSession s = m.sessionFor(file);
        return s != null && declarationProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code implementationProvider} (null-safe). */
    static boolean implementationProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getImplementationProvider());
    }

    /** Pure: whether a server's capabilities include {@code typeDefinitionProvider} (null-safe). */
    static boolean typeDefinitionProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getTypeDefinitionProvider());
    }

    /** Pure: whether a server's capabilities include {@code declarationProvider} (null-safe). */
    static boolean declarationProvider(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
        return caps != null && LspManager.eitherTrue(caps.getDeclarationProvider());
    }

    /**
     * One position-to-locations request. {@code definition}, {@code implementation}, {@code typeDefinition}
     * and {@code declaration} differ only in which session method runs — all four answer in the same two
     * shapes (a {@code Location} list or a {@code LocationLink} list), and all four can name a {@code jdt://}
     * class file, so they share one response walk rather than four copies of it.
     */
    @FunctionalInterface
    private interface LocationRequest {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> run(
                LanguageServerSession session, String uri, Position pos);
    }

    private void requestDefinition(
            @Nullable LanguageServerSession s,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        // XML: the grammar declaration instead of the element's own other tag, else the linked schema (B5, B10);
        // a pom on the Maven server is lemminx too, its own answer still first (XML/pom review 4, B34).
        boolean xml = s != null && XmlServerSettings.isLemminx(s.serverId());
        var missingLink = new java.util.concurrent.atomic.AtomicReference<@Nullable String>();
        LocationRequest request = xml
                ? (session, uri, pos) -> XmlServer.definition(session, uri, pos, missingLink::set)
                : LanguageServerSession::definition;
        lastMissingInclude = null;
        Consumer<List<Target>> answer = xml && s != null && documentUri != null
                ? notingMissingInclude(s, documentUri, new Position(line, character), missingLink, cb)
                : cb;
        requestLocations(s, documentUri, line, character, request, answer, onError);
    }

    /**
     * Where the server's document link at {@code line}/{@code character} of {@code file} points (HTML review B6),
     * delivered on the FX thread; null when the server has no link there (or is not running). The HTML server
     * percent-encodes a fragment ({@code about.html%23team}); the target comes back split into file and fragment.
     */
    public void documentLinkTarget(
            Path file, int line, int character, Consumer<com.editora.editops.HtmlLinks.@Nullable Target> cb) {
        LanguageServerSession s = m.sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        Position pos = new Position(line, character);
        s.documentLinks(LspManager.uri(file))
                .thenCompose(links -> {
                    org.eclipse.lsp4j.DocumentLink link = XmlServer.linkAt(links, pos);
                    return link == null || link.getTarget() != null
                            ? CompletableFuture.completedFuture(link)
                            : s.resolveDocumentLink(link);
                })
                .<com.editora.editops.HtmlLinks.@Nullable Target>thenApply(link -> {
                    String target = link == null ? null : link.getTarget();
                    if (target == null) {
                        return null;
                    }
                    if (target.startsWith("http://") || target.startsWith("https://")) {
                        return com.editora.editops.HtmlLinks.fromUri(target);
                    }
                    Path to = s.uris()
                            .toPath(com.editora.editops.HtmlLinks.withoutQuery(
                                    com.editora.editops.HtmlLinks.withoutFragment(target)));
                    return to == null
                            ? null
                            : new com.editora.editops.HtmlLinks.Target(
                                    com.editora.editops.HtmlLinks.Kind.FILE,
                                    to,
                                    com.editora.editops.HtmlLinks.fragmentOf(target),
                                    "");
                })
                .exceptionally(t -> null)
                .thenAccept(target -> Platform.runLater(() -> cb.accept(target)));
    }

    /** {@code cb}, after noting when nothing was found on an include href that names no file (C26). */
    private Consumer<List<Target>> notingMissingInclude(
            LanguageServerSession s,
            String documentUri,
            Position pos,
            java.util.concurrent.atomic.AtomicReference<@Nullable String> missingLink,
            Consumer<List<Target>> cb) {
        return targets -> {
            String link = missingLink.get();
            lastMissingInclude =
                    !targets.isEmpty() ? null : link != null ? link : XmlServer.missingInclude(s, documentUri, pos);
            cb.accept(targets);
        };
    }

    /**
     * The file the last Go to Definition's include href (C26) or document link (XML/pom review 4, B33) named when it
     * does not exist, or null.
     */
    private volatile @Nullable String lastMissingInclude;

    public @Nullable String lastMissingInclude() {
        return lastMissingInclude;
    }

    private void requestLocations(
            @Nullable LanguageServerSession s,
            @Nullable String documentUri,
            int line,
            int character,
            LocationRequest request,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        if (s == null || documentUri == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        request.run(s, documentUri, new Position(line, character)).whenComplete((result, error) -> {
            if (error != null && onError != null) {
                String why = LspManager.failureReason(error); // not an empty list, which reads as "none found" (B13)
                Platform.runLater(() -> onError.accept(why));
                return;
            }
            List<Target> targets = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (Location l : result.getLeft()) {
                        var range = l.getRange(); // a non-conforming server can omit the (spec-required) range
                        if (range != null) {
                            addDefinitionTarget(targets, s.uris(), l.getUri(), range.getStart());
                        }
                    }
                } else if (result.getRight() != null) {
                    for (LocationLink ll : result.getRight()) {
                        var range = ll.getTargetSelectionRange() != null
                                ? ll.getTargetSelectionRange()
                                : ll.getTargetRange();
                        if (range != null) {
                            addDefinitionTarget(targets, s.uris(), ll.getTargetUri(), range.getStart());
                        }
                    }
                }
            }
            Platform.runLater(() -> cb.accept(targets));
        });
    }

    public void references(Path file, int line, int character, Consumer<List<Target>> cb) {
        references(file, line, character, cb, null);
    }

    /**
     * As above; {@code onError} (when given) hears a failed or timed-out request instead of {@code cb} hearing an
     * empty list — which read as "No references found" (Java review B13).
     */
    public void references(
            Path file, int line, int character, Consumer<List<Target>> cb, @Nullable Consumer<String> onError) {
        referencesAt(file, null, line, character, cb, onError);
    }

    /** As above in {@code documentUri} (a {@code jdt://} library source; null for {@code anchor} itself). */
    public void referencesAt(
            Path anchor,
            @Nullable String documentUri,
            int line,
            int character,
            Consumer<List<Target>> cb,
            @Nullable Consumer<String> onError) {
        LanguageServerSession s = m.sessionFor(anchor);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.references(documentUri != null ? documentUri : LspManager.uri(anchor), new Position(line, character))
                .whenComplete((locations, error) -> {
                    if (error != null && onError != null) {
                        String why = LspManager.failureReason(error);
                        Platform.runLater(() -> onError.accept(why));
                        return;
                    }
                    List<Target> targets = new ArrayList<>();
                    if (error == null && locations != null) {
                        for (Location l : locations) {
                            var range = l.getRange(); // guard against a server omitting the spec-required range
                            if (range != null) {
                                addTarget(targets, s.uris(), l.getUri(), range.getStart());
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(targets));
                });
    }

    private static void addTarget(List<Target> out, WireUris uris, String uri, Position start) {
        Path p = uris.toPath(uri);
        if (p != null && start != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        }
    }

    /**
     * Like {@link #addTarget}, but also keeps a {@code jdt://} class-file target (as a path-less
     * {@link Target} carrying the URI). Definition-only: silently dropping these made {@code M-.} on
     * {@code String}/{@code List}/any dependency symbol report "no definition" — the most common Java
     * navigation (#665). References stay file-only (the References panel is file-based).
     */
    private static void addDefinitionTarget(List<Target> out, WireUris uris, String uri, Position start) {
        if (start == null) {
            return;
        }
        Path p = uris.toPath(uri);
        if (p != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        } else if (uri != null && uri.startsWith(LspManager.JDT_SCHEME)) {
            out.add(new Target(null, start.getLine(), start.getCharacter(), uri));
        }
    }
}
