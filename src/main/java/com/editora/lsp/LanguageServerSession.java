package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private volatile List<String> command;
    private final Path root;
    private final Consumer<PublishDiagnosticsParams> onDiagnostics;
    /** Server status sink: {@code accept(type, message)} — type is a JDT LS {@code language/status} type
     *  (e.g. "Starting"/"ServiceReady"/"Error") or "Message" for {@code window/showMessage}. */
    private final java.util.function.BiConsumer<String, String> onStatus;
    /** Server-specific {@code initialize.initializationOptions} (e.g. jdtls {@code {"bundles":[…]}} to
     *  load the java-debug plugin); null for the default. */
    private volatile java.util.function.Supplier<Object> initializationOptionsSupplier;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-session");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Integer> versions = new ConcurrentHashMap<>();
    /** Per-document shadow of the text the server last received — the diff base for incremental sync
     *  (#678). Invariant: after every send, {@code shadows.get(uri)} equals the server's document. */
    private final Map<String, String> shadows = new ConcurrentHashMap<>();
    /** Incremental sends since the last full resync, per document (the divergence safety net, #678). */
    private final Map<String, Integer> sendsSinceResync = new ConcurrentHashMap<>();
    /** Active JDT legacy progress ids; intermediate reports are intentionally coalesced like standard
     *  {@code $/progress} reports so a cold import cannot flood the FX queue. */
    private final java.util.Set<String> jdtProgressIds = ConcurrentHashMap.newKeySet();

    /** Every {@code RESYNC_EVERY}-th change goes out as a full-text event even under incremental sync — a
     *  cheap safety net: if shadow and server ever diverged, every later delta would corrupt the server's
     *  copy silently and forever; a periodic full write re-converges them (#678). */
    private static final int RESYNC_EVERY = 256;

    private final List<Pending> pending = new ArrayList<>();

    // volatile: start() runs off the FX thread (#407) and must NOT hold the `this` monitor during the fork (that
    // would block whenReady()'s synchronized check on the FX thread, re-introducing the stall). volatile safely
    // publishes these to the FX reader (ready()) and the launcher's drain thread instead of the monitor.
    private volatile Process process;
    private volatile LanguageServer server;
    /** The LSP4J launcher, kept for {@link #rawRequest} (custom, non-standard requests like jdtls's
     *  {@code java/classFileContents}, which the typed {@link LanguageServer} proxy can't express). */
    private volatile Launcher<LanguageServer> launcher;
    /** The ordered writer between LSP4J and the server's stdin; null for an in-process test server. */
    private volatile AsyncPipeWriter writer;

    private volatile boolean initialized;
    /** Set only after a complete initialize response. Distinguishes a corrupt-startup failure from a
     *  later crash so the manager can safely discard only an unusable jdtls cache. */
    private volatile boolean initializedOnce;

    private volatile Runnable onDead = () -> {};
    private final java.util.concurrent.atomic.AtomicBoolean deadReported =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean disposed;
    private volatile Consumer<String> onRefresh = kind -> {};
    // Written on the LSP4J init thread (initialize().whenComplete), read on the FX thread (capabilities()).
    // volatile gives the FX reader the happens-before edge so it can't transiently see null after init completed.
    private volatile ServerCapabilities capabilities;
    /** Dynamic registrations keyed by registration id. Capability reads use the same effective
     *  {@link #capabilities} object as static initialize results, so all existing feature gates update. */
    private final Map<String, org.eclipse.lsp4j.Registration> dynamicRegistrations = new ConcurrentHashMap<>();

    private final java.util.Set<String> staticCapabilityMethods = ConcurrentHashMap.newKeySet();

    LanguageServerSession(
            LspServerRegistry.ServerSpec spec,
            Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus) {
        this(spec, root, onDiagnostics, onStatus, null);
    }

    /** How long to wait for the {@code initialize} handshake before giving up on the server. */
    private static final java.time.Duration INITIALIZE_TIMEOUT = java.time.Duration.ofSeconds(60);

    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(30);

    /** How long {@link #dispose()} waits for the server to answer {@code shutdown} before killing it. */
    private static final java.time.Duration SHUTDOWN_REPLY_WAIT = java.time.Duration.ofSeconds(2);

    /** How long a server that acknowledged {@code shutdown} gets to act on {@code exit} by itself. */
    private static final java.time.Duration EXIT_WAIT = java.time.Duration.ofSeconds(1);

    /**
     * One daemon timer for every session's request timeouts. {@code removeOnCancel} matters: a cancelled
     * timer otherwise stays in the queue until it would have fired, and its task holds the request future —
     * so every completion list and semantic-token array stayed reachable for the full timeout after the
     * reply had long been delivered.
     */
    private static final java.util.concurrent.ScheduledThreadPoolExecutor REQUEST_TIMER = requestTimer();

    private static java.util.concurrent.ScheduledThreadPoolExecutor requestTimer() {
        var timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "lsp-request-timeout");
            t.setDaemon(true);
            return t;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    /** Request timers still scheduled — package-private so a test can show a reply releases its timer. */
    static int pendingRequestTimeouts() {
        return REQUEST_TIMER.getQueue().size();
    }

    /** Bounds an ordinary server request with the shared {@link #REQUEST_TIMEOUT}. */
    private <T> CompletableFuture<T> bounded(CompletableFuture<T> request) {
        return track(bounded(request, REQUEST_TIMEOUT));
    }

    /**
     * Requests on the wire that the server has not answered. A session that dies or is disposed settles
     * them at once: left alone, each would wait for its own timer — 30 s for an ordinary request, ten
     * minutes for a workspace build — and then report a failure against whatever the window was doing by
     * then, while the timer queue kept the request, its callbacks and the dead session reachable.
     */
    private final java.util.Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    /** Registers {@code request} so a lost session fails it immediately instead of at its timeout. */
    private <T> CompletableFuture<T> track(CompletableFuture<T> request) {
        if (request.isDone()) {
            return request;
        }
        inFlight.add(request);
        request.whenComplete((result, error) -> inFlight.remove(request));
        if (disposed || deadReported.get()) {
            failInFlight(); // lost the race with markDead()/dispose(): nobody else will settle it
        }
        return request;
    }

    /**
     * Completes every unanswered request exceptionally. Not {@code cancel}: LSP4J's cancel writes a
     * {@code $/cancelRequest} to a pipe nobody reads any more. Completing the future also drops its timer.
     */
    private void failInFlight() {
        var failure = new IllegalStateException("language server not available");
        for (CompletableFuture<?> request : List.copyOf(inFlight)) {
            request.completeExceptionally(failure);
        }
    }

    /** Unanswered requests still tracked — package-private for the lost-session test. */
    int inFlightRequests() {
        return inFlight.size();
    }

    /**
     * Bounds {@code request}: its JSON-RPC future is cancelled if the server has not replied within
     * {@code timeout}, and the timer is dropped as soon as the request completes on its own.
     */
    static <T> CompletableFuture<T> bounded(CompletableFuture<T> request, java.time.Duration timeout) {
        if (request.isDone()) {
            return request;
        }
        var timer = REQUEST_TIMER.schedule(
                () -> {
                    if (!request.isDone()) {
                        request.cancel(true);
                    }
                },
                Math.max(1, timeout.toMillis()),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        request.whenComplete((result, error) -> timer.cancel(false));
        return request;
    }

    /**
     * Invoked (once) when this session can no longer serve requests — the process exited on its own, or the
     * handshake failed/timed out. The manager drops the session so the next request starts a fresh one; the
     * default is a no-op so the extra constructors need no change.
     */
    void setOnDead(Runnable onDead) {
        this.onDead = onDead == null ? () -> {} : onDead;
    }

    void setOnRefresh(Consumer<String> onRefresh) {
        this.onRefresh = onRefresh == null ? kind -> {} : onRefresh;
    }

    /** The argv this session launches (or launched) — package-private so a test can see which command won. */
    List<String> command() {
        return command;
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
        // A disposed session keeps reading for a moment while its server exits gracefully; nothing the
        // server says in that time (progress, "shutting down") may reach the status bar of a session that
        // is already gone — a progress Begin would start a loading bar no End will ever stop.
        java.util.function.BiConsumer<String, String> sink = onStatus == null ? (t, m) -> {} : onStatus;
        this.onStatus = (type, message) -> {
            if (!disposed) {
                sink.accept(type, message);
            } else if ("Error".equals(type) || "ProgressEnd".equals(type)) {
                sink.accept(type, null); // still allowed to stop a loading bar, never to start one or speak
            }
        };
        this.initializationOptionsSupplier = () -> initializationOptions;
    }

    /** Configures work that is deliberately resolved by the manager's start executor, immediately before
     *  {@link #start()}. This keeps filesystem/JDK discovery out of the FX-thread session-routing path. */
    void configureStart(List<String> command, java.util.function.Supplier<Object> initializationOptionsSupplier) {
        this.command = List.copyOf(command);
        this.initializationOptionsSupplier =
                initializationOptionsSupplier == null ? () -> null : initializationOptionsSupplier;
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
            ProcessRunner.applyUserEnv(pb); // the user's locale: under LC_ALL=C jdtls cannot index non-ASCII paths
            pb.command(JavaServerEnvironment.configure(serverId, pb.command(), pb.environment()));
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
            // Everything LSP4J sends goes through one ordered writer thread, so no caller — least of all
            // the FX thread — ever blocks in a pipe write when the server stops reading its stdin.
            AsyncPipeWriter out = new AsyncPipeWriter(
                    process.getOutputStream(), "lsp-writer-" + command.get(0), this::onInputStalled);
            writer = out;
            // The builder keeps its JSON handler to itself; the deferred document-sync encoding needs the
            // same one LSP4J encodes every other message with.
            var json = new java.util.concurrent.atomic.AtomicReference<
                    org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler>();
            Launcher<LanguageServer> launcher = new Launcher.Builder<LanguageServer>() {
                @Override
                protected org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler createJsonHandler() {
                    json.set(super.createJsonHandler());
                    return json.get();
                }
            }.setLocalService(this)
                    .setRemoteInterface(JdtLanguageServer.class)
                    .setInput(process.getInputStream())
                    .setOutput(out)
                    .setExecutorService(executor)
                    // LSP4J wraps both directions with this; only its stream writer is the outgoing end.
                    .wrapMessages(consumer -> consumer instanceof org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer
                            ? new DeferredSyncConsumer(consumer, out, json::get)
                            : consumer)
                    .create();
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

    /**
     * The server stopped reading its stdin and the outgoing backlog reached its limit. It cannot serve
     * requests in that state and will not recover by being sent more, so end it: the process exit then takes
     * the ordinary died-on-its-own path ({@link #markDead}), which lets the manager restart it.
     */
    private void onInputStalled() {
        LOG.warning("Language server " + command.get(0) + " stopped reading its input; ending it");
        Process p = process;
        if (p != null) {
            ProcessRegistry.killTree(p);
        } else {
            markDead();
        }
    }

    private void sendInitialize() {
        InitializeParams ip = new InitializeParams();
        ip.setProcessId((int) ProcessHandle.current().pid());
        String uri = root.toUri().toString();
        ip.setRootUri(uri);
        ip.setWorkspaceFolders(List.of(new WorkspaceFolder(uri, workspaceFolderName(root))));
        ip.setCapabilities(clientCapabilities());
        Object initializationOptions = initializationOptionsSupplier.get();
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
                    rememberStaticCapabilities();
                    server.initialized(new InitializedParams());
                    pushConfiguration(); // this server's own settings only (also answered via configuration())
                    List<Pending> toRun;
                    synchronized (this) {
                        toRun = new ArrayList<>(pending);
                        pending.clear();
                        // Preserve the wire order across the initialization boundary. A didChange arriving
                        // while this queue flushes must wait until every earlier open/change/close has been
                        // emitted, or its shadow can advance ahead of the server.
                        toRun.forEach(p -> p.action().run());
                        initialized = true;
                        initializedOnce = true;
                    }
                    // Signal the UI that the handshake completed so the status-bar loading bar stops. The
                    // jdtls-specific language/status notification (handled below) only fires for JDT LS and only
                    // once a project is ready; this universal signal covers every server — and a clean file that
                    // never publishes a diagnostic — with a null message so it doesn't write to the echo area.
                    onStatus.accept("ServiceReady", null);
                })
                .exceptionally(t -> {
                    LOG.log(Level.WARNING, "initialize failed", t);
                    failPending(new IllegalStateException("language server initialization failed", t));
                    onStatus.accept("Error", null); // also stop the loading bar on a failed handshake
                    markDead(); // drop the session: it is cached but can never serve a request
                    return null;
                });
    }

    /**
     * The display name sent for the workspace folder: the root's last path element. A filesystem or drive
     * root ({@code /}, {@code C:\}) has none — {@code getFileName()} is null there — so it is named by its
     * whole path instead of failing the handshake with a NullPointerException.
     */
    static String workspaceFolderName(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
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

    /** Package-private for {@code ClientCapabilitiesTest}: what we declare here decides what servers will
     *  do for us, and three separate features have died from a silent change to it (#468, #674, #676). */
    static ClientCapabilities clientCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        CompletionItemCapabilities completionItem = new CompletionItemCapabilities(true); // snippetSupport
        // Advertise that we can resolve additionalTextEdits — Pyright (and others) only emit an
        // auto-import's `import` edit when the client says it can resolve it. We resolve on accept
        // (see MainController.autoImportAccept). detail/documentation stay eager so the popup hint shows.
        completionItem.setResolveSupport(new org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities(
                java.util.List.of("additionalTextEdits")));
        completionItem.setPreselectSupport(true);
        completionItem.setDeprecatedSupport(true);
        completionItem.setInsertReplaceSupport(true);
        completionItem.setCommitCharactersSupport(true);
        completionItem.setLabelDetailsSupport(true);
        completionItem.setInsertTextModeSupport(new org.eclipse.lsp4j.CompletionItemInsertTextModeSupportCapabilities(
                List.of(org.eclipse.lsp4j.InsertTextMode.AsIs, org.eclipse.lsp4j.InsertTextMode.AdjustIndentation)));
        td.setCompletion(new CompletionCapabilities(completionItem));
        td.getCompletion().setContextSupport(true);
        td.getCompletion().setInsertTextMode(org.eclipse.lsp4j.InsertTextMode.AdjustIndentation);
        td.getCompletion()
                .setCompletionList(new org.eclipse.lsp4j.CompletionListCapabilities(
                        List.of("editRange", "insertTextFormat", "insertTextMode", "data", "commitCharacters")));
        // contentFormat: without it a conforming server (pyright, rust-analyzer, clangd) answers in
        // plaintext, which the hover popup then had to guess its way through as Markdown.
        td.setHover(new HoverCapabilities(List.of("markdown", "plaintext"), true));
        td.setDefinition(new DefinitionCapabilities());
        td.setReferences(new ReferencesCapabilities());
        // Implementation / type definition / declaration (#735, #736) — the three navigation requests
        // beside definition. A server only advertises its provider when the client declares the
        // capability, so dropping one of these silently removes that menu entry for every language.
        td.setImplementation(new org.eclipse.lsp4j.ImplementationCapabilities());
        td.setTypeDefinition(new org.eclipse.lsp4j.TypeDefinitionCapabilities());
        td.setDeclaration(new org.eclipse.lsp4j.DeclarationCapabilities());
        // Folding (#738): lineFoldingOnly is not optional for us — the gutter folds whole lines, and without
        // it a server may answer with character-precise ranges we cannot render.
        var folding = new org.eclipse.lsp4j.FoldingRangeCapabilities();
        folding.setLineFoldingOnly(true);
        td.setFoldingRange(folding);
        td.setSelectionRange(new org.eclipse.lsp4j.SelectionRangeCapabilities()); // expand/shrink (#739)
        td.setDocumentHighlight(new org.eclipse.lsp4j.DocumentHighlightCapabilities()); // occurrences (#675)
        td.setInlayHint(new org.eclipse.lsp4j.InlayHintCapabilities()); // parameter/type hints (#681)
        td.setCodeLens(new org.eclipse.lsp4j.CodeLensCapabilities()); // reference/implementation counts
        td.setCallHierarchy(new org.eclipse.lsp4j.CallHierarchyCapabilities()); // who-calls-this (#682)
        td.setTypeHierarchy(new org.eclipse.lsp4j.TypeHierarchyCapabilities()); // super/subtypes (#682)
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
        var sigCaps = new org.eclipse.lsp4j.SignatureHelpCapabilities(sigInfo, true);
        sigCaps.setContextSupport(true);
        td.setSignatureHelp(sigCaps);
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
        // full (with delta — #679) + range: a range-less server (jdtls) otherwise re-sends the whole
        // document's tokens on every 300 ms edit pulse; with delta it sends only the changed splice.
        var stRequests = new org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests(
                new org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequestsFull(true), (Boolean) true);
        td.setSemanticTokens(new org.eclipse.lsp4j.SemanticTokensCapabilities(
                stRequests, SEMANTIC_TOKEN_TYPES, SEMANTIC_TOKEN_MODIFIERS, java.util.List.of("relative")));
        // Every feature below is handled through the same effective-capabilities object when a server
        // registers it after initialize. Advertising dynamic registration is therefore truthful and lets
        // servers such as tinymist and jdtls enable features conditionally.
        td.getCompletion().setDynamicRegistration(true);
        td.getHover().setDynamicRegistration(true);
        td.getDefinition().setDynamicRegistration(true);
        td.getReferences().setDynamicRegistration(true);
        td.getImplementation().setDynamicRegistration(true);
        td.getTypeDefinition().setDynamicRegistration(true);
        td.getDeclaration().setDynamicRegistration(true);
        td.getFoldingRange().setDynamicRegistration(true);
        td.getSelectionRange().setDynamicRegistration(true);
        td.getDocumentHighlight().setDynamicRegistration(true);
        td.getInlayHint().setDynamicRegistration(true);
        td.getCallHierarchy().setDynamicRegistration(true);
        td.getTypeHierarchy().setDynamicRegistration(true);
        td.getRename().setDynamicRegistration(true);
        td.getSignatureHelp().setDynamicRegistration(true);
        td.getCodeAction().setDynamicRegistration(true);
        td.getDiagnostic().setDynamicRegistration(true);
        td.getSemanticTokens().setDynamicRegistration(true);
        // The one-argument constructor is dynamicRegistration. hierarchicalDocumentSymbolSupport is what
        // makes jdtls, pyright, clangd, lemminx and the JSON/YAML servers answer with a DocumentSymbol tree
        // instead of the legacy flat SymbolInformation list (every member a top-level outline row).
        var documentSymbol = new org.eclipse.lsp4j.DocumentSymbolCapabilities(true);
        documentSymbol.setHierarchicalDocumentSymbolSupport(true);
        td.setDocumentSymbol(documentSymbol);
        td.setFormatting(new org.eclipse.lsp4j.FormattingCapabilities(true));
        td.setRangeFormatting(new org.eclipse.lsp4j.RangeFormattingCapabilities(true));
        td.setOnTypeFormatting(new org.eclipse.lsp4j.OnTypeFormattingCapabilities(true));
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        // $/progress (#683): the standard progress channel every server speaks (jdtls indexing, gopls
        // setup, rust-analyzer's cargo check) — drives the status-bar loading bar + echo, replacing the
        // guessed indeterminate spinner for servers that report real progress.
        var window = new org.eclipse.lsp4j.WindowClientCapabilities();
        window.setWorkDoneProgress(true);
        cc.setWindow(window);
        // Declare we answer workspace/configuration (Pyright reads python.analysis that way). Servers that
        // ask because of it must get an answer they can live with — see LspServerSettings.
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
        // Renaming a public Java class makes jdtls move the .java file too — but jdtls's
        // isResourceOperationSupported() gate is ALL-OR-NOTHING: it emits a resource operation (the
        // RenameFile) only when the client declares Create AND Rename AND Delete. Declaring just Rename
        // made jdtls strip the file move, so a class rename renamed the symbols but left OldName.java on
        // disk (#676). We still only *apply* renames — a Create/Delete op refuses the whole edit in
        // WorkspaceEditMapper (safe: nothing half-applied), which is why the far-less-common create/delete
        // refactorings degrade to a "failed" status rather than corrupting the workspace. (jdtls's own
        // gate mirrors VS Code / eglot, which likewise declare all three.)
        wsEdit.setResourceOperations(java.util.List.of(
                org.eclipse.lsp4j.ResourceOperationKind.Create,
                org.eclipse.lsp4j.ResourceOperationKind.Rename,
                org.eclipse.lsp4j.ResourceOperationKind.Delete));
        ws.setWorkspaceEdit(wsEdit);
        // The four workspace/*/refresh requests are implemented below (refreshSemanticTokens & co.), but a
        // server only sends one to a client that declares refreshSupport: clangd, rust-analyzer, jdtls and
        // typescript-language-server all gate on it, so a header edit left dependent tabs on stale tokens.
        ws.setSemanticTokens(new org.eclipse.lsp4j.SemanticTokensWorkspaceCapabilities(true));
        ws.setInlayHint(new org.eclipse.lsp4j.InlayHintWorkspaceCapabilities(true));
        ws.setDiagnostics(new org.eclipse.lsp4j.DiagnosticWorkspaceCapabilities(true));
        ws.setFoldingRange(new org.eclipse.lsp4j.FoldingRangeWorkspaceCapabilities(true));
        cc.setWorkspace(ws);
        return cc;
    }

    /**
     * Whether jdtls should format as you type ({@code java.format.onType.enabled}), mirroring
     * {@code Settings.lspOnTypeFormatting}. jdtls registers its on-type formatting provider dynamically and
     * only while this preference is on, so without it the feature is dead for Java however the editor's own
     * gate is set.
     */
    private volatile boolean javaOnTypeFormatting;

    /** Sets {@link #javaOnTypeFormatting} and, for a running Java server, pushes the changed preference. */
    void setJavaOnTypeFormatting(boolean enabled) {
        boolean changed = javaOnTypeFormatting != enabled;
        javaOnTypeFormatting = enabled;
        if (changed && LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /**
     * Pushes this server's own settings via workspace/didChangeConfiguration. A server Editora has no
     * settings for is sent nothing — see {@link LspServerSettings}.
     */
    private void pushConfiguration() {
        java.util.Map<String, Object> settings = LspServerSettings.push(serverId, javaOnTypeFormatting);
        if (settings == null) {
            return;
        }
        try {
            server.getWorkspaceService()
                    .didChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationParams(settings));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "didChangeConfiguration failed", e);
        }
    }

    boolean isInitialized() {
        return initialized;
    }

    /**
     * TEST SEAM — attaches an in-process {@link LanguageServer} as if {@code initialize} had just completed,
     * instead of forking a real one ({@link #start()}). Mirrors {@code WindowManager.buildWindowForTest}.
     *
     * <p>This exists because the two classes that build every request Editora puts on the wire —
     * {@code LanguageServerSession} and {@link LspManager} — were only reachable through a forked subprocess,
     * so nothing asserted <em>what we actually send</em>. That is not a hypothetical gap: #725 (the
     * {@code TriggerCharacter} branch of {@link #signatureHelp} was unreachable, so every request claimed
     * {@code Invoked}) and #715 (an inlay-hint range one line past the end of the document, which jdtls
     * answers with an empty list rather than an error) both lived here and were invisible to the suite, while
     * the pure mappers beside them — at 90-100% coverage — had none.
     *
     * <p>Flushes the pending queue exactly as the real handshake does, so a test can also exercise the
     * queue-until-ready path by calling document methods before attaching.
     */
    void attachForTest(LanguageServer testServer, ServerCapabilities caps) {
        this.server = testServer;
        this.capabilities = caps;
        rememberStaticCapabilities();
        List<Pending> toRun;
        synchronized (this) {
            toRun = new ArrayList<>(pending);
            pending.clear();
            toRun.forEach(p -> p.action().run());
            initialized = true;
            initializedOnce = true;
        }
    }

    ServerCapabilities capabilities() {
        return capabilities;
    }

    Path root() {
        return root;
    }

    /** TEST SEAM — simulates the server dying on its own (a crash / OOM-kill), which in production arrives
     *  via {@code process.onExit()}. Distinct from {@link #dispose()}: a session that died but was never
     *  disposed is what the auto-restart keys on (#666), and that distinction had no test. */
    void simulateServerDeathForTest() {
        markDead();
    }

    private void markDead() {
        if (deadReported.compareAndSet(false, true)) {
            initialized = false;
            failPending(new IllegalStateException("language server stopped"));
            failInFlight();
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

    /** Whether this session ever completed its initialize handshake. */
    boolean initializedOnce() {
        return initializedOnce;
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
        whenReady(collapseKey, action, () -> {});
    }

    private void whenReady(String collapseKey, Runnable action, Runnable onUnavailable) {
        if (disposed || deadReported.get()) { // a dead session never initializes again: do not queue for it
            onUnavailable.run();
            return;
        }
        synchronized (this) {
            if (disposed) {
                onUnavailable.run();
                return;
            }
            if (!initialized) {
                if (collapseKey != null) {
                    pending.removeIf(p -> collapseKey.equals(p.key()));
                }
                pending.add(new Pending(collapseKey, action, onUnavailable));
                return;
            }
        }
        action.run();
    }

    /** A queued call, with an optional key identifying entries a later one supersedes. */
    private record Pending(String key, Runnable action, Runnable onUnavailable) {}

    /** Completes request futures whose queued wire action can no longer run, and releases notification data. */
    private void failPending(Throwable failure) {
        List<Pending> abandoned;
        synchronized (this) {
            abandoned = new ArrayList<>(pending);
            pending.clear();
        }
        abandoned.forEach(p -> p.onUnavailable().run());
    }

    // --- Document synchronization (full-text) ---------------------------------------------------

    void didOpen(String uri, String languageId, String text) {
        versions.put(uri, 1);
        whenReady(() -> {
            versions.put(uri, 1);
            shadows.put(uri, text); // update in the same order as the wire notification
            sendsSinceResync.remove(uri);
            server.getTextDocumentService()
                    .didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, languageId, 1, text)));
        });
    }

    /**
     * Syncs {@code text}. Returns false only when the server is known to hold exactly this text already, so
     * nothing was (or will be) sent — and therefore nothing will make it publish diagnostics again.
     */
    boolean didChange(String uri, String text) {
        if (changeSyncDisabled()) {
            return true; // server negotiated TextDocumentSyncKind.None — it doesn't track content changes
        }
        boolean[] identical = {false};
        // Collapse: a queued didChange for this uri is superseded by this one (only the latest content
        // matters) — otherwise every typing pause before initialize pins another copy of the document.
        // The full-vs-incremental decision happens INSIDE the queued action (#678): capabilities are only
        // known post-initialize, and the shadow must be read in send order.
        whenReady("didChange:" + uri, () -> {
            if (changeSyncDisabled()) return;
            List<TextDocumentContentChangeEvent> events = changeEventsFor(uri, text);
            if (events.isEmpty()) {
                identical[0] = true;
                return; // content identical to what the server already holds — nothing to sync
            }
            int version = versions.merge(uri, 1, Integer::sum);
            server.getTextDocumentService()
                    .didChange(
                            new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, version), events));
        });
        return !identical[0]; // a send still queued for initialize has compared nothing yet: reported as sent
    }

    /**
     * The change events for syncing {@code text}: under {@code TextDocumentSyncKind.Incremental} (and with
     * a shadow held), the minimal splice vs. the server's last-known content — a fraction of the transport
     * and lets the server process incrementally (#678); otherwise (Full/unspecified/no shadow) the whole
     * text, exactly the previous behavior. Every {@link #RESYNC_EVERY}-th incremental send goes out full as
     * a divergence safety net. The shadow updates unconditionally — it must always equal what the server
     * holds after this send.
     */
    private List<TextDocumentContentChangeEvent> changeEventsFor(String uri, String text) {
        String old = shadows.put(uri, text);
        if (text.equals(old)) return List.of();
        if (old == null || changeSyncKind(capabilities) != TextDocumentSyncKind.Incremental) {
            return List.of(new TextDocumentContentChangeEvent(text));
        }
        int count = sendsSinceResync.merge(uri, 1, Integer::sum);
        if (count >= RESYNC_EVERY) {
            sendsSinceResync.put(uri, 0);
            return List.of(new TextDocumentContentChangeEvent(text)); // periodic full re-convergence
        }
        TextSyncDiff.Delta d = TextSyncDiff.diff(old, text);
        if (d == null) {
            return List.of(); // identical (e.g. a redundant pre-request sync) — skip the send entirely
        }
        var range = TextSyncDiff.rangeOf(old, d.start(), d.end());
        return List.of(new TextDocumentContentChangeEvent(range, d.replacement()));
    }

    /** Pure: the server's declared change-sync kind, or null when unspecified (either capability form). */
    static TextDocumentSyncKind changeSyncKind(ServerCapabilities caps) {
        if (caps == null || caps.getTextDocumentSync() == null) {
            return null;
        }
        var sync = caps.getTextDocumentSync();
        if (sync.isLeft()) {
            return sync.getLeft();
        }
        return sync.getRight() == null ? null : sync.getRight().getChange();
    }

    /**
     * Whether the server explicitly declared {@link TextDocumentSyncKind#None} for change sync — in which
     * case we skip the (debounced, per-edit) {@code didChange} entirely instead of pushing the full text
     * it will ignore. Conservative: an <em>unspecified</em> sync capability keeps the current full-text
     * behavior (we still send), since omitting it is rare and a missed update is worse than a wasted one.
     */
    private boolean changeSyncDisabled() {
        // Unspecified (null kind) keeps the current full-text behavior — a missed update is worse than a
        // wasted one; only an explicit None turns change sync off.
        return capabilities != null && changeSyncKind(capabilities) == TextDocumentSyncKind.None;
    }

    void didSave(String uri, String savedText) {
        whenReady(() -> {
            String text = saveIncludesText() ? savedText : null;
            server.getTextDocumentService()
                    .didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri), text));
        });
    }

    /** Backward-compatible protocol-test helper for servers that do not request save text. */
    void didSave(String uri) {
        didSave(uri, null);
    }

    private boolean saveIncludesText() {
        if (capabilities == null || capabilities.getTextDocumentSync() == null) {
            return false;
        }
        var sync = capabilities.getTextDocumentSync();
        if (!sync.isRight() || sync.getRight() == null || sync.getRight().getSave() == null) {
            return false;
        }
        var save = sync.getRight().getSave();
        return save.isRight()
                && save.getRight() != null
                && Boolean.TRUE.equals(save.getRight().getIncludeText());
    }

    void didClose(String uri) {
        versions.remove(uri);
        whenReady(() -> {
            versions.remove(uri);
            shadows.remove(uri); // remove in wire order; queued changes before this still need their base
            sendsSinceResync.remove(uri);
            server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
        });
    }

    boolean isOpen(String uri) {
        return versions.containsKey(uri);
    }

    /** Current client document version, or null when the URI is not open in this session. */
    Integer documentVersion(String uri) {
        return versions.get(uri);
    }

    /** Snapshot of the text sent for each open document, used to reject stale unversioned workspace edits. */
    Map<String, String> documentSnapshots() {
        return Map.copyOf(shadows);
    }

    // --- Requests (return raw LSP futures; LspManager marshals to the FX thread) ----------------

    /**
     * Runs a server-side command ({@code workspace/executeCommand}) — used to drive jdtls's debug
     * commands ({@code vscode.java.resolveMainClass}/{@code resolveClasspath}/{@code startDebugSession}/…).
     * Queued until {@code initialize} completes; completes exceptionally if the session is disposed first.
     */
    CompletableFuture<Object> executeCommand(String command, List<Object> args) {
        CompletableFuture<Object> out = new CompletableFuture<>();
        whenReady(
                null,
                () -> {
                    if (disposed || server == null) {
                        out.completeExceptionally(new IllegalStateException("language server not available"));
                        return;
                    }
                    try {
                        bounded(server.getWorkspaceService()
                                        .executeCommand(
                                                new ExecuteCommandParams(command, args == null ? List.of() : args)))
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
                },
                () -> out.completeExceptionally(new IllegalStateException("language server not available")));
        return out;
    }

    /**
     * Sends a <b>non-standard</b> request the typed {@link LanguageServer} proxy can't express — e.g. jdtls's
     * {@code java/classFileContents} (#665) — via the launcher's raw JSON-RPC endpoint. The result is the
     * gson-decoded payload (lsp4j has no registered response type for an unknown method, so expect a
     * {@code JsonElement}/{@code JsonPrimitive}). Queued until {@code initialize} completes.
     */
    /**
     * TEST SEAM (inert in production, like {@link #attachForTest}): intercepts the custom {@code java/…}
     * traffic, which otherwise goes out through the launcher's raw endpoint and so is invisible to a
     * {@link org.eclipse.lsp4j.services.LanguageServer} test double.
     *
     * <p>It exists because request-vs-notification is a real hazard here: jdtls declares some extensions
     * {@code void}, and sending one of those as a request hangs until the timeout rather than failing.
     */
    interface RawSink {
        CompletableFuture<Object> request(String method, Object params);

        void notification(String method, Object params);
    }

    private volatile RawSink rawSinkForTest;

    void setRawSinkForTest(RawSink sink) {
        this.rawSinkForTest = sink;
    }

    CompletableFuture<Object> rawRequest(String method, Object params) {
        return rawRequest(method, params, REQUEST_TIMEOUT);
    }

    /** {@link #rawRequest(String, Object)} with its own budget, for a request that legitimately runs long. */
    CompletableFuture<Object> rawRequest(String method, Object params, java.time.Duration timeout) {
        RawSink sink = rawSinkForTest;
        if (sink != null) {
            return sink.request(method, params);
        }
        CompletableFuture<Object> out = new CompletableFuture<>();
        whenReady(
                null,
                () -> {
                    Launcher<LanguageServer> l = launcher;
                    if (disposed || l == null) {
                        out.completeExceptionally(new IllegalStateException("language server not available"));
                        return;
                    }
                    try {
                        track(bounded(l.getRemoteEndpoint().request(method, params), timeout))
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
                },
                () -> out.completeExceptionally(new IllegalStateException("language server not available")));
        return out;
    }

    /**
     * Sends a custom <b>notification</b> (no reply expected) — the counterpart to {@link #rawRequest} for
     * the jdtls extensions declared {@code void}, such as {@code java/projectConfigurationUpdate} (#746).
     *
     * <p>Sending one of those as a request instead would hang until the timeout: the server never answers a
     * method it declares as a notification.
     */
    void rawNotify(String method, Object params) {
        RawSink sink = rawSinkForTest;
        if (sink != null) {
            sink.notification(method, params);
            return;
        }
        whenReady(() -> {
            Launcher<LanguageServer> l = launcher;
            if (disposed || l == null) {
                return;
            }
            try {
                l.getRemoteEndpoint().notify(method, params);
            } catch (RuntimeException ignored) {
                LOG.log(Level.FINE, "notification failed: " + method, ignored);
            }
        });
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
        return bounded(server.getTextDocumentService().codeAction(params))
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
        return bounded(server.getTextDocumentService().resolveCodeAction(action))
                .exceptionally(t -> action);
    }

    /** Handler for a server-initiated {@code workspace/applyEdit}: {@code accept(edit, respond)} — the
     *  handler applies (on whatever thread it marshals to) and answers via {@code respond}. Default: refuse. */
    private volatile java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> onApplyEdit =
            (edit, respond) -> respond.accept(false);

    void setOnApplyEdit(java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> handler) {
        this.onApplyEdit = handler == null ? (edit, respond) -> respond.accept(false) : handler;
    }

    /** {@code window/workDoneProgress/create} — the server allocates a progress token. Accepted (we key
     *  nothing off tokens; begin/report/end arrive via {@code $/progress}). lsp4j's default throws. */
    @Override
    public CompletableFuture<Void> createProgress(org.eclipse.lsp4j.WorkDoneProgressCreateParams params) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * {@code $/progress} (#683): a server's long-running work — jdtls project import/indexing, gopls
     * loading, rust-analyzer's initial check. Begin starts the status-bar loading bar (with the title in
     * the echo area); End stops it. Per-step Report notifications are deliberately NOT echoed — jdtls
     * emits hundreds and the echo area is one line — the bar alone signals "still working".
     */
    @Override
    public void notifyProgress(org.eclipse.lsp4j.ProgressParams params) {
        if (params == null || params.getValue() == null || !params.getValue().isLeft()) {
            return;
        }
        var n = params.getValue().getLeft();
        if (n instanceof org.eclipse.lsp4j.WorkDoneProgressBegin begin) {
            onStatus.accept("Progress", progressText(begin.getTitle(), begin.getMessage(), begin.getPercentage()));
        } else if (n instanceof org.eclipse.lsp4j.WorkDoneProgressEnd end) {
            onStatus.accept("ProgressEnd", end.getMessage()); // null message ⇒ just stop the bar
        }
    }

    /** Pure: the echo-area text for a progress Begin — title, plus the message and/or percentage. */
    static String progressText(String title, String message, Integer percentage) {
        StringBuilder sb = new StringBuilder(title == null || title.isBlank() ? "…" : title.strip());
        if (message != null && !message.isBlank()) {
            sb.append(" — ").append(message.strip());
        }
        if (percentage != null) {
            sb.append(" (").append(percentage).append("%)");
        }
        return sb.toString();
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
        if (disposed) {
            out.complete(new org.eclipse.lsp4j.ApplyWorkspaceEditResponse(false));
            return out; // a server that is being shut down must not edit the workspace on its way out
        }
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

    /**
     * Signature help ({@code textDocument/signatureHelp}) at a position → the overloads + active parameter,
     * or null when unavailable/failed (#674).
     *
     * <p>{@code triggerChar} non-null ⇒ {@code triggerKind=TriggerCharacter} carrying that character; null ⇒
     * {@code Invoked} (the explicit command) or {@code ContentChange} when this is a refresh of an already-open
     * popup. {@code retrigger} tells the server the popup is already up, which is what lets it keep the active
     * overload stable while arguments are typed. Both were previously hardcoded to "Invoked, not a retrigger"
     * even on the auto-trigger path, so the {@code TriggerCharacter} branch was unreachable (#725).
     */
    CompletableFuture<org.eclipse.lsp4j.SignatureHelp> signatureHelp(
            String uri, Position pos, String triggerChar, boolean retrigger) {
        return signatureHelp(uri, pos, triggerChar, retrigger, null);
    }

    CompletableFuture<org.eclipse.lsp4j.SignatureHelp> signatureHelp(
            String uri,
            Position pos,
            String triggerChar,
            boolean retrigger,
            org.eclipse.lsp4j.SignatureHelp activeHelp) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.SignatureHelpParams(new TextDocumentIdentifier(uri), pos);
        var context = new org.eclipse.lsp4j.SignatureHelpContext();
        if (triggerChar == null) {
            context.setTriggerKind(
                    retrigger
                            ? org.eclipse.lsp4j.SignatureHelpTriggerKind.ContentChange
                            : org.eclipse.lsp4j.SignatureHelpTriggerKind.Invoked);
        } else {
            context.setTriggerKind(org.eclipse.lsp4j.SignatureHelpTriggerKind.TriggerCharacter);
            context.setTriggerCharacter(triggerChar);
        }
        context.setIsRetrigger(retrigger);
        context.setActiveSignatureHelp(activeHelp);
        params.setContext(context);
        var request = bounded(server.getTextDocumentService().signatureHelp(params));
        return cancelling(request, request.exceptionally(t -> null));
    }

    /** Occurrences of the symbol at a position ({@code textDocument/documentHighlight}) → highlights with
     *  their Read/Write kind, or empty (#675). */
    CompletableFuture<List<? extends org.eclipse.lsp4j.DocumentHighlight>> documentHighlight(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.DocumentHighlightParams(new TextDocumentIdentifier(uri), pos);
        var request = bounded(server.getTextDocumentService().documentHighlight(params));
        return cancelling(request, request.exceptionally(t -> List.of()));
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
        return bounded(server.getTextDocumentService().prepareRename(params)).exceptionally(t -> null);
    }

    /** Renames the symbol at a position ({@code textDocument/rename}) → the workspace edit, or null (#676). */
    CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(String uri, Position pos, String newName) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.RenameParams(new TextDocumentIdentifier(uri), pos, newName);
        return bounded(server.getTextDocumentService().rename(params)).exceptionally(t -> null);
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

    /** Inlay hints ({@code textDocument/inlayHint}) over {@code range} — parameter names / inferred
     *  types — or empty (#681). */
    CompletableFuture<List<org.eclipse.lsp4j.InlayHint>> inlayHint(String uri, org.eclipse.lsp4j.Range range) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.InlayHintParams(new TextDocumentIdentifier(uri), range);
        var request = bounded(server.getTextDocumentService().inlayHint(params));
        return cancelling(
                request,
                request.<List<org.eclipse.lsp4j.InlayHint>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                        .exceptionally(t -> List.of()));
    }

    /** The most lenses resolved for one window; each resolve is a search on the server. */
    static final int MAX_RESOLVED_CODE_LENSES = 80;

    /**
     * The code lenses on lines {@code [startLine..endLine]} ({@code textDocument/codeLens}), each resolved
     * to its command ({@code codeLens/resolve}) when the server left that for later — or empty. A lens
     * outside the window is not resolved: jdtls counts references per lens, so resolving a whole file
     * costs a search per declaration for text nobody is looking at.
     */
    CompletableFuture<List<org.eclipse.lsp4j.CodeLens>> codeLens(String uri, int startLine, int endLine) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var request = bounded(server.getTextDocumentService()
                .codeLens(new org.eclipse.lsp4j.CodeLensParams(new TextDocumentIdentifier(uri))));
        var options = capabilities() == null ? null : capabilities().getCodeLensProvider();
        boolean resolves = options != null && Boolean.TRUE.equals(options.getResolveProvider());
        List<CompletableFuture<org.eclipse.lsp4j.CodeLens>> resolving =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<List<org.eclipse.lsp4j.CodeLens>> result = request.thenCompose(lenses -> {
            List<CompletableFuture<org.eclipse.lsp4j.CodeLens>> each = new ArrayList<>();
            for (org.eclipse.lsp4j.CodeLens lens : lenses == null ? List.<org.eclipse.lsp4j.CodeLens>of() : lenses) {
                if (lens == null || lens.getRange() == null || each.size() >= MAX_RESOLVED_CODE_LENSES) {
                    continue;
                }
                int line = lens.getRange().getStart().getLine();
                if (line < startLine || line > endLine) {
                    continue;
                }
                if (lens.getCommand() != null || !resolves) {
                    each.add(CompletableFuture.completedFuture(lens));
                } else {
                    var resolve = bounded(server.getTextDocumentService().resolveCodeLens(lens));
                    resolving.add(resolve);
                    each.add(resolve.exceptionally(t -> null));
                }
            }
            return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new))
                    .thenApply(done -> each.stream()
                            .map(f -> f.getNow(null))
                            .filter(l -> l != null && l.getCommand() != null)
                            .toList());
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                resolving.forEach(f -> f.cancel(true));
            }
        });
        return cancelling(request, result.exceptionally(t -> List.of()));
    }

    /** Call-hierarchy anchor at a position ({@code textDocument/prepareCallHierarchy}) → items or empty. */
    CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyItem>> prepareCallHierarchy(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.CallHierarchyPrepareParams(new TextDocumentIdentifier(uri), pos);
        return bounded(server.getTextDocumentService().prepareCallHierarchy(params))
                .<List<org.eclipse.lsp4j.CallHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Incoming calls (callers) of a call-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyIncomingCall>> incomingCalls(
            org.eclipse.lsp4j.CallHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server.getTextDocumentService()
                        .callHierarchyIncomingCalls(new org.eclipse.lsp4j.CallHierarchyIncomingCallsParams(item)))
                .<List<org.eclipse.lsp4j.CallHierarchyIncomingCall>>thenApply(
                        l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Outgoing calls (callees) of a call-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyOutgoingCall>> outgoingCalls(
            org.eclipse.lsp4j.CallHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server.getTextDocumentService()
                        .callHierarchyOutgoingCalls(new org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams(item)))
                .<List<org.eclipse.lsp4j.CallHierarchyOutgoingCall>>thenApply(
                        l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Type-hierarchy anchor at a position ({@code textDocument/prepareTypeHierarchy}) → items or empty. */
    CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> prepareTypeHierarchy(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.TypeHierarchyPrepareParams(new TextDocumentIdentifier(uri), pos);
        return bounded(server.getTextDocumentService().prepareTypeHierarchy(params))
                .<List<org.eclipse.lsp4j.TypeHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Supertypes of a type-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> supertypes(org.eclipse.lsp4j.TypeHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server.getTextDocumentService()
                        .typeHierarchySupertypes(new org.eclipse.lsp4j.TypeHierarchySupertypesParams(item)))
                .<List<org.eclipse.lsp4j.TypeHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Subtypes of a type-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> subtypes(org.eclipse.lsp4j.TypeHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server.getTextDocumentService()
                        .typeHierarchySubtypes(new org.eclipse.lsp4j.TypeHierarchySubtypesParams(item)))
                .<List<org.eclipse.lsp4j.TypeHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(String uri, Position pos) {
        return completion(
                uri, pos, new org.eclipse.lsp4j.CompletionContext(org.eclipse.lsp4j.CompletionTriggerKind.Invoked));
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            String uri, Position pos, org.eclipse.lsp4j.CompletionContext context) {
        if (!ready()) return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        var params = new CompletionParams(new TextDocumentIdentifier(uri), pos);
        params.setContext(context);
        return bounded(server.getTextDocumentService().completion(params));
    }

    /** Whole-document formatting ({@code textDocument/formatting}) → the edits to apply, or empty. */
    CompletableFuture<List<? extends TextEdit>> formatting(String uri, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        DocumentFormattingParams params = new DocumentFormattingParams(new TextDocumentIdentifier(uri), options);
        return bounded(server.getTextDocumentService().formatting(params)).exceptionally(t -> List.of());
    }

    /** Range formatting ({@code textDocument/rangeFormatting}) over {@code range} → the edits, or empty. */
    CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            String uri, org.eclipse.lsp4j.Range range, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.eclipse.lsp4j.DocumentRangeFormattingParams params =
                new org.eclipse.lsp4j.DocumentRangeFormattingParams(new TextDocumentIdentifier(uri), options, range);
        return bounded(server.getTextDocumentService().rangeFormatting(params)).exceptionally(t -> List.of());
    }

    /** Resolves a completion item ({@code completionItem/resolve}) to fill in its {@code additionalTextEdits}
     *  (e.g. a TypeScript auto-import); returns the item unchanged if the server can't resolve. */
    CompletableFuture<CompletionItem> resolveCompletion(CompletionItem item) {
        if (!ready()
                || item == null
                || capabilities == null
                || capabilities.getCompletionProvider() == null
                || !Boolean.TRUE.equals(capabilities.getCompletionProvider().getResolveProvider())) {
            return CompletableFuture.completedFuture(item);
        }
        return bounded(server.getTextDocumentService().resolveCompletionItem(item))
                .exceptionally(t -> item);
    }

    CompletableFuture<Hover> hover(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return bounded(server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri), pos)));
    }

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return bounded(
                server.getTextDocumentService().definition(new DefinitionParams(new TextDocumentIdentifier(uri), pos)));
    }

    /**
     * On-type formatting ({@code textDocument/onTypeFormatting}, #740) — the edits the server would apply
     * having just seen {@code ch} typed at {@code pos}. Empty when not ready or on error.
     */
    CompletableFuture<List<org.eclipse.lsp4j.TextEdit>> onTypeFormatting(
            String uri, Position pos, String ch, org.eclipse.lsp4j.FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params =
                new org.eclipse.lsp4j.DocumentOnTypeFormattingParams(new TextDocumentIdentifier(uri), options, pos, ch);
        return bounded(server.getTextDocumentService().onTypeFormatting(params))
                .<List<org.eclipse.lsp4j.TextEdit>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Implementations of the symbol at a position ({@code textDocument/implementation}) — the concrete
     *  overrides of an interface/abstract member, or the implementors of a type (#735). */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return bounded(server.getTextDocumentService()
                .implementation(new org.eclipse.lsp4j.ImplementationParams(new TextDocumentIdentifier(uri), pos)));
    }

    /** The declaration of the <em>type</em> of the symbol at a position ({@code textDocument/typeDefinition})
     *  — from a variable to its type, as opposed to definition's "to the variable" (#736). */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return bounded(server.getTextDocumentService()
                .typeDefinition(new org.eclipse.lsp4j.TypeDefinitionParams(new TextDocumentIdentifier(uri), pos)));
    }

    /** The declaration of the symbol at a position ({@code textDocument/declaration}); most servers alias
     *  this to definition, but they differ for Java module directives and some forward declarations (#736). */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return bounded(server.getTextDocumentService()
                .declaration(new org.eclipse.lsp4j.DeclarationParams(new TextDocumentIdentifier(uri), pos)));
    }

    /** Foldable regions for the whole document ({@code textDocument/foldingRange}, #738); empty on error. */
    CompletableFuture<List<org.eclipse.lsp4j.FoldingRange>> foldingRange(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var request = bounded(server.getTextDocumentService()
                .foldingRange(new org.eclipse.lsp4j.FoldingRangeRequestParams(new TextDocumentIdentifier(uri))));
        return cancelling(
                request,
                request.<List<org.eclipse.lsp4j.FoldingRange>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                        .exceptionally(t -> List.of()));
    }

    /** The nested selection-range chain at {@code positions} ({@code textDocument/selectionRange}, #739). */
    CompletableFuture<List<org.eclipse.lsp4j.SelectionRange>> selectionRange(String uri, List<Position> positions) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server.getTextDocumentService()
                        .selectionRange(
                                new org.eclipse.lsp4j.SelectionRangeParams(new TextDocumentIdentifier(uri), positions)))
                .<List<org.eclipse.lsp4j.SelectionRange>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    CompletableFuture<List<? extends Location>> references(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(uri), pos, new ReferenceContext(true));
        return bounded(server.getTextDocumentService().references(params));
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
        return bounded(server.getWorkspaceService().symbol(new org.eclipse.lsp4j.WorkspaceSymbolParams(query)))
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
        var request = bounded(server.getTextDocumentService()
                .documentSymbol(new org.eclipse.lsp4j.DocumentSymbolParams(new TextDocumentIdentifier(uri))));
        return cancelling(request, request.exceptionally(t -> java.util.List.of()));
    }

    /** Pull diagnostics ({@code textDocument/diagnostic}) for {@code uri}; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport> diagnostic(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return bounded(server.getTextDocumentService()
                .diagnostic(new org.eclipse.lsp4j.DocumentDiagnosticParams(new TextDocumentIdentifier(uri))));
    }

    /** Semantic tokens over {@code range} ({@code textDocument/semanticTokens/range}); null when not ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(String uri, org.eclipse.lsp4j.Range range) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var request = bounded(server.getTextDocumentService()
                .semanticTokensRange(
                        new org.eclipse.lsp4j.SemanticTokensRangeParams(new TextDocumentIdentifier(uri), range)));
        return cancelling(request, request.exceptionally(t -> null));
    }

    /** Whole-document semantic-token <b>delta</b> ({@code semanticTokens/full/delta}) against a previous
     *  {@code resultId} — the changed splices instead of the whole array (#679); null when not ready. */
    CompletableFuture<
                    org.eclipse.lsp4j.jsonrpc.messages.Either<
                            org.eclipse.lsp4j.SemanticTokens, org.eclipse.lsp4j.SemanticTokensDelta>>
            semanticTokensFullDelta(String uri, String previousResultId) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.SemanticTokensDeltaParams(new TextDocumentIdentifier(uri), previousResultId);
        var request = bounded(server.getTextDocumentService().semanticTokensFullDelta(params));
        return cancelling(request, request.exceptionally(t -> null));
    }

    /** Whole-document semantic tokens ({@code textDocument/semanticTokens/full}), for servers that don't
     *  advertise range requests; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var request = bounded(server.getTextDocumentService()
                .semanticTokensFull(new org.eclipse.lsp4j.SemanticTokensParams(new TextDocumentIdentifier(uri))));
        return cancelling(request, request.exceptionally(t -> null));
    }

    private boolean ready() {
        return initialized && server != null && !disposed;
    }

    /**
     * {@code result}, with its cancellation passed on to {@code request}. A dependent stage does not cancel
     * the future it was derived from, and it is the JSON-RPC future's {@code cancel} that sends
     * {@code $/cancelRequest} — so without this a caller that abandons a request (it has a newer one) leaves
     * the server computing an answer nobody will read.
     */
    private static <T> CompletableFuture<T> cancelling(CompletableFuture<?> request, CompletableFuture<T> result) {
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                request.cancel(true);
            }
        });
        return result;
    }

    /**
     * Ends the session. Returns at once on every path — it is called on the FX thread during a window close.
     *
     * <p>A live, initialized server is shut down the way the protocol asks: {@code shutdown}, a short bounded
     * wait for its reply, {@code exit}, a short bounded wait for the process to leave by itself, and only
     * then the tree kill. That sequence runs on its own daemon thread. Killing in the same call as
     * {@code shutdown} (what this used to do) meant {@code exit} was never sent and the server never got to
     * close its state — jdtls's Eclipse workspace was torn down uncleanly on every quit.
     *
     * <p>Nothing here writes to the pipe on the calling thread or closes a stream a blocked writer holds, so
     * a server that has stopped reading its input cannot hang the caller: the bounded waits expire and the
     * kill releases the writer thread.
     */
    synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        failPending(new IllegalStateException("language server disposed"));
        failInFlight();
        LanguageServer live = server;
        Process p = process;
        boolean handshook = live != null && initialized;
        if (handshook && p != null && p.isAlive()) {
            ProcessRegistry.expectExit(p); // a JVM shutdown racing this waits briefly instead of force-killing
            Thread t = new Thread(() -> exitGracefully(live, p), "lsp-shutdown-" + command.get(0));
            t.setDaemon(true);
            t.start();
            return;
        }
        if (handshook && p == null) {
            // An in-process server (tests): no pipe to block on and no process to wait for.
            try {
                live.shutdown().whenComplete((r, t) -> {
                    try {
                        live.exit();
                    } catch (Exception ignored) {
                        // best effort
                    }
                });
            } catch (Exception ignored) {
                // best effort
            }
        }
        tearDown(p);
    }

    /**
     * Completes when this session's server process has gone — at once when it never had one. A deliberate
     * {@link #dispose()} lets the server leave by itself for a moment, and until it has, it still holds
     * whatever it locked (jdtls: its Eclipse workspace).
     */
    CompletableFuture<Void> exited() {
        Process p = process;
        return p == null ? CompletableFuture.completedFuture(null) : p.onExit().thenApply(done -> null);
    }

    /** Off the FX thread: {@code shutdown} → bounded wait → {@code exit} → bounded wait → kill. */
    private void exitGracefully(LanguageServer live, Process p) {
        try {
            live.shutdown().get(SHUTDOWN_REPLY_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            live.exit();
            AsyncPipeWriter out = writer;
            if (out != null) {
                out.awaitDrained(EXIT_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            p.waitFor(EXIT_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.FINE, "language server did not shut down cleanly", e);
        } finally {
            tearDown(p);
        }
    }

    private void tearDown(Process p) {
        if (p != null) {
            // The launcher is often a wrapper (Homebrew jdtls → python → java); destroying only the
            // wrapper orphans the real server JVM, which keeps running and holds its workspace `.lock`
            // so the next session for the same root can't start. ProcessRegistry.killTree kills the whole
            // descendant tree (children first), escalates to a force-kill if SIGTERM is ignored, and
            // untracks it so the shutdown hook / next-run reaper won't chase a dead pid.
            ProcessRegistry.killTree(p);
        }
        AsyncPipeWriter out = writer;
        if (out != null) {
            out.close();
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
     * overriding it to complete normally silences that noise. The refresh hook asks the manager/coordinator
     * to re-pull diagnostics for every managed open document immediately.
     */
    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        onRefresh.accept("diagnostics");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        onRefresh.accept("semanticTokens");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        onRefresh.accept("inlayHints");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshFoldingRanges() {
        onRefresh.accept("foldingRanges");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Applies {@code client/registerCapability} / {@code client/unregisterCapability}. Servers
     * that use dynamic capability registration (e.g. tinymist, whose init reports
     * {@code cfg_change_registration: true}) send these requests after initialize. lsp4j's default
     * {@link LanguageClient#registerCapability}/{@link LanguageClient#unregisterCapability} throw
     * {@code UnsupportedOperationException}, which lsp4j logs as a SEVERE "Internal error" and the
     * server then reports back as a failed registration. Registrations are folded into the effective
     * capability object so existing manager gates and trigger-character lookups see them immediately.
     */
    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        boolean changed = false;
        if (params != null && params.getRegistrations() != null) {
            for (var registration : params.getRegistrations()) {
                if (registration != null && registration.getId() != null && registration.getMethod() != null) {
                    dynamicRegistrations.put(registration.getId(), registration);
                    changed |= applyDynamicCapabilitySafely(
                            registration.getMethod(), registration.getRegisterOptions(), true);
                }
            }
        }
        if (changed && !disposed) {
            onRefresh.accept("capabilities");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        boolean changed = false;
        if (params != null && params.getUnregisterations() != null) {
            for (var removal : params.getUnregisterations()) {
                if (removal == null) {
                    continue;
                }
                var removed = dynamicRegistrations.remove(removal.getId());
                String method = removed != null ? removed.getMethod() : removal.getMethod();
                if (method == null) {
                    continue; // names neither a registration we hold nor a method: nothing to undo
                }
                var replacement = dynamicRegistrations.values().stream()
                        .filter(r -> java.util.Objects.equals(method, r.getMethod()))
                        .findFirst();
                if (replacement.isPresent()) {
                    changed |= applyDynamicCapabilitySafely(
                            method, replacement.get().getRegisterOptions(), true);
                } else if (!staticCapabilityMethods.contains(method)) {
                    changed |= applyDynamicCapabilitySafely(method, null, false);
                }
            }
        }
        if (changed) {
            onRefresh.accept("capabilities");
        }
        return CompletableFuture.completedFuture(null);
    }

    private void rememberStaticCapabilities() {
        if (capabilities == null) {
            return;
        }
        for (String method : DYNAMIC_METHODS) {
            if (capabilityEnabled(method)) {
                staticCapabilityMethods.add(method);
            }
        }
    }

    private static final List<String> DYNAMIC_METHODS = List.of(
            "textDocument/completion",
            "textDocument/signatureHelp",
            "textDocument/hover",
            "textDocument/definition",
            "textDocument/implementation",
            "textDocument/typeDefinition",
            "textDocument/declaration",
            "textDocument/references",
            "textDocument/documentHighlight",
            "textDocument/documentSymbol",
            "textDocument/codeAction",
            "textDocument/formatting",
            "textDocument/rangeFormatting",
            "textDocument/onTypeFormatting",
            "textDocument/rename",
            "textDocument/foldingRange",
            "textDocument/selectionRange",
            "textDocument/prepareCallHierarchy",
            "textDocument/prepareTypeHierarchy",
            "textDocument/inlayHint",
            "textDocument/semanticTokens",
            "textDocument/diagnostic",
            "workspace/symbol",
            "workspace/executeCommand");

    private boolean capabilityEnabled(String method) {
        ServerCapabilities c = capabilities;
        if (c == null) {
            return false;
        }
        return switch (method) {
            case "textDocument/completion" -> c.getCompletionProvider() != null;
            case "textDocument/signatureHelp" -> c.getSignatureHelpProvider() != null;
            case "textDocument/hover" -> enabled(c.getHoverProvider());
            case "textDocument/definition" -> enabled(c.getDefinitionProvider());
            case "textDocument/implementation" -> enabled(c.getImplementationProvider());
            case "textDocument/typeDefinition" -> enabled(c.getTypeDefinitionProvider());
            case "textDocument/declaration" -> enabled(c.getDeclarationProvider());
            case "textDocument/references" -> enabled(c.getReferencesProvider());
            case "textDocument/documentHighlight" -> enabled(c.getDocumentHighlightProvider());
            case "textDocument/documentSymbol" -> enabled(c.getDocumentSymbolProvider());
            case "textDocument/codeAction" -> enabled(c.getCodeActionProvider());
            case "textDocument/formatting" -> enabled(c.getDocumentFormattingProvider());
            case "textDocument/rangeFormatting" -> enabled(c.getDocumentRangeFormattingProvider());
            case "textDocument/onTypeFormatting" -> c.getDocumentOnTypeFormattingProvider() != null;
            case "textDocument/rename" -> enabled(c.getRenameProvider());
            case "textDocument/foldingRange" -> enabled(c.getFoldingRangeProvider());
            case "textDocument/selectionRange" -> enabled(c.getSelectionRangeProvider());
            case "textDocument/prepareCallHierarchy" -> enabled(c.getCallHierarchyProvider());
            case "textDocument/prepareTypeHierarchy" -> enabled(c.getTypeHierarchyProvider());
            case "textDocument/inlayHint" -> enabled(c.getInlayHintProvider());
            case "textDocument/semanticTokens" -> c.getSemanticTokensProvider() != null;
            case "textDocument/diagnostic" -> c.getDiagnosticProvider() != null;
            case "workspace/symbol" -> enabled(c.getWorkspaceSymbolProvider());
            case "workspace/executeCommand" -> c.getExecuteCommandProvider() != null;
            default -> false;
        };
    }

    private static boolean enabled(org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, ?> capability) {
        return capability != null && (capability.isRight() || Boolean.TRUE.equals(capability.getLeft()));
    }

    /**
     * Applies one registration and reports whether a feature gate may have changed. A registration whose
     * options cannot be decoded is logged and skipped on its own: a server sends several in one request, and
     * one malformed entry must not abort the rest (nor fail the whole request back to the server).
     */
    private boolean applyDynamicCapabilitySafely(String method, Object options, boolean enabled) {
        if (method == null || !DYNAMIC_METHODS.contains(method)) {
            return false;
        }
        try {
            applyDynamicCapability(method, options, enabled);
            return true;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ignoring unusable dynamic registration for " + method, e);
            return false;
        }
    }

    private synchronized void applyDynamicCapability(String method, Object options, boolean enabled) {
        ServerCapabilities c = capabilities;
        if (c == null) {
            return;
        }
        switch (method) {
            case "textDocument/completion" ->
                c.setCompletionProvider(
                        enabled ? dynamicOptions(options, org.eclipse.lsp4j.CompletionOptions.class) : null);
            case "textDocument/signatureHelp" ->
                c.setSignatureHelpProvider(
                        enabled ? dynamicOptions(options, org.eclipse.lsp4j.SignatureHelpOptions.class) : null);
            case "textDocument/hover" -> c.setHoverProvider(enabled);
            case "textDocument/definition" -> c.setDefinitionProvider(enabled);
            case "textDocument/implementation" -> c.setImplementationProvider(enabled);
            case "textDocument/typeDefinition" -> c.setTypeDefinitionProvider(enabled);
            case "textDocument/declaration" -> c.setDeclarationProvider(enabled);
            case "textDocument/references" -> c.setReferencesProvider(enabled);
            case "textDocument/documentHighlight" -> c.setDocumentHighlightProvider(enabled);
            case "textDocument/documentSymbol" -> c.setDocumentSymbolProvider(enabled);
            case "textDocument/codeAction" -> {
                // Keep the options object: a Boolean would drop codeActionKinds/resolveProvider.
                if (enabled && options != null) {
                    c.setCodeActionProvider(dynamicOptions(options, org.eclipse.lsp4j.CodeActionOptions.class));
                } else {
                    c.setCodeActionProvider(enabled);
                }
            }
            case "textDocument/formatting" -> c.setDocumentFormattingProvider(enabled);
            case "textDocument/rangeFormatting" -> c.setDocumentRangeFormattingProvider(enabled);
            case "textDocument/onTypeFormatting" ->
                c.setDocumentOnTypeFormattingProvider(
                        enabled
                                ? dynamicOptions(options, org.eclipse.lsp4j.DocumentOnTypeFormattingOptions.class)
                                : null);
            case "textDocument/rename" -> {
                // Keep the options object: a Boolean would drop prepareProvider, and with it the
                // validate-and-placeholder step before the rename prompt.
                if (enabled && options != null) {
                    c.setRenameProvider(dynamicOptions(options, org.eclipse.lsp4j.RenameOptions.class));
                } else {
                    c.setRenameProvider(enabled);
                }
            }
            case "textDocument/foldingRange" -> c.setFoldingRangeProvider(enabled);
            case "textDocument/selectionRange" -> c.setSelectionRangeProvider(enabled);
            case "textDocument/prepareCallHierarchy" -> c.setCallHierarchyProvider(enabled);
            case "textDocument/prepareTypeHierarchy" -> c.setTypeHierarchyProvider(enabled);
            case "textDocument/inlayHint" -> c.setInlayHintProvider(enabled);
            case "textDocument/semanticTokens" ->
                c.setSemanticTokensProvider(
                        enabled
                                ? dynamicOptions(options, org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions.class)
                                : null);
            case "textDocument/diagnostic" ->
                c.setDiagnosticProvider(
                        enabled
                                ? dynamicOptions(options, org.eclipse.lsp4j.DiagnosticRegistrationOptions.class)
                                : null);
            case "workspace/symbol" -> c.setWorkspaceSymbolProvider(enabled);
            case "workspace/executeCommand" ->
                c.setExecuteCommandProvider(
                        enabled ? dynamicOptions(options, org.eclipse.lsp4j.ExecuteCommandOptions.class) : null);
            default -> {
                return;
            }
        }
        capabilities = c; // volatile write publishes the mutation to FX-thread feature gates
    }

    /**
     * LSP4J's own gson. Registration options arrive as raw JSON and must be decoded the way LSP4J decodes
     * every other message: its type-adapter factories are what make an {@code Either} field readable. A
     * plain {@code new Gson()} decodes those empty or throws — {@code SemanticTokensWithRegistrationOptions}
     * ({@code range}/{@code full}) lost both, which switched semantic tokens off for servers that register
     * them dynamically (tinymist).
     */
    static final com.google.gson.Gson LSP_GSON =
            new org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(java.util.Map.of()).getGson();

    private static <T> T dynamicOptions(Object options, Class<T> type) {
        T converted = options == null ? null : LSP_GSON.fromJson(LSP_GSON.toJsonTree(options), type);
        if (converted != null) {
            return converted;
        }
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot create dynamic capability options for " + type.getName(), e);
        }
    }

    /**
     * Answers {@code workspace/configuration} per server and section from {@link LspServerSettings}: Pyright's
     * {@code python} / {@code python.analysis} objects, and an empty object for the CSS and HTML servers'
     * own sections (they throw on {@code null}). Any other section returns null so the server keeps its
     * own default.
     */
    @Override
    public CompletableFuture<List<Object>> configuration(org.eclipse.lsp4j.ConfigurationParams params) {
        List<Object> out = new java.util.ArrayList<>();
        if (params != null && params.getItems() != null) {
            for (org.eclipse.lsp4j.ConfigurationItem item : params.getItems()) {
                out.add(LspServerSettings.answer(serverId, item.getSection()));
            }
        }
        return CompletableFuture.completedFuture(out);
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

    /** JDT LS's legacy progress channel, enabled by {@code progressReportProvider}. Recent servers still
     *  emit it alongside standard work-done progress for project import and builds. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/progressReport")
    public void languageProgressReport(LanguageProgressReport report) {
        if (report == null) {
            return;
        }
        String id = report.id == null ? String.valueOf(report.task) : report.id;
        if (report.complete) {
            if (jdtProgressIds.remove(id)) {
                onStatus.accept("ProgressEnd", report.status);
            }
            return;
        }
        if (!jdtProgressIds.add(id)) {
            return;
        }
        Integer percentage = report.totalWork > 0
                ? Math.max(0, Math.min(100, (int) Math.round(report.workDone * 100.0 / report.totalWork)))
                : null;
        String detail = report.subTask == null || report.subTask.isBlank() ? report.status : report.subTask;
        if (detail != null && detail.strip().matches("\\d+%")) {
            detail = null; // percentage already appears in the structured suffix
        }
        onStatus.accept("Progress", progressText(report.task, detail, percentage));
    }

    /** A JDT project-model event. Editora already refreshes from diagnostics and watched files; accepting the
     *  event keeps the client contract complete without duplicating those flows. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/eventNotification")
    public void languageEventNotification(LanguageEventNotification event) {}

    /** Surface JDT's actionable message even though Editora does not render the optional command buttons. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/actionableNotification")
    public void languageActionableNotification(LanguageActionableNotification notification) {
        if (notification != null && notification.message != null && !notification.message.isBlank()) {
            onStatus.accept("Message", notification.message.strip());
        }
    }

    /** Payload of JDT LS's {@code language/status} notification (deserialized by LSP4J's Gson). */
    public static final class LanguageStatus {
        public String type;
        public String message;
    }

    /** Payload of JDT LS's {@code language/progressReport} notification. */
    public static final class LanguageProgressReport {
        public String id;
        public String task;
        public String subTask;
        public String status;
        public int totalWork;
        public int workDone;
        public boolean complete;
    }

    /** Payload of JDT LS's {@code language/eventNotification}; data remains intentionally opaque. */
    public static final class LanguageEventNotification {
        public String type;
        public Object data;
    }

    /** Payload of JDT LS's {@code language/actionableNotification}. */
    public static final class LanguageActionableNotification {
        public org.eclipse.lsp4j.MessageType severity;
        public String message;
        public Object data;
        public List<org.eclipse.lsp4j.Command> commands;
    }
}
