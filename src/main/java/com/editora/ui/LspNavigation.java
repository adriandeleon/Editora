package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Navigation through the language servers for {@link LspCoordinator}: Go to Definition, Declaration, Type
 * Definition and Implementation, Peek Definition, Find References and the References window, the call and type
 * hierarchies and the Hierarchy window, Go to Symbol in Workspace, Search Everywhere's library types, and the
 * read-only library ({@code jdt://}) source tabs these open, from which navigation chains on. Go to Super Method is
 * {@link LspSuperMethods}'. Every request is tied to the tab, document version and request generation it was made
 * from ({@link #navigationRequestCurrent}); an answer that arrives after any of them changed is dropped. FX thread.
 */
final class LspNavigation {

    /** What navigation needs of {@link LspCoordinator}, resolved when called. */
    interface Host {
        /** The active buffer when its server manages it; otherwise says so and returns null. */
        @Nullable
        EditorBuffer activeLspBuffer();

        @Nullable
        Path lspPath(EditorBuffer buffer);

        @Nullable
        EditorBuffer bufferForLspPath(@Nullable Path path);

        /** A definition outside the project but under a server's root: its tab is library code (view mode). */
        void noteLibraryTarget(@Nullable Path anchor, Path target);

        /** An HTML link at the caret is followed to its file; false when there is none. */
        boolean followLinkAtCaret();
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final Host lsp;
    private final LibrarySources librarySourcesBar;
    private final HierarchyPanel.Loader hierarchyLoader;

    /** Bumped by every navigation request; an answer to an older one is stale. */
    private long navigationRequestGeneration;

    private @Nullable ReferencesPanel referencesPanel;
    private @Nullable HierarchyPanel hierarchyPanel;

    /** The buffer path a hierarchy was started from — routes child expansions to the right session. */
    private @Nullable Path hierarchyAnchor;
    /** The mode of the currently shown hierarchy (drives which children request a node expansion makes). */
    private HierarchyPanel.Mode hierarchyMode = HierarchyPanel.Mode.CALLS;

    LspNavigation(CoordinatorHost host, LspManager lspManager, LspCoordinator.Ops ops, Host lsp) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.lsp = lsp;
        this.librarySourcesBar = new LibrarySources(lspManager, host::setStatus); // a decompiled tab's sources
        this.hierarchyLoader = new HierarchyPanel.Loader() {
            @Override
            public void children(
                    com.editora.lsp.LspManager.HierarchyNode node,
                    boolean primary,
                    java.util.function.Consumer<List<com.editora.lsp.LspManager.HierarchyNode>> cb) {
                Path anchor = hierarchyAnchor;
                if (anchor == null) {
                    cb.accept(List.of());
                } else if (hierarchyMode == HierarchyPanel.Mode.CALLS) {
                    lspManager.callHierarchyChildren(anchor, node.raw(), primary, cb);
                } else {
                    lspManager.typeHierarchyChildren(anchor, node.raw(), primary, cb);
                }
            }

            @Override
            public void open(Path file, int line, int col) {
                ops.openAndGoto(file, line, col);
            }

            @Override
            public void openLibrary(String uri, int line) {
                Path anchor = hierarchyAnchor;
                if (anchor != null) {
                    openLibraryFrame(anchor, uri, line);
                }
            }
        };
    }

    /** Starts a navigation request: answers to the ones before it are stale from now on. */
    long nextRequestGeneration() {
        return ++navigationRequestGeneration;
    }

    /** The References tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    ReferencesPanel referencesPanel() {
        if (referencesPanel == null) {
            referencesPanel = new ReferencesPanel(ops::openAndGoto);
        }
        return referencesPanel;
    }

    /** The Hierarchy tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    HierarchyPanel hierarchyPanel() {
        if (hierarchyPanel == null) {
            hierarchyPanel = new HierarchyPanel(hierarchyLoader);
        }
        return hierarchyPanel;
    }

    /**
     * Call hierarchy at the caret (#682): who calls this method (Callers, the default) / what it calls
     * (Callees), expanded lazily one server request per node into the Hierarchy tool window.
     */
    void callHierarchy() {
        showHierarchy(HierarchyPanel.Mode.CALLS);
    }

    /** Type hierarchy at the caret (#682): the type's supertypes (default) / subtypes. */
    void typeHierarchy() {
        showHierarchy(HierarchyPanel.Mode.TYPES);
    }

    private void showHierarchy(HierarchyPanel.Mode mode) {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        Path path = origin.anchor();
        boolean supported = mode == HierarchyPanel.Mode.CALLS
                ? lspManager.supportsCallHierarchy(path)
                : lspManager.supportsTypeHierarchy(path);
        if (!supported) {
            host.setStatus(tr("status.lsp.noHierarchy"));
            return;
        }
        CodeArea area = b.getFocusedArea();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        syncForRequest(origin); // sync latest text before the request
        java.util.function.Consumer<List<com.editora.lsp.LspManager.HierarchyNode>> onRoots = roots -> {
            if (!navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
                return;
            }
            if (roots.isEmpty()) {
                host.setStatus(tr("status.lsp.noHierarchy"));
                return;
            }
            hierarchyAnchor = path;
            hierarchyMode = mode;
            hierarchyPanel().setRoots(mode, roots);
            ops.openHierarchyWindow();
        };
        if (mode == HierarchyPanel.Mode.CALLS) {
            lspManager.prepareCallHierarchyAt(
                    path, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        } else {
            lspManager.prepareTypeHierarchyAt(
                    path, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), onRoots);
        }
    }

    /**
     * Where a position request from the active tab goes: a workspace file to its own session, or a library
     * ({@code jdt://}) source tab to the session that handed the source out, about its URI. Only Go to
     * Definition did that; hover, references, implementation and the hierarchies said "No language server
     * for this file" inside {@code ArrayList.java} (Java review B7). Null, with that status said, when neither.
     */
    @Nullable
    NavOrigin navOrigin() {
        EditorBuffer active = host.activeBuffer();
        LibrarySource lib = active == null ? null : librarySources.get(active);
        if (active != null
                && lib != null
                && lib.anchor() != null
                && lib.uri() != null
                && lspManager.isManaged(lib.anchor())) {
            return new NavOrigin(active, lib.anchor(), lib.uri());
        }
        EditorBuffer b = lsp.activeLspBuffer();
        Path path = b == null ? null : lsp.lspPath(b);
        return b == null || path == null ? null : new NavOrigin(b, path, null);
    }

    /**
     * A request's origin: the tab, the file whose session answers, and the {@code jdt://} URI asked about when
     * the tab is library source (null for the file itself).
     */
    record NavOrigin(
            EditorBuffer buffer, Path anchor, @Nullable String libraryUri) {
        /** The path {@link #navigationRequestCurrent} compares: none for a library tab. */
        @Nullable
        Path ownPath() {
            return libraryUri == null ? anchor : null;
        }
    }

    /** Sends the tab's latest text before a request (a library tab is read-only: nothing to send). */
    void syncForRequest(NavOrigin origin) {
        if (origin.libraryUri() == null) {
            lspManager.changeDocument(origin.anchor(), origin.buffer().text());
        }
    }

    /** Reads a peeked file off the FX thread; peek is user-initiated, so one lazy thread is plenty. */
    private static final java.util.concurrent.ExecutorService PEEK_READ =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "peek-read");
                t.setDaemon(true);
                return t;
            });

    private @Nullable PeekPopup peekPopup;

    /**
     * {@code lsp.peekDefinition}: show the definition here rather than going to it.
     *
     * <p>The same request as {@link #gotoDefinition()}, answered without moving. Most uses of
     * go-to-definition are a question — what is this, what does it take — and the answer does not justify
     * losing your place, your scroll position and usually a tab. Enter in the popup still commits to the
     * real jump, so nothing is taken away.
     */
    void peekDefinition() {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path originPath = origin.ownPath();
        Path anchor = origin.anchor();
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        syncForRequest(origin);
        lspManager.definitionAt(
                anchor, origin.libraryUri(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
                    if (!navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                        return;
                    }
                    if (targets.isEmpty()) {
                        host.setStatus(XmlLspActions.noDefinition(lspManager.lastMissingInclude()));
                        return;
                    }
                    LspManager.Target t = targets.get(0);
                    Path file = t.file();
                    if (file == null) {
                        peekLibrary(
                                anchor,
                                t,
                                () -> navigationRequestCurrent(b, originPath, originVersion, requestGeneration));
                        return;
                    }
                    peekTarget(t, file, b, originPath, originVersion, requestGeneration);
                });
    }

    /**
     * Peeks a {@code jdt://} definition — a JDK or dependency member — in the peek window like any other
     * (Java review B7), its source asked of {@code anchor}'s session; Open there opens it as library source.
     * Before, peek gave up and opened a tab.
     */
    private void peekLibrary(Path anchor, LspManager.Target t, java.util.function.BooleanSupplier current) {
        String uri = t.classFileUri();
        if (uri == null) {
            host.setStatus(tr("status.lsp.noDefinition"));
            return;
        }
        lspManager.classFileContents(anchor, uri, content -> {
            if (!current.getAsBoolean()) {
                return;
            }
            if (content == null) {
                host.setStatus(tr("status.lsp.libraryUnavailable"));
                return;
            }
            int line = com.editora.lsp.JavadocSkip.declarationLine(content, t.line());
            showPeek(
                    LspManager.classFileTitle(uri),
                    content,
                    line,
                    "java",
                    () -> openLibraryDefinition(anchor, t, current));
        });
    }

    private void peekTarget(
            LspManager.Target t,
            Path file,
            EditorBuffer origin,
            @Nullable Path originPath,
            long originVersion,
            long requestGeneration) {
        EditorBuffer open = lsp.bufferForLspPath(file);
        if (open != null) {
            // Already open: its text is authoritative (it may hold unsaved edits) and free to read here.
            showPeek(t, file, open.text());
            return;
        }
        PEEK_READ.submit(() -> {
            String text;
            try {
                text = java.nio.file.Files.readString(file);
            } catch (java.io.IOException | RuntimeException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (navigationRequestCurrent(origin, originPath, originVersion, requestGeneration)) {
                        host.setStatus(tr("status.lsp.peekUnreadable"));
                    }
                });
                return;
            }
            javafx.application.Platform.runLater(() -> {
                if (navigationRequestCurrent(origin, originPath, originVersion, requestGeneration)) {
                    showPeek(t, file, text);
                }
            });
        });
    }

    private void showPeek(LspManager.Target t, Path file, String text) {
        String name = PathDisplay.fileName(file);
        showPeek(
                name,
                text,
                t.line(),
                com.editora.text.LanguageRegistry.forFileName(name),
                () -> ops.openAndGoto(file, t.line(), t.character()));
    }

    private void showPeek(String name, String text, int line, String language, Runnable open) {
        if (peekPopup == null) {
            peekPopup = new PeekPopup(host.overlayHost());
        }
        String title = tr("lsp.peek.title", name, line + 1);
        // The editor's own font, so the peeked lines wrap and align the way the file does.
        peekPopup.show(
                PeekPopup.build(title, text, line, language),
                host.settings().getFontFamily(),
                host.settings().getFontSize(),
                open);
    }

    void gotoDefinition() {
        gotoDefinition(null);
    }

    /**
     * As {@link #gotoDefinition()}, running {@code afterJump} once the caret has landed.
     *
     * <p>The hook exists because the destination arrives from the server: a caller that wants to do
     * something with the file the jump opened cannot simply run afterwards, since "afterwards" is before
     * the answer has come back. It runs only on a jump that actually happened.
     */
    void gotoDefinition(@Nullable Runnable afterJump) {
        gotoDefinition(afterJump, lsp::followLinkAtCaret); // an HTML href/src: its file (B6)
    }

    /**
     * As {@link #gotoDefinition(Runnable)}; {@code noAnswer} runs when no server serves the file or the server
     * finds nothing — the server-free index then gets its turn — and keeps this method's own status message
     * when it returns false (it could not answer either).
     */
    void gotoDefinition(@Nullable Runnable afterJump, java.util.function.@Nullable BooleanSupplier noAnswer) {
        EditorBuffer active = host.activeBuffer();
        LibrarySource lib = active == null ? null : librarySources.get(active);
        if (active != null && lib != null) {
            libraryGotoDefinition(active, lib); // navigating INSIDE an opened jdt:// source (#684)
            return;
        }
        EditorBuffer b = lsp.activeLspBuffer();
        if (b == null) {
            if (noAnswer != null) {
                noAnswer.getAsBoolean();
            }
            return;
        }
        CodeArea area = b.getFocusedArea();
        Path originPath = lsp.lspPath(b);
        if (originPath == null) {
            return;
        }
        long originVersion = b.docVersion();
        long requestGeneration = ++navigationRequestGeneration;
        String text = b.text();
        int caret = area.getCaretPosition();
        lspManager.changeDocument(originPath, text); // sync latest text before the request
        lspManager.definition(originPath, area.getCurrentParagraph(), area.getCaretColumn(), answered -> {
            if (navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                InstantiatedType.checkDefinition(lspManager, originPath, text, caret, answered, targets -> {
                    if (!navigationRequestCurrent(b, originPath, originVersion, requestGeneration)) {
                        return;
                    }
                    if (targets.isEmpty()) {
                        if (noAnswer == null || !noAnswer.getAsBoolean()) {
                            host.setStatus(XmlLspActions.noDefinition(lspManager.lastMissingInclude()));
                        }
                    } else {
                        LspManager.Target t = targets.get(0);
                        if (t.file() != null) {
                            lsp.noteLibraryTarget(originPath, t.file());
                            ops.openAndGoto(t.file(), t.line(), t.character());
                            if (afterJump != null) {
                                // openAndGoto finishes its work in a runLater of its own, so the hook has to queue
                                // behind it or it would act on the tab we are leaving.
                                javafx.application.Platform.runLater(afterJump);
                            }
                        } else {
                            openLibraryDefinition(
                                    lsp.lspPath(b),
                                    t,
                                    () -> navigationRequestCurrent(b, originPath, originVersion, requestGeneration));
                        }
                    }
                });
            }
        });
    }

    /**
     * Class-file source tabs already opened from a {@code jdt://} definition, keyed by URI so a repeated
     * {@code M-.} on the same class re-selects its tab instead of spawning another. Weak values: a closed
     * tab's buffer is disposed and must be collectable; a stale entry just falls through to a re-fetch.
     */
    private final Map<String, java.lang.ref.WeakReference<EditorBuffer>> libraryBuffers = new java.util.HashMap<>();

    /** What a library-source (jdt://) tab was opened from: its own URI + the workspace file whose session
     *  produced it — lets navigation chain from inside library code (#684). Weak keys: dies with the tab. */
    private final Map<EditorBuffer, LibrarySource> librarySources = new java.util.WeakHashMap<>();

    private record LibrarySource(
            @Nullable String uri, @Nullable Path anchor) {}

    /**
     * Opens a {@code jdt://} class-file URI at a line — how a stack-trace frame inside a dependency or the
     * JDK becomes clickable (#744). Reuses the same read-only library-source tab as go-to-definition (#665),
     * so a repeat click re-selects the existing tab rather than opening another.
     */
    void openLibraryFrame(Path anchorPath, String jdtUri, int line0) {
        openLibraryFrame(anchorPath, jdtUri, line0, b -> {});
    }

    /** As above, handing the library-source tab to {@code opened} once it shows (a debugger frame paints it). */
    void openLibraryFrame(Path anchorPath, String jdtUri, int line0, java.util.function.Consumer<EditorBuffer> opened) {
        openLibraryDefinition(
                anchorPath, new LspManager.Target(null, Math.max(0, line0), 0, jdtUri), () -> true, opened);
    }

    /**
     * Opens the source of a JDK/dependency class the server reported under a {@code jdt://} URI: fetched via
     * jdtls's {@code java/classFileContents} into a read-only, Java-highlighted, path-less buffer (#665).
     * Before this, such a definition was silently dropped and {@code M-.} on {@code String}/{@code List}
     * reported "no definition". {@code anchor} is the buffer the navigation started from (it routes the
     * request to the right session).
     */
    private void openLibraryDefinition(Path anchorPath, LspManager.Target t) {
        openLibraryDefinition(anchorPath, t, () -> true);
    }

    private void openLibraryDefinition(
            @Nullable Path anchorPath, LspManager.Target t, java.util.function.BooleanSupplier stillCurrent) {
        openLibraryDefinition(anchorPath, t, stillCurrent, b -> {});
    }

    private void openLibraryDefinition(
            @Nullable Path anchorPath,
            LspManager.Target t,
            java.util.function.BooleanSupplier stillCurrent,
            java.util.function.Consumer<EditorBuffer> onOpened) {
        if (!stillCurrent.getAsBoolean()) {
            return;
        }
        String uri = t.classFileUri();
        var ref = libraryBuffers.get(uri);
        EditorBuffer existing = ref == null ? null : ref.get();
        if (existing != null && ops.selectBufferTab(existing)) {
            gotoInBuffer(existing, t.line(), t.character());
            onOpened.accept(existing);
            return;
        }
        if (anchorPath == null) {
            host.setStatus(tr("status.lsp.libraryUnavailable")); // no session to ask: as an empty answer says
            return;
        }
        host.setStatus(tr("status.lsp.libraryLoading"));
        lspManager.classFileContents(anchorPath, uri, content -> {
            if (!stillCurrent.getAsBoolean()) {
                return;
            }
            if (content == null) {
                host.setStatus(tr("status.lsp.libraryUnavailable"));
                return;
            }
            EditorBuffer opened = ops.openReadOnlyDoc(LspManager.classFileTitle(uri), content, "java");
            if (opened != null) {
                libraryBuffers.put(uri, new java.lang.ref.WeakReference<>(opened));
                librarySources.put(opened, new LibrarySource(uri, anchorPath)); // chain point (#684)
                librarySourcesBar.opened(opened, uri, anchorPath);
                // jdtls's class-file range starts at the member's Javadoc: land on the declaration (B16).
                int line = com.editora.lsp.JavadocSkip.declarationLine(content, t.line());
                gotoInBuffer(opened, line, line == t.line() ? t.character() : -1);
                host.setStatus(""); // the "Loading library source…" it said is over
                onOpened.accept(opened);
            }
        });
    }

    /**
     * Go-to-definition from INSIDE an opened {@code jdt://} library source (#684): the tab has no
     * filesystem path, so the request goes out with the library document's own jdt URI on the anchor
     * file's session — jdtls resolves positions in the jdt documents it has served. Results chain: a
     * file target opens normally, another library target opens (or re-selects) its own read-only tab.
     */
    private void libraryGotoDefinition(EditorBuffer buffer, LibrarySource lib) {
        CodeArea area = buffer.getFocusedArea();
        long version = buffer.docVersion();
        long generation = ++navigationRequestGeneration;
        java.util.function.BooleanSupplier current = () -> generation == navigationRequestGeneration
                && buffer == host.activeBuffer()
                && version == buffer.docVersion();
        Path anchor = lib.anchor();
        if (anchor == null) {
            host.setStatus(tr("status.lsp.noDefinition")); // no session to ask: as an empty answer says
            return;
        }
        lspManager.definitionAt(anchor, lib.uri(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (!current.getAsBoolean()) {
                return;
            }
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
                return;
            }
            LspManager.Target t = targets.get(0);
            if (t.file() != null) {
                lsp.noteLibraryTarget(anchor, t.file());
                ops.openAndGoto(t.file(), t.line(), t.character());
            } else {
                openLibraryDefinition(anchor, t, current);
            }
        });
    }

    /**
     * Moves {@code buffer}'s caret to a 0-based line/column (clamped) and shows the line; a negative {@code col0}
     * is the line's first non-blank character.
     */
    static void gotoInBuffer(EditorBuffer buffer, int line0, int col0) {
        CodeArea a = buffer.getFocusedArea();
        int par = Math.max(0, Math.min(line0, a.getParagraphs().size() - 1));
        String text = a.getParagraph(par).getText();
        int col = col0 >= 0
                ? Math.min(col0, text.length())
                : text.length() - text.stripLeading().length();
        a.moveTo(par, col);
        // At the top below the pinned rows, as every jump shows its target; followed only, the line sat under the
        // sticky header and a library tab's View Mode bar (Java review B16). A tab just opened anchors after layout.
        buffer.jumps().reveal(a, par, false);
        a.requestFocus();
    }

    void findReferences() {
        findReferences(false);
    }

    /**
     * Find References at the caret. The command jumps straight to a lone reference and only reports that
     * there are none; a code lens ({@code listAlways}) is a click on a count, so it always opens the
     * References tool window on what was counted — one row, or the empty list behind "0 references".
     */
    void findReferences(boolean listAlways) {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        Path path = origin.ownPath();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        String symbol = LspCoordinator.wordAtCaret(area);
        syncForRequest(origin); // sync latest text before the request
        lspManager.referencesAt(
                origin.anchor(),
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                targets -> {
                    if (!navigationRequestCurrent(b, path, version, generation)) {
                        return;
                    }
                    if (listAlways) {
                        showInReferencesWindow(targets, symbol);
                        return;
                    }
                    if (targets.isEmpty()) {
                        host.setStatus(tr("status.lsp.noReferences"));
                        return;
                    }
                    if (targets.size() == 1) {
                        LspManager.Target t = targets.get(0); // a lone reference: jump straight there (IDE behavior)
                        ops.openAndGoto(t.file(), t.line(), t.character());
                        return;
                    }
                    showInReferencesWindow(targets, symbol);
                },
                why -> {
                    if (navigationRequestCurrent(b, path, version, generation)) {
                        host.setError(
                                "timeout".equals(why)
                                        ? tr("status.lsp.referencesTimedOut")
                                        : tr("status.lsp.referencesFailed", why));
                    }
                });
    }

    /**
     * Go to Implementation ({@code lsp.gotoImplementation}, #735) — the concrete overrides of the member at
     * the caret. Result handling mirrors {@link #findReferences}: a lone implementation jumps straight there,
     * several fill the References tool window. Unlike references, a target can be a {@code jdt://} class file
     * (an interface implemented inside a dependency), so those route through the library-source path.
     */
    void gotoImplementation() {
        withNavRequest(
                "status.lsp.implementationUnsupported",
                lspManager::supportsImplementation,
                lspManager::implementationAt,
                (anchor, targets) -> {
                    if (targets.isEmpty()) {
                        host.setStatus(tr("status.lsp.noImplementations"));
                        return;
                    }
                    if (targets.size() == 1) {
                        openTarget(anchor, targets.get(0));
                        return;
                    }
                    EditorBuffer active = host.activeBuffer();
                    String symbol = active == null ? null : LspCoordinator.wordAtCaret(active.getFocusedArea());
                    if (targets.stream().allMatch(t -> t.file() != null)) {
                        showInReferencesWindow(targets, symbol, true);
                        return;
                    }
                    // The References window is keyed by file and a library target has none: list them all in a
                    // picker instead of opening one unexplained class (re-review BN4).
                    nestedImplementationNames(anchor, targets, names -> {
                        java.nio.file.Path base = ops.lspProjectRoot();
                        List<String> excluded = lspManager.javaFilteredTypes();
                        java.util.function.Function<LspManager.Target, String> label =
                                t -> names.getOrDefault(t, ImplementationTargets.implementationLabel(t));
                        List<LspManager.Target> listed = targets.stream() // no sun.awt.util.IdentityArrayList (BN4)
                                .filter(t -> t.file() != null
                                        || !SymbolRanking.excluded(
                                                ImplementationTargets.libraryPackage(t) + "."
                                                        + ImplementationTargets.implementationLabel(t),
                                                excluded))
                                // by class and line, so a class's own rows sit together in order (fourth review BN4)
                                .sorted(java.util.Comparator.comparing(label).thenComparingInt(LspManager.Target::line))
                                .toList();
                        LspCoordinator.pickOne(
                                host,
                                symbol == null
                                        ? tr("picker.implementations.title")
                                        : tr("picker.implementations.of", symbol),
                                tr("picker.implementations.prompt"),
                                listed.isEmpty() ? targets : listed,
                                label,
                                t -> ImplementationTargets.implementationDetail(t, base),
                                chosen -> openTarget(anchor, chosen));
                    });
                });
    }

    /**
     * Each library target's type by its own name, nested ones dotted ({@code AbstractList.SubList}), read from its
     * class's source; a target whose source is not had keeps its file's name (fifth review BN4). One request per
     * class file, at most {@link #NESTED_NAME_FILES}.
     */
    private void nestedImplementationNames(
            Path anchor,
            List<LspManager.Target> targets,
            java.util.function.Consumer<Map<LspManager.Target, String>> cb) {
        Map<String, List<LspManager.Target>> byClass = new java.util.LinkedHashMap<>();
        for (LspManager.Target t : targets) {
            if (t.file() == null && t.classFileUri() != null && byClass.size() < NESTED_NAME_FILES) {
                byClass.computeIfAbsent(t.classFileUri(), k -> new java.util.ArrayList<>())
                        .add(t);
            }
        }
        Map<LspManager.Target, String> names = new java.util.HashMap<>();
        if (byClass.isEmpty()) {
            cb.accept(names);
            return;
        }
        int[] left = {byClass.size()};
        byClass.forEach((uri, rows) -> lspManager.classFileContents(anchor, uri, source -> {
            if (source != null) {
                for (LspManager.Target t : rows) {
                    String name = com.editora.lsp.JavaTypeAtLine.at(source, t.line());
                    if (name != null) {
                        names.put(t, name);
                    }
                }
            }
            if (--left[0] == 0) {
                cb.accept(names);
            }
        }));
    }

    private static final int NESTED_NAME_FILES = 24;

    /** Go to Type Definition ({@code lsp.gotoTypeDefinition}, #736) — from a symbol to its type's declaration. */
    void gotoTypeDefinition() {
        withNavRequest(
                "status.lsp.typeDefinitionUnsupported",
                lspManager::supportsTypeDefinition,
                lspManager::typeDefinitionAt,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noTypeDefinition"));
    }

    /** The active library tab's sources: Download Sources, else Choose Sources… ({@code lsp.*LibrarySources}). */
    void librarySources(boolean choose) {
        if (choose) {
            librarySourcesBar.choose(host.activeBuffer());
        } else {
            librarySourcesBar.download(host.activeBuffer());
        }
    }

    /** Go to Declaration ({@code lsp.gotoDeclaration}, #736). */
    void gotoDeclaration() {
        withNavRequest(
                "status.lsp.declarationUnsupported",
                lspManager::supportsDeclaration,
                lspManager::declarationAt,
                (anchor, targets) -> openFirstTarget(anchor, targets, "status.lsp.noDeclaration"));
    }

    /** A position-to-targets request on {@link LspManager} (implementation / type definition / declaration). */
    @FunctionalInterface
    private interface NavRequest {
        void run(
                Path anchor,
                @Nullable String documentUri,
                int line,
                int character,
                Consumer<List<LspManager.Target>> cb,
                Consumer<String> onError);
    }

    /**
     * Shared preamble for the navigation commands: resolve the managed buffer, refuse (with a precise status)
     * when the server doesn't advertise the provider — rather than letting an empty result read as "nothing
     * found" — sync the latest text, then run the request at the caret.
     */
    private void withNavRequest(
            String unsupportedKey,
            java.util.function.Predicate<Path> supported,
            NavRequest request,
            java.util.function.BiConsumer<Path, List<LspManager.Target>> onTargets) {
        NavOrigin origin = navOrigin();
        if (origin == null) {
            return;
        }
        Path path = origin.anchor();
        if (!supported.test(path)) {
            host.setStatus(tr(unsupportedKey));
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        long version = b.docVersion();
        long generation = ++navigationRequestGeneration;
        syncForRequest(origin); // sync latest text before the request
        request.run(
                path,
                origin.libraryUri(),
                area.getCurrentParagraph(),
                area.getCaretColumn(),
                targets -> {
                    if (navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
                        onTargets.accept(path, targets);
                    }
                },
                why -> { // a failed request said "No implementations found" (Java review B13)
                    if (navigationRequestCurrent(b, origin.ownPath(), version, generation)) {
                        host.setError(
                                "timeout".equals(why)
                                        ? tr("status.lsp.navigationTimedOut")
                                        : tr("status.lsp.navigationFailed", why));
                    }
                });
    }

    boolean navigationRequestCurrent(EditorBuffer buffer, @Nullable Path path, long version, long requestGeneration) {
        return requestGeneration == navigationRequestGeneration
                && buffer == host.activeBuffer()
                && java.util.Objects.equals(path, lsp.lspPath(buffer))
                && version == buffer.docVersion();
    }

    /** Opens the first target, reporting {@code emptyKey} when there is none. */
    private void openFirstTarget(Path anchor, List<LspManager.Target> targets, String emptyKey) {
        if (targets.isEmpty()) {
            host.setStatus(tr(emptyKey));
            return;
        }
        openTarget(anchor, targets.get(0));
    }

    /** Opens a resolved target: a workspace file directly, a {@code jdt://} class file as read-only source. */
    void openTarget(Path anchor, LspManager.Target t) {
        if (t.file() != null) {
            lsp.noteLibraryTarget(anchor, t.file());
            ops.openAndGoto(t.file(), t.line(), t.character());
        } else {
            EditorBuffer origin = host.activeBuffer();
            long version = origin == null ? -1 : origin.docVersion();
            long generation = navigationRequestGeneration;
            // the tab's own path, none for a library tab: comparing the anchor (the project file a library tab is
            // served through) dropped every library target opened from a library tab (eighth review BQ3)
            Path originPath = origin == null ? null : lsp.lspPath(origin);
            openLibraryDefinition(
                    anchor,
                    t,
                    () -> origin != null
                            && generation == navigationRequestGeneration
                            && origin == host.activeBuffer()
                            && java.util.Objects.equals(originPath, lsp.lspPath(origin))
                            && version == origin.docVersion());
        }
    }

    /** Fills the References tool window with {@code targets} (each must have a real path) and opens it. */
    private void showInReferencesWindow(List<LspManager.Target> targets) {
        showInReferencesWindow(targets, null);
    }

    /** As above, naming the {@code symbol} the list is about in its summary. */
    private void showInReferencesWindow(List<LspManager.Target> targets, @Nullable String symbol) {
        showInReferencesWindow(targets, symbol, false);
    }

    /** As above; {@code implementations} titles the list "2 implementations of area()" (re-review BN9). */
    private void showInReferencesWindow(
            List<LspManager.Target> targets, @Nullable String symbol, boolean implementations) {
        List<String> previews = ReferencePreviews.previewLines(targets, f -> {
            EditorBuffer buffer = lsp.bufferForLspPath(f);
            return buffer == null ? null : buffer.getContent();
        });
        List<ReferencesPanel.Reference> refs = new java.util.ArrayList<>(targets.size());
        java.util.Set<Path> unread = new java.util.LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            LspManager.Target t = targets.get(i);
            refs.add(new ReferencesPanel.Reference(
                    t.file(),
                    t.line(),
                    t.character(),
                    previews.get(i),
                    previewRuns(t, previews.get(i)),
                    symbol != null && ReferencesPanel.writes(openLine(t), t.character(), symbol.length())));
            if (previews.get(i).isEmpty() && t.file() != null && lsp.bufferForLspPath(t.file()) == null) {
                unread.add(t.file());
            }
        }
        ReferencesPanel panel = referencesPanel();
        panel.setReferences(refs, symbol, ops.lspProjectRoot(), implementations);
        ops.openReferencesWindow();
        if (!unread.isEmpty()) {
            readPreviewsFromDisk(panel, panel.generation(), unread);
        }
    }

    /** A reference's whole line from its file's open tab, or null without one. */
    private @Nullable String openLine(LspManager.Target t) {
        EditorBuffer buffer = t.file() == null ? null : lsp.bufferForLspPath(t.file());
        if (buffer == null) {
            return null;
        }
        CodeArea area = buffer.getArea();
        return t.line() >= 0 && t.line() < area.getParagraphs().size()
                ? area.getParagraph(t.line()).getText()
                : null;
    }

    /** The largest file whose lines a References preview reads from disk. */
    private static final long PREVIEW_READ_LIMIT = 4L * 1024 * 1024;

    /**
     * The previews of references into files without a tab, read off the FX thread (the Peek reader) and filled
     * into the list when it is still the one shown. Before, those rows showed only {@code 13:19}, and in a
     * real project most references are in closed files (Java review B6). Remote and huge files stay bare.
     */
    private void readPreviewsFromDisk(ReferencesPanel panel, long generation, java.util.Set<Path> files) {
        PEEK_READ.submit(() -> {
            java.util.Map<Path, String[]> lines = new java.util.HashMap<>();
            for (Path f : files) {
                try {
                    if (!com.editora.vfs.Vfs.isRemote(f) && java.nio.file.Files.size(f) <= PREVIEW_READ_LIMIT) {
                        lines.put(f, java.nio.file.Files.readString(f).split("\n", -1));
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    // left without a preview, as before
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            javafx.application.Platform.runLater(() -> panel.fillPreviews(generation, r -> {
                String[] in = r.file() == null ? null : lines.get(r.file());
                return in != null && r.line() >= 0 && r.line() < in.length ? in[r.line()] : "";
            }));
        });
    }

    /**
     * The syntax-styled runs of a reference's preview, read from the open buffer's own highlighting so the
     * row is colored as the editor colors that line. Empty (a plain preview) when the file has no open tab or
     * the view's line is not the document's line (a narrowed buffer, a filtered log).
     */
    private List<ReferencesPanel.Run> previewRuns(LspManager.Target t, String preview) {
        EditorBuffer buffer = preview.isEmpty() ? null : lsp.bufferForLspPath(t.file());
        if (buffer == null) {
            return List.of();
        }
        try {
            CodeArea area = buffer.getArea();
            if (t.line() >= area.getParagraphs().size()) {
                return List.of();
            }
            String line = area.getParagraph(t.line()).getText();
            return line.strip().equals(preview)
                    ? ReferencesPanel.previewRuns(line, area.getStyleSpans(t.line()))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of(); // the document changed under us; a plain preview is safe
        }
    }

    /**
     * Opens the "Go to Symbol in Workspace" popup ({@code workspace/symbol}) — an incremental picker that
     * re-queries the active file's language server as you type, jumping to the chosen symbol across files.
     * Seeds the query from a single-line selection when present.
     */
    void gotoSymbolInWorkspace() {
        EditorBuffer b = lsp.activeLspBuffer();
        if (b == null) {
            return;
        }
        Path anchor = lsp.lspPath(b);
        if (anchor == null) {
            return;
        }
        if (!lspManager.supportsWorkspaceSymbols(anchor)) {
            host.setStatus(tr("status.lsp.workspaceSymbolsUnsupported"));
            return;
        }
        String sel = b.getFocusedArea().getSelectedText();
        String seed = sel != null && !sel.isBlank() && !sel.contains("\n") ? sel.trim() : "";
        WorkspaceSymbolPopup popup = new WorkspaceSymbolPopup(host.overlayHost(), new WorkspaceSymbolPopup.Ops() {
            @Override
            public void query(
                    String text, java.util.function.Consumer<java.util.List<LspManager.WorkspaceSymbolMatch>> cb) {
                lspManager.workspaceSymbols(
                        anchor,
                        text,
                        ms -> cb.accept(SymbolRanking.withoutExcluded(ms, lspManager.javaFilteredTypes())));
            }

            @Override
            public void open(LspManager.WorkspaceSymbolMatch match) {
                openWorkspaceSymbol(anchor, match);
            }
        });
        popup.show(seed);
    }

    /** Opens a workspace-symbol match: its file, or a {@code jdt://} one as library source asked of {@code anchor}. */
    void openWorkspaceSymbol(Path anchor, LspManager.WorkspaceSymbolMatch match) {
        Path file = match.file();
        if (file != null) {
            ops.openAndGoto(file, match.line(), match.character());
        } else {
            var target = new LspManager.Target(null, match.line(), match.character(), match.libraryUri());
            if (match.line() > 0) {
                openLibraryDefinition(anchor, target);
                return;
            }
            // jdtls places a class-file type at line 0, its package line: find the declaration (BN7).
            openLibraryDefinition(anchor, target, () -> true, opened -> {
                int line = SymbolRanking.typeDeclarationLine(opened.getContent(), match.name());
                if (line >= 0) {
                    gotoInBuffer(opened, line, -1);
                }
            });
        }
    }

    /** The file or project a Java request goes through when no tab names one: the active file, else the project. */
    private @Nullable Path javaAnchor() {
        EditorBuffer b = host.activeBuffer();
        Path own = b == null ? null : lsp.lspPath(b);
        return own != null && lspManager.hasJavaSession(own) ? own : ops.lspProjectRoot();
    }

    /** Search Everywhere's JDK and dependency classes from jdtls (Java review E8); empty without a Java server. */
    void libraryTypes(String query, java.util.function.Consumer<List<LspManager.WorkspaceSymbolMatch>> cb) {
        Path anchor = javaAnchor();
        if (anchor == null || query.isBlank()) {
            cb.accept(List.of());
            return;
        }
        lspManager.javaLibraryTypes(
                anchor, query, ms -> cb.accept(SymbolRanking.withoutExcluded(ms, lspManager.javaFilteredTypes())));
    }

    /**
     * {@link #libraryTypes} as Search Everywhere rows: fuzzy-matched like the index's symbols, one point below an
     * equal project match, so the project's own class is listed first.
     */
    void libraryTypeItems(
            String query, java.util.function.Consumer<List<com.editora.search.SearchEverywhere.Item>> cb) {
        libraryTypes(query, matches -> cb.accept(libraryRows(matches, query)));
    }

    /**
     * {@code matches} as Search Everywhere rows, in Ctrl+N's tie order: Search Everywhere keeps the arrival order of
     * rows with an equal score and label, so "Entry" listed {@code Map.Entry} 16th, past its group (fifth review EV3).
     */
    static List<com.editora.search.SearchEverywhere.Item> libraryRows(
            List<LspManager.WorkspaceSymbolMatch> matches, String query) {
        List<LspManager.WorkspaceSymbolMatch> ordered = new java.util.ArrayList<>(matches);
        ordered.sort(SymbolRanking.TIE_BREAK);
        List<com.editora.search.SearchEverywhere.Item> out = new java.util.ArrayList<>();
        for (LspManager.WorkspaceSymbolMatch m : ordered) {
            int score = SymbolRanking.score(m, query);
            if (score != com.editora.search.FuzzyMatch.NO_SCORE) {
                out.add(new com.editora.search.SearchEverywhere.Item(
                        com.editora.search.SearchEverywhere.Kind.SYMBOL,
                        m.name(),
                        m.container().isEmpty() ? LspManager.classFileTitle(m.libraryUri()) : m.container(),
                        score - 1,
                        m));
            }
        }
        return out;
    }

    /** Opens a {@link #libraryTypes} match. */
    void openLibraryType(LspManager.WorkspaceSymbolMatch match) {
        Path anchor = javaAnchor();
        if (anchor != null) {
            openWorkspaceSymbol(anchor, match);
        }
    }
}
