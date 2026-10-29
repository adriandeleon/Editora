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

import com.editora.process.BusyExecutable;
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
import org.jspecify.annotations.Nullable;

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
    /**
     * How this server names documents. Every {@code String uri} parameter below is a document <em>key</em>
     * (what {@code LspManager} routes by); it becomes the server's URI only where a request is built.
     */
    private volatile WireUris uris = WireUris.LOCAL;

    /**
     * Documents the server is shown as another file, with their text rewritten on the wire (key → that
     * file's URI, key → the rewrite) — see {@link #alias}. Kept apart from {@link #shadows}, which holds the
     * editor's own text: that is what an edit the server sends back is checked against.
     */
    private final Map<String, String> aliasUris = new ConcurrentHashMap<>();

    private final Map<String, java.util.function.UnaryOperator<String>> aliasTexts = new ConcurrentHashMap<>();
    /** {@link #uris} with the {@link #aliasUris} on top; what every request and reply goes through. */
    private final WireUris named = WireUris.aliasing(() -> uris, aliasUris);

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
    private volatile @Nullable Process process;
    private volatile @Nullable LanguageServer server;

    /** The server proxy; set by {@link #start()} before anything is sent and never cleared. */
    private LanguageServer server() {
        LanguageServer s = server;
        if (s == null) {
            throw new IllegalStateException("language server not started");
        }
        return s;
    }

    /** The LSP4J launcher, kept for {@link #rawRequest} (custom, non-standard requests like jdtls's
     *  {@code java/classFileContents}, which the typed {@link LanguageServer} proxy can't express). */
    private volatile @Nullable Launcher<LanguageServer> launcher;
    /** The ordered writer between LSP4J and the server's stdin; null for an in-process test server. */
    private volatile @Nullable AsyncPipeWriter writer;

    private volatile boolean initialized;
    /** Set only after a complete initialize response. Distinguishes a corrupt-startup failure from a
     *  later crash so the manager can safely discard only an unusable jdtls cache. */
    private volatile boolean initializedOnce;

    private final java.util.concurrent.atomic.AtomicBoolean deadReported =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean disposed;
    /** Set when {@link #start()} threw before the server was up (not when it was disposed meanwhile). */
    private volatile boolean startFailed;

    // Written on the LSP4J init thread (initialize().whenComplete), read on the FX thread (capabilities()).
    // volatile gives the FX reader the happens-before edge so it can't transiently see null after init completed.
    private volatile @Nullable ServerCapabilities capabilities;
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
        return track(bounded(request, REQUEST_TIMEOUT, this));
    }

    /**
     * The questions the server asked the user ({@link #CHOOSE_IMPORTS}) that are still open. While one is, the
     * server is waiting on the user, so no request on this session times out (fifth review DV1).
     */
    private final java.util.Set<CompletableFuture<?>> openQuestions = ConcurrentHashMap.newKeySet();

    /** When the last question was answered: a request's time runs again from then. */
    private volatile long questionAnsweredAt;

    /** Asks the user {@code question}, holding back request timeouts until it is answered. */
    private <T> CompletableFuture<T> asking(CompletableFuture<T> question) {
        openQuestions.add(question);
        question.whenComplete((answer, error) -> {
            questionAnsweredAt = System.nanoTime();
            openQuestions.remove(question);
        });
        if (disposed) {
            closeQuestions();
        }
        return question;
    }

    /** Tests: a question the server asked, open until {@code question} completes. */
    <T> CompletableFuture<T> askForTest(CompletableFuture<T> question) {
        return asking(question);
    }

    /** Whether the server is waiting on the user's answer to a question it asked. */
    boolean questionOpen() {
        return !openQuestions.isEmpty();
    }

    /** Takes down every open question: what it asked for is gone (the request ended, or the server did). */
    private void closeQuestions() {
        for (CompletableFuture<?> question : List.copyOf(openQuestions)) {
            question.cancel(false);
        }
    }

    /** How much longer a request that has run out of {@code timeout} may wait: the user may be answering. */
    private long holdMillis(java.time.Duration timeout) {
        if (!openQuestions.isEmpty()) {
            return timeout.toMillis();
        }
        long since = (System.nanoTime() - questionAnsweredAt) / 1_000_000;
        return questionAnsweredAt != 0 && since < timeout.toMillis() ? timeout.toMillis() - since : 0;
    }

    /**
     * Told how each observed request ended: {@code (method, null)} on an answer, {@code (method, error)} on a
     * failure — before any mapping of the failure to "nothing" that the request's caller does. How the manager
     * notices a server that fails every request inside itself (Maven review C2).
     */
    void setOnRequestOutcome(java.util.function.BiConsumer<String, Throwable> listener) {
        primary.onRequestOutcome = listener == null ? (method, error) -> {} : listener;
    }

    /** {@link #bounded(CompletableFuture)} whose outcome is reported to {@link #setOnRequestOutcome} as {@code method}. */
    private <T> CompletableFuture<T> observed(String method, CompletableFuture<T> request) {
        CompletableFuture<T> bounded = bounded(request);
        bounded.whenComplete((value, error) -> {
            if (!(error instanceof java.util.concurrent.CancellationException)) {
                for (Client c : clients) { // a cancellation is the client's doing, not an outcome
                    c.onRequestOutcome.accept(method, error);
                }
            }
        });
        return bounded;
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
        closeQuestions();
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
        return bounded(request, timeout, null);
    }

    /**
     * {@link #bounded(CompletableFuture, java.time.Duration)} that waits on while {@code owner} has a question
     * open to the user, and for the whole {@code timeout} again once it is answered (fifth review DV1).
     */
    static <T> CompletableFuture<T> bounded(
            CompletableFuture<T> request, java.time.Duration timeout, @Nullable LanguageServerSession owner) {
        if (request.isDone()) {
            return request;
        }
        var timer = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
        Runnable expire = new Runnable() {
            @Override
            public void run() {
                if (request.isDone()) {
                    return;
                }
                long hold = owner == null ? 0 : owner.holdMillis(timeout);
                if (hold > 0) {
                    timer.set(REQUEST_TIMER.schedule(this, hold, java.util.concurrent.TimeUnit.MILLISECONDS));
                    if (request.isDone()) {
                        timer.get().cancel(false);
                    }
                } else {
                    request.cancel(true);
                }
            }
        };
        timer.set(REQUEST_TIMER.schedule(
                expire, Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS));
        request.whenComplete((result, error) -> timer.get().cancel(false));
        return request;
    }

    /**
     * Invoked (once) when this session can no longer serve requests — the process exited on its own, or the
     * handshake failed/timed out. The manager drops the session so the next request starts a fresh one; the
     * default is a no-op so the extra constructors need no change.
     */
    void setOnDead(Runnable onDead) {
        primary.onDead = onDead == null ? () -> {} : onDead;
    }

    void setOnRefresh(Consumer<String> onRefresh) {
        primary.onRefresh = onRefresh == null ? kind -> {} : onRefresh;
    }

    // --- Windows sharing this server (Java review C12) ------------------------------------------

    /**
     * One window's ends of this session: where its diagnostics, status, refreshes and death go, and how it
     * answers what the server asks. A Java server is shared by every window on its project: each window
     * attaches a client of its own, notifications reach them all, a question the server asks goes to the
     * focused one, and the server is stopped only once the last one has left. The window that started the
     * session holds {@link #primaryClient()}, which the setters above configure.
     */
    static final class Client {
        final Consumer<PublishDiagnosticsParams> diagnostics;
        final java.util.function.BiConsumer<String, String> status;
        volatile Runnable onDead = () -> {};
        volatile Consumer<String> onRefresh = kind -> {};
        volatile java.util.function.BiConsumer<String, Throwable> onRequestOutcome = (method, error) -> {};
        volatile java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> onApplyEdit =
                (edit, respond) -> respond.accept(false);
        volatile @Nullable LspClientUi ui;
        volatile java.util.function.Function<String, @Nullable FormattingIndent> indentForUri = uri -> null;
        volatile java.util.function.BooleanSupplier focused = () -> false;

        Client(
                @Nullable Consumer<PublishDiagnosticsParams> diagnostics,
                java.util.function.@Nullable BiConsumer<String, String> status) {
            this.diagnostics = diagnostics == null ? params -> {} : diagnostics;
            this.status = status == null ? (type, message) -> {} : status;
        }
    }

    private final Client primary;
    private final List<Client> clients = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean shared;

    /** The client of the window that started this session. */
    Client primaryClient() {
        return primary;
    }

    /** Adds another window's client; false when this session can no longer serve one (disposed or dead). */
    boolean attach(Client client) {
        synchronized (clients) {
            if (disposed || deadReported.get()) {
                return false;
            }
            clients.add(client);
            shared = true;
            return true;
        }
    }

    /** Removes a window's client; returns how many are left. */
    int detach(Client client) {
        synchronized (clients) {
            clients.remove(client);
            return clients.size();
        }
    }

    int clientCount() {
        return clients.size();
    }

    /**
     * After a deliberate {@link #dispose()} of a server other windows used: tells every window it has gone now,
     * not when the process exits (an in-process server has none), so theirs reopen on a new one.
     */
    void reportEndedForAll() {
        if (disposed) {
            markDead();
        }
    }

    /** Whether a second window ever attached. */
    boolean wasShared() {
        return shared;
    }

    /** Where a question from the server goes: the focused window's client, else the longest-attached one. */
    private Client routed() {
        Client first = null;
        for (Client c : clients) {
            if (first == null) {
                first = c;
            }
            if (c.focused.getAsBoolean()) {
                return c;
            }
        }
        return first == null ? primary : first;
    }

    private void refresh(String kind) {
        for (Client c : clients) {
            c.onRefresh.accept(kind);
        }
    }

    /** The window UI a question goes to: the routed client's, else any client's. */
    private @Nullable LspClientUi clientUi() {
        LspClientUi ui = routed().ui;
        if (ui != null) {
            return ui;
        }
        for (Client c : clients) {
            if (c.ui != null) {
                return c.ui;
            }
        }
        return null;
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
            @Nullable Object initializationOptions) {
        this.serverId = spec.serverId();
        this.command = spec.command();
        this.root = root;
        this.primary = new Client(onDiagnostics, onStatus);
        clients.add(primary);
        this.onDiagnostics = params -> {
            keepProjectDiagnostics(params);
            for (Client c : clients) {
                c.diagnostics.accept(params);
            }
        };
        // A disposed session keeps reading for a moment while its server exits gracefully; nothing the
        // server says in that time (progress, "shutting down") may reach the status bar of a session that
        // is already gone — a progress Begin would start a loading bar no End will ever stop.
        java.util.function.BiConsumer<String, String> sink = (t, m) -> {
            for (Client c : clients) {
                c.status.accept(t, m);
            }
        };
        this.onStatus = (type, message) -> {
            if (!disposed) {
                sink.accept(type, message);
            } else if ("Error".equals(type) || "ProgressEnd".equals(type)) {
                sink.accept(type, null); // still allowed to stop a loading bar, never to start one or speak
            }
        };
        this.initializationOptionsSupplier = () -> initializationOptions;
    }

    private volatile java.util.Map<String, String> remoteEnvironment = java.util.Map.of();

    /** Configures work that is deliberately resolved by the manager's start executor, immediately before
     *  {@link #start()}. This keeps filesystem/JDK discovery out of the FX-thread session-routing path. */
    void configureStart(List<String> command, java.util.function.Supplier<Object> initializationOptionsSupplier) {
        configureStart(command, java.util.Map.of(), initializationOptionsSupplier);
    }

    /** As above, with variables a remote server is started with (a local one gets them from its command). */
    void configureStart(
            List<String> command,
            java.util.Map<String, String> remoteEnvironment,
            java.util.function.Supplier<Object> initializationOptionsSupplier) {
        this.remoteEnvironment = java.util.Map.copyOf(remoteEnvironment);
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
            com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(root);
            targetId = target.id();
            if (!target.isLocal()) {
                // Where the folder's tools run (a remote host, a container, WSL): its own server, its own PATH
                // and locale. It is told about documents by its own paths for them.
                uris = com.editora.vfs.Vfs.isRemote(root)
                        ? WireUris.remote(com.editora.vfs.Vfs.authorityOf(root))
                        : WireUris.mapped(target);
                process = target.start(com.editora.process.ExecSpec.of(
                                command, root, remoteEnvironment, com.editora.process.ExecSpec.Locale.USER)
                        .withLongLived(true));
            } else {
                ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
                pb.directory(root.toFile());
                ProcessRunner.applyUserEnv(pb); // the user's locale: under LC_ALL=C jdtls cannot index non-ASCII paths
                Path prepend = pathPrepend;
                if (prepend != null) {
                    // The Node.js the user chose runs a Node server's "#!/usr/bin/env node" (and its tsserver).
                    String key = pathKey(pb.environment());
                    pb.environment()
                            .put(
                                    key,
                                    prependedPath(
                                            pb.environment().get(key), prepend.toString(), java.io.File.pathSeparator));
                }
                pb.command(JavaServerEnvironment.configure(serverId, pb.command(), pb.environment()));
                process = BusyExecutable.start(pb);
            }
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
            // The exit is reported once LSP4J has read everything the server wrote before it went: a server that
            // refuses initialize and exits ("Could not find a valid TypeScript installation") must be heard as a
            // refusal, not as a crash that a restart would repeat (Node review B3).
            process.onExit().thenRunAsync(() -> {
                awaitReaderDrained();
                awaitStderrDrained(); // what it said on the way out is the reason a crash notice gives
                markDead();
            });
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
                            : new AfterRequestConsumer(
                                    consumer, "workspace/configuration", this::configurationAnswered))
                    .create();
            this.launcher = launcher;
            server = launcher.getRemoteProxy();
            listening = launcher.startListening();
            onStatus.accept("Launching", null); // a new process: the UI forgets what the last one said
            sendInitialize();
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to start language server " + command, e);
            onStatus.accept("Error", "Failed to start language server: " + e.getMessage());
            startFailed = !disposed; // a dispose during the fork is a teardown, not a failure
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
                            if (firstStderrLine == null && !line.isBlank()) {
                                String first = line.strip();
                                firstStderrLine = first.length() > 240 ? first.substring(0, 240) + "…" : first;
                            }
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
        stderrDrain = t;
        t.start();
    }

    /** The thread reading the server's stderr, once started. */
    private volatile @Nullable Thread stderrDrain;

    /** The first non-blank line the server wrote to stderr, or null while it has written none. */
    private volatile @Nullable String firstStderrLine;

    /**
     * The first non-blank line the server wrote to stderr (cut to 240 characters), or null. For a server that
     * exits before it answers {@code initialize} this is the reason: a native build on too old a C library says
     * "version `GLIBC_2.34' not found" there and nowhere else (XML review A4).
     */
    @Nullable
    String firstStderrLine() {
        return firstStderrLine;
    }

    /** Waits briefly for the stderr reader to reach the end of a process that has exited. */
    private void awaitStderrDrained() {
        Thread t = stderrDrain;
        if (t == null) {
            return;
        }
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** LSP4J's reader, done when the server's output has been read to its end (null before the start). */
    private volatile java.util.concurrent.@Nullable Future<Void> listening;

    /** Why the server refused {@code initialize}, in its own words (null: it did not refuse). */
    private volatile @Nullable String initializeRefusal;

    /** The server's own reason for refusing {@code initialize}, or null. Such a session is not a crash. */
    @Nullable
    String initializeRefusal() {
        return initializeRefusal;
    }

    /** Waits (briefly) until everything the exited server wrote has been read. */
    private void awaitReaderDrained() {
        java.util.concurrent.Future<Void> reader = listening;
        if (reader == null) {
            return;
        }
        try {
            reader.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | java.util.concurrent.CancellationException e) {
            // Read as far as it got; the exit is reported either way.
        }
    }

    /**
     * The message of a server's error answer somewhere in {@code t}'s causes — what it said when it refused a
     * request — or null when {@code t} is not such an answer (a timeout, a lost connection). Pure.
     */
    static @Nullable String responseErrorMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.eclipse.lsp4j.jsonrpc.ResponseErrorException ree) {
                String message = ree.getResponseError() == null
                        ? null
                        : ree.getResponseError().getMessage();
                return message == null || message.isBlank() ? ree.getMessage() : message.strip();
            }
        }
        return null;
    }

    private void sendInitialize() {
        InitializeParams ip = new InitializeParams();
        // Our pid means nothing on another machine; a server that watches its parent would exit at once.
        ip.setProcessId(
                com.editora.process.ExecTargets.runsHere(root)
                        ? (int) ProcessHandle.current().pid()
                        : null);
        String uri = uris.toWire(DocumentUris.ofDirectory(root));
        ip.setRootUri(uri);
        ip.setWorkspaceFolders(List.of(new WorkspaceFolder(uri, workspaceFolderName(root))));
        ip.setCapabilities(clientCapabilities(serverId));
        Object initializationOptions = initializationOptionsSupplier.get();
        if (initializationOptions != null) {
            ip.setInitializationOptions(initializationOptions); // jdtls: {"bundles":[<java-debug jar>]}
        }
        server().initialize(ip)
                // A server that never answers leaves this future — and every queued call — hanging forever:
                // the status bar's loading bar spins with no error, the session is cached as if it were live,
                // and the queue grows with each edit. That is exactly the jdtls workspace-lock wedge. Time it
                // out so the handshake either completes or fails, like executeCommand already does.
                .orTimeout(INITIALIZE_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .thenAccept(result -> {
                    capabilities = result.getCapabilities();
                    rememberStaticCapabilities();
                    server().initialized(new InitializedParams());
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
                    initializeRefusal = responseErrorMessage(t);
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

    /**
     * {@link #clientCapabilities()} as {@code serverId} is told them. lemminx registers its commands
     * ({@code xml.validation.current.file}, {@code xml.associate.grammar.insert}, …) only with a client that declares
     * dynamic {@code workspace/executeCommand} registration, and has no static {@code executeCommandProvider}, so
     * without it none could run (XML review B15). The other servers keep their static command lists.
     */
    static ClientCapabilities clientCapabilities(@Nullable String serverId) {
        ClientCapabilities cc = clientCapabilities();
        if (XmlServerSettings.isLemminx(serverId)) { // the pom server is lemminx too (review 2, D15)
            cc.getWorkspace().setExecuteCommand(new org.eclipse.lsp4j.ExecuteCommandCapabilities(true));
        }
        return cc;
    }

    /** Package-private for {@code ClientCapabilitiesTest}: what we declare here decides what servers will
     *  do for us, and three separate features have died from a silent change to it (#468, #674, #676). */
    static ClientCapabilities clientCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        // tagSupport: Pyright (and others) send "x is not accessed" / "f is deprecated" only to a client that
        // can show them — faded or struck through. Without it unused imports never looked unused (review B3).
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(
                true,
                new org.eclipse.lsp4j.DiagnosticsTagSupport(List.of(
                        org.eclipse.lsp4j.DiagnosticTag.Unnecessary, org.eclipse.lsp4j.DiagnosticTag.Deprecated))));
        CompletionItemCapabilities completionItem = new CompletionItemCapabilities(true); // snippetSupport
        // Advertise that we can resolve additionalTextEdits — Pyright (and others) only emit an
        // auto-import's `import` edit when the client says it can resolve it. We resolve on accept
        // (see MainController.autoImportAccept). `documentation` too: the doc popup resolves the selected
        // item, and jdtls computes an item's Javadoc in completionItem/resolve only for a client that lists
        // it here — without it the popup never showed Javadoc for Java (review A1). `detail` stays eager so
        // every row keeps its type column.
        completionItem.setResolveSupport(new org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities(
                java.util.List.of("additionalTextEdits", "documentation")));
        completionItem.setPreselectSupport(true);
        // The doc popup renders Markdown. Without this the HTML and CSS servers (and jdtls, Pyright) send their
        // docs as plain text: a bare "MDN Reference: https://…" line instead of a link (HTML review D7).
        completionItem.setDocumentationFormat(List.of("markdown", "plaintext"));
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
        // Document links (XML review B5): lemminx names the schema a schemaLocation, DOCTYPE or xml-model refers
        // to; Go to Definition falls back to them for XML. Requested only where that fallback asks.
        td.setDocumentLink(new org.eclipse.lsp4j.DocumentLinkCapabilities(false, false));
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
        // A file or folder renamed in the Project tree is offered to the server first: jdtls renames the class
        // with its .java file, or updates the package declarations and imports of a moved folder (review B15).
        var fileOperations = new org.eclipse.lsp4j.FileOperationsWorkspaceCapabilities();
        fileOperations.setWillRename(true);
        ws.setFileOperations(fileOperations);
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
        ws.setCodeLens(new org.eclipse.lsp4j.CodeLensWorkspaceCapabilities(true)); // jdtls asks too (B2)
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

    /** bash-language-server's {@code bashIde} section for this session (null until the manager sets it). */
    private volatile @Nullable BashIdeConfig bashIde;

    /**
     * Sets the {@code bashIde} section this session answers and pushes. A server that runs elsewhere — another
     * host, a container, WSL — is told to look ShellCheck and shfmt up on that side's PATH
     * ({@link BashIdeConfig#forRemote}): a container or WSL folder is a local path, but this machine's tool paths
     * mean nothing there (Bash review A2). A running Bash server gets the change pushed; it re-reads its
     * configuration, and every open document is sent again: 5.6 re-lints only the one it heard of last (B8).
     */
    void setBashIde(@Nullable BashIdeConfig config) {
        BashIdeConfig next =
                config == null || com.editora.process.ExecTargets.runsHere(root) ? config : config.forRemote();
        boolean changed = !java.util.Objects.equals(bashIde, next);
        bashIde = next;
        if (changed && "bash".equals(serverId) && ready()) {
            pushConfiguration();
            // 5.6 re-lints only the document it heard of last; every other tab kept what the old settings said (B8)
            resyncOpenDocuments();
        }
    }

    /** Where the open documents are re-sent from: the thread every other document sync comes from. */
    private volatile java.util.concurrent.Executor clientThread = Runnable::run;

    /** Sets {@link #clientThread} ({@link LspManager}: the FX thread, so a re-sync never races an edit's). */
    void setClientThread(java.util.concurrent.Executor thread) {
        clientThread = thread;
    }

    /** Whether the Bash server's first {@code workspace/configuration} has been answered. */
    private final java.util.concurrent.atomic.AtomicBoolean configuredOnce =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The server's {@code workspace/configuration} request has been answered (on the reader thread, the answer
     * already queued for the writer). bash-language-server lints nothing that arrives before it has that answer
     * and then only the document it heard of last, so every tab opened while it started — a restored session,
     * several files opened at once, a restart — showed no problems until it was edited (Bash review B1). Each
     * open document is sent again, unchanged, which the server lints like any change.
     */
    void configurationAnswered() {
        if (BashShims.isBash(serverId) && configuredOnce.compareAndSet(false, true)) {
            clientThread.execute(this::resyncOpenDocuments);
        }
    }

    /** Re-sends every open document's text, unchanged, under a new version: the server analyses it again. */
    void resyncOpenDocuments() {
        for (var e : openers.entrySet()) {
            if (!e.getValue().isEmpty()) {
                resend(e.getKey());
            }
        }
    }

    /**
     * Opens {@code uri}, a file no window has open, and closes it again at once, sending {@code text}: the Bash
     * server reads a workspace file once and has no watched-files handler, so a library changed on disk kept its
     * old functions and lines until a restart (Bash review B7). Not sent while the server starts: it reads the
     * disk then anyway. The close clears what it publishes for the file; a late, versioned publish for a document
     * that is not open is dropped by the manager.
     */
    void reanalyze(String uri, String text) {
        if (!ready() || isOpen(uri)) {
            return;
        }
        String wire = named.toWire(uri);
        server().getTextDocumentService()
                .didOpen(new DidOpenTextDocumentParams(
                        new TextDocumentItem(wire, LspServerRegistry.protocolLanguageId("shell"), 1, text)));
        server().getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(wire)));
    }

    /** The explainshell endpoint this Bash session's server is sent (blank = none: hover asks no one else). */
    String explainshellEndpoint() {
        return effectiveBashIde().sentExplainshellEndpoint();
    }

    /**
     * The {@code bashIde} section this session sends: what it was given (else the server's defaults), with
     * background analysis off when the workspace is a folder nobody should walk — home, the temp folder or a
     * filesystem root, which is what a loose {@code ~/.bashrc} or {@code /tmp/x.sh} gets as its root.
     */
    private BashIdeConfig effectiveBashIde() {
        BashIdeConfig base = bashIde == null ? BashIdeConfig.defaults() : bashIde;
        return RootResolver.isBroadRoot(root) ? base.withBackgroundAnalysisMaxFiles(0) : base;
    }

    /** The Python interpreter this session's Pyright analyses with (null = the server's own choice). */
    private volatile @Nullable String pythonPath;

    /**
     * Sets the interpreter a Python server is told about ({@code python.pythonPath}); a running server gets the
     * change pushed, re-reads its configuration and re-resolves every import against the new environment.
     */
    void setPythonPath(@Nullable String path) {
        String next = path == null || path.isBlank() ? null : path;
        boolean changed = !java.util.Objects.equals(pythonPath, next);
        pythonPath = next;
        if (changed && "python".equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /** The interpreter last given to this session (null = none). */
    @Nullable
    String pythonPath() {
        return pythonPath;
    }

    /** jdtls's {@code java.cleanup.actions}: the clean-ups {@code java/cleanup} runs (Settings → Java clean-ups). */
    private volatile List<String> javaCleanups = List.of();

    /** jdtls's {@code java.completion.filteredTypes}: types never offered or imported (Settings → Java). */
    private volatile List<String> javaFilteredTypes = LspServerSettings.FILTERED_TYPES;

    /** Sets {@link #javaFilteredTypes} and, for a running Java server, pushes them. */
    void setJavaFilteredTypes(List<String> types) {
        List<String> next = types == null ? List.of() : List.copyOf(types);
        boolean changed = !next.equals(javaFilteredTypes);
        javaFilteredTypes = next;
        if (changed && LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /** Sets {@link #javaCleanups} and, for a running Java server, pushes them. */
    void setJavaCleanups(List<String> cleanups) {
        List<String> next = cleanups == null ? List.of() : List.copyOf(cleanups);
        boolean changed = !next.equals(javaCleanups);
        javaCleanups = next;
        if (changed && LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /**
     * Pushes the project's style again — its {@code .editorconfig}, formatter profile or build file changed — so
     * Organize Imports and Format follow it without a restart (re-review AN5).
     */
    void refreshProjectSettings() {
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /** Whether jdtls downloads every dependency's sources at import (Maven and Gradle). */
    private volatile boolean javaDownloadSources;

    /** Sets {@link #javaDownloadSources} and, for a running Java server, pushes it. */
    void setJavaDownloadSources(boolean enabled) {
        boolean changed = javaDownloadSources != enabled;
        javaDownloadSources = enabled;
        if (changed && LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

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
        java.util.Map<String, Object> settings = TypeScriptServer.SERVER_ID.equals(serverId)
                ? typeScriptSettings
                : LspServerSettings.push(serverId, javaOnTypeFormatting, effectiveBashIde(), pythonPath);
        java.util.Map<String, Object> xml = xmlSettings;
        if (xml != null && XmlServerSettings.isLemminx(serverId)) {
            settings = Map.of(XmlServerSettings.SECTION, xml); // lemminx's own; for a pom, with lemminx-maven's
        }
        if (settings == null) {
            return;
        }
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && settings.get("java") instanceof Map<?, ?> javaMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> own = (Map<String, Object>) javaMap;
            Map<String, Object> merged = JavaProjectSettings.merge(
                    own,
                    JavaProjectSettings.withJdtlsDefaults(
                            JavaProjectSettings.forRoot(root, uris == WireUris.LOCAL ? null : uris::toWire)));
            // The clean-ups java/cleanup runs; never jdtls's own on-save switch (Editora runs them after a save).
            merged.put("cleanup", Map.of("actions", javaCleanups));
            Map<String, Object> completion = new java.util.HashMap<>();
            if (merged.get("completion") instanceof Map<?, ?> c) {
                c.forEach((k, v) -> completion.put(String.valueOf(k), v));
            }
            completion.put("filteredTypes", javaFilteredTypes);
            merged.put("completion", completion);
            JavaLibrarySources.into(merged, javaDownloadSources);
            settings = Map.of("java", merged); // the project's own style, and the chosen clean-ups
        }
        try {
            server().getWorkspaceService()
                    .didChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationParams(settings));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "didChangeConfiguration failed", e);
        }
    }

    /** The poms this session's lemminx-maven was told to leave out of its workspace, by wire URI (review 2, E3). */
    private final Map<String, java.nio.file.Path> leftOutOfWorkspace = new ConcurrentHashMap<>();

    /**
     * Takes {@code poms} (pom files by wire URI) out of the server's workspace: a
     * {@code workspace/didChangeWorkspaceFolders} that removes each file, which lemminx-maven applies to every
     * workspace pom under the removed path — so to that one pom. Returns the ones newly left out: none while the
     * server is not ready, when nothing can be sent (review 3, E Gaps). They stay out for this session: lemminx-maven
     * never rebuilds a closed pom, so one put back would bring its failed build back with it.
     */
    List<java.nio.file.Path> leaveOutOfWorkspace(Map<String, java.nio.file.Path> poms) {
        if (!ready()) {
            return List.of();
        }
        Map<String, java.nio.file.Path> fresh = new java.util.LinkedHashMap<>();
        poms.forEach((uri, pom) -> {
            if (!leftOutOfWorkspace.containsKey(uri)) {
                fresh.put(uri, pom);
            }
        });
        if (fresh.isEmpty()) {
            return List.of();
        }
        try {
            server().getWorkspaceService()
                    .didChangeWorkspaceFolders(new org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams(
                            new org.eclipse.lsp4j.WorkspaceFoldersChangeEvent(
                                    List.of(),
                                    fresh.keySet().stream()
                                            .map(u -> new WorkspaceFolder(u, u.substring(u.lastIndexOf('/') + 1)))
                                            .toList())));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "didChangeWorkspaceFolders failed", e);
            return List.of();
        }
        leftOutOfWorkspace.putAll(fresh);
        return List.copyOf(fresh.values());
    }

    /** Whether {@code uri} (a wire URI) was left out of this session's workspace ({@link #leaveOutOfWorkspace}). */
    boolean leftOutOfWorkspace(String uri) {
        return leftOutOfWorkspace.containsKey(uri);
    }

    /** Every pom left out of this session's workspace ({@link #leaveOutOfWorkspace}). */
    List<java.nio.file.Path> leftOutOfWorkspace() {
        return List.copyOf(leftOutOfWorkspace.values());
    }

    /**
     * How many coordinate completions lemminx-maven failed on its workspace list (review 3, E21): with
     * {@code xml.logs.client} on it logs the participant's exception (a null project in
     * {@code internalCollectWorkspaceArtifacts}) before it answers with markup only. The proof that an empty answer
     * is the broken one and not an honest no-match.
     */
    private final java.util.concurrent.atomic.AtomicInteger workspaceCompletionFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    /** See {@link #workspaceCompletionFailures}. */
    int workspaceCompletionFailures() {
        return workspaceCompletionFailures.get();
    }

    /** {@code message} up to its first stack frame ({@code "\tat …"}). Pure. */
    static String withoutStack(@Nullable String message) {
        String m = message == null ? "" : message;
        int at = m.indexOf("\n\tat ");
        return at < 0 ? m : m.substring(0, at);
    }

    /** Whether {@code message} is lemminx-maven's coordinate completion failing on its workspace's projects. Pure. */
    static boolean mavenWorkspaceCompletionFailed(@Nullable String message) {
        return message != null
                && message.contains("MavenCompletionParticipant")
                && message.contains("internalCollectWorkspaceArtifacts");
    }

    /** lemminx's {@code xml} object for this session's root ({@link XmlServerSettings#section}; null: none yet). */
    private volatile java.util.@Nullable Map<String, Object> xmlSettings;

    /** Sets lemminx's {@code xml} object; a running XML server gets it pushed when it changed. */
    void setXmlSettings(java.util.Map<String, Object> settings) {
        boolean changed = !java.util.Objects.equals(xmlSettings, settings);
        xmlSettings = settings;
        if (changed && XmlServerSettings.isLemminx(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /** The {@code xml} object this session was last given (null: none). */
    java.util.@Nullable Map<String, Object> xmlSettings() {
        return xmlSettings;
    }

    /** typescript-language-server's {@code javascript}/{@code typescript} settings (null: none to send). */
    private volatile java.util.@Nullable Map<String, Object> typeScriptSettings;

    /** Sets the TypeScript settings ({@link TypeScriptServer#settings}); a running server gets them pushed. */
    void setTypeScriptSettings(java.util.Map<String, Object> settings) {
        boolean changed = !java.util.Objects.equals(typeScriptSettings, settings);
        typeScriptSettings = settings;
        if (changed && TypeScriptServer.SERVER_ID.equals(serverId) && ready()) {
            pushConfiguration();
        }
    }

    /**
     * A document's indentation by its wire URI, for the TypeScript server's {@code formattingOptions} question
     * and jdtls's {@code java.format.tabSize} / {@code insertSpaces}.
     */
    void setIndentForUri(java.util.function.Function<String, @Nullable FormattingIndent> lookup) {
        primary.indentForUri = lookup == null ? uri -> null : lookup;
    }

    /** The first answer any window gives for a document's indentation. */
    private @Nullable FormattingIndent indentForUri(@Nullable String uri) {
        if (uri == null) {
            return null;
        }
        for (Client c : clients) {
            FormattingIndent indent = c.indentForUri.apply(uri);
            if (indent != null) {
                return indent;
            }
        }
        return null;
    }

    /** A folder put first on the local server's PATH (the resolved Node.js's bin folder), or null. */
    private volatile @Nullable Path pathPrepend;

    void setPathPrepend(@Nullable Path dir) {
        pathPrepend = dir;
    }

    /** {@code dir} put in front of the PATH value {@code existing} (null/blank: just {@code dir}). Pure. */
    static @Nullable String prependedPath(@Nullable String existing, String dir, String separator) {
        if (dir == null || dir.isBlank()) {
            return existing;
        }
        return existing == null || existing.isBlank() ? dir : dir + separator + existing;
    }

    /** The environment key PATH is stored under (Windows spells it {@code Path}). Pure. */
    static String pathKey(java.util.Map<String, String> env) {
        for (String key : env.keySet()) {
            if (key.equalsIgnoreCase("PATH")) {
                return key;
            }
        }
        return "PATH";
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
    WireUris uris() {
        return named;
    }

    /**
     * Shows the server document {@code key} as the file {@code wireUri}, its text passed through
     * {@code wireText} on the way out ({@link JavaScriptShadow}). Call before {@link #didOpen}; the alias
     * lasts until {@link #didClose} has been sent. Such a document always syncs in full, since an
     * incremental splice is computed on the editor's text, not the rewritten one.
     */
    void alias(String key, String wireUri, java.util.function.UnaryOperator<String> wireText) {
        aliasUris.put(key, wireUri);
        aliasTexts.put(key, wireText);
    }

    /** Whether {@code key} is shown to the server as another file ({@link #alias}). */
    boolean isAliased(String key) {
        return aliasUris.containsKey(key);
    }

    /** {@code text} of document {@code key} as the server is to receive it. */
    private String wireText(String key, String text) {
        var rewrite = aliasTexts.get(key);
        return rewrite == null ? text : rewrite.apply(text);
    }

    /** TEST SEAM: a server that names documents differently from their keys, as a remote one will. */
    void setUrisForTest(WireUris testUris) {
        uris = testUris;
    }

    private TextDocumentIdentifier doc(String key) {
        return new TextDocumentIdentifier(named.toWire(key));
    }

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

    @Nullable
    ServerCapabilities capabilities() {
        return capabilities;
    }

    /** The id of the target the server was started on ({@code local}, a host, a container), or null before. */
    private volatile @Nullable String targetId;

    @Nullable
    String targetId() {
        return targetId;
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

    /** TEST SEAM — {@link #simulateServerDeathForTest} for a server that died before it ever answered
     *  {@code initialize}, having said {@code stderr} first (a native build on too old a glibc). */
    void simulateStartupDeathForTest(String stderr) {
        initializedOnce = false;
        firstStderrLine = stderr;
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
            for (Client c : clients) {
                c.onDead.run();
            }
        }
    }

    /** Whether {@link #dispose()} ran — i.e. this session was torn down deliberately. A dead session that was
     *  never disposed died on its own (crash / failed handshake), which is what the auto-restart keys on. */
    boolean isDisposed() {
        return disposed;
    }

    /** Whether {@link #start()} failed on its own — the command could not be run, or a remote host's
     *  connection broke while it was being started. Such a session is disposed, so it is not a crash. */
    boolean startFailed() {
        return startFailed;
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
    private void whenReady(@Nullable String collapseKey, Runnable action) {
        whenReady(collapseKey, action, () -> {});
    }

    private void whenReady(@Nullable String collapseKey, Runnable action, Runnable onUnavailable) {
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
    private record Pending(@Nullable String key, Runnable action, Runnable onUnavailable) {}

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

    /** Which windows have each document open: the server is told of the first open and the last close. */
    private final Map<String, java.util.Set<Object>> openers = new ConcurrentHashMap<>();

    void didOpen(String uri, String languageId, String text) {
        didOpen(uri, languageId, text, primary);
    }

    /** Whether a Java document other than {@code uri} is open on this session, in any window (ninth review CP1). */
    boolean otherJavaDocumentOpen(String uri) {
        for (var e : openers.entrySet()) {
            if (!e.getKey().equals(uri)
                    && e.getKey().endsWith(".java")
                    && !e.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Opens {@code uri} for {@code opener} (a window). A document another window already has open is not opened
     * again: the server gets this window's text as a change, so it holds what the window it last heard from
     * sees, and each window sends its text before it asks about the document.
     */
    void didOpen(String uri, String languageId, String text, Object opener) {
        java.util.Set<Object> by = openers.computeIfAbsent(uri, u -> ConcurrentHashMap.newKeySet());
        boolean first;
        synchronized (by) {
            first = by.isEmpty();
            by.add(opener);
        }
        if (!first && versions.containsKey(uri)) {
            didChange(uri, text);
            return;
        }
        versions.put(uri, 1);
        whenReady(() -> {
            versions.put(uri, 1);
            shadows.put(uri, text); // update in the same order as the wire notification
            sendsSinceResync.remove(uri);
            server().getTextDocumentService()
                    .didOpen(new DidOpenTextDocumentParams(
                            new TextDocumentItem(named.toWire(uri), languageId, 1, wireText(uri, text))));
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
            server().getTextDocumentService()
                    .didChange(new DidChangeTextDocumentParams(
                            new VersionedTextDocumentIdentifier(named.toWire(uri), version), events));
        });
        return !identical[0]; // a send still queued for initialize has compared nothing yet: reported as sent
    }

    /**
     * Re-sends each other open Java document's text, unchanged, so jdtls reconciles it against the file just saved:
     * an error that save caused or fixed shows in those files without a workspace build, which would compile a Gradle
     * project into Buildship's {@code bin/} (tenth review CO1).
     */
    void revalidateOtherJavaDocuments(String saved) {
        for (var e : openers.entrySet()) {
            String uri = e.getKey();
            if (uri.equals(saved) || !uri.endsWith(".java") || e.getValue().isEmpty()) {
                continue;
            }
            resend(uri);
        }
    }

    /** Sends {@code uri}'s last-sent text again as a full change with the next version. */
    private void resend(String uri) {
        whenReady("revalidate:" + uri, () -> {
            String text = shadows.get(uri);
            if (text == null || changeSyncDisabled()) return;
            int version = versions.merge(uri, 1, Integer::sum);
            server().getTextDocumentService()
                    .didChange(new DidChangeTextDocumentParams(
                            new VersionedTextDocumentIdentifier(named.toWire(uri), version),
                            List.of(new TextDocumentContentChangeEvent(wireText(uri, text)))));
        });
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
        if (aliasTexts.containsKey(uri)) {
            return List.of(new TextDocumentContentChangeEvent(wireText(uri, text))); // see alias()
        }
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
    static @Nullable TextDocumentSyncKind changeSyncKind(@Nullable ServerCapabilities caps) {
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

    void didSave(String uri, @Nullable String savedText) {
        whenReady(() -> {
            String text = saveIncludesText() && savedText != null ? wireText(uri, savedText) : null;
            server().getTextDocumentService().didSave(new DidSaveTextDocumentParams(doc(uri), text));
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
        didClose(uri, primary);
    }

    /** Closes {@code uri} for {@code opener}; the server hears of it once no window has it open. */
    void didClose(String uri, Object opener) {
        java.util.Set<Object> by = openers.get(uri);
        if (by != null) {
            synchronized (by) {
                by.remove(opener);
                if (!by.isEmpty()) {
                    return;
                }
            }
            openers.remove(uri, by);
        }
        versions.remove(uri);
        whenReady(() -> {
            versions.remove(uri);
            shadows.remove(uri); // remove in wire order; queued changes before this still need their base
            sendsSinceResync.remove(uri);
            server().getTextDocumentService().didClose(new DidCloseTextDocumentParams(doc(uri)));
            aliasUris.remove(uri); // only now: the close itself had to name the alias
            aliasTexts.remove(uri);
        });
    }

    boolean isOpen(String uri) {
        return versions.containsKey(uri);
    }

    /** Current client document version, or null when the URI is not open in this session. */
    @Nullable
    Integer documentVersion(@Nullable String uri) {
        return versions.get(uri);
    }

    /** The text last sent for {@code uri}, or null when it is not open in this session (any window's). */
    @Nullable
    String documentSnapshot(String uri) {
        return shadows.get(uri);
    }

    /** The text last sent for the document the server calls {@code wireUri}, or null when it is not open. */
    private @Nullable String shadowOfWire(@Nullable String wireUri) {
        return wireUri == null ? null : shadows.get(named.toKey(wireUri));
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
                        bounded(server().getWorkspaceService()
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

    private volatile @Nullable RawSink rawSinkForTest;

    void setRawSinkForTest(RawSink sink) {
        this.rawSinkForTest = sink;
    }

    /**
     * Sends a <b>non-standard</b> request the typed {@link LanguageServer} proxy can't express — e.g. jdtls's
     * {@code java/classFileContents} (#665) — via the launcher's raw JSON-RPC endpoint. The result is the
     * gson-decoded payload (lsp4j has no registered response type for an unknown method, so expect a
     * {@code JsonElement}/{@code JsonPrimitive}). Queued until {@code initialize} completes.
     */
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
                        track(bounded(l.getRemoteEndpoint().request(method, params), timeout, this))
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
        return codeAction(uri, range, diagnostics, null);
    }

    /** As above, asking only for the {@code only} kinds (null: whatever the server offers). */
    CompletableFuture<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> codeAction(
            String uri,
            org.eclipse.lsp4j.Range range,
            List<org.eclipse.lsp4j.Diagnostic> diagnostics,
            @Nullable List<String> only) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var context = new org.eclipse.lsp4j.CodeActionContext(diagnostics == null ? List.of() : diagnostics);
        if (only != null) {
            context.setOnly(only);
        } else if (TypeScriptServer.SERVER_ID.equals(serverId)) {
            // typescript-language-server offers its source actions (fix all, add missing imports, organize)
            // only when they are asked for by kind (Node review B5).
            context.setOnly(TypeScriptServer.CODE_ACTION_KINDS);
        }
        var params = new org.eclipse.lsp4j.CodeActionParams(doc(uri), range, context);
        return bounded(server().getTextDocumentService().codeAction(params))
                .<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>>thenApply(l -> {
                    if (l != null && BashShims.isBash(serverId)) { // ShellCheck's fixes count code points (B2)
                        BashShims.codeActionsToUtf16(l, named.toWire(uri), shadows.get(uri));
                    }
                    return l == null ? List.of() : List.copyOf(l);
                })
                .exceptionally(t -> List.of());
    }

    /** Resolves a code action ({@code codeAction/resolve}) to fill in its deferred {@code edit}; the action
     *  unchanged before the server is ready. A refusal fails the future, so the caller can say why (BN9). */
    CompletableFuture<org.eclipse.lsp4j.CodeAction> resolveCodeAction(org.eclipse.lsp4j.CodeAction action) {
        if (!ready()) {
            return CompletableFuture.completedFuture(action);
        }
        return bounded(server().getTextDocumentService().resolveCodeAction(action));
    }

    /** Handler for a server-initiated {@code workspace/applyEdit}: {@code accept(edit, respond)} — the
     *  handler applies (on whatever thread it marshals to) and answers via {@code respond}. Default: refuse. */
    void setOnApplyEdit(java.util.function.BiConsumer<org.eclipse.lsp4j.WorkspaceEdit, Consumer<Boolean>> handler) {
        primary.onApplyEdit = handler == null ? (edit, respond) -> respond.accept(false) : handler;
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
            onStatus.accept("ProgressEnd", endText(end.getMessage())); // null message ⇒ just stop the bar
        }
    }

    /** Pure: the echo-area text for a progress End, or null to just stop the bar. A bare percentage is
     *  dropped: jdtls formats every status as {@code "N% subtask"}, so a job that ends without a subtask
     *  (and without using up its work) would leave e.g. "50%" in the echo area with nothing to replace it. */
    static @Nullable String endText(@Nullable String message) {
        return message == null || message.isBlank() || message.strip().matches("\\d+%") ? null : message.strip();
    }

    /**
     * Pure: the echo-area text for a progress Begin — title, plus the message and/or percentage — or null when
     * there is nothing to say: Pyright begins every analysis with an empty title, and "…" then sat in the status
     * bar long after the work had ended (Python review B11). The loading bar still spins either way. A 0% is
     * left out: every jdtls job begins at it, and the log then read as if each one had stalled.
     */
    static @Nullable String progressText(
            @Nullable String title, @Nullable String message, @Nullable Integer percentage) {
        StringBuilder sb = new StringBuilder(title == null || title.isBlank() ? "" : title.strip());
        if (message != null && !message.isBlank()) {
            sb.append(sb.isEmpty() ? "" : " — ").append(message.strip());
        }
        if (sb.isEmpty()) {
            return null;
        }
        if (percentage != null && percentage > 0) { // "(0%)" on a Begin only made the work look stalled
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
            routed().onApplyEdit
                    .accept( // the window the user is working in made the request that led here
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
            @Nullable String triggerChar,
            boolean retrigger,
            org.eclipse.lsp4j.@Nullable SignatureHelp activeHelp) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.SignatureHelpParams(doc(uri), pos);
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
        var request = bounded(server().getTextDocumentService().signatureHelp(params));
        return cancelling(request, request.exceptionally(t -> null));
    }

    /** Occurrences of the symbol at a position ({@code textDocument/documentHighlight}) → highlights with
     *  their Read/Write kind, or empty (#675). */
    CompletableFuture<List<? extends org.eclipse.lsp4j.DocumentHighlight>> documentHighlight(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.DocumentHighlightParams(doc(uri), pos);
        var request = bounded(server().getTextDocumentService().documentHighlight(params));
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
        var params = new org.eclipse.lsp4j.PrepareRenameParams(doc(uri), pos);
        return bounded(server().getTextDocumentService().prepareRename(params)).exceptionally(t -> null);
    }

    /** Renames the symbol at a position ({@code textDocument/rename}) → the workspace edit, or null (#676). */
    CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> rename(String uri, Position pos, String newName) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var params = new org.eclipse.lsp4j.RenameParams(doc(uri), pos, newName);
        // A refusal fails the future: its message is the reason the user is shown (re-review BN9).
        return bounded(server().getTextDocumentService().rename(params));
    }

    /** Notifies the server of external file changes ({@code workspace/didChangeWatchedFiles}) so its
     *  project model tracks a git checkout / CLI build / external editor (#677). Dropped when not ready —
     *  a starting server reads the disk fresh anyway. */
    void didChangeWatchedFiles(List<org.eclipse.lsp4j.FileEvent> events) {
        if (!ready() || events == null || events.isEmpty()) {
            return;
        }
        try {
            server().getWorkspaceService()
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
        var params = new org.eclipse.lsp4j.InlayHintParams(doc(uri), range);
        var request = bounded(server().getTextDocumentService().inlayHint(params));
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
        var request =
                bounded(server().getTextDocumentService().codeLens(new org.eclipse.lsp4j.CodeLensParams(doc(uri))));
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
                    var resolve = bounded(server().getTextDocumentService().resolveCodeLens(lens));
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
        // The caller holds (and cancels) the stage returned below, not `result`, and cancelling a dependent
        // stage does not cancel what it was derived from: the check has to be on the returned one, or a
        // superseded request leaves every resolve it started running on the server.
        CompletableFuture<List<org.eclipse.lsp4j.CodeLens>> answer =
                cancelling(request, result.exceptionally(t -> List.of()));
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) {
                resolving.forEach(f -> f.cancel(true));
            }
        });
        return answer;
    }

    /** Call-hierarchy anchor at a position ({@code textDocument/prepareCallHierarchy}) → items or empty. */
    CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyItem>> prepareCallHierarchy(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var params = new org.eclipse.lsp4j.CallHierarchyPrepareParams(doc(uri), pos);
        return bounded(server().getTextDocumentService().prepareCallHierarchy(params))
                .<List<org.eclipse.lsp4j.CallHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Incoming calls (callers) of a call-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.CallHierarchyIncomingCall>> incomingCalls(
            org.eclipse.lsp4j.CallHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server().getTextDocumentService()
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
        return bounded(server().getTextDocumentService()
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
        var params = new org.eclipse.lsp4j.TypeHierarchyPrepareParams(doc(uri), pos);
        return bounded(server().getTextDocumentService().prepareTypeHierarchy(params))
                .<List<org.eclipse.lsp4j.TypeHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Supertypes of a type-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> supertypes(org.eclipse.lsp4j.TypeHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server().getTextDocumentService()
                        .typeHierarchySupertypes(new org.eclipse.lsp4j.TypeHierarchySupertypesParams(item)))
                .<List<org.eclipse.lsp4j.TypeHierarchyItem>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** Subtypes of a type-hierarchy item (#682). */
    CompletableFuture<List<org.eclipse.lsp4j.TypeHierarchyItem>> subtypes(org.eclipse.lsp4j.TypeHierarchyItem item) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server().getTextDocumentService()
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
        var params = new CompletionParams(doc(uri), pos);
        params.setContext(context);
        return observed("completion", server().getTextDocumentService().completion(params));
    }

    /** Whole-document formatting ({@code textDocument/formatting}) → the edits to apply, or empty. */
    CompletableFuture<List<? extends TextEdit>> formatting(String uri, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        DocumentFormattingParams params = new DocumentFormattingParams(doc(uri), options);
        return bounded(server().getTextDocumentService().formatting(params)).exceptionally(t -> List.of());
    }

    /** Range formatting ({@code textDocument/rangeFormatting}) over {@code range} → the edits, or empty. */
    CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            String uri, org.eclipse.lsp4j.Range range, FormattingOptions options) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        org.eclipse.lsp4j.DocumentRangeFormattingParams params =
                new org.eclipse.lsp4j.DocumentRangeFormattingParams(doc(uri), options, range);
        return bounded(server().getTextDocumentService().rangeFormatting(params))
                .exceptionally(t -> List.of());
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
        return bounded(server().getTextDocumentService().resolveCompletionItem(item))
                .exceptionally(t -> item);
    }

    CompletableFuture<Hover> hover(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return observed("hover", server().getTextDocumentService().hover(new HoverParams(doc(uri), pos)));
    }

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return observed(
                "definition", server().getTextDocumentService().definition(new DefinitionParams(doc(uri), pos)));
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
        var params = new org.eclipse.lsp4j.DocumentOnTypeFormattingParams(doc(uri), options, pos, ch);
        return bounded(server().getTextDocumentService().onTypeFormatting(params))
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
        return observed(
                "implementation",
                server().getTextDocumentService()
                        .implementation(new org.eclipse.lsp4j.ImplementationParams(doc(uri), pos)));
    }

    /**
     * jdtls's {@code java/findLinks} of {@code type} ({@code superImplementation}: the method or type the one at
     * {@code pos} overrides or extends), as locations. Empty from a server without it.
     */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> findLinks(
            String type, String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        Map<String, Object> params =
                Map.of("type", type, "position", new org.eclipse.lsp4j.TextDocumentPositionParams(doc(uri), pos));
        return rawRequest("java/findLinks", params).thenApply(result -> {
            List<Location> out = new ArrayList<>();
            com.google.gson.JsonElement json = LspManager.asJson(result);
            if (json != null && json.isJsonArray()) {
                for (com.google.gson.JsonElement e : json.getAsJsonArray()) {
                    Location l = new com.google.gson.Gson().fromJson(e, Location.class);
                    if (l != null && l.getUri() != null && l.getRange() != null) {
                        out.add(l);
                    }
                }
            }
            return Either.forLeft(out);
        });
    }

    /** The declaration of the <em>type</em> of the symbol at a position ({@code textDocument/typeDefinition})
     *  — from a variable to its type, as opposed to definition's "to the variable" (#736). */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return observed(
                "typeDefinition",
                server().getTextDocumentService()
                        .typeDefinition(new org.eclipse.lsp4j.TypeDefinitionParams(doc(uri), pos)));
    }

    /**
     * The document's links ({@code textDocument/documentLink}) — lemminx's schema, DTD and {@code xml-model}
     * references (XML review B5; it has none for {@code xsl:include}). Empty when not ready, unsupported or on error.
     */
    CompletableFuture<List<org.eclipse.lsp4j.DocumentLink>> documentLinks(String uri) {
        ServerCapabilities c = capabilities;
        if (!ready() || c == null || c.getDocumentLinkProvider() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return bounded(server().getTextDocumentService()
                        .documentLink(new org.eclipse.lsp4j.DocumentLinkParams(doc(uri))))
                .<List<org.eclipse.lsp4j.DocumentLink>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    /** A link with its target filled in ({@code documentLink/resolve}); the link itself when that fails. */
    CompletableFuture<org.eclipse.lsp4j.DocumentLink> resolveDocumentLink(org.eclipse.lsp4j.DocumentLink link) {
        ServerCapabilities c = capabilities;
        if (!ready()
                || c == null
                || c.getDocumentLinkProvider() == null
                || !Boolean.TRUE.equals(c.getDocumentLinkProvider().getResolveProvider())) {
            return CompletableFuture.completedFuture(link);
        }
        return bounded(server().getTextDocumentService().documentLinkResolve(link))
                .handle((resolved, error) -> error == null && resolved != null ? resolved : link);
    }

    /** The declaration of the symbol at a position ({@code textDocument/declaration}); most servers alias
     *  this to definition, but they differ for Java module directives and some forward declarations (#736). */
    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return observed(
                "declaration",
                server().getTextDocumentService().declaration(new org.eclipse.lsp4j.DeclarationParams(doc(uri), pos)));
    }

    /** Foldable regions for the whole document ({@code textDocument/foldingRange}, #738); empty on error. */
    CompletableFuture<List<org.eclipse.lsp4j.FoldingRange>> foldingRange(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var request = bounded(server().getTextDocumentService()
                .foldingRange(new org.eclipse.lsp4j.FoldingRangeRequestParams(doc(uri))));
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
        return bounded(server().getTextDocumentService()
                        .selectionRange(new org.eclipse.lsp4j.SelectionRangeParams(doc(uri), positions)))
                .<List<org.eclipse.lsp4j.SelectionRange>>thenApply(l -> l == null ? List.of() : List.copyOf(l))
                .exceptionally(t -> List.of());
    }

    CompletableFuture<List<? extends Location>> references(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        // Usages only: with the declaration included, Find References on an unused symbol answered with the
        // declaration alone, and the lone result was "jumped to" — the caret stayed put (Java review B5).
        if (BashShims.isBash(serverId)) {
            return bashReferences(uri, pos);
        }
        ReferenceParams params = new ReferenceParams(doc(uri), pos, new ReferenceContext(false));
        return bounded(server().getTextDocumentService().references(params));
    }

    /**
     * bash-language-server reads {@code includeDeclaration: false} as "without the occurrence at the request
     * position": from a call the list held the definition and not the call (Bash review C4, B9). It is asked with
     * the declaration included, and the declaration — where {@code textDocument/definition} says it is — is left
     * out here. Should the definition request fail, the whole list is shown.
     */
    private CompletableFuture<List<? extends Location>> bashReferences(String uri, Position pos) {
        var refs = bounded(server().getTextDocumentService()
                .references(new ReferenceParams(doc(uri), pos, new ReferenceContext(true))));
        var defs = bounded(server().getTextDocumentService().definition(new DefinitionParams(doc(uri), pos)))
                .exceptionally(t -> null);
        return refs.thenCombine(defs, (r, d) -> BashShims.withoutDeclarations(r, BashShims.locations(d)));
    }

    /**
     * {@code workspace/willRenameFiles} for {@code from} → {@code to} (wire URIs): the edit the server wants made
     * before the move — an empty one when it has none or does not take the request, and null when it could not
     * say: still starting, or the request failed or timed out (fourth review BF5).
     */
    CompletableFuture<org.eclipse.lsp4j.@Nullable WorkspaceEdit> willRenameFiles(String from, String to) {
        ServerCapabilities c = capabilities;
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        if (c == null
                || c.getWorkspace() == null
                || c.getWorkspace().getFileOperations() == null
                || c.getWorkspace().getFileOperations().getWillRename() == null) {
            return CompletableFuture.completedFuture(new org.eclipse.lsp4j.WorkspaceEdit());
        }
        var params = new org.eclipse.lsp4j.RenameFilesParams(List.of(new org.eclipse.lsp4j.FileRename(from, to)));
        return bounded(server().getWorkspaceService().willRenameFiles(params))
                .<org.eclipse.lsp4j.@Nullable WorkspaceEdit>thenApply(
                        edit -> edit == null ? new org.eclipse.lsp4j.WorkspaceEdit() : edit)
                .exceptionally(t -> null);
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
        return bounded(server().getWorkspaceService().symbol(new org.eclipse.lsp4j.WorkspaceSymbolParams(query)))
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
        var request = observed(
                "documentSymbol",
                server().getTextDocumentService().documentSymbol(new org.eclipse.lsp4j.DocumentSymbolParams(doc(uri))));
        return cancelling(request, request.exceptionally(t -> java.util.List.of()));
    }

    /** Pull diagnostics ({@code textDocument/diagnostic}) for {@code uri}; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport> diagnostic(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return bounded(
                server().getTextDocumentService().diagnostic(new org.eclipse.lsp4j.DocumentDiagnosticParams(doc(uri))));
    }

    /** Semantic tokens over {@code range} ({@code textDocument/semanticTokens/range}); null when not ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensRange(String uri, org.eclipse.lsp4j.Range range) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var request = bounded(server().getTextDocumentService()
                .semanticTokensRange(new org.eclipse.lsp4j.SemanticTokensRangeParams(doc(uri), range)));
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
        var params = new org.eclipse.lsp4j.SemanticTokensDeltaParams(doc(uri), previousResultId);
        var request = bounded(server().getTextDocumentService().semanticTokensFullDelta(params));
        return cancelling(request, request.exceptionally(t -> null));
    }

    /** Whole-document semantic tokens ({@code textDocument/semanticTokens/full}), for servers that don't
     *  advertise range requests; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.SemanticTokens> semanticTokensFull(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        var request = bounded(server().getTextDocumentService()
                .semanticTokensFull(new org.eclipse.lsp4j.SemanticTokensParams(doc(uri))));
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
        if (live != null && initialized) {
            if (p != null && p.isAlive()) {
                ProcessRegistry.expectExit(p); // a JVM shutdown racing this waits briefly instead of force-killing
                Thread t = new Thread(() -> exitGracefully(live, p), "lsp-shutdown-" + command.get(0));
                t.setDaemon(true);
                t.start();
                return;
            }
            if (p == null) {
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

    private void tearDown(@Nullable Process p) {
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
        if (BashShims.isBash(serverId) && diagnostics != null) { // ShellCheck's columns are code points (B2)
            BashShims.diagnosticsToUtf16(diagnostics.getDiagnostics(), shadowOfWire(diagnostics.getUri()));
        }
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
        refresh("diagnostics");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        refresh("semanticTokens");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        refresh("inlayHints");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshFoldingRanges() {
        refresh("foldingRanges");
        return CompletableFuture.completedFuture(null);
    }

    /**
     * lemminx sends {@code workspace/codeLens/refresh} after every settings push; lsp4j's default threw, so it got
     * an "Internal error" reply, a SEVERE log line, and its lenses went stale (XML review 2 B2).
     */
    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        refresh("codeLenses");
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
            refresh("capabilities");
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
                if ("workspace/executeCommand".equals(method)) {
                    changed |= reregisterCommands(); // the union of what is still registered (withCommands)
                    continue;
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
            refresh("capabilities");
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Rebuilds the command list from the registrations left after one was withdrawn: none when every one is gone.
     * A server that listed commands at initialize keeps its list as it stands.
     */
    private boolean reregisterCommands() {
        if (staticCapabilityMethods.contains("workspace/executeCommand")) {
            return false;
        }
        applyDynamicCapabilitySafely("workspace/executeCommand", null, false);
        for (var r : List.copyOf(dynamicRegistrations.values())) {
            if ("workspace/executeCommand".equals(r.getMethod())) {
                applyDynamicCapabilitySafely(r.getMethod(), r.getRegisterOptions(), true);
            }
        }
        return true;
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
    private boolean applyDynamicCapabilitySafely(String method, @Nullable Object options, boolean enabled) {
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

    private synchronized void applyDynamicCapability(String method, @Nullable Object options, boolean enabled) {
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
                        enabled
                                ? withCommands(
                                        c.getExecuteCommandProvider(),
                                        dynamicOptions(options, org.eclipse.lsp4j.ExecuteCommandOptions.class))
                                : null);
            default -> {
                return;
            }
        }
        capabilities = c; // volatile write publishes the mutation to FX-thread feature gates
    }

    /**
     * The commands of {@code added} together with those already registered: a server may register its commands in
     * several registrations (one per extension), and the last one replacing the rest left the others unrunnable.
     */
    static org.eclipse.lsp4j.ExecuteCommandOptions withCommands(
            org.eclipse.lsp4j.@Nullable ExecuteCommandOptions current, org.eclipse.lsp4j.ExecuteCommandOptions added) {
        if (current == null
                || current.getCommands() == null
                || current.getCommands().isEmpty()) {
            return added;
        }
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(current.getCommands());
        if (added.getCommands() != null) {
            all.addAll(added.getCommands());
        }
        return new org.eclipse.lsp4j.ExecuteCommandOptions(List.copyOf(all));
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

    private static <T> T dynamicOptions(@Nullable Object options, Class<T> type) {
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
     * {@code python} / {@code python.analysis} objects, bash-language-server's {@code bashIde} section, and an
     * empty object for the CSS and HTML servers' own sections (they throw on {@code null}). Any other section
     * returns null so the server keeps its own default.
     */
    @Override
    public CompletableFuture<List<Object>> configuration(org.eclipse.lsp4j.ConfigurationParams params) {
        List<Object> out = new java.util.ArrayList<>();
        if (params != null && params.getItems() != null) {
            for (org.eclipse.lsp4j.ConfigurationItem item : params.getItems()) {
                out.add(
                        TypeScriptServer.SERVER_ID.equals(serverId)
                                ? TypeScriptServer.answer(
                                        item.getSection(),
                                        item.getScopeUri() == null ? null : indentForUri(item.getScopeUri()))
                                : answerFor(item));
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    /** A non-TypeScript server's answer for one {@code workspace/configuration} item. */
    private @Nullable Object answerFor(org.eclipse.lsp4j.ConfigurationItem item) {
        Object answer = LspServerSettings.answer(serverId, item.getSection(), effectiveBashIde(), pythonPath);
        if (answer == null && LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && item.getScopeUri() != null) {
            answer = LspServerSettings.javaFormatAnswer(item.getSection(), indentForUri(item.getScopeUri()));
        }
        if (answer == null && XmlServerSettings.isLemminx(serverId)) {
            answer = XmlServerSettings.answer(
                    xmlSettings,
                    item.getSection(),
                    item.getScopeUri() == null ? null : indentForUri(item.getScopeUri()));
        }
        return answer;
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        onStatus.accept("Message", messageParams.getMessage());
    }

    /** Sets where this server's questions go ({@link LspManager} hands a wrapper that runs on the FX thread);
     *  none (a test, a headless session) answers none. */
    void setClientUi(LspClientUi ui) {
        primary.ui = ui;
    }

    /**
     * Asks the user the server's question and answers with the action they chose, or null when they closed it
     * (or nobody can ask). The dialog runs on the FX thread and this future completes from there, so the
     * reader thread is free meanwhile: a server that asks "Import build?" and keeps talking still gets its
     * other replies. Metals asks this way before importing an sbt or Mill build; it used to wait forever.
     */
    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
        LspClientUi ui = clientUi();
        if (ui == null || disposed || params == null || params.getMessage() == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> titles = new ArrayList<>();
        if (params.getActions() != null) {
            for (MessageActionItem action : params.getActions()) {
                if (action != null && action.getTitle() != null) {
                    titles.add(action.getTitle());
                }
            }
        }
        return ui.chooseAction(serverId, params.getType(), params.getMessage(), List.copyOf(titles))
                .handle((chosen, error) ->
                        chosen == null || error != null || disposed ? null : new MessageActionItem(chosen));
    }

    // --- Metals' client extensions (scalameta.org/metals/docs/integrations/new-editor) ------------------

    /**
     * {@code metals/status}: Metals' status-bar text. A spinning item (an import, a compile) is progress — the
     * loading bar runs until Metals hides it; any other text is a status message.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("metals/status")
    public void metalsStatus(MetalsStatus status) {
        MetalsStatusView view = MetalsStatusView.of(status);
        if (view != null) {
            onStatus.accept(view.type(), view.text());
        }
    }

    /**
     * {@code metals/executeClientCommand}: an action Metals asks the editor for. Opening a location is carried
     * out; every other command (its tree views, doctor page, run lenses, focusing diagnostics) is ignored, as the
     * protocol allows a client that does not know one.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("metals/executeClientCommand")
    public void metalsExecuteClientCommand(org.eclipse.lsp4j.ExecuteCommandParams params) {
        LspClientUi ui = clientUi();
        if (ui == null || disposed || params == null || params.getCommand() == null) {
            return;
        }
        switch (params.getCommand()) {
            case "metals-goto-location" -> {
                org.eclipse.lsp4j.Location at = firstArgument(params, org.eclipse.lsp4j.Location.class);
                if (at != null && at.getUri() != null && at.getRange() != null) {
                    Path file = DocumentUris.toPath(named.toKey(at.getUri()));
                    if (file != null) {
                        ui.openLocation(
                                serverId,
                                file,
                                at.getRange().getStart().getLine(),
                                at.getRange().getStart().getCharacter());
                    }
                }
            }
            default -> LOG.fine(() -> "[" + serverId + "] client command not handled: " + params.getCommand());
        }
    }

    /** {@code metals/inputBox}: a line of text from the user, or {@code cancelled}. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("metals/inputBox")
    public CompletableFuture<MetalsInputBoxResult> metalsInputBox(MetalsInputBoxParams params) {
        LspClientUi ui = clientUi();
        if (ui == null || disposed || params == null) {
            return CompletableFuture.completedFuture(MetalsInputBoxResult.cancel());
        }
        return ui.inputBox(serverId, params.prompt, params.value, params.placeHolder)
                .handle((value, error) -> value == null || error != null
                        ? MetalsInputBoxResult.cancel()
                        : MetalsInputBoxResult.of(value));
    }

    /** {@code metals/quickPick}: one of the offered items, by id, or {@code cancelled}. */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("metals/quickPick")
    public CompletableFuture<MetalsQuickPickResult> metalsQuickPick(MetalsQuickPickParams params) {
        LspClientUi ui = clientUi();
        if (ui == null || disposed || params == null || params.items == null || params.items.isEmpty()) {
            return CompletableFuture.completedFuture(MetalsQuickPickResult.cancel());
        }
        List<LspClientUi.PickItem> items = new ArrayList<>();
        for (MetalsQuickPickItem item : params.items) {
            if (item != null && item.id != null) {
                items.add(new LspClientUi.PickItem(
                        item.id, item.label == null ? item.id : item.label, item.description, item.detail));
            }
        }
        return ui.quickPick(serverId, params.placeHolder, List.copyOf(items))
                .handle((id, error) ->
                        id == null || error != null ? MetalsQuickPickResult.cancel() : MetalsQuickPickResult.of(id));
    }

    /** The first argument of a client command read as {@code type} (lsp4j hands it over as raw JSON), or null. */
    private static <T> @Nullable T firstArgument(org.eclipse.lsp4j.ExecuteCommandParams params, Class<T> type) {
        if (params.getArguments() == null || params.getArguments().isEmpty()) {
            return null;
        }
        try {
            return LSP_GSON.fromJson(LSP_GSON.toJsonTree(params.getArguments().get(0)), type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Payload of {@code metals/status}. */
    public static final class MetalsStatus {
        public @Nullable String text;
        public @Nullable Boolean show;
        public @Nullable Boolean hide;
        public @Nullable String tooltip;
        public @Nullable String command;
    }

    /** Payload of {@code metals/inputBox}. */
    public static final class MetalsInputBoxParams {
        public @Nullable String prompt;
        public @Nullable String value;
        public @Nullable String placeHolder;
        public @Nullable Boolean password;
        public @Nullable Boolean ignoreFocusOut;
    }

    /** Reply to {@code metals/inputBox}. */
    public static final class MetalsInputBoxResult {
        public @Nullable String value;
        public @Nullable Boolean cancelled;

        static MetalsInputBoxResult of(String value) {
            MetalsInputBoxResult r = new MetalsInputBoxResult();
            r.value = value;
            return r;
        }

        static MetalsInputBoxResult cancel() {
            MetalsInputBoxResult r = new MetalsInputBoxResult();
            r.cancelled = true;
            return r;
        }
    }

    /** One item of {@code metals/quickPick}. */
    public static final class MetalsQuickPickItem {
        public @Nullable String id;
        public @Nullable String label;
        public @Nullable String description;
        public @Nullable String detail;
        public @Nullable Boolean alwaysShow;
    }

    /** Payload of {@code metals/quickPick}. */
    public static final class MetalsQuickPickParams {
        public @Nullable List<MetalsQuickPickItem> items;
        public @Nullable Boolean matchOnDescription;
        public @Nullable Boolean matchOnDetail;
        public @Nullable String placeHolder;
        public @Nullable Boolean ignoreFocusOut;
    }

    /** Reply to {@code metals/quickPick}. */
    public static final class MetalsQuickPickResult {
        public @Nullable String itemId;
        public @Nullable Boolean cancelled;

        static MetalsQuickPickResult of(String id) {
            MetalsQuickPickResult r = new MetalsQuickPickResult();
            r.itemId = id;
            return r;
        }

        static MetalsQuickPickResult cancel() {
            MetalsQuickPickResult r = new MetalsQuickPickResult();
            r.cancelled = true;
            return r;
        }
    }

    /**
     * Whether an Error jdtls logs may be an import's failure: not a build that found a compile error (eleventh review
     * CM1), nor a request about a file outside the source folders, which jdtls logs as a Java Model Exception
     * ("scripts [in shelf] does not exist") for each request on it (twelfth review CM1). Only that element shape is
     * skipped: Gradle's own "Included build '…' does not exist" is a failed import (thirteenth review CK1).
     */
    static boolean importError(@Nullable String message) {
        return message != null
                && !message.contains("while building workspace")
                && !message.contains("Java Model Exception")
                && !JAVA_MODEL_MISSING.matcher(firstLine(message)).find()
                && !message.contains("Error in calling delegate command handler");
    }

    /** A Java element jdtls cannot find, "scripts [in shelf] does not exist": no import's failure. */
    private static final java.util.regex.Pattern JAVA_MODEL_MISSING =
            java.util.regex.Pattern.compile("\\[in [^\\]]+\\] does not exist");

    @Override
    public void logMessage(MessageParams message) {
        if (message == null) {
            return;
        }
        // A server's warnings and errors reach the Debug Log (the root logger drops FINE): bash-language-server
        // says only here that it found no ShellCheck ("disabling linting as no executable was found") or that
        // a format failed, and the user otherwise saw a script with no diagnostics and no hint why.
        org.eclipse.lsp4j.MessageType type = message.getType();
        if (mavenWorkspaceCompletionFailed(message.getMessage())) {
            workspaceCompletionFailures.incrementAndGet(); // seen by the completion's answer (E21); no stack logged
            LOG.fine(() -> "[" + serverId + "] " + firstLine(message.getMessage()));
            return;
        }
        if (type == org.eclipse.lsp4j.MessageType.Error && importError(message.getMessage())) {
            lastErrorLog = firstLine(message.getMessage());
        }
        if (type == org.eclipse.lsp4j.MessageType.Error) {
            lastLoggedError = firstLine(message.getMessage());
            loggedErrors.incrementAndGet(); // after the text: a reader that sees the count sees its message
        }
        if (type == org.eclipse.lsp4j.MessageType.Error || type == org.eclipse.lsp4j.MessageType.Warning) {
            LOG.info("[" + serverId + "] "
                    + (LspServerRegistry.MAVEN_POM_SERVER_ID.equals(serverId)
                            ? withoutStack(message.getMessage()) // its log reaches us since E21: no stack per start
                            : message.getMessage()));
        } else if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(message.getMessage());
        }
    }

    /** The first line of the last error this server logged: a failed Gradle import's reason is only there. */
    private volatile @Nullable String lastErrorLog;

    /**
     * The first line of the last Error this server logged, whatever it was about, and how many it has logged: how a
     * request can tell that the server failed it and said why only in its log (bash-language-server answers a
     * Format Document that shfmt could not parse with no edits, Bash review B3).
     */
    private volatile @Nullable String lastLoggedError;

    private final java.util.concurrent.atomic.AtomicLong loggedErrors = new java.util.concurrent.atomic.AtomicLong();

    long loggedErrorCount() {
        return loggedErrors.get();
    }

    @Nullable
    String lastLoggedError() {
        return lastLoggedError;
    }

    /** Forgets the last logged error: an import starts again, or one succeeded (eleventh review CM1). */
    void forgetLastErrorLog() {
        lastErrorLog = null;
    }

    @Nullable
    String lastErrorLog() {
        return lastErrorLog;
    }

    /**
     * The latest diagnostics of the project itself (its folder, its build files), by URI, for a window that joins
     * later: it heard the import's verdict but not the problems behind it (seventh review CR2).
     */
    private final Map<String, PublishDiagnosticsParams> projectDiagnostics = new ConcurrentHashMap<>();

    private void keepProjectDiagnostics(PublishDiagnosticsParams params) {
        String uri = params == null ? null : params.getUri();
        if (uri == null || !uri.startsWith("file:")) {
            return;
        }
        Path file;
        try {
            file = Path.of(java.net.URI.create(uri));
        } catch (RuntimeException e) {
            return;
        }
        if (!LspManager.projectLevel(file)) {
            return;
        }
        if (params.getDiagnostics() == null || params.getDiagnostics().isEmpty()) {
            projectDiagnostics.remove(uri);
        } else {
            projectDiagnostics.put(uri, params);
        }
    }

    /** What {@link #keepProjectDiagnostics} kept, to replay to a window that joins. */
    List<PublishDiagnosticsParams> projectDiagnostics() {
        return List.copyOf(projectDiagnostics.values());
    }

    /** jdtls's last {@code ProjectStatus} ("OK", "WARNING"), for a window that joins the session later (CS2). */
    private volatile @Nullable String lastProjectStatus;

    void setLastProjectStatus(@Nullable String status) {
        lastProjectStatus = status;
    }

    @Nullable
    String lastProjectStatus() {
        return lastProjectStatus;
    }

    /** {@code text}'s first non-blank line (jdtls's log lines carry a date prefix, kept off), at most 300 chars. */
    static String firstLine(String text) {
        String line = text.strip()
                .lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("")
                .strip();
        // JDK 20+ puts a narrow no-break space (U+202F) before AM/PM, which \\s does not match (third review CT7)
        line = line.replaceFirst(
                "^[A-Z][a-z]{2} \\d{1,2}, \\d{4},? \\d{1,2}:\\d{2}:\\d{2}[\\s\\u202F]*(?:[AP]M)?[\\s\\u202F]*", "");
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    /** JDT LS sends a non-standard {@code language/status} notification with start/ready/progress text
     *  (e.g. "Ready", "X% Starting Java Language Server"). Surface its message so the UI can show the
     *  server's loading/ready state; also stops LSP4J logging it as an "unsupported notification". */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/status")
    public void languageStatus(LanguageStatus status) {
        if (status != null) {
            String message = status.message == null ? "" : status.message.strip();
            String type = status.type == null ? "" : status.type;
            if (type.equalsIgnoreCase("Starting")) {
                lastStarting = message;
            } else if (type.toLowerCase(java.util.Locale.ROOT).contains("ready")) {
                saidReady = true;
            } else if (type.equalsIgnoreCase("Error") && !saidReady && !message.isEmpty()) {
                startupError = firstLine(message); // an import that threw, then "ready" (thirteenth review CK2)
            }
            onStatus.accept(type, message);
        }
    }

    /** jdtls's last "Starting" line, and whether it has said itself that it is ready (its import done). */
    private volatile @Nullable String lastStarting;

    private volatile boolean saidReady;

    private volatile @Nullable String startupError;

    /** The first line of a {@code language/status} Error jdtls sent before it said it is ready, or null. */
    @Nullable
    String startupError() {
        return startupError;
    }

    /**
     * Whether jdtls is still importing: its handshake is done, but it has not said it is ready. A window that joins
     * now is told it is starting, not ready (eighth review CQ1).
     */
    boolean stillImporting() {
        return LspServerRegistry.JAVA_SERVER_ID.equals(serverId()) && !saidReady;
    }

    @Nullable
    String lastStarting() {
        return lastStarting;
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
                onStatus.accept("ProgressEnd", endText(report.status));
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

    /**
     * A JDT project-model event. Editora refreshes the project from diagnostics and watched files; the one it acts on
     * is SourceInvalidated, a library that got sources after its class file was read, whose open tabs then show them.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/eventNotification")
    public void languageEventNotification(LanguageEventNotification event) {
        java.util.Set<String> roots =
                event == null ? java.util.Set.of() : JavaLibrarySources.invalidatedRoots(event.eventType, event.data);
        if (!roots.isEmpty()) {
            onSourceInvalidated.accept(roots);
        }
    }

    /** Told (on the reader's thread) of the libraries whose sources jdtls attached. */
    private volatile java.util.function.Consumer<java.util.Set<String>> onSourceInvalidated = roots -> {};

    void setOnSourceInvalidated(java.util.function.Consumer<java.util.Set<String>> handler) {
        onSourceInvalidated = handler;
    }

    /**
     * JDT's actionable message: its commands become the buttons of a non-modal question, and the one chosen is
     * run through {@code workspace/executeCommand}. Only commands the server itself executes are offered — some
     * name client commands of VS Code's extension ("learn more" pages, settings editors) that mean nothing
     * here. Without a command to offer, or without a window to ask in, the message is echoed as before. Before
     * this, "A build file was modified. Do you want to synchronize the Java classpath/configuration?" could not
     * be answered (Java review C1).
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("language/actionableNotification")
    public void languageActionableNotification(LanguageActionableNotification notification) {
        if (notification == null || notification.message == null || notification.message.isBlank()) {
            return;
        }
        String message = notification.message.strip();
        List<org.eclipse.lsp4j.Command> runnable = new ArrayList<>();
        java.util.Set<String> executable = executableCommands();
        if (notification.commands != null) {
            for (org.eclipse.lsp4j.Command c : notification.commands) {
                if (c != null
                        && c.getTitle() != null
                        && c.getCommand() != null
                        && (executable.contains(c.getCommand()) || clientRuns(c.getCommand()))) {
                    runnable.add(c);
                }
            }
        }
        LspClientUi ui = clientUi();
        if (ui == null || runnable.isEmpty() || disposed) {
            onStatus.accept("Message", message);
            return;
        }
        List<String> titles =
                runnable.stream().map(org.eclipse.lsp4j.Command::getTitle).toList();
        ui.chooseAction(
                        serverId,
                        notification.severity == null ? org.eclipse.lsp4j.MessageType.Info : notification.severity,
                        message,
                        titles)
                .thenAccept(chosen -> {
                    if (chosen == null || disposed) {
                        return;
                    }
                    for (org.eclipse.lsp4j.Command c : runnable) {
                        if (chosen.equals(c.getTitle())) {
                            List<Object> args = c.getArguments() == null ? List.of() : c.getArguments();
                            if (clientRuns(c.getCommand())) {
                                ui.clientCommand(serverId, c.getCommand(), args);
                            } else {
                                executeCommand(c.getCommand(), args);
                            }
                            return;
                        }
                    }
                });
    }

    /**
     * lemminx's {@code xml/actionableNotification} (declared by {@code actionableNotificationSupport}): a message
     * with commands to offer as buttons — the same shape as jdtls's, answered the same way (XML review D6).
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonNotification("xml/actionableNotification")
    public void xmlActionableNotification(LanguageActionableNotification notification) {
        languageActionableNotification(notification);
    }

    /**
     * lemminx's {@code xml/executeClientCommand}: an editor action the server (or one of its extensions) asks for,
     * carried out by the window when it is one of {@link XmlServer#CLIENT_COMMANDS}; any other is answered null, as
     * the protocol allows a client that does not know it (XML review D6). Before this the request failed with
     * "method not found".
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("xml/executeClientCommand")
    public CompletableFuture<Object> xmlExecuteClientCommand(org.eclipse.lsp4j.ExecuteCommandParams params) {
        LspClientUi ui = clientUi();
        if (ui == null || disposed || params == null || params.getCommand() == null) {
            return CompletableFuture.completedFuture(null);
        }
        return ui.clientCommand(
                        serverId,
                        params.getCommand(),
                        params.getArguments() == null ? List.of() : List.copyOf(params.getArguments()))
                .exceptionally(t -> null);
    }

    /** Whether {@code command} is one the window carries out for this server (an XML client command). */
    private boolean clientRuns(@Nullable String command) {
        return XmlServerSettings.isLemminx(serverId) && command != null && XmlServer.CLIENT_COMMANDS.contains(command);
    }

    /** The commands the server says it executes ({@code executeCommandProvider}). */
    private java.util.Set<String> executableCommands() {
        ServerCapabilities c = capabilities;
        if (c == null
                || c.getExecuteCommandProvider() == null
                || c.getExecuteCommandProvider().getCommands() == null) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(c.getExecuteCommandProvider().getCommands());
    }

    /** jdtls's {@code java.action.organizeImports.chooseImports}: which class each ambiguous name means. */
    static final String CHOOSE_IMPORTS = "java.action.organizeImports.chooseImports";

    /**
     * The documents (wire URIs) whose {@link #CHOOSE_IMPORTS} is answered with no choice: an Organize Imports run on
     * save. Per document, not per session: another file's explicit Organize Imports in the same moment — in
     * another window, since a Java server is shared — still asks (re-review AN8).
     */
    final java.util.Set<String> quietImportChoices = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** jdtls asks this at every start and expects a list of the bundles to reload; it has none to reload here. */
    static final String RELOAD_BUNDLES = "_java.reloadBundles.command";

    /**
     * {@code workspace/executeClientCommand}: a command jdtls asks the client to carry out while it waits for the
     * answer (declared by {@code executeClientCommandSupport}). {@link #CHOOSE_IMPORTS} asks, for each simple name
     * with several candidates, which class is meant; the answer is the chosen candidates, and a name the user
     * skips is left unimported while the rest still applies. Before this the request failed, jdtls cancelled the
     * whole Organize Imports, and nothing was organized (Java review A3). Any other command is answered null.
     */
    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("workspace/executeClientCommand")
    public CompletableFuture<Object> executeClientCommand(org.eclipse.lsp4j.ExecuteCommandParams params) {
        LspClientUi ui = clientUi();
        if (params != null && RELOAD_BUNDLES.equals(params.getCommand())) {
            return CompletableFuture.completedFuture(List.of()); // null logged an error in jdtls's log (CN6)
        }
        if (params == null || !CHOOSE_IMPORTS.equals(params.getCommand()) || ui == null || disposed) {
            return CompletableFuture.completedFuture(null);
        }
        List<Object> args = params.getArguments();
        com.google.gson.JsonElement doc = args == null || args.isEmpty() ? null : LspManager.asJson(args.get(0));
        if (doc != null && doc.isJsonPrimitive() && quietImportChoices.contains(doc.getAsString())) {
            return CompletableFuture.completedFuture(List.of()); // on save: an ambiguous name is skipped, not asked
        }
        List<ImportChoice> choices = ImportChoice.of(params.getArguments());
        List<Object> chosen = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ImportChoice choice : choices) {
            chain = chain.thenCompose(ignored -> disposed
                    ? CompletableFuture.completedFuture(null)
                    : asking(ui.chooseImport(serverId, choice.simpleName(), choice.line(), choice.items()))
                            .thenAccept(id -> {
                                Object candidate =
                                        id == null ? null : choice.candidates().get(id);
                                if (candidate != null) {
                                    chosen.add(candidate);
                                }
                            }));
        }
        CompletableFuture<Object> answer = chain.handle((ignored, error) -> List.copyOf(chosen));
        // The server gave up on the request that asked: the picker still open asks for nothing (DV1).
        answer.whenComplete((value, error) -> {
            if (answer.isCancelled()) {
                closeQuestions();
            }
        });
        return answer;
    }

    /**
     * One name {@link #CHOOSE_IMPORTS} asks about, read from jdtls's {@code ImportSelection}
     * ({@code {candidates: [{fullyQualifiedName, id}], range}}): the candidates by id, as the server sent them.
     */
    record ImportChoice(String simpleName, int line, List<LspClientUi.PickItem> items, Map<String, Object> candidates) {

        static List<ImportChoice> of(@Nullable List<Object> arguments) {
            List<ImportChoice> out = new ArrayList<>();
            if (arguments == null || arguments.size() < 2) {
                return out;
            }
            com.google.gson.JsonElement selections = LspManager.asJson(arguments.get(1));
            if (selections == null || !selections.isJsonArray()) {
                return out;
            }
            for (com.google.gson.JsonElement e : selections.getAsJsonArray()) {
                if (!e.isJsonObject() || !e.getAsJsonObject().has("candidates")) {
                    continue;
                }
                com.google.gson.JsonObject selection = e.getAsJsonObject();
                List<LspClientUi.PickItem> items = new ArrayList<>();
                Map<String, Object> byId = new java.util.LinkedHashMap<>();
                String simple = "";
                for (com.google.gson.JsonElement c : selection.getAsJsonArray("candidates")) {
                    if (!c.isJsonObject() || !c.getAsJsonObject().has("fullyQualifiedName")) {
                        continue;
                    }
                    String fqn = c.getAsJsonObject().get("fullyQualifiedName").getAsString();
                    String id = c.getAsJsonObject().has("id")
                            ? c.getAsJsonObject().get("id").getAsString()
                            : fqn;
                    int dot = fqn.lastIndexOf('.');
                    simple = dot < 0 ? fqn : fqn.substring(dot + 1);
                    items.add(new LspClientUi.PickItem(id, fqn, dot < 0 ? null : fqn.substring(0, dot), null));
                    byId.put(id, c);
                }
                int line = 0;
                com.google.gson.JsonElement range = selection.get("range");
                if (range != null
                        && range.isJsonObject()
                        && range.getAsJsonObject().has("start")) {
                    line = range.getAsJsonObject()
                            .getAsJsonObject("start")
                            .get("line")
                            .getAsInt();
                }
                if (!items.isEmpty()) {
                    out.add(new ImportChoice(simple, line, List.copyOf(items), Map.copyOf(byId)));
                }
            }
            return out;
        }
    }

    /** Payload of JDT LS's {@code language/status} notification (deserialized by LSP4J's Gson). */
    public static final class LanguageStatus {
        public @Nullable String type;
        public @Nullable String message;
    }

    /** Payload of JDT LS's {@code language/progressReport} notification. */
    public static final class LanguageProgressReport {
        public @Nullable String id;
        public @Nullable String task;
        public @Nullable String subTask;
        public @Nullable String status;
        public int totalWork;
        public int workDone;
        public boolean complete;
    }

    /** Payload of JDT LS's {@code language/eventNotification}; data remains intentionally opaque. */
    public static final class LanguageEventNotification {
        public @Nullable String type;
        /** jdtls's {@code EventType}, sent as its number ({@code 500}: SourceInvalidated). */
        public @Nullable Object eventType;

        public @Nullable Object data;
    }

    /** Payload of JDT LS's {@code language/actionableNotification}. */
    public static final class LanguageActionableNotification {
        public org.eclipse.lsp4j.@Nullable MessageType severity;
        public @Nullable String message;
        public @Nullable Object data;
        public @Nullable List<org.eclipse.lsp4j.Command> commands;
    }
}
