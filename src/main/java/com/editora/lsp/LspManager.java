package com.editora.lsp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.LspDiagnostic;
import com.editora.process.ProcessRunner;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;

/**
 * UI-facing facade over the LSP layer (mirrors {@code MermaidService}): owns one
 * {@link LanguageServerSession} per workspace root, routes document open/change/save/close from the
 * editor, runs requests (completion/hover/definition/references), and pushes mapped diagnostics back on
 * the JavaFX thread. The controller supplies the workspace root (it knows the active project) and a
 * diagnostics callback. All public methods are safe to call from the FX thread; server I/O happens on
 * LSP4J's threads and results are marshaled via {@link Platform#runLater}.
 */
public final class LspManager {

    /**
     * A resolved navigation target (definition/reference): a file + 0-based line/character. For a definition
     * inside a JDK/dependency class — which jdtls reports under a {@code jdt://contents/...} URI, not a
     * {@code file:} one — {@code file} is null and {@code classFileUri} carries the URI; the coordinator
     * fetches its source via {@link #classFileContents} instead of opening a path (#665).
     */
    public record Target(Path file, int line, int character, String classFileUri) {
        public Target(Path file, int line, int character) {
            this(file, line, character, null);
        }
    }

    /** The scheme jdtls uses for class-file (JDK/dependency) contents — a URI no filesystem can open. */
    private static final String JDT_SCHEME = "jdt://";

    private final BiConsumer<Path, List<LspDiagnostic>> onDiagnostics;
    /** Server status: {@code accept(type, message)} (e.g. "ServiceReady"/"Ready"), posted to the FX thread. */
    private final BiConsumer<String, String> onStatus;
    /** Fired (on the FX thread) when a session died on its own — crash or failed handshake, never a
     *  deliberate dispose/shutdown — so the coordinator can clear its stale diagnostics and auto-restart
     *  it for the affected open buffers (#666). {@code accept(serverId, root)}. */
    private volatile BiConsumer<String, Path> onSessionCrashed = (id, root) -> {};

    private final ExecutorService detectExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsp-detect");
        t.setDaemon(true);
        return t;
    });

    // Server processes are forked here, off the FX thread (#407): a JVM/Node fork+exec + PATH scan + ledger write
    // would otherwise stall the UI on the first open of a language. A (small) daemon pool so several servers can
    // start concurrently rather than one blocking the next.
    private final ExecutorService startExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-start");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, LanguageServerSession> sessionsByRoot = new ConcurrentHashMap<>();
    private final Map<String, LanguageServerSession> sessionByDocUri = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    /** jdtls {@code initializationOptions.bundles} — the java-debug plugin jar(s) that enable debugging.
     *  Applied only to the java server, at {@code initialize}; empty = no debug bundle. */
    private volatile List<String> debugBundles = List.of();
    /** serverId → resolved command (blank-substituted to the default). */
    private final Map<String, String> commands = new ConcurrentHashMap<>();
    /** serverId → last availability probe (invalidated when that server's command changes). */
    private final Map<String, Boolean> availableCache = new ConcurrentHashMap<>();
    /** Base dir for jdtls's per-project {@code -data} workspaces (see {@link #setJdtlsWorkspaceBase}). */
    private volatile Path jdtlsWorkspaceBase;

    public LspManager(BiConsumer<Path, List<LspDiagnostic>> onDiagnostics, BiConsumer<String, String> onStatus) {
        this.onDiagnostics = onDiagnostics == null ? (p, d) -> {} : onDiagnostics;
        this.onStatus = onStatus == null ? (t, m) -> {} : onStatus;
        // Warm the augmented PATH off-thread now (it spawns a login shell to recover nvm/fnm/asdf dirs):
        // server detection and the FX-thread session start both read it, and this caches the result
        // before either needs it so neither blocks.
        detectExec.submit(ProcessRunner::augmentedPath);
    }

    /** Updates the feature flag + each server's command ({@code serverId → command}); a changed command
     *  invalidates that server's cached probe. */
    public void configure(boolean enabled, Map<String, String> serverCommands) {
        if (serverCommands != null) {
            serverCommands.forEach(this::putCommand);
        }
        this.enabled = enabled;
        if (!enabled) {
            shutdownAll();
        }
    }

    private void putCommand(String serverId, String command) {
        String def = LspServerRegistry.defaultCommandFor(serverId);
        String cmd = command == null || command.isBlank() ? def : command;
        String prev = commands.put(serverId, cmd);
        if (!cmd.equals(prev)) {
            availableCache.remove(serverId); // command changed → re-probe on next detect
            if (prev != null) {
                // …and the session running the OLD command must go. The session key is (serverId, root) — the
                // command isn't part of it — so without this the stale session is handed straight back:
                // isManaged() stays true so the buffer is never re-opened, the new command never runs, and the
                // old process leaks. Meanwhile the Settings row re-probes the new command and turns green, so
                // it looks like it applied.
                shutdownServer(serverId);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Clears the cached per-server availability so the next {@link #detect} re-probes — used after an
     *  in-app install drops a server onto PATH / into the config dir without changing its command. */
    public void invalidateDetection() {
        availableCache.clear();
    }

    /**
     * Base directory under which jdtls gets a dedicated per-project workspace (via {@code -data}). Without
     * this, jdtls falls back to a single shared default workspace that deadlocks on its {@code .lock} when
     * two project roots — or a leaked previous run — contend for it, so the server never finishes
     * {@code initialize} (loading bar spins forever, no completion). Injected once at startup.
     */
    public void setJdtlsWorkspaceBase(Path base) {
        this.jdtlsWorkspaceBase = base;
    }

    /** Sets the java-debug plugin bundle jar(s) injected into the java server's {@code initialize} so
     *  jdtls can debug. Takes effect on the next java session start — call {@link #restartServer} after
     *  changing it to reload a running jdtls. */
    public void setDebugBundles(List<String> jars) {
        this.debugBundles = jars == null ? List.of() : List.copyOf(jars);
    }

    /**
     * Runs a server-side command ({@code workspace/executeCommand}) on {@code file}'s session — used by the
     * DAP layer to drive jdtls's debug commands. The result (or error) is delivered on the FX thread; a
     * 30 s timeout guards against a server that never answers.
     */
    public void executeCommand(Path file, String command, List<Object> args, BiConsumer<Object, Throwable> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null, new IllegalStateException("no language server for file")));
            return;
        }
        s.executeCommand(command, args)
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(r, e)));
    }

    /** Restarts {@code serverId} (dispose + drop its cached sessions) so the next {@code openDocument}
     *  re-initializes it — e.g. to reload jdtls with the java-debug bundle. The controller re-opens the
     *  affected buffers afterwards. */
    public void restartServer(String serverId) {
        shutdownServer(serverId);
    }

    /** Probes whether {@code serverId}'s configured command resolves to an executable; cached, off-thread. */
    public void detect(String serverId, Consumer<Boolean> onResult) {
        Boolean hit = availableCache.get(serverId);
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        List<String> command = LspServerRegistry.commandFor(serverId, commands);
        detectExec.submit(() -> {
            boolean ok = available(command);
            availableCache.put(serverId, ok);
            Platform.runLater(() -> onResult.accept(ok));
        });
    }

    /** True if the command's executable resolves (an absolute path that exists, or found on the PATH). */
    static boolean available(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String exe = command.get(0);
        if (exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
            return Files.isExecutable(Path.of(exe));
        }
        // resolveExecutable rewrites a bare name to an absolute path when found on the augmented PATH.
        return !ProcessRunner.resolveExecutable(command).get(0).equals(exe);
    }

    // --- Document lifecycle (called from EditorBuffer hooks via the controller) -----------------

    /**
     * Opens {@code file} on the server that serves {@code routeLanguageId}, starting that server if needed.
     * The {@code routeLanguageId} selects the server + session (e.g. {@code maven-pom} routes a pom.xml to the
     * Maven server); the id actually sent to the server in {@code didOpen} is
     * {@link LspServerRegistry#protocolLanguageId} (so a {@code maven-pom} route opens as {@code xml}).
     */
    public void openDocument(Path file, Path root, String routeLanguageId, String text) {
        if (!enabled || file == null || root == null || !LspServerRegistry.isSupported(routeLanguageId)) {
            return;
        }
        LanguageServerSession session = sessionForRoot(root, routeLanguageId);
        if (session == null) {
            return;
        }
        cancelEviction(session); // a reopened document keeps an idle session alive (#669)
        String uri = file.toUri().toString();
        sessionByDocUri.put(uri, session);
        session.didOpen(uri, LspServerRegistry.protocolLanguageId(routeLanguageId), text);
    }

    public void changeDocument(Path file, String text) {
        LanguageServerSession s = sessionFor(file);
        if (s != null) {
            s.didChange(uri(file), text);
        }
    }

    public void saveDocument(Path file) {
        LanguageServerSession s = sessionFor(file);
        if (s != null) {
            s.didSave(uri(file));
        }
    }

    public void closeDocument(Path file) {
        if (file == null) {
            return;
        }
        String uri = uri(file);
        LanguageServerSession s = sessionByDocUri.remove(uri);
        rawDiagnostics.remove(uri); // open-documents-only retention (#670); the symlink-form key, if any,
        try { //                       is dropped too so a closed file can't pin its diagnostics
            rawDiagnostics.remove(file.toRealPath().toUri().toString());
        } catch (java.io.IOException | RuntimeException ignored) {
            // file gone/remote — nothing more to drop
        }
        if (s != null) {
            s.didClose(uri);
            maybeScheduleEviction(s);
        }
    }

    // --- Idle-session eviction (#669) -----------------------------------------------------------

    /**
     * How long a session with no open documents is kept alive before being disposed. Closing the last file
     * of a root used to leave its server running until window close — browsing Java files across N unrelated
     * roots accumulated N live jdtls JVMs (hundreds of MB each plus Eclipse indexing). The grace period means
     * tab churn within one project doesn't cold-restart the server (and jdtls's persisted {@code -data} index
     * makes an eventual cold start cheap-ish).
     */
    static final java.time.Duration IDLE_EVICTION_GRACE = java.time.Duration.ofMinutes(3);

    /** Shared timer for idle-session eviction (one daemon thread app-wide; the decision runs on FX). */
    private static final java.util.concurrent.ScheduledExecutorService evictExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lsp-evict");
                t.setDaemon(true);
                return t;
            });

    /** Session key → its pending idle-eviction timer (cancelled when a document reopens on that session). */
    private final Map<String, java.util.concurrent.ScheduledFuture<?>> pendingEvictions = new ConcurrentHashMap<>();

    /** The {@code sessionsByRoot} key currently holding {@code session}, or null (it was dropped). */
    private String keyOf(LanguageServerSession session) {
        for (var e : sessionsByRoot.entrySet()) {
            if (e.getValue() == session) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Starts the idle-eviction timer for {@code session} if it no longer serves any open document. */
    private void maybeScheduleEviction(LanguageServerSession session) {
        if (sessionByDocUri.containsValue(session)) {
            return; // still serving other open documents
        }
        String key = keyOf(session);
        if (key == null) {
            return; // already dropped
        }
        var prev = pendingEvictions.put(
                key,
                evictExec.schedule(
                        // The decision runs on the FX thread — openDocument/closeDocument are FX-thread calls,
                        // so re-checking there means no eviction can race a concurrent re-open.
                        () -> Platform.runLater(() -> evictIfIdle(key, session)),
                        IDLE_EVICTION_GRACE.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS));
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** Cancels a pending idle eviction for {@code session} (a document just [re]opened on it). */
    private void cancelEviction(LanguageServerSession session) {
        String key = keyOf(session);
        if (key != null) {
            var f = pendingEvictions.remove(key);
            if (f != null) {
                f.cancel(false);
            }
        }
    }

    /** FX thread: disposes {@code session} if it is still cached under {@code key} and still document-less. */
    private void evictIfIdle(String key, LanguageServerSession session) {
        pendingEvictions.remove(key);
        if (sessionsByRoot.get(key) != session || sessionByDocUri.containsValue(session)) {
            return; // replaced, already dropped, or a document reopened during the grace period
        }
        sessionsByRoot.remove(key, session);
        session.dispose();
        releaseJdtlsWorkspace(session);
    }

    public boolean isManaged(Path file) {
        // A remote (SFTP) file is never LSP-managed; bail before uri(), whose toUri() throws for such paths.
        return file != null && com.editora.vfs.Vfs.isLocal(file) && sessionByDocUri.containsKey(uri(file));
    }

    /** The server id currently managing {@code file}, or null if it is not open on any server. Lets the
     *  coordinator detect a server change (e.g. a pom.xml moving to lemminx-maven) and close+reopen. */
    public String managedServerId(Path file) {
        if (file == null || !com.editora.vfs.Vfs.isLocal(file)) {
            return null;
        }
        LanguageServerSession s = sessionByDocUri.get(uri(file));
        return s == null ? null : s.serverId();
    }

    /**
     * Separator between the two halves of a session key. It must be a character that cannot occur in a server
     * id or a URI, so the {@code startsWith(serverId + SEP)} scan in {@link #shutdownServer} can't match the
     * wrong session.
     *
     * <p>Written as an escape, not as a raw NUL byte in the source: a literal control character made this
     * whole file <b>binary</b> to grep/rg (they skip it silently), which is exactly how the key here and the
     * prefix in {@code shutdownServer} came to disagree — one used the NUL, the other a space, so
     * {@code shutdownServer} never matched a single session and quietly did nothing at all.
     */
    private static final String SESSION_KEY_SEP = "\u0000";

    /** The {@code sessionsByRoot} key: one session per (server, root). The command is deliberately not part
     *  of it — {@link #putCommand} disposes the session when the command changes. */
    static String sessionKey(String serverId, Path root) {
        return serverId + SESSION_KEY_SEP + root.toUri();
    }

    /** The prefix every {@link #sessionKey} for {@code serverId} starts with — how {@link #shutdownServer}
     *  finds that server's sessions. Must agree with {@link #sessionKey}; unit-tested, because it silently
     *  did not. */
    static String sessionKeyPrefix(String serverId) {
        return serverId + SESSION_KEY_SEP;
    }

    private LanguageServerSession sessionForRoot(Path root, String languageId) {
        String serverId = LspServerRegistry.serverIdFor(languageId);
        if (serverId == null) {
            return null;
        }
        // Key by (serverId, root) — not languageId — so js/ts/jsx/tsx in one root share one tsserver.
        String key = sessionKey(serverId, root);
        LanguageServerSession cached = sessionsByRoot.get(key);
        if (cached != null) {
            return cached;
        }
        LspServerRegistry.ServerSpec spec = LspServerRegistry.specFor(languageId, commands);
        if (spec == null) {
            return null;
        }
        // jdtls: give each project root its own Eclipse workspace (-data) so it never deadlocks on the shared
        // default workspace's .lock. Created on demand; jdtls reuses it across sessions (its index persists).
        // The claim registry (static, spanning windows) suffixes the dir when another window's live session
        // already holds it — see claimJdtlsWorkspaceName (#668).
        String claimedWorkspace = null;
        if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && jdtlsWorkspaceBase != null) {
            claimedWorkspace = claimJdtlsWorkspaceName(LspServerRegistry.workspaceDirName(root));
            Path ws = jdtlsWorkspaceBase.resolve(claimedWorkspace);
            try {
                Files.createDirectories(ws);
                spec = LspServerRegistry.withDataDir(spec, ws.toString());
            } catch (java.io.IOException e) {
                // Couldn't create the workspace dir — fall back to the default (better than not starting).
                releaseJdtlsWorkspaceName(claimedWorkspace);
                claimedWorkspace = null;
            }
        }
        Object initOptions = initOptionsFor(serverId, debugBundles);
        LanguageServerSession session = new LanguageServerSession(
                spec,
                root,
                this::onPublishDiagnostics,
                (type, msg) -> Platform.runLater(() -> onStatus.accept(type, msg)),
                initOptions);
        // Drop the session the moment it can no longer serve requests — the process died on its own, or the
        // handshake failed/timed out. Otherwise it stays cached looking alive: isManaged() keeps returning true
        // (so the re-open guard never restarts it), every request fails into an empty result, and LSP is silently
        // dead for the rest of the session while the status bar still names the server.
        session.setOnDead(() -> dropSession(key, session));
        session.setOnApplyEdit(this::onServerApplyEdit); // a server-side quick fix lands its edits via us (#670)
        if (claimedWorkspace != null) {
            jdtlsWorkspaceBySession.put(session, claimedWorkspace);
        }
        LanguageServerSession prev = sessionsByRoot.putIfAbsent(key, session);
        if (prev != null) {
            releaseJdtlsWorkspace(session); // this un-started session's claim must not leak
            return prev; // another open created it first (rare) — use that one; this un-started session is dropped
        }
        // Fork + connect + initialize OFF the FX thread (#407): the process fork + PATH scan + ledger write would
        // otherwise freeze the UI for the first open of a language. The session is already cached, so the caller's
        // didOpen queues (LanguageServerSession.whenReady) until the async initialize completes; a failed start
        // uncaches it so a later open retries.
        startExec.execute(() -> {
            if (!session.start()) {
                dropSession(key, session);
            }
        });
        return session;
    }

    /** Sets the session-crashed callback (see {@link #onSessionCrashed}); marshaled to the FX thread. */
    public void setOnSessionCrashed(BiConsumer<String, Path> callback) {
        this.onSessionCrashed = callback == null ? (id, root) -> {} : callback;
    }

    /**
     * jdtls {@code -data} dirs currently claimed by a live session in <b>any</b> window. {@code LspManager} is
     * per-window, so two windows opening files under the same Java root would otherwise fork two jdtls against
     * <b>one</b> Eclipse workspace — whose {@code .metadata/.lock} admits a single process, wedging the second
     * at {@code initialize} until its 60 s timeout, and every retry the same way (#668). Static so the claim
     * spans windows; released when the owning session drops. In-process only — a second Editora <i>process</i>
     * can still collide (rare, pre-existing).
     */
    private static final java.util.Set<String> claimedJdtlsWorkspaces = ConcurrentHashMap.newKeySet();

    /** This manager's session → the jdtls workspace dir name it claimed (released on drop/shutdown). */
    private final Map<LanguageServerSession, String> jdtlsWorkspaceBySession = new ConcurrentHashMap<>();

    /** Claims the first free workspace dir name for {@code baseName}: the canonical hash dir when free (so
     *  the persisted index is reused), else {@code <base>-2}, {@code -3}, … A suffixed dir costs a second
     *  jdtls index but unwedges the second window. Package-visible for tests. */
    static String claimJdtlsWorkspaceName(String baseName) {
        for (int i = 1; i <= 20; i++) {
            String candidate = workspaceCandidate(baseName, i);
            if (claimedJdtlsWorkspaces.add(candidate)) {
                return candidate;
            }
        }
        // 20 live same-root claims cannot happen in practice; hand out a unique last resort anyway.
        String unique = baseName + "-x" + Long.toHexString(System.nanoTime());
        claimedJdtlsWorkspaces.add(unique);
        return unique;
    }

    /** The {@code attempt}-th candidate dir name for {@code baseName} (1 = the canonical name). Pure. */
    static String workspaceCandidate(String baseName, int attempt) {
        return attempt <= 1 ? baseName : baseName + "-" + attempt;
    }

    /** Releases a claim taken by {@link #claimJdtlsWorkspaceName}. Package-visible for tests. */
    static void releaseJdtlsWorkspaceName(String name) {
        if (name != null) {
            claimedJdtlsWorkspaces.remove(name);
        }
    }

    /** Releases the workspace claim held by {@code session}, if any. */
    private void releaseJdtlsWorkspace(LanguageServerSession session) {
        releaseJdtlsWorkspaceName(jdtlsWorkspaceBySession.remove(session));
    }

    /** Removes a session that can no longer serve requests, so the next open starts a fresh one. */
    private void dropSession(String key, LanguageServerSession session) {
        sessionsByRoot.remove(key, session);
        sessionByDocUri.values().removeIf(s -> s == session);
        releaseJdtlsWorkspace(session);
        // Distinguish a crash from a deliberate teardown: dispose() sets its flag BEFORE killing the
        // process, so a session that is dead but never disposed died on its own (process crash, failed/
        // timed-out handshake). Only that case notifies the coordinator to auto-restart — a shutdownServer/
        // shutdownAll/putCommand teardown, or a start() whose fork failed (dispose()d in its catch), must
        // not re-fork in a loop (#666).
        if (!session.isDisposed()) {
            session.dispose(); // hygiene: stop its executor + untrack the dead process
            Platform.runLater(() -> onSessionCrashed.accept(session.serverId(), session.root()));
        }
    }

    private LanguageServerSession sessionFor(Path file) {
        return file == null ? null : sessionByDocUri.get(uri(file));
    }

    // --- Requests (results marshaled to the FX thread) -----------------------------------------

    public void completion(Path file, int line, int character, Consumer<List<CompletionItem>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.completion(uri(file), new Position(line, character)).whenComplete((result, error) -> {
            List<CompletionItem> items = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    items.addAll(result.getLeft());
                } else if (result.getRight() != null && result.getRight().getItems() != null) {
                    items.addAll(result.getRight().getItems());
                }
            }
            Platform.runLater(() -> cb.accept(items));
        });
    }

    /**
     * Resolves {@code item} (for {@code file}) and delivers its {@code additionalTextEdits} — the extra
     * edits a completion implies, e.g. a TypeScript auto-import's {@code import} line — to {@code cb} on
     * the FX thread (empty list if none or unresolved).
     */
    public void resolveCompletion(Path file, CompletionItem item, Consumer<List<com.editora.editor.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || item == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            List<com.editora.editor.LspTextEdit> edits = new ArrayList<>();
            CompletionItem use = error == null && resolved != null ? resolved : item;
            if (use.getAdditionalTextEdits() != null) {
                for (org.eclipse.lsp4j.TextEdit e : use.getAdditionalTextEdits()) {
                    if (e != null && e.getRange() != null) {
                        edits.add(new com.editora.editor.LspTextEdit(
                                e.getRange().getStart().getLine(),
                                e.getRange().getStart().getCharacter(),
                                e.getRange().getEnd().getLine(),
                                e.getRange().getEnd().getCharacter(),
                                e.getNewText() == null ? "" : e.getNewText()));
                    }
                }
            }
            Platform.runLater(() -> cb.accept(edits));
        });
    }

    /**
     * Resolves the documentation for a completion item (the opaque {@code token} is the LSP
     * {@code CompletionItem} from {@link CompletionMapper}) and delivers its markdown/plain-text body to
     * {@code cb} on the FX thread — {@code null} when there is none. Short-circuits when the item already
     * carries its documentation (many servers send it eagerly), else issues {@code completionItem/resolve}.
     * Drives the IntelliJ-style documentation side-popup.
     */
    public void resolveCompletionDoc(Path file, Object token, Consumer<String> cb) {
        if (!(token instanceof CompletionItem item)) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        String existing = documentationOf(item);
        LanguageServerSession s = sessionFor(file);
        if (existing != null || s == null) {
            Platform.runLater(() -> cb.accept(existing));
            return;
        }
        s.resolveCompletion(item).whenComplete((resolved, error) -> {
            String doc = (error == null && resolved != null) ? documentationOf(resolved) : null;
            Platform.runLater(() -> cb.accept(doc));
        });
    }

    /** The item's documentation as a string (markdown or plain text), or null/blank → null. */
    private static String documentationOf(CompletionItem item) {
        var doc = item.getDocumentation();
        if (doc == null) {
            return null;
        }
        String s = doc.isLeft()
                ? doc.getLeft()
                : (doc.getRight() == null ? null : doc.getRight().getValue());
        return (s == null || s.isBlank()) ? null : s;
    }

    /** True if {@code file}'s server is ready and advertises whole-document formatting. */
    public boolean supportsFormatting(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && formattingProvider(s.capabilities());
    }

    /** True if {@code file}'s server is ready and advertises signature help (#674). */
    public boolean supportsSignatureHelp(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && signatureHelpProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code signatureHelpProvider} (null-safe). */
    static boolean signatureHelpProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        return caps != null && caps.getSignatureHelpProvider() != null;
    }

    /** The signature-help trigger characters {@code file}'s server advertised (usually {@code (} and
     *  {@code ,}), retrigger characters included; empty when not ready / none. */
    public java.util.Set<Character> signatureTriggerCharacters(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? java.util.Set.of() : signatureTriggerCharsOf(s.capabilities());
    }

    /** Pure extraction of signature-help trigger + retrigger characters (null-safe). */
    static java.util.Set<Character> signatureTriggerCharsOf(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null || caps.getSignatureHelpProvider() == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        addFirstChars(out, caps.getSignatureHelpProvider().getTriggerCharacters());
        addFirstChars(out, caps.getSignatureHelpProvider().getRetriggerCharacters());
        return out;
    }

    private static void addFirstChars(java.util.Set<Character> out, List<String> tokens) {
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isEmpty()) {
                    out.add(t.charAt(0));
                }
            }
        }
    }

    /** Requests signature help at a 0-based position; the raw lsp4j result (or null) arrives on the FX
     *  thread — resolved for display via {@link SignatureFormat#resolve}. */
    public void signatureHelp(Path file, int line, int character, Consumer<org.eclipse.lsp4j.SignatureHelp> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.signatureHelp(uri(file), new Position(line, character))
                .whenComplete((r, e) -> Platform.runLater(() -> cb.accept(e == null ? r : null)));
    }

    /** True if {@code file}'s server is ready and advertises document highlight (occurrences — #675). */
    public boolean supportsDocumentHighlight(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && documentHighlightProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentHighlightProvider} (null-safe). */
    static boolean documentHighlightProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentHighlightProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Occurrences of the symbol at a 0-based position, mapped to neutral {@link OccurrenceSpan}s
     *  (Write kind flagged) and delivered on the FX thread — empty when unavailable (#675). */
    public void documentHighlights(
            Path file, int line, int character, Consumer<List<com.editora.editor.OccurrenceSpan>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.documentHighlight(uri(file), new Position(line, character)).whenComplete((result, error) -> {
            List<com.editora.editor.OccurrenceSpan> spans = new ArrayList<>();
            if (error == null && result != null) {
                for (var h : result) {
                    if (h == null || h.getRange() == null) {
                        continue;
                    }
                    var r = h.getRange();
                    spans.add(new com.editora.editor.OccurrenceSpan(
                            r.getStart().getLine(),
                            r.getStart().getCharacter(),
                            r.getEnd().getLine(),
                            r.getEnd().getCharacter(),
                            h.getKind() == org.eclipse.lsp4j.DocumentHighlightKind.Write));
                }
            }
            Platform.runLater(() -> cb.accept(spans));
        });
    }

    // --- Watched files (#677) ------------------------------------------------------------------

    /** An external file change to forward to the language servers. */
    public enum WatchedKind {
        CREATED,
        CHANGED,
        DELETED
    }

    /** One changed file + what happened to it. */
    public record WatchedFile(Path file, WatchedKind kind) {}

    /**
     * Forwards external file changes to every live session whose root contains them
     * ({@code workspace/didChangeWatchedFiles}) — how a git checkout / CLI build / external editor reaches
     * the servers' project models instead of leaving them stale until restart (#677). Callers coalesce
     * bursts (a branch switch touches hundreds of files); one notification per session per batch.
     */
    public void notifyWatchedFiles(List<WatchedFile> changes) {
        if (!enabled || changes == null || changes.isEmpty()) {
            return;
        }
        for (LanguageServerSession s : sessionsByRoot.values()) {
            List<org.eclipse.lsp4j.FileEvent> events = eventsUnderRoot(changes, s.root());
            if (!events.isEmpty()) {
                s.didChangeWatchedFiles(events);
            }
        }
    }

    /** Pure: the {@code FileEvent}s for the changes under {@code root} (path-component containment). */
    static List<org.eclipse.lsp4j.FileEvent> eventsUnderRoot(List<WatchedFile> changes, Path root) {
        Path rootNorm = root.toAbsolutePath().normalize();
        List<org.eclipse.lsp4j.FileEvent> out = new ArrayList<>();
        for (WatchedFile c : changes) {
            if (c == null || c.file() == null) {
                continue;
            }
            Path p = c.file().toAbsolutePath().normalize();
            if (!p.startsWith(rootNorm)) {
                continue;
            }
            var type =
                    switch (c.kind()) {
                        case CREATED -> org.eclipse.lsp4j.FileChangeType.Created;
                        case CHANGED -> org.eclipse.lsp4j.FileChangeType.Changed;
                        case DELETED -> org.eclipse.lsp4j.FileChangeType.Deleted;
                    };
            out.add(new org.eclipse.lsp4j.FileEvent(p.toUri().toString(), type));
        }
        return out;
    }

    /** True if {@code file}'s server is ready and advertises rename (#676). */
    public boolean supportsRename(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && renameProvider(s.capabilities());
    }

    /** True if the server also advertises {@code prepareRename} (validate + placeholder). */
    public boolean supportsPrepareRename(Path file) {
        LanguageServerSession s = sessionFor(file);
        if (s == null || s.capabilities() == null) {
            return false;
        }
        var p = s.capabilities().getRenameProvider();
        return p != null
                && p.isRight()
                && p.getRight() != null
                && Boolean.TRUE.equals(p.getRight().getPrepareProvider());
    }

    /** Pure: whether a server's capabilities include {@code renameProvider} (null-safe, either form). */
    static boolean renameProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getRenameProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** The outcome of {@code prepareRename}: whether renaming here is possible, and the placeholder (the
     *  current name) when the server supplied one — blank means "derive it from the document range". */
    public record RenamePrep(
            boolean allowed, String placeholder, int startLine, int startCol, int endLine, int endCol) {
        static final RenamePrep REFUSED = new RenamePrep(false, "", 0, 0, 0, 0);
    }

    /** Validates a rename at a 0-based position; the result arrives on the FX thread (#676). */
    public void prepareRename(Path file, int line, int character, Consumer<RenamePrep> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(RenamePrep.REFUSED));
            return;
        }
        s.prepareRename(uri(file), new Position(line, character))
                .whenComplete(
                        (r, e) -> Platform.runLater(() -> cb.accept(e != null ? RenamePrep.REFUSED : mapPrepare(r))));
    }

    /** Pure: maps a {@code prepareRename} response (any of its three shapes; null = refused). */
    static RenamePrep mapPrepare(
            org.eclipse.lsp4j.jsonrpc.messages.Either3<
                            org.eclipse.lsp4j.Range,
                            org.eclipse.lsp4j.PrepareRenameResult,
                            org.eclipse.lsp4j.PrepareRenameDefaultBehavior>
                    r) {
        if (r == null) {
            return RenamePrep.REFUSED; // per spec: null/undefined ⇒ rename not valid at this position
        }
        if (r.isFirst() && r.getFirst() != null) {
            var range = r.getFirst();
            return new RenamePrep(
                    true,
                    "",
                    range.getStart().getLine(),
                    range.getStart().getCharacter(),
                    range.getEnd().getLine(),
                    range.getEnd().getCharacter());
        }
        if (r.isSecond() && r.getSecond() != null) {
            var prep = r.getSecond();
            var range = prep.getRange();
            return new RenamePrep(
                    true,
                    prep.getPlaceholder() == null ? "" : prep.getPlaceholder(),
                    range == null ? 0 : range.getStart().getLine(),
                    range == null ? 0 : range.getStart().getCharacter(),
                    range == null ? 0 : range.getEnd().getLine(),
                    range == null ? 0 : range.getEnd().getCharacter());
        }
        if (r.isThird() && r.getThird() != null) {
            return new RenamePrep(true, "", 0, 0, 0, 0); // default behavior: rename the word at the caret
        }
        return RenamePrep.REFUSED;
    }

    /** Renames the symbol at a 0-based position to {@code newName}; the workspace edit applies through the
     *  registered handler and {@code cb} gets overall success on the FX thread (#676). */
    public void rename(Path file, int line, int character, String newName, Consumer<Boolean> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.rename(uri(file), new Position(line, character), newName)
                .whenComplete((edit, e) ->
                        Platform.runLater(() -> cb.accept(e == null && edit != null && applyWorkspaceEditNow(edit))));
    }

    /** True if {@code file}'s server is ready and advertises code actions (quick fixes — #670). */
    public boolean supportsCodeActions(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && codeActionProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code codeActionProvider} (null-safe). */
    static boolean codeActionProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getCodeActionProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** Pure: whether a server's capabilities include {@code documentFormattingProvider} (null-safe). */
    static boolean formattingProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /** True if {@code file}'s server is ready and advertises range formatting (for Tab line re-indent). */
    public boolean supportsRangeFormatting(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && rangeFormattingProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentRangeFormattingProvider} (null-safe). */
    static boolean rangeFormattingProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return false;
        }
        var p = caps.getDocumentRangeFormattingProvider();
        return p != null && (p.isRight() || Boolean.TRUE.equals(p.getLeft()));
    }

    /**
     * Requests range formatting ({@code textDocument/rangeFormatting}) over the line range
     * {@code [startLine..endLine]} and delivers the resulting edits to {@code cb} on the FX thread (empty
     * on error / unsupported). Used by Tab to re-indent the current line to the server's convention.
     */
    public void rangeFormatting(
            Path file,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            int tabSize,
            boolean insertSpaces,
            Consumer<List<com.editora.editor.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        org.eclipse.lsp4j.Range range =
                new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        s.rangeFormatting(uri(file), range, new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.editor.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.editor.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /**
     * Requests whole-document formatting ({@code textDocument/formatting}) for {@code file} and delivers the
     * resulting edits to {@code cb} on the FX thread (empty list if the server returns nothing or errors).
     * {@code tabSize}/{@code insertSpaces} are formatter hints (many formatters use their own config).
     */
    public void formatDocument(
            Path file, int tabSize, boolean insertSpaces, Consumer<List<com.editora.editor.LspTextEdit>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.formatting(uri(file), new org.eclipse.lsp4j.FormattingOptions(tabSize, insertSpaces))
                .whenComplete((result, error) -> {
                    List<com.editora.editor.LspTextEdit> edits = new ArrayList<>();
                    if (error == null && result != null) {
                        for (org.eclipse.lsp4j.TextEdit e : result) {
                            if (e != null && e.getRange() != null) {
                                edits.add(new com.editora.editor.LspTextEdit(
                                        e.getRange().getStart().getLine(),
                                        e.getRange().getStart().getCharacter(),
                                        e.getRange().getEnd().getLine(),
                                        e.getRange().getEnd().getCharacter(),
                                        e.getNewText() == null ? "" : e.getNewText()));
                            }
                        }
                    }
                    Platform.runLater(() -> cb.accept(edits));
                });
    }

    /** True if {@code file}'s server is ready and advertises the document-symbol outline (Structure window). */
    public boolean supportsDocumentSymbols(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && documentSymbolProvider(s.capabilities());
    }

    /** Pure: whether a server's capabilities include {@code documentSymbolProvider} (null-safe). */
    static boolean documentSymbolProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
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
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.documentSymbol(uri(file)).whenComplete((result, error) -> {
            List<SymbolNode> symbols = error == null && result != null ? DocumentSymbolMapper.map(result) : List.of();
            Platform.runLater(() -> cb.accept(symbols));
        });
    }

    /** A project-wide symbol match ({@code workspace/symbol}): name + container + lowercased kind + location. */
    public record WorkspaceSymbolMatch(
            String name, String container, String kind, Path file, int line, int character) {}

    /** True if {@code file}'s server is ready and advertises {@code workspace/symbol} (Go to Symbol). */
    public boolean supportsWorkspaceSymbols(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && workspaceSymbolProvider(s.capabilities());
    }

    /** Pure: whether the server advertises {@code workspace/symbol} (Boolean or options form). Null-safe. */
    static boolean workspaceSymbolProvider(org.eclipse.lsp4j.ServerCapabilities caps) {
        return caps != null && eitherTrue(caps.getWorkspaceSymbolProvider());
    }

    /**
     * Runs {@code workspace/symbol} on {@code anchorFile}'s server for {@code query} and delivers the matches
     * on the FX thread. Empty when the file has no server. Handles both the legacy flat {@code SymbolInformation}
     * and the modern {@code WorkspaceSymbol} response shapes (the latter may carry a range-less URI-only
     * location, mapped to line 0).
     */
    public void workspaceSymbols(Path anchorFile, String query, Consumer<List<WorkspaceSymbolMatch>> cb) {
        LanguageServerSession s = sessionFor(anchorFile);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        s.workspaceSymbol(query).whenComplete((result, error) -> {
            List<WorkspaceSymbolMatch> out = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (org.eclipse.lsp4j.SymbolInformation si : result.getLeft()) {
                        addSymbol(out, si.getName(), si.getContainerName(), si.getKind(), si.getLocation());
                    }
                } else if (result.getRight() != null) {
                    for (org.eclipse.lsp4j.WorkspaceSymbol ws : result.getRight()) {
                        addSymbol(out, ws.getName(), ws.getContainerName(), ws.getKind(), ws.getLocation());
                    }
                }
            }
            Platform.runLater(() -> cb.accept(out));
        });
    }

    /** Adds a {@code SymbolInformation} match (its location always carries a range). */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            Location loc) {
        if (loc == null) {
            return;
        }
        Path p = uriToPath(loc.getUri());
        if (p == null) {
            return;
        }
        Position start = loc.getRange() != null ? loc.getRange().getStart() : new Position(0, 0);
        out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, start.getLine(), start.getCharacter()));
    }

    /** Adds a {@code WorkspaceSymbol} match whose location is {@code Either<Location, URI-only>}. */
    private static void addSymbol(
            List<WorkspaceSymbolMatch> out,
            String name,
            String container,
            org.eclipse.lsp4j.SymbolKind kind,
            org.eclipse.lsp4j.jsonrpc.messages.Either<Location, org.eclipse.lsp4j.WorkspaceSymbolLocation> loc) {
        if (loc == null) {
            return;
        }
        if (loc.isLeft()) {
            addSymbol(out, name, container, kind, loc.getLeft());
        } else if (loc.getRight() != null) {
            Path p = uriToPath(loc.getRight().getUri());
            if (p != null) {
                out.add(new WorkspaceSymbolMatch(name, container, kindName(kind), p, 0, 0));
            }
        }
    }

    private static String kindName(org.eclipse.lsp4j.SymbolKind k) {
        return k == null ? "" : k.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** True if {@code file}'s server is ready and advertises range semantic tokens (viewport highlighting). */
    public boolean supportsSemanticTokens(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s != null && semanticTokensProvider(s.capabilities()) != null;
    }

    /**
     * Pure: the server's semantic-tokens registration options if it supports range <em>or</em> full
     * requests (and carries a legend), else {@code null}. Null-safe — gates {@link #requestSemanticTokens},
     * which prefers range (viewport) and falls back to full for range-less servers.
     */
    static org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions semanticTokensProvider(
            org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null) {
            return null;
        }
        var p = caps.getSemanticTokensProvider();
        if (p == null || p.getLegend() == null) {
            return null;
        }
        return (eitherTrue(p.getRange()) || eitherTrue(p.getFull())) ? p : null;
    }

    /** True if an LSP {@code Either<Boolean, ?>} capability is present (right form) or explicitly true. */
    private static boolean eitherTrue(org.eclipse.lsp4j.jsonrpc.messages.Either<Boolean, ?> e) {
        return e != null && (e.isRight() || Boolean.TRUE.equals(e.getLeft()));
    }

    /**
     * Requests semantic tokens over the line window {@code [startLine..endLine]} (inclusive) and delivers
     * the decoded, CSS-classed {@link com.editora.editor.SemanticToken}s to {@code cb} on the FX thread
     * (empty when unsupported / on error). The window bounds cost on large files — the caller passes the
     * visible paragraph range. Decoding uses the server's effective legend, never hardcoded indices.
     */
    public void requestSemanticTokens(
            Path file, int startLine, int endLine, Consumer<List<com.editora.editor.SemanticToken>> cb) {
        LanguageServerSession s = sessionFor(file);
        var prov = s == null ? null : semanticTokensProvider(s.capabilities());
        if (prov == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var legend = prov.getLegend();
        // Prefer a range (viewport) request to bound cost; fall back to a whole-document request for a
        // server that advertises only `full` (no range).
        java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.SemanticTokens> fut;
        if (eitherTrue(prov.getRange())) {
            // [startLine,0) .. [endLine+1,0) covers whole lines startLine..endLine; clamp start to >= 0.
            var range = new org.eclipse.lsp4j.Range(
                    new Position(Math.max(0, startLine), 0), new Position(Math.max(0, endLine) + 1, 0));
            fut = s.semanticTokensRange(uri(file), range);
        } else {
            fut = s.semanticTokensFull(uri(file));
        }
        fut.whenComplete((tokens, error) -> {
            List<com.editora.editor.SemanticToken> out = (error != null || tokens == null)
                    ? List.of()
                    : SemanticTokensDecoder.decode(
                            tokens.getData(), legend.getTokenTypes(), legend.getTokenModifiers());
            Platform.runLater(() -> cb.accept(out));
        });
    }

    public void hover(Path file, int line, int character, Consumer<String> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            cb.accept("");
            return;
        }
        s.hover(uri(file), new Position(line, character)).whenComplete((hover, error) -> {
            String text = error == null ? hoverText(hover) : "";
            Platform.runLater(() -> cb.accept(text));
        });
    }

    public void definition(Path file, int line, int character, Consumer<List<Target>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.definition(uri(file), new Position(line, character)).whenComplete((result, error) -> {
            List<Target> targets = new ArrayList<>();
            if (error == null && result != null) {
                if (result.isLeft()) {
                    for (Location l : result.getLeft()) {
                        var range = l.getRange(); // a non-conforming server can omit the (spec-required) range
                        if (range != null) {
                            addDefinitionTarget(targets, l.getUri(), range.getStart());
                        }
                    }
                } else if (result.getRight() != null) {
                    for (LocationLink ll : result.getRight()) {
                        var range = ll.getTargetSelectionRange() != null
                                ? ll.getTargetSelectionRange()
                                : ll.getTargetRange();
                        if (range != null) {
                            addDefinitionTarget(targets, ll.getTargetUri(), range.getStart());
                        }
                    }
                }
            }
            Platform.runLater(() -> cb.accept(targets));
        });
    }

    // --- Code actions / quick fixes (#670) ------------------------------------------------------

    /** A code action offered by the server: display fields + the opaque lsp4j payload ({@code raw}) handed
     *  back to {@link #applyCodeAction}. {@code kind} is the LSP kind ({@code quickfix}, {@code source.…})
     *  or empty; {@code preferred} marks the server's recommended fix (listed first). */
    public record CodeActionItem(String title, String kind, boolean preferred, Object raw) {}

    /** Last raw published diagnostics per server-reported URI (open documents only — see
     *  {@link #onPublishDiagnostics}); the context a code-action request sends back to the server. */
    private final Map<String, List<org.eclipse.lsp4j.Diagnostic>> rawDiagnostics = new ConcurrentHashMap<>();

    /** The retained raw diagnostics for {@code file}, tolerant of the server having reported a
     *  symlink-resolved URI ({@code /private/tmp} vs {@code /tmp} — the #470 mismatch). */
    private List<org.eclipse.lsp4j.Diagnostic> rawDiagnosticsFor(Path file) {
        List<org.eclipse.lsp4j.Diagnostic> hit = rawDiagnostics.get(uri(file));
        if (hit != null) {
            return hit;
        }
        try {
            return rawDiagnostics.getOrDefault(file.toRealPath().toUri().toString(), List.of());
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Requests the code actions available over {@code [startLine:startChar .. endLine:endChar]} (0-based),
     * sending the range-overlapping known diagnostics as context, and delivers them on the FX thread —
     * preferred actions first, disabled ones dropped.
     */
    public void codeActions(
            Path file, int startLine, int startChar, int endLine, int endChar, Consumer<List<CodeActionItem>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(List.of()));
            return;
        }
        var range = new org.eclipse.lsp4j.Range(new Position(startLine, startChar), new Position(endLine, endChar));
        List<org.eclipse.lsp4j.Diagnostic> context = diagnosticsOverlapping(rawDiagnosticsFor(file), range);
        s.codeAction(uri(file), range, context).whenComplete((result, error) -> {
            List<CodeActionItem> items = new ArrayList<>();
            if (error == null && result != null) {
                for (var either : result) {
                    if (either == null) {
                        continue;
                    }
                    if (either.isLeft() && either.getLeft() != null) {
                        var cmd = either.getLeft();
                        items.add(new CodeActionItem(cmd.getTitle() == null ? "" : cmd.getTitle(), "", false, cmd));
                    } else if (either.isRight() && either.getRight() != null) {
                        var action = either.getRight();
                        if (action.getDisabled() != null) {
                            continue; // the server says it doesn't apply here
                        }
                        items.add(new CodeActionItem(
                                action.getTitle() == null ? "" : action.getTitle(),
                                action.getKind() == null ? "" : action.getKind(),
                                Boolean.TRUE.equals(action.getIsPreferred()),
                                action));
                    }
                }
                items.sort((a, b) -> Boolean.compare(b.preferred(), a.preferred())); // preferred first (stable)
            }
            Platform.runLater(() -> cb.accept(items));
        });
    }

    /** Pure: the diagnostics whose range overlaps {@code range} (what a code-action context should carry). */
    static List<org.eclipse.lsp4j.Diagnostic> diagnosticsOverlapping(
            List<org.eclipse.lsp4j.Diagnostic> all, org.eclipse.lsp4j.Range range) {
        List<org.eclipse.lsp4j.Diagnostic> out = new ArrayList<>();
        for (var d : all) {
            if (d != null && d.getRange() != null && rangesOverlap(d.getRange(), range)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean rangesOverlap(org.eclipse.lsp4j.Range a, org.eclipse.lsp4j.Range b) {
        return comparePositions(a.getStart(), b.getEnd()) <= 0 && comparePositions(b.getStart(), a.getEnd()) <= 0;
    }

    private static int comparePositions(Position a, Position b) {
        int byLine = Integer.compare(a.getLine(), b.getLine());
        return byLine != 0 ? byLine : Integer.compare(a.getCharacter(), b.getCharacter());
    }

    /**
     * Applies a picked {@link CodeActionItem#raw()}: an inline {@code edit} applies directly; an edit-less
     * action is first resolved ({@code codeAction/resolve}); a {@code command} (bare, or riding the action)
     * runs via {@code workspace/executeCommand} — whose server-side handler typically answers with a
     * {@code workspace/applyEdit} back to us, which the registered apply-edit handler lands in the editor.
     * {@code cb} gets overall success on the FX thread.
     */
    public void applyCodeAction(Path file, Object raw, Consumer<Boolean> cb) {
        if (raw instanceof org.eclipse.lsp4j.Command cmd) {
            executeCommand(file, cmd.getCommand(), cmd.getArguments(), (r, e) -> cb.accept(e == null));
            return;
        }
        if (!(raw instanceof org.eclipse.lsp4j.CodeAction action)) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        if (action.getEdit() != null) {
            Platform.runLater(() -> finishCodeAction(file, action, action.getEdit(), cb));
            return;
        }
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            Platform.runLater(() -> cb.accept(false));
            return;
        }
        s.resolveCodeAction(action).whenComplete((resolved, error) -> {
            org.eclipse.lsp4j.CodeAction use = error == null && resolved != null ? resolved : action;
            Platform.runLater(() -> finishCodeAction(file, use, use.getEdit(), cb));
        });
    }

    /** FX thread: applies {@code edit} (if any), then runs the action's trailing {@code command} (if any). */
    private void finishCodeAction(
            Path file,
            org.eclipse.lsp4j.CodeAction action,
            org.eclipse.lsp4j.WorkspaceEdit edit,
            Consumer<Boolean> cb) {
        boolean editOk = true;
        if (edit != null) {
            editOk = applyWorkspaceEditNow(edit);
        }
        if (action.getCommand() != null && action.getCommand().getCommand() != null) {
            boolean editApplied = editOk;
            executeCommand(
                    file,
                    action.getCommand().getCommand(),
                    action.getCommand().getArguments(),
                    (r, e) -> cb.accept(editApplied && e == null));
            return;
        }
        // No command and no edit either ⇒ nothing was done — report failure, not a silent success.
        cb.accept(edit != null && editOk);
    }

    /** UI-side workspace-edit applier (set by the coordinator; runs on the FX thread): applies each file's
     *  batch through an undoable buffer, then the trailing file renames — all-or-nothing. Default: refuse. */
    private volatile java.util.function.Function<WorkspaceEditMapper.Mapped, Boolean> applyEditHandler = edits -> false;

    public void setApplyEditHandler(java.util.function.Function<WorkspaceEditMapper.Mapped, Boolean> handler) {
        this.applyEditHandler = handler == null ? edits -> false : handler;
    }

    /** FX thread: maps + applies a workspace edit through the registered handler (false when unsupported). */
    private boolean applyWorkspaceEditNow(org.eclipse.lsp4j.WorkspaceEdit edit) {
        WorkspaceEditMapper.Mapped mapped = WorkspaceEditMapper.map(edit);
        return mapped != null && Boolean.TRUE.equals(applyEditHandler.apply(mapped));
    }

    /** A server-initiated {@code workspace/applyEdit} (from any session): apply on FX, answer the server. */
    private void onServerApplyEdit(org.eclipse.lsp4j.WorkspaceEdit edit, Consumer<Boolean> respond) {
        Platform.runLater(() -> respond.accept(applyWorkspaceEditNow(edit)));
    }

    public void references(Path file, int line, int character, Consumer<List<Target>> cb) {
        LanguageServerSession s = sessionFor(file);
        if (s == null) {
            cb.accept(List.of());
            return;
        }
        s.references(uri(file), new Position(line, character)).whenComplete((locations, error) -> {
            List<Target> targets = new ArrayList<>();
            if (error == null && locations != null) {
                for (Location l : locations) {
                    var range = l.getRange(); // guard against a server omitting the spec-required range
                    if (range != null) {
                        addTarget(targets, l.getUri(), range.getStart());
                    }
                }
            }
            Platform.runLater(() -> cb.accept(targets));
        });
    }

    /**
     * The completion <em>trigger characters</em> {@code file}'s server advertised (e.g. {@code .} for Java,
     * {@code <} for HTML), or empty if the server isn't ready / advertises none. The editor fires LSP
     * completion when one of these is typed — so HTML/CSS/etc. complete like VS Code, not just on a word
     * prefix (see {@code EditorBuffer.setLspTriggerChars}).
     */
    public java.util.Set<Character> triggerCharacters(Path file) {
        LanguageServerSession s = sessionFor(file);
        return s == null ? java.util.Set.of() : triggerCharsOf(s.capabilities());
    }

    /** Pure extraction of completion trigger characters from a server's capabilities (null-safe). */
    static java.util.Set<Character> triggerCharsOf(org.eclipse.lsp4j.ServerCapabilities caps) {
        if (caps == null || caps.getCompletionProvider() == null) {
            return java.util.Set.of();
        }
        List<String> triggers = caps.getCompletionProvider().getTriggerCharacters();
        if (triggers == null) {
            return java.util.Set.of();
        }
        java.util.Set<Character> out = new java.util.HashSet<>();
        for (String t : triggers) {
            if (t != null && !t.isEmpty()) {
                out.add(t.charAt(0));
            }
        }
        return out;
    }

    /**
     * jdtls's {@code initializationOptions}: {@code settings.java.autobuild.enabled=false} always, plus
     * the java-debug plugin {@code bundles} when {@code debugBundles} is non-empty. Editora only needs
     * jdtls for per-file diagnostics/completion/hover/nav — never its on-disk build output — but by
     * default jdtls runs a full incremental Eclipse workspace <em>build</em> on save/change, which m2e
     * points at the project's own configured Maven/Gradle output directory (e.g. {@code target/classes}
     * for a Maven project — the exact same directory {@code mvn}/{@code gradle} CLI builds write to).
     * Racing jdtls's ECJ builder against a CLI build there can leave that directory missing classes (most
     * visibly javac's synthetic enum-switch-map inner classes, since ECJ doesn't regenerate them in
     * lockstep) even right after a clean CLI build. Disabling autobuild stops jdtls from ever touching
     * that directory; live per-file diagnostics still work via reconcile-on-type, which isn't gated by it.
     * Pure + package-private so it's directly unit-tested.
     */
    static Map<String, Object> javaInitOptions(List<String> debugBundles) {
        Map<String, Object> autobuildOff = Map.of("java", Map.of("autobuild", Map.of("enabled", false)));
        return debugBundles.isEmpty()
                ? Map.of("settings", autobuildOff)
                : Map.of("bundles", debugBundles, "settings", autobuildOff);
    }

    /**
     * The {@code initialize.initializationOptions} for a server, or {@code null} for one that needs none. Pure
     * + package-private so it's unit-tested:
     * <ul>
     *   <li><b>java</b> (jdtls): {@code settings.java.autobuild.enabled=false} always + the java-debug plugin
     *       {@code bundles} when debugging is on (see {@link #javaInitOptions}).</li>
     *   <li><b>go</b> (gopls): {@code semanticTokens=true} — gopls ships semantic tokens disabled and only
     *       advertises {@code semanticTokensProvider} when this flat key is set (confirmed; {@code ui.} doesn't).</li>
     *   <li><b>json/css/html</b> (vscode-*-language-server): {@code provideFormatter=true} — these servers
     *       implement formatting but only advertise {@code documentFormattingProvider} when VS Code's own
     *       {@code provideFormatter} flag is passed; without it, Format Document was silently unavailable on a
     *       {@code .json}/{@code .css}/{@code .html} even though the server would format (#468; verified by
     *       driving the real servers: the flag flips the advertised capability from false to true).</li>
     *   <li><b>maven-pom</b> (JVM lemminx + lemminx-maven): {@code settings.xml.maven.central.skip=true} —
     *       the lemminx-maven settings live under an {@code xml.maven} object (schema verified against the
     *       extension's {@code XMLMavenSettings}). {@code central.skip=true} disables the heavy Maven Central
     *       <i>index</i> download so completion is served from the local {@code ~/.m2} repository only —
     *       offline-friendly and network-conservative, matching how Git/LSP/update-check are gated. Sending any
     *       {@code xml.maven} object is also what activates the extension's POM awareness in the first place.</li>
     * </ul>
     */
    static Object initOptionsFor(String serverId, List<String> debugBundles) {
        return switch (serverId == null ? "" : serverId) {
            case "java" -> javaInitOptions(debugBundles);
            case "go" -> Map.of("semanticTokens", true);
            case "json", "css", "html" -> Map.of("provideFormatter", true);
            case LspServerRegistry.MAVEN_POM_SERVER_ID ->
                Map.of("settings", Map.of("xml", Map.of("maven", Map.of("central", Map.of("skip", true)))));
            default -> null;
        };
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
        LanguageServerSession s = sessionFor(file);
        if (s == null || s.capabilities() == null || s.capabilities().getDiagnosticProvider() == null) {
            return;
        }
        s.diagnostic(uri(file)).whenComplete((report, error) -> {
            if (error != null) {
                return;
            }
            List<LspDiagnostic> mapped = DiagnosticMapper.mapReport(report);
            if (mapped == null) {
                return; // unchanged report — keep what's already shown
            }
            Platform.runLater(() -> onDiagnostics.accept(file, mapped));
        });
    }

    /** Shuts down every running server (best-effort) and clears all routing state. */
    public void shutdownAll() {
        sessionByDocUri.clear();
        rawDiagnostics.clear();
        for (LanguageServerSession s : sessionsByRoot.values()) {
            s.dispose();
            releaseJdtlsWorkspace(s);
        }
        sessionsByRoot.clear();
    }

    /** Shuts down every session for {@code serverId} (e.g. when that server's per-server toggle goes off). */
    public void shutdownServer(String serverId) {
        String prefix = sessionKeyPrefix(serverId);
        var it = sessionsByRoot.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix)) {
                sessionByDocUri.values().removeIf(s -> s == e.getValue());
                e.getValue().dispose();
                releaseJdtlsWorkspace(e.getValue());
                it.remove();
            }
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    private void onPublishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams params) {
        Path file = uriToPath(params.getUri());
        if (file == null) {
            return;
        }
        // Retain the RAW lsp4j diagnostics for open documents: a code-action request must send the
        // originals as context (their code/source/data are what a quick fix keys off — the mapped neutral
        // LspDiagnostic loses them). Open-documents-only, so a server's project-wide publishes don't
        // accumulate (#670).
        if (sessionByDocUri.containsKey(params.getUri())
                || sessionByDocUri.containsKey(file.toUri().toString())) {
            rawDiagnostics.put(
                    params.getUri(),
                    params.getDiagnostics() == null ? List.of() : List.copyOf(params.getDiagnostics()));
        }
        List<LspDiagnostic> mapped = DiagnosticMapper.map(params.getDiagnostics());
        Platform.runLater(() -> onDiagnostics.accept(file, mapped));
    }

    private static void addTarget(List<Target> out, String uri, Position start) {
        Path p = uriToPath(uri);
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
    private static void addDefinitionTarget(List<Target> out, String uri, Position start) {
        if (start == null) {
            return;
        }
        Path p = uriToPath(uri);
        if (p != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        } else if (uri != null && uri.startsWith(JDT_SCHEME)) {
            out.add(new Target(null, start.getLine(), start.getCharacter(), uri));
        }
    }

    /**
     * Fetches the source of a {@code jdt://} class-file URI via jdtls's {@code java/classFileContents}
     * request (the attached/decompiled source of a JDK or dependency class) and delivers it on the FX
     * thread — {@code null} when the session is gone, the request fails/times out, or the server returns
     * nothing. {@code anchorFile} is any file managed by the jdtls session that produced the URI.
     */
    public void classFileContents(Path anchorFile, String jdtUri, Consumer<String> cb) {
        LanguageServerSession s = sessionFor(anchorFile);
        if (s == null || jdtUri == null) {
            Platform.runLater(() -> cb.accept(null));
            return;
        }
        s.rawRequest("java/classFileContents", new org.eclipse.lsp4j.TextDocumentIdentifier(jdtUri))
                .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((r, e) -> {
                    String text = e != null ? null : rawStringResult(r);
                    Platform.runLater(() -> cb.accept(text == null || text.isBlank() ? null : text));
                });
    }

    /** The string payload of a raw (untyped) JSON-RPC response — lsp4j decodes an unknown method's result
     *  as a gson element, so accept both. Null for anything else. */
    static String rawStringResult(Object r) {
        if (r instanceof String s) {
            return s;
        }
        if (r instanceof com.google.gson.JsonPrimitive p && p.isString()) {
            return p.getAsString();
        }
        return null;
    }

    /**
     * A display title for a {@code jdt://contents/...} URI — its last path segment ({@code String.class}),
     * query dropped. Falls back to the whole URI when it has no path segment.
     */
    public static String classFileTitle(String jdtUri) {
        if (jdtUri == null) {
            return "";
        }
        String noQuery = jdtUri;
        int q = noQuery.indexOf('?');
        if (q >= 0) {
            noQuery = noQuery.substring(0, q);
        }
        int slash = noQuery.lastIndexOf('/');
        String name = slash >= 0 && slash < noQuery.length() - 1 ? noQuery.substring(slash + 1) : noQuery;
        return name.isBlank() ? jdtUri : name;
    }

    private static String hoverText(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return "";
        }
        var contents = hover.getContents();
        if (contents.isRight()) {
            MarkupContent mc = contents.getRight();
            return mc == null || mc.getValue() == null ? "" : mc.getValue();
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : contents.getLeft()) {
            if (entry.isLeft()) {
                sb.append(entry.getLeft());
            } else {
                MarkedString ms = entry.getRight();
                if (ms != null && ms.getValue() != null) {
                    sb.append(ms.getValue());
                }
            }
            sb.append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String uri(Path file) {
        return file.toUri().toString();
    }

    private static Path uriToPath(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
