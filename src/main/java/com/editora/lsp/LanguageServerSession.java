package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

/**
 * One external language-server process for a single workspace {@code root}, driven over stdio via LSP4J.
 * Handles {@code initialize}/{@code initialized}, document synchronization (full-text), and the request
 * subset Phase 1 needs (completion/hover/definition/references). Implements {@link LanguageClient} so it
 * receives {@code publishDiagnostics}/log/show-message notifications.
 *
 * <p>Pure of JavaFX: callbacks fire on LSP4J's reader thread, so the caller ({@link LspManager}) marshals
 * results to the FX thread. Requests sent before {@code initialize} completes are queued and flushed once
 * the server is ready. Not started in the constructor — call {@link #start()}.
 */
final class LanguageServerSession implements LanguageClient {

    private static final Logger LOG = Logger.getLogger(LanguageServerSession.class.getName());

    private final String serverId;
    private final List<String> command;
    private final Path root;
    private final Consumer<PublishDiagnosticsParams> onDiagnostics;
    /** Server status sink: {@code accept(type, message)} — type is a JDT LS {@code language/status} type
     *  (e.g. "Starting"/"ServiceReady"/"Error") or "Message" for {@code window/showMessage}. */
    private final java.util.function.BiConsumer<String, String> onStatus;
    /** Server-specific {@code initialize.initializationOptions} (e.g. jdtls {@code {"bundles":[…]}} to
     *  load the java-debug plugin); null for the default. */
    private final Object initializationOptions;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-session");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final AtomicInteger nextVersion = new AtomicInteger(1);

    // volatile: start() runs off the FX thread (#407) and must NOT hold the `this` monitor during the fork (that
    // would block whenReady()'s synchronized check on the FX thread, re-introducing the stall). volatile safely
    // publishes these to the FX reader (ready()) and the launcher's drain thread instead of the monitor.
    private volatile Process process;
    private volatile LanguageServer server;
    /** The LSP4J launcher, kept for {@link #rawRequest} (custom, non-standard requests like jdtls's
     *  {@code java/classFileContents}, which the typed {@link LanguageServer} proxy can't express). */
    private volatile Launcher<LanguageServer> launcher;

    private volatile boolean initialized;
    private volatile Runnable onDead = () -> {};
    private final java.util.concurrent.atomic.AtomicBoolean deadReported =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean disposed;
    // Written on the LSP4J init thread (initialize().whenComplete), read on the FX thread (capabilities()).
    // volatile gives the FX reader the happens-before edge so it can't transiently see null after init completed.
    private volatile ServerCapabilities capabilities;

    LanguageServerSession(
            LspServerRegistry.ServerSpec spec,
            Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus) {
        this(spec, root, onDiagnostics, onStatus, null);
    }

    /** How long to wait for the {@code initialize} handshake before giving up on the server. */
    private static final java.time.Duration INITIALIZE_TIMEOUT = java.time.Duration.ofSeconds(60);

    /**
     * Invoked (once) when this session can no longer serve requests — the process exited on its own, or the
     * handshake failed/timed out. The manager drops the session so the next request starts a fresh one; the
     * default is a no-op so the extra constructors need no change.
     */
    void setOnDead(Runnable onDead) {
        this.onDead = onDead == null ? () -> {} : onDead;
    }

    /** The server id this session runs (from its {@link LspServerRegistry.ServerSpec}); used to detect when a
     *  document's desired server has changed (e.g. a pom.xml moving from the plain XML server to lemminx-maven). */
    String serverId() {
        return serverId;
    }

    LanguageServerSession(
            LspServerRegistry.ServerSpec spec,
            Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus,
            Object initializationOptions) {
        this.serverId = spec.serverId();
        this.command = spec.command();
        this.root = root;
        this.onDiagnostics = onDiagnostics;
        this.onStatus = onStatus == null ? (t, m) -> {} : onStatus;
        this.initializationOptions = initializationOptions;
    }

    /**
     * Launches the server process + LSP4J client and sends {@code initialize}. Returns false on failure. Called
     * once, off the FX thread (see {@code LspManager.sessionForRoot}); deliberately <b>not</b> {@code synchronized}
     * — the fork/exec below blocks for the JVM/Node startup, and holding the {@code this} monitor across it would
     * block {@link #whenReady}'s {@code synchronized} check on the FX thread, re-introducing the very stall #407
     * fixes. Fields it publishes ({@code process}/{@code server}) are {@code volatile}; {@code dispose()} stays
     * {@code synchronized} and a post-fork {@link #disposed} guard covers a dispose that races the fork.
     */
    boolean start() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
            pb.directory(root.toFile());
            ProcessRunner.applyStandardEnv(pb);
            process = pb.start();
            if (disposed) {
                // The session was disposed while we were forking (e.g. the window closed) — kill the just-forked
                // process and bail rather than leave it running orphaned.
                ProcessRegistry.killTree(process);
                return false;
            }
            ProcessRegistry.track(process); // reaped on JVM exit / next-run startup if we die without dispose()
            // A server that dies on its own (crash, OOM-kill) otherwise stays cached as a live session:
            // ready()/isManaged() keep returning true, every request fails into an empty result, and the
            // re-open guard (which tests isManaged) never restarts it — so LSP is silently dead for the
            // session, while the status bar still names the server.
            process.onExit().thenRun(() -> markDead());
            // Drain the server's stderr on a daemon thread (LSP traffic is on stdout). It MUST be drained —
            // an undrained PIPE fills its ~64 KB OS buffer on a chatty server (jdtls logs heavily) and the
            // server blocks writing, deadlocking mid-startup. Capturing it to the Debug Log (instead of the
            // old Redirect.DISCARD) surfaces *why* a server fails to come up (missing JDK, lock, bad command)
            // — otherwise that's invisible. Capped so a chatty server can't flood the log.
            drainStderr(process);
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                    this, process.getInputStream(), process.getOutputStream(), executor, c -> c);
            this.launcher = launcher;
            server = launcher.getRemoteProxy();
            launcher.startListening();
            sendInitialize();
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to start language server " + command, e);
            onStatus.accept("Error", "Failed to start language server: " + e.getMessage());
            dispose();
            return false;
        }
    }

    /**
     * Reads {@code p}'s stderr to EOF on a daemon thread so the OS pipe never fills (which would block the
     * server mid-startup), logging the first {@value #STDERR_LOG_CAP} lines to the Debug Log so a failed
     * launch is diagnosable. Past the cap it keeps draining but stops logging.
     */
    private void drainStderr(Process p) {
        Thread t = new Thread(
                () -> {
                    int logged = 0;
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                            p.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (logged < STDERR_LOG_CAP) {
                                LOG.info("[" + command.get(0) + " stderr] " + line);
                                if (++logged == STDERR_LOG_CAP) {
                                    LOG.info("[" + command.get(0) + " stderr] …(further output suppressed)");
                                }
                            }
                            // keep reading past the cap so the pipe drains and the server never blocks.
                        }
                    } catch (java.io.IOException ignored) {
                        // stream closed (server exited) — nothing more to drain.
                    }
                },
                "lsp-stderr-" + command.get(0));
        t.setDaemon(true);
        t.start();
    }

    private static final int STDERR_LOG_CAP = 200;

    private void sendInitialize() {
        InitializeParams ip = new InitializeParams();
        ip.setProcessId((int) ProcessHandle.current().pid());
        String uri = root.toUri().toString();
        ip.setRootUri(uri);
        ip.setWorkspaceFolders(
                List.of(new WorkspaceFolder(uri, root.getFileName().toString())));
        ip.setCapabilities(clientCapabilities());
        if (initializationOptions != null) {
            ip.setInitializationOptions(initializationOptions); // jdtls: {"bundles":[<java-debug jar>]}
        }
        server.initialize(ip)
                // A server that never answers leaves this future — and every queued call — hanging forever:
                // the status bar's loading bar spins with no error, the session is cached as if it were live,
                // and the queue grows with each edit. That is exactly the jdtls workspace-lock wedge. Time it
                // out so the handshake either completes or fails, like executeCommand already does.
                .orTimeout(INITIALIZE_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> {
                    capabilities = result.getCapabilities();
                    server.initialized(new InitializedParams());
                    pushConfiguration(); // proactively enable Pyright auto-imports (also answered via configuration())
                    List<Pending> toRun;
                    synchronized (this) {
                        initialized = true;
                        toRun = new ArrayList<>(pending);
                        pending.clear();
                    }
                    toRun.forEach(p -> p.action().run());
                    // Signal the UI that the handshake completed so the status-bar loading bar stops. The
                    // jdtls-specific language/status notification (handled below) only fires for JDT LS and only
                    // once a project is ready; this universal signal covers every server — and a clean file that
                    // never publishes a diagnostic — with a null message so it doesn't write to the echo area.
                    onStatus.accept("ServiceReady", null);
                })
                .exceptionally(t -> {
                    LOG.log(Level.WARNING, "initialize failed", t);
                    synchronized (this) {
                        pending.clear(); // nothing will ever run these; they pin document copies
                    }
                    onStatus.accept("Error", null); // also stop the loading bar on a failed handshake
                    markDead(); // drop the session: it is cached but can never serve a request
                    return null;
                });
    }

    /**
     * The semantic token types/modifiers we understand (the standard LSP 3.16 legend). The server
     * intersects these with its own and reports the <em>effective</em> legend in its capabilities, which
     * {@code LspManager} reads to decode responses — so this list only bounds what a server may send.
     */
    private static final java.util.List<String> SEMANTIC_TOKEN_TYPES = java.util.List.of(
            org.eclipse.lsp4j.SemanticTokenTypes.Namespace,
            org.eclipse.lsp4j.SemanticTokenTypes.Type,
            org.eclipse.lsp4j.SemanticTokenTypes.Class,
            org.eclipse.lsp4j.SemanticTokenTypes.Enum,
            org.eclipse.lsp4j.SemanticTokenTypes.Interface,
            org.eclipse.lsp4j.SemanticTokenTypes.Struct,
            org.eclipse.lsp4j.SemanticTokenTypes.TypeParameter,
            org.eclipse.lsp4j.SemanticTokenTypes.Parameter,
            org.eclipse.lsp4j.SemanticTokenTypes.Variable,
            org.eclipse.lsp4j.SemanticTokenTypes.Property,
            org.eclipse.lsp4j.SemanticTokenTypes.EnumMember,
            org.eclipse.lsp4j.SemanticTokenTypes.Event,
            org.eclipse.lsp4j.SemanticTokenTypes.Function,
            org.eclipse.lsp4j.SemanticTokenTypes.Method,
            org.eclipse.lsp4j.SemanticTokenTypes.Macro,
            org.eclipse.lsp4j.SemanticTokenTypes.Keyword,
            org.eclipse.lsp4j.SemanticTokenTypes.Modifier,
            org.eclipse.lsp4j.SemanticTokenTypes.Comment,
            org.eclipse.lsp4j.SemanticTokenTypes.String,
            org.eclipse.lsp4j.SemanticTokenTypes.Number,
            org.eclipse.lsp4j.SemanticTokenTypes.Regexp,
            org.eclipse.lsp4j.SemanticTokenTypes.Operator,
            org.eclipse.lsp4j.SemanticTokenTypes.Decorator);

    private static final java.util.List<String> SEMANTIC_TOKEN_MODIFIERS = java.util.List.of(
            org.eclipse.lsp4j.SemanticTokenModifiers.Declaration,
            org.eclipse.lsp4j.SemanticTokenModifiers.Definition,
            org.eclipse.lsp4j.SemanticTokenModifiers.Readonly,
            org.eclipse.lsp4j.SemanticTokenModifiers.Static,
            org.eclipse.lsp4j.SemanticTokenModifiers.Deprecated,
            org.eclipse.lsp4j.SemanticTokenModifiers.Abstract,
            org.eclipse.lsp4j.SemanticTokenModifiers.Async,
            org.eclipse.lsp4j.SemanticTokenModifiers.Modification,
            org.eclipse.lsp4j.SemanticTokenModifiers.Documentation,
            org.eclipse.lsp4j.SemanticTokenModifiers.DefaultLibrary);

    private static ClientCapabilities clientCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        CompletionItemCapabilities completionItem = new CompletionItemCapabilities(true); // snippetSupport
        // Advertise that we can resolve additionalTextEdits — Pyright (and others) only emit an
        // auto-import's `import` edit when the client says it can resolve it. We resolve on accept
        // (see MainController.autoImportAccept). detail/documentation stay eager so the popup hint shows.
        completionItem.setResolveSupport(new org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities(
                java.util.List.of("additionalTextEdits")));
        td.setCompletion(new CompletionCapabilities(completionItem));
        td.setHover(new HoverCapabilities());
        td.setDefinition(new DefinitionCapabilities());
        td.setReferences(new ReferencesCapabilities());
        td.setDocumentHighlight(new org.eclipse.lsp4j.DocumentHighlightCapabilities()); // occurrences (#675)
        // Rename (#676): prepareSupport lets the server validate the position + hand us the placeholder.
        var rename = new org.eclipse.lsp4j.RenameCapabilities();
        rename.setPrepareSupport(true);
        td.setRename(rename);
        // Signature help (#674): declare markdown docs + label offsets (servers send precise active-parameter
        // ranges only when the client says it can render them) + per-signature activeParameter.
        var sigInfo =
                new org.eclipse.lsp4j.SignatureInformationCapabilities(java.util.List.of("markdown", "plaintext"));
        sigInfo.setParameterInformation(new org.eclipse.lsp4j.ParameterInformationCapabilities(true));
        sigInfo.setActiveParameterSupport(true);
        td.setSignatureHelp(new org.eclipse.lsp4j.SignatureHelpCapabilities(sigInfo, false));
        // Code actions (#670): literal support is what makes servers return CodeAction objects (kind,
        // isPreferred, an inline edit) instead of bare Commands; resolveSupport("edit") lets a server defer
        // the expensive edit to codeAction/resolve; dataSupport lets its opaque data ride the round-trip
        // (jdtls quick fixes need all three).
        var codeAction = new org.eclipse.lsp4j.CodeActionCapabilities();
        codeAction.setCodeActionLiteralSupport(new org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities(
                new org.eclipse.lsp4j.CodeActionKindCapabilities(java.util.List.of(
                        "",
                        "quickfix",
                        "refactor",
                        "refactor.extract",
                        "refactor.inline",
                        "refactor.rewrite",
                        "source",
                        "source.organizeImports"))));
        codeAction.setResolveSupport(
                new org.eclipse.lsp4j.CodeActionResolveSupportCapabilities(java.util.List.of("edit")));
        codeAction.setDataSupport(true);
        codeAction.setIsPreferredSupport(true);
        td.setCodeAction(codeAction);
        // Pull diagnostics (LSP 3.17): many modern servers — vscode-html/css/json, etc. — deliver
        // diagnostics only on a textDocument/diagnostic *request* (a diagnosticProvider) instead of
        // pushing publishDiagnostics. Declaring this lets us pull them (see LspManager.pullDiagnostics).
        td.setDiagnostic(new org.eclipse.lsp4j.DiagnosticCapabilities(false));
        // Semantic tokens (LSP 3.16): a server-resolved classification we overlay onto TextMate — it
        // distinguishes a parameter from a field from a type and flags deprecated/static/readonly. We
        // advertise both encodings (the decoder handles either); LspManager prefers range and falls back to
        // a full request for range-less servers (e.g. jdtls, which advertises range=false, full=true).
        var stRequests = new org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests(true, true); // full, range
        td.setSemanticTokens(new org.eclipse.lsp4j.SemanticTokensCapabilities(
                stRequests, SEMANTIC_TOKEN_TYPES, SEMANTIC_TOKEN_MODIFIERS, java.util.List.of("relative")));
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        // Declare we answer workspace/configuration — otherwise Pyright never asks for
        // python.analysis.autoImportCompletions and keeps its (off) default, so no auto-imports.
        org.eclipse.lsp4j.WorkspaceClientCapabilities ws = new org.eclipse.lsp4j.WorkspaceClientCapabilities();
        ws.setConfiguration(true);
        ws.setDidChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationCapabilities());
        ws.setSymbol(new org.eclipse.lsp4j.SymbolCapabilities()); // we answer workspace/symbol (Go to Symbol)
        // We push watched-file events (#677) — without them a git checkout / CLI build leaves the server's
        // project model stale until restart. Static push only (no dynamic watcher registration).
        ws.setDidChangeWatchedFiles(new org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities(false));
        // We answer workspace/applyEdit (#670) — how a server-side command (a jdtls quick fix routed
        // through executeCommand) actually lands its edits in the editor. documentChanges=true because
        // jdtls emits the modern TextDocumentEdit[] shape when the client accepts it.
        ws.setApplyEdit(true);
        var wsEdit = new org.eclipse.lsp4j.WorkspaceEditCapabilities();
        wsEdit.setDocumentChanges(true);
        // Renaming a public Java class makes jdtls move the .java file too — declared so it sends the
        // RenameFile op (create/delete stay undeclared and refused by the mapper) (#676).
        wsEdit.setResourceOperations(java.util.List.of(org.eclipse.lsp4j.ResourceOperationKind.Rename));
        ws.setWorkspaceEdit(wsEdit);
        cc.setWorkspace(ws);
        return cc;
    }

    /** Pushes our default settings (e.g. enable Pyright auto-imports) via workspace/didChangeConfiguration. */
    private void pushConfiguration() {
        try {
            java.util.Map<String, Object> analysis = new java.util.HashMap<>();
            analysis.put("autoImportCompletions", true);
            java.util.Map<String, Object> python = new java.util.HashMap<>();
            python.put("analysis", analysis);
            java.util.Map<String, Object> settings = new java.util.HashMap<>();
            settings.put("python", python);
            server.getWorkspaceService()
                    .didChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationParams(settings));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "didChangeConfiguration failed", e);
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    ServerCapabilities capabilities() {
        return capabilities;
    }

    Path root() {
        return root;
    }

    /** Runs {@code action} now if initialized, else queues it until {@code initialize} completes. */
    /** Marks the session unusable and tells the manager to drop it. Idempotent; safe from any thread. */
    private void markDead(java.lang.Process ignored) {
        markDead();
    }

    private void markDead() {
        if (deadReported.compareAndSet(false, true)) {
            initialized = false;
            synchronized (this) {
                pending.clear();
            }
            if (!disposed) {
                // The server died on its own (crash, OOM-kill, instant startup death) — not a deliberate
                // dispose(). Stop the status-bar loading bar NOW: for a process that dies before initialize
                // resolves, the only other exit used to be the 60 s handshake timeout, so the bar spun for a
                // minute over a corpse (#666). A null message stops the bar without writing to the echo area.
                onStatus.accept("Error", null);
            }
            onDead.run();
        }
    }

    /** Whether {@link #dispose()} ran — i.e. this session was torn down deliberately. A dead session that was
     *  never disposed died on its own (crash / failed handshake), which is what the auto-restart keys on. */
    boolean isDisposed() {
        return disposed;
    }

    /** True while the session can actually serve a request — initialized AND its process still alive. */
    boolean isLive() {
        return initialized && !disposed && process != null && process.isAlive();
    }

    private void whenReady(Runnable action) {
        whenReady(null, action);
    }

    /**
     * Runs {@code action} now, or queues it until {@code initialize} completes.
     *
     * <p>A non-null {@code collapseKey} makes this <b>replace</b> any queued action with the same key. That
     * matters for {@code didChange}, whose lambda captures the whole document text: while a server is still
     * initializing (jdtls takes seconds — or forever, if it wedges) the 300 ms debounce queued one entry per
     * typing pause, each pinning a full copy of the file. A 1 MB file and five minutes of typing retained
     * ~1 GB, against the packaged app's {@code -Xmx2g}. Only the latest text per document is worth keeping.
     */
    private void whenReady(String collapseKey, Runnable action) {
        if (disposed) {
            return;
        }
        synchronized (this) {
            if (!initialized) {
                if (collapseKey != null) {
                    pending.removeIf(p -> collapseKey.equals(p.key()));
                }
                pending.add(new Pending(collapseKey, action));
                return;
            }
        }
        action.run();
    }

    /** A queued call, with an optional key identifying entries a later one supersedes. */
    private record Pending(String key, Runnable action) {}

    // --- Document synchronization (full-text) ---------------------------------------------------

    void didOpen(String uri, String languageId, String text) {
        versions.put(uri, 1);
        whenReady(() -> server.getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, languageId, 1, text))));
    }

    void didChange(String uri, String text) {
        if (changeSyncDisabled()) {
            return; // server negotiated TextDocumentSyncKind.None — it doesn't track content changes
        }
        int version = versions.merge(uri, 1, Integer::sum);
        // Collapse: a queued didChange for this uri is superseded by this one (full-text sync, so only the
        // latest matters) — otherwise every typing pause before initialize pins another copy of the document.
        whenReady(
                "didChange:" + uri,
                () -> server.getTextDocumentService()
                        .didChange(new DidChangeTextDocumentParams(
                                new VersionedTextDocumentIdentifier(uri, version),
                                List.of(new TextDocumentContentChangeEvent(text)))));
    }

    /**
     * Whether the server explicitly declared {@link TextDocumentSyncKind#None} for change sync — in which
     * case we skip the (debounced, per-edit) {@code didChange} entirely instead of pushing the full text
     * it will ignore. Conservative: an <em>unspecified</em> sync capability keeps the current full-text
     * behavior (we still send), since omitting it is rare and a missed update is worse than a wasted one.
     */
    private boolean changeSyncDisabled() {
        if (capabilities == null) {
            return false;
        }
        var sync = capabilities.getTextDocumentSync();
        if (sync == null) {
            return false; // unspecified → keep sending full text (existing behavior)
        }
        if (sync.isLeft()) {
            return sync.getLeft() == TextDocumentSyncKind.None;
        }
        // Detailed options form: an explicit change == None means no change notifications are wanted.
        return sync.getRight() != null && sync.getRight().getChange() == TextDocumentSyncKind.None;
    }

    void didSave(String uri) {
        whenReady(() -> server.getTextDocumentService()
                .didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri))));
    }

    void didClose(String uri) {
        versions.remove(uri);
        whenReady(() -> server.getTextDocumentService()
                .didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri))));
    }

    boolean isOpen(String uri) {
        return versions.containsKey(uri);
    }

    // --- Requests (return raw LSP futures; LspManager marshals to the FX thread) ----------------

    /**
     * Runs a server-side command ({@code workspace/executeCommand}) — used to drive jdtls's debug
     * commands ({@code vscode.java.resolveMainClass}/{@code resolveClasspath}/{@code startDebugSession}/…).
     * Queued until {@code initialize} completes; completes exceptionally if the session is disposed first.
     */
    CompletableFuture<Object> executeCommand(String command, List<Object> args) {
        CompletableFuture<Object> out = new CompletableFuture<>();
        whenReady(() -> {
            if (disposed || server == null) {
                out.completeExceptionally(new IllegalStateException("language server not available"));
                return;
            }
            try {
                server.getWorkspaceService()
                        .executeCommand(new ExecuteCommandParams(command, args == null ? List.of() : args))
                        .whenComplete((r, e) -> {
                            if (e != null) {
                                out.completeExceptionally(e);
                            } else {
                                out.complete(r);
                            }
                        });
            } catch (RuntimeException ex) {
                out.completeExceptionally(ex);
            }
        });
        return out;
    }

    /**
     * Sends a <b>non-standard</b> request the typed {@link LanguageServer} proxy can't express — e.g. jdtls's
     * {@code java/classFileContents} (#665) — via the launcher's raw JSON-RPC endpoint. The result is the
     * gson-decoded payload (lsp4j has no registered response type for an unknown method, so expect a
     * {@code JsonElement}/{@code JsonPrimitive}). Queued until {@code initialize} completes.
     */
    CompletableFuture<Object> rawRequest(String method, Object params) {
        CompletableFuture<Object> out = new CompletableFuture<>();
        whenReady(() -> {
            Launcher<LanguageServer> l = launcher;
            if (disposed || l == null) {
                out.completeExceptionally(new IllegalStateException("language server not available"));
                return;
            }
            try {
                l.getRemoteEndpoint().request(method, params).whenComplete((r, e) -> {
                    if (e != null) {
                        out.completeExceptionally(e);
                    } else {
                        out.complete(r);
                    }
                });
            } catch (RuntimeException ex) {
                out.completeExceptionally(ex);
            }
        });
        return out;
    }

    /** Code actions ({@code textDocument/codeAction}) for {@code range}, with the client-known diagnostics
     *  overlapping it as context (quick fixes key off them) → the actions, or empty (#670). */
    CompletableFuture<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> codeAction(
            String uri, org.eclipse.lsp4j.Range range, List<org.eclipse.lsp4j.Diagnostic> diagnostics) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var context = new org.eclipse.lsp4j.CodeActionContext(diagnostics == null ? List.of() : diagnostics);
        var params = new org.eclipse.lsp4j.CodeActionParams(new TextDocumentIdentifier(uri), range, context);
        return server.getTextDocumentService()
                .codeAction(params)
                .<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>>thenApply(
                        l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Resolves a code action ({@code codeAction/resolve}) to fill in its deferred {@code edit};
     *  returns the action unchanged if the server can't resolve. */
    CompletableFuture<org.eclipse.lsp4j.CodeAction> resolveCodeAction(org.eclipse.lsp4j.CodeAction action) {
        if (!ready()) {
            return CompletableFuture.completedFuture(action);
        }
        return server.getTextDocumentService().resolveCodeAction(action).exceptionally(t -> action);
    }

    /** Handler for a server-initiated {@code workspace/applyEdit}: {@code accept(edit, respond)} — the
     *  handler applies (on whatever thread it marshals to) and answers via {@code respond}. Default: refuse. */
    private volatile java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> onApplyEdit =
            (edit, respond) -> respond.accept(false);

    void setOnApplyEdit(java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> handler) {
        this.onApplyEdit = handler == null ? (edit, respond) -> respond.accept(false) : handler;
    }

    /**
     * {@code workspace/applyEdit} — the server asks <b>us</b> to apply a workspace edit (how a jdtls
     * quick-fix command lands its changes). lsp4j's default implementation throws, so before this existed
     * any server-initiated edit errored (#670). Arrives on the reader thread; the handler marshals to FX
     * and answers the future when done.
     */
    @Override
    public CompletableFuture<org.eclipse.lsp4j.ApplyWorkspaceEditResponse> applyEdit(
            org.eclipse.lsp4j.ApplyWorkspaceEditParams params) {
        CompletableFuture<org.eclipse.lsp4j.ApplyWorkspaceEditResponse> out = new CompletableFuture<>();
        try {
            onApplyEdit.accept(
                    params == null ? null : params.getEdit(),
                    applied -> out.complete(
                            new org.eclipse.lsp4j.ApplyWorkspaceEditResponse(Boolean.TRUE.equals(applied))));
        } catch (RuntimeException e) {
            out.complete(new org.eclipse.lsp4j.ApplyWorkspaceEditResponse(false));
        }
        return out;
    }

    /** Signature help ({@code textDocument/signatureHelp}) at a position → the overloads + active
     *  parameter, or null when unavailable/failed (#674). */
    CompletableFuture<org.eclipse.lsp4j.SignatureHelp> signatureHelp(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.SignatureHelpParams(new TextDocumentIdentifier(uri), pos);
        return server.getTextDocumentService().signatureHelp(params).exceptionally(t -> null);
    }

    /** Occurrences of the symbol at a position ({@code textDocument/documentHighlight}) → highlights with
     *  their Read/Write kind, or empty (#675). */
    CompletableFuture<List<? extends org.eclipse.lsp4j.DocumentHighlight>> documentHighlight(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.DocumentHighlightParams(new TextDocumentIdentifier(uri), pos);
        return server.getTextDocumentService().documentHighlight(params).exceptionally(t -> List.of());
    }

    /** Validates a rename at a position ({@code textDocument/prepareRename}) → the symbol range and/or
     *  placeholder, or null when renaming here is not possible (#676). */
    CompletableFuture<
                    org.eclipse.lsp4j.jsonrpc.messages.Either3<
                            org.eclipse.lsp4j.Range,
                            org.eclipse.lsp4j.PrepareRenameResult,
                            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>>
            prepareRename(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.PrepareRenameParams(new TextDocumentIdentifier(uri), pos);
        return server.getTextDocumentService().prepareRename(params).exceptionally(t -> null);
    }

    /** Renames the symbol at a position ({@code textDocument/rename}) → the workspace edit, or null (#676). */
    CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(String uri, Position pos, String newName) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.RenameParams(new TextDocumentIdentifier(uri), pos, newName);
        return server.getTextDocumentService().rename(params).exceptionally(t -> null);
    }

    /** Notifies the server of external file changes ({@code workspace/didChangeWatchedFiles}) so its
     *  project model tracks a git checkout / CLI build / external editor (#677). Dropped when not ready —
     *  a starting server reads the disk fresh anyway. */
    void didChangeWatchedFiles(List<org.eclipse.lsp4j.FileEvent> events) {
        if (!ready() || events == null || events.isEmpty()) {
            return;
        }
        try {
            server.getWorkspaceService()
                    .didChangeWatchedFiles(new org.eclipse.lsp4j.DidChangeWatchedFilesParams(events));
        } catch (RuntimeException ignored) {
            // best effort — an advisory notification
        }
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return server.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(uri), pos));
    }

    /** Whole-document formatting ({@code textDocument/formatting}) → the edits to apply, or empty. */
    CompletableFuture<List<? extends TextEdit>> formatting(String uri, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        DocumentFormattingParams params = new DocumentFormattingParams(new TextDocumentIdentifier(uri), options);
        return server.getTextDocumentService().formatting(params).exceptionally(t -> List.of());
    }

    /** Range formatting ({@code textDocument/rangeFormatting}) over {@code range} → the edits, or empty. */
    CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            String uri, org.eclipse.lsp4j.Range range, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.eclipse.lsp4j.DocumentRangeFormattingParams params =
                new org.eclipse.lsp4j.DocumentRangeFormattingParams(new TextDocumentIdentifier(uri), options, range);
        return server.getTextDocumentService().rangeFormatting(params).exceptionally(t -> List.of());
    }

    /** Resolves a completion item ({@code completionItem/resolve}) to fill in its {@code additionalTextEdits}
     *  (e.g. a TypeScript auto-import); returns the item unchanged if the server can't resolve. */
    CompletableFuture<CompletionItem> resolveCompletion(CompletionItem item) {
        if (!ready() || item == null) {
            return CompletableFuture.completedFuture(item);
        }
        return server.getTextDocumentService().resolveCompletionItem(item).exceptionally(t -> item);
    }

    CompletableFuture<Hover> hover(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri), pos));
    }

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return server.getTextDocumentService().definition(new DefinitionParams(new TextDocumentIdentifier(uri), pos));
    }

    CompletableFuture<List<? extends Location>> references(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(uri), pos, new ReferenceContext(true));
        return server.getTextDocumentService().references(params);
    }

    /** Project-wide symbol search ({@code workspace/symbol}) for {@code query}; empty when not ready/on error. */
    CompletableFuture<
                    org.eclipse.lsp4j.jsonrpc.messages.Either<
                            List<? extends org.eclipse.lsp4j.SymbolInformation>,
                            List<? extends org.eclipse.lsp4j.WorkspaceSymbol>>>
            workspaceSymbol(String query) {
        if (!ready()) {
            return CompletableFuture.completedFuture(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of()));
        }
        return server.getWorkspaceService()
                .symbol(new org.eclipse.lsp4j.WorkspaceSymbolParams(query))
                .exceptionally(t -> org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(List.of()));
    }

    /** Document symbols ({@code textDocument/documentSymbol}) for {@code uri} — empty when not ready/on error. */
    CompletableFuture<
                    java.util.List<
                            org.eclipse.lsp4j.jsonrpc.messages.Either<
                                    org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>>
            documentSymbol(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }
        return server.getTextDocumentService()
                .documentSymbol(new org.eclipse.lsp4j.DocumentSymbolParams(new TextDocumentIdentifier(uri)))
                .exceptionally(t -> java.util.List.of());
    }

    /** Pull diagnostics ({@code textDocument/diagnostic}) for {@code uri}; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport> diagnostic(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService()
                .diagnostic(new org.eclipse.lsp4j.DocumentDiagnosticParams(new TextDocumentIdentifier(uri)));
    }

    /** Semantic tokens over {@code range} ({@code textDocument/semanticTokens/range}); null when not ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(String uri, org.eclipse.lsp4j.Range range) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService()
                .semanticTokensRange(
                        new org.eclipse.lsp4j.SemanticTokensRangeParams(new TextDocumentIdentifier(uri), range))
                .exceptionally(t -> null);
    }

    /** Whole-document semantic tokens ({@code textDocument/semanticTokens/full}), for servers that don't
     *  advertise range requests; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService()
                .semanticTokensFull(new org.eclipse.lsp4j.SemanticTokensParams(new TextDocumentIdentifier(uri)))
                .exceptionally(t -> null);
    }

    private boolean ready() {
        return initialized && server != null && !disposed;
    }

    /** Sends {@code shutdown}+{@code exit} (best-effort) and tears down the process + threads. */
    synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        synchronized (this) {
            pending.clear(); // a session torn down mid-handshake must not retain its queued document copies
        }
        try {
            if (server != null && initialized) {
                server.shutdown().whenComplete((r, t) -> {
                    try {
                        server.exit();
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
            }
        } catch (Exception ignored) {
            // best effort
        }
        if (process != null) {
            // The launcher is often a wrapper (Homebrew jdtls → python → java); destroying only the
            // wrapper orphans the real server JVM, which keeps running and holds its workspace `.lock`
            // so the next session for the same root can't start. ProcessRegistry.killTree kills the whole
            // descendant tree (children first), escalates to a force-kill if SIGTERM is ignored, and
            // untracks it so the shutdown hook / next-run reaper won't chase a dead pid.
            ProcessRegistry.killTree(process);
        }
        executor.shutdownNow();
    }

    // --- LanguageClient callbacks (on LSP4J's reader thread) ------------------------------------

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        if (!disposed) {
            onDiagnostics.accept(diagnostics);
        }
    }

    @Override
    public void telemetryEvent(Object object) {
        // ignored
    }

    /**
     * Acknowledges {@code workspace/diagnostic/refresh}. Servers that support pull diagnostics
     * (vscode-html/css/json) send this request to ask the client to re-request diagnostics for its
     * open documents. lsp4j's default {@link LanguageClient#refreshDiagnostics()} throws
     * {@code UnsupportedOperationException}, which lsp4j then logs as a SEVERE "Internal error";
     * overriding it to complete normally silences that noise. We don't force an immediate re-pull
     * here — {@code LspManager.pullDiagnostics} already runs on every (debounced) edit, on save, and
     * when the server first reports ready — so the hint is effectively honored on the next pulse.
     */
    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Acknowledges {@code client/registerCapability} / {@code client/unregisterCapability}. Servers
     * that use dynamic capability registration (e.g. tinymist, whose init reports
     * {@code cfg_change_registration: true}) send these requests after initialize. lsp4j's default
     * {@link LanguageClient#registerCapability}/{@link LanguageClient#unregisterCapability} throw
     * {@code UnsupportedOperationException}, which lsp4j logs as a SEVERE "Internal error" and the
     * server then reports back as a failed registration. We don't track dynamic registrations
     * (capabilities are read from the initialize result), so accept-and-ignore is the correct
     * minimal handling — it silences the crash without changing behavior.
     */
    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Answers {@code workspace/configuration} so servers that read settings this way pick up our defaults
     * — notably **Pyright**, which only offers auto-import completions when
     * {@code python.analysis.autoImportCompletions} is on (its own default is off). We enable it however
     * the server phrases the request (the whole {@code python} object, the {@code python.analysis} object,
     * or the leaf key); unknown sections return null so the server keeps its own default.
     */
    @Override
    public CompletableFuture<List<Object>> configuration(org.eclipse.lsp4j.ConfigurationParams params) {
        List<Object> out = new java.util.ArrayList<>();
        if (params != null && params.getItems() != null) {
            for (org.eclipse.lsp4j.ConfigurationItem item : params.getItems()) {
                out.add(configFor(item.getSection() == null ? "" : item.getSection()));
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    private static Object configFor(String section) {
        if (section.endsWith("autoImportCompletions")) {
            return Boolean.TRUE;
        }
        if (section.equals("python.analysis") || section.endsWith(".analysis")) {
            java.util.Map<String, Object> analysis = new java.util.HashMap<>();
            analysis.put("autoImportCompletions", true);
            return analysis;
        }
        if (section.equals("python")) {
            java.util.Map<String, Object> analysis = new java.util.HashMap<>();
            analysis.put("autoImportCompletions", true);
            java.util.Map<String, Object> python = new java.util.HashMap<>();
            python.put("analysis", analysis);
            return python;
        }
        return null;
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        onStatus.accept("Message", messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(message.getMessage());
        }
    }

    /** JDT LS sends a non-standard {@code language/status} notification with start/ready/progress text
     *  (e.g. "Ready", "X% Starting Java Language Server"). Surface its message so the UI can show the
     *  server's loading/ready state; also stops LSP4J logging it as an "unsupported notification". */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/status")
    public void languageStatus(LanguageStatus status) {
        if (status != null) {
            String message = status.message == null ? "" : status.message.strip();
            onStatus.accept(status.type == null ? "" : status.type, message);
        }
    }

    /** Payload of JDT LS's {@code language/status} notification (deserialized by LSP4J's Gson). */
    public static final class LanguageStatus {
        public String type;
        public String message;
    }
}
