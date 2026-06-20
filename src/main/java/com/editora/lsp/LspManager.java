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

    /** A resolved navigation target (definition/reference): a file + 0-based line/character. */
    public record Target(Path file, int line, int character) {}

    private final BiConsumer<Path, List<LspDiagnostic>> onDiagnostics;
    /** Server status: {@code accept(type, message)} (e.g. "ServiceReady"/"Ready"), posted to the FX thread. */
    private final BiConsumer<String, String> onStatus;

    private final ExecutorService detectExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lsp-detect");
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
        }
    }

    public boolean isEnabled() {
        return enabled;
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

    /** Opens {@code file} (Java) on the server for {@code root}, starting that server if needed. */
    public void openDocument(Path file, Path root, String languageId, String text) {
        if (!enabled || file == null || root == null || !LspServerRegistry.isSupported(languageId)) {
            return;
        }
        LanguageServerSession session = sessionForRoot(root, languageId);
        if (session == null) {
            return;
        }
        String uri = file.toUri().toString();
        sessionByDocUri.put(uri, session);
        session.didOpen(uri, languageId, text);
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
        if (s != null) {
            s.didClose(uri);
        }
    }

    public boolean isManaged(Path file) {
        // A remote (SFTP) file is never LSP-managed; bail before uri(), whose toUri() throws for such paths.
        return file != null && com.editora.vfs.Vfs.isLocal(file) && sessionByDocUri.containsKey(uri(file));
    }

    private LanguageServerSession sessionForRoot(Path root, String languageId) {
        String serverId = LspServerRegistry.serverIdFor(languageId);
        if (serverId == null) {
            return null;
        }
        // Key by (serverId, root) — not languageId — so js/ts/jsx/tsx in one root share one tsserver.
        String key = serverId + " " + root.toUri();
        return sessionsByRoot.computeIfAbsent(key, k -> {
            LspServerRegistry.ServerSpec spec = LspServerRegistry.specFor(languageId, commands);
            if (spec == null) {
                return null;
            }
            // jdtls: give each project root its own Eclipse workspace (-data) so it never deadlocks on the
            // shared default workspace's .lock. Created on demand; jdtls reuses it across sessions (its index
            // persists, so subsequent opens are faster).
            if (LspServerRegistry.JAVA_SERVER_ID.equals(serverId) && jdtlsWorkspaceBase != null) {
                Path ws = jdtlsWorkspaceBase.resolve(LspServerRegistry.workspaceDirName(root));
                try {
                    Files.createDirectories(ws);
                    spec = LspServerRegistry.withDataDir(spec, ws.toString());
                } catch (java.io.IOException e) {
                    // Couldn't create the workspace dir — fall back to the default (better than not starting).
                }
            }
            // Per-server initializationOptions:
            //  - jdtls gets the java-debug plugin bundle (when debugging is on) so it registers the
            //    vscode.java.* debug commands.
            //  - gopls ships semantic tokens DISABLED and only advertises semanticTokensProvider when its
            //    `semanticTokens` option is set at initialize (confirmed: the flat key works, `ui.` doesn't);
            //    enabling it is free — gopls computes tokens only when we request them.
            Object initOptions = null;
            if ("java".equals(serverId) && !debugBundles.isEmpty()) {
                initOptions = Map.of("bundles", debugBundles);
            } else if ("go".equals(serverId)) {
                initOptions = Map.of("semanticTokens", true);
            }
            LanguageServerSession session = new LanguageServerSession(
                    spec,
                    root,
                    this::onPublishDiagnostics,
                    (type, msg) -> Platform.runLater(() -> onStatus.accept(type, msg)),
                    initOptions);
            return session.start() ? session : null;
        });
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
                        addTarget(targets, l.getUri(), l.getRange().getStart());
                    }
                } else if (result.getRight() != null) {
                    for (LocationLink ll : result.getRight()) {
                        var range = ll.getTargetSelectionRange() != null
                                ? ll.getTargetSelectionRange()
                                : ll.getTargetRange();
                        addTarget(targets, ll.getTargetUri(), range.getStart());
                    }
                }
            }
            Platform.runLater(() -> cb.accept(targets));
        });
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
                    addTarget(targets, l.getUri(), l.getRange().getStart());
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
        for (LanguageServerSession s : sessionsByRoot.values()) {
            s.dispose();
        }
        sessionsByRoot.clear();
    }

    /** Shuts down every session for {@code serverId} (e.g. when that server's per-server toggle goes off). */
    public void shutdownServer(String serverId) {
        String prefix = serverId + " ";
        var it = sessionsByRoot.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().startsWith(prefix)) {
                sessionByDocUri.values().removeIf(s -> s == e.getValue());
                e.getValue().dispose();
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
        List<LspDiagnostic> mapped = DiagnosticMapper.map(params.getDiagnostics());
        Platform.runLater(() -> onDiagnostics.accept(file, mapped));
    }

    private static void addTarget(List<Target> out, String uri, Position start) {
        Path p = uriToPath(uri);
        if (p != null && start != null) {
            out.add(new Target(p, start.getLine(), start.getCharacter()));
        }
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
