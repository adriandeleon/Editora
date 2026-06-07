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
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
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
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import com.editora.process.ProcessRunner;

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

    LanguageServerSession(LspServerRegistry.ServerSpec spec, Path root,
            Consumer<PublishDiagnosticsParams> onDiagnostics,
            java.util.function.BiConsumer<String, String> onStatus) {
        this.command = spec.command();
        this.root = root;
        this.onDiagnostics = onDiagnostics;
        this.onStatus = onStatus == null ? (t, m) -> { } : onStatus;
    }

    /** Launches the server process + LSP4J client and sends {@code initialize}. Returns false on failure. */
    synchronized boolean start() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
            pb.directory(root.toFile());
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
        ip.setWorkspaceFolders(List.of(new WorkspaceFolder(uri, root.getFileName().toString())));
        ip.setCapabilities(clientCapabilities());
        server.initialize(ip).thenAccept(result -> {
            capabilities = result.getCapabilities();
            server.initialized(new InitializedParams());
            List<Runnable> toRun;
            synchronized (this) {
                initialized = true;
                toRun = new ArrayList<>(pending);
                pending.clear();
            }
            toRun.forEach(Runnable::run);
        }).exceptionally(t -> {
            LOG.log(Level.WARNING, "initialize failed", t);
            return null;
        });
    }

    private static ClientCapabilities clientCapabilities() {
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new SynchronizationCapabilities(false, false, true));
        td.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        td.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true)));
        td.setHover(new HoverCapabilities());
        td.setDefinition(new DefinitionCapabilities());
        td.setReferences(new ReferencesCapabilities());
        ClientCapabilities cc = new ClientCapabilities();
        cc.setTextDocument(td);
        return cc;
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
        whenReady(() -> server.getTextDocumentService().didOpen(
                new DidOpenTextDocumentParams(new TextDocumentItem(uri, languageId, 1, text))));
    }

    void didChange(String uri, String text) {
        int version = versions.merge(uri, 1, Integer::sum);
        whenReady(() -> server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version),
                List.of(new TextDocumentContentChangeEvent(text)))));
    }

    void didSave(String uri) {
        whenReady(() -> server.getTextDocumentService().didSave(
                new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri))));
    }

    void didClose(String uri) {
        versions.remove(uri);
        whenReady(() -> server.getTextDocumentService().didClose(
                new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri))));
    }

    boolean isOpen(String uri) {
        return versions.containsKey(uri);
    }

    // --- Requests (return raw LSP futures; LspManager marshals to the FX thread) ----------------

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return server.getTextDocumentService()
                .completion(new CompletionParams(new TextDocumentIdentifier(uri), pos));
    }

    CompletableFuture<Hover> hover(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(null);
        }
        return server.getTextDocumentService()
                .hover(new HoverParams(new TextDocumentIdentifier(uri), pos));
    }

    CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(Either.forLeft(List.of()));
        }
        return server.getTextDocumentService()
                .definition(new DefinitionParams(new TextDocumentIdentifier(uri), pos));
    }

    CompletableFuture<List<? extends Location>> references(String uri, Position pos) {
        if (!ready()) {
            return CompletableFuture.completedFuture(List.of());
        }
        ReferenceParams params = new ReferenceParams(new TextDocumentIdentifier(uri), pos,
                new ReferenceContext(true));
        return server.getTextDocumentService().references(params);
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
            process.destroy();
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
