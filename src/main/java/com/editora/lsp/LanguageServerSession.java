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
import org.eclipse.lsp4j.ExecuteCommandParams;
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
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
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
    private final List<Runnable> pending = new ArrayList<>();
    private final AtomicInteger nextVersion = new AtomicInteger(1);

    private Process process;
    private LanguageServer server;
    private volatile boolean initialized;
    private volatile boolean disposed;
    private ServerCapabilities capabilities;

    LanguageServerSession(
            LspServerRegistry.ServerSpec spec,
            Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus) {
        this(spec, root, onDiagnostics, onStatus, null);
    }

    LanguageServerSession(
            LspServerRegistry.ServerSpec spec,
            Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus,
            Object initializationOptions) {
        this.command = spec.command();
        this.root = root;
        this.onDiagnostics = onDiagnostics;
        this.onStatus = onStatus == null ? (t, m) -> {} : onStatus;
        this.initializationOptions = initializationOptions;
    }

    /** Launches the server process + LSP4J client and sends {@code initialize}. Returns false on failure. */
    synchronized boolean start() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
            pb.directory(root.toFile());
            // Discard the server's stderr. Nothing drains it, so an undrained PIPE (the default) fills
            // its ~64 KB OS buffer on a chatty server (jdtls logs heavily) and the server blocks writing
            // to it — deadlocking mid-startup with no diagnostics ever published. LSP traffic is on stdout.
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            ProcessRunner.applyStandardEnv(pb);
            process = pb.start();
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                    this, process.getInputStream(), process.getOutputStream(), executor, c -> c);
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
                .thenAccept(result -> {
                    capabilities = result.getCapabilities();
                    server.initialized(new InitializedParams());
                    pushConfiguration(); // proactively enable Pyright auto-imports (also answered via configuration())
                    List<Runnable> toRun;
                    synchronized (this) {
                        initialized = true;
                        toRun = new ArrayList<>(pending);
                        pending.clear();
                    }
                    toRun.forEach(Runnable::run);
                    // Signal the UI that the handshake completed so the status-bar loading bar stops. The
                    // jdtls-specific language/status notification (handled below) only fires for JDT LS and only
                    // once a project is ready; this universal signal covers every server — and a clean file that
                    // never publishes a diagnostic — with a null message so it doesn't write to the echo area.
                    onStatus.accept("ServiceReady", null);
                })
                .exceptionally(t -> {
                    LOG.log(Level.WARNING, "initialize failed", t);
                    onStatus.accept("Error", null); // also stop the loading bar on a failed handshake
                    return null;
                });
    }

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
        // Pull diagnostics (LSP 3.17): many modern servers — vscode-html/css/json, etc. — deliver
        // diagnostics only on a textDocument/diagnostic *request* (a diagnosticProvider) instead of
        // pushing publishDiagnostics. Declaring this lets us pull them (see LspManager.pullDiagnostics).
        td.setDiagnostic(new org.eclipse.lsp4j.DiagnosticCapabilities(false));
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        // Declare we answer workspace/configuration — otherwise Pyright never asks for
        // python.analysis.autoImportCompletions and keeps its (off) default, so no auto-imports.
        org.eclipse.lsp4j.WorkspaceClientCapabilities ws = new org.eclipse.lsp4j.WorkspaceClientCapabilities();
        ws.setConfiguration(true);
        ws.setDidChangeConfiguration(new org.eclipse.lsp4j.DidChangeConfigurationCapabilities());
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
    private void whenReady(Runnable action) {
        if (disposed) {
            return;
        }
        synchronized (this) {
            if (!initialized) {
                pending.add(action);
                return;
            }
        }
        action.run();
    }

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
        whenReady(() -> server.getTextDocumentService()
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

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return server.getTextDocumentService().completion(new CompletionParams(new TextDocumentIdentifier(uri), pos));
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

    /** Pull diagnostics ({@code textDocument/diagnostic}) for {@code uri}; null when the session isn't ready. */
    CompletableFuture<org.eclipse.lsp4j.DocumentDiagnosticReport> diagnostic(String uri) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService()
                .diagnostic(new org.eclipse.lsp4j.DocumentDiagnosticParams(new TextDocumentIdentifier(uri)));
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
            // so the next session for the same root can't start (it hangs / never initializes — no
            // diagnostics, no completion). Kill the whole descendant tree (snapshot first, before the
            // root dies and the children reparent).
            java.util.List<ProcessHandle> tree = process.descendants().toList();
            process.destroy();
            tree.forEach(ProcessHandle::destroy);
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
