package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** Adapts MCP operations to the owning window. */
final class WindowMcpBridge implements com.editora.mcp.McpBridge {
    interface Host {
        WindowSessionCoordinator sessions();

        FileWorkflowCoordinator fileWorkflows();

        EditorArea editorArea();

        CommandRegistry registry();

        /** This window's project root, or null when it has no project open. */
        @Nullable
        Path projectRoot();

        com.editora.lsp.LspManager lspManager();

        NavigationCoordinator navigation();

        GitCoordinator git();

        TodoCoordinator todoCoordinator();

        SearchCoordinator searchCoordinator();

        LspCoordinator lspCoordinator();

        boolean lspEnabled();

        @Nullable
        EditorBuffer activeBuffer();

        @Nullable
        ImageViewerPane imagePaneOf(Tab tab);

        @Nullable
        HexViewerPane hexPaneOf(Tab tab);

        @Nullable
        PdfViewerPane pdfPaneOf(Tab tab);

        @Nullable
        EditorBuffer bufferOf(Tab tab);

        /** {@link #bufferOf}, building the editor of a restored tab the session restore has not reached yet. */
        @Nullable
        EditorBuffer ensureBuffer(Tab tab);

        @Nullable
        Path tabPath(Tab tab);

        Path canonicalPath(Path p);

        /** The editor's configuration directory, which no MCP write may land in. */
        Path configDirectory();

        /** The window's review threads. */
        ReviewCoordinator reviewCoordinator();

        /** What agents changed, turn by turn. */
        default @Nullable AgentChangeSets agentChanges() {
            return null;
        }

        /** Whether agent edits are proposed for approval rather than applied (a setting, off by default). */
        boolean editsNeedApproval();

        /** Opens a proposed edit of {@code buffer} for the user to accept or reject; {@code decided} hears which. */
        void openProposal(
                EditorBuffer buffer,
                String before,
                String after,
                com.editora.diff.DiffModels.DiffModel model,
                @Nullable String why,
                String agent,
                java.util.concurrent.CompletableFuture<ProposalPane.Decision> decided);

        /** The window's Local History; null for a host without one (a test's). */
        default @Nullable HistoryCoordinator historyCoordinator() {
            return null;
        }

        /** Where revision bodies are read; null for a host without one. */
        default com.editora.history.@Nullable HistoryService historyService() {
            return null;
        }
    }

    private final Host host;

    WindowMcpBridge(Host host) {
        this.host = host;
    }

    /** How long an MCP call waits for the FX thread; a call that never started by then is cancelled. */
    static final long FX_TIMEOUT_MILLIS = 5_000;

    /** How long {@code save_buffer} waits for the write itself (it may be behind a conflict prompt). */
    static final long SAVE_TIMEOUT_MILLIS = 30_000;

    /**
     * What MCP clients were last shown of each buffer by {@code read_buffer}, keyed by the buffer itself (an
     * untitled buffer has no path); see {@link ServedText}. One record per window, not per client: MCP
     * requests carry no client identity here.
     */
    private final ServedText served = new ServedText(new java.util.WeakHashMap<>());

    /** Why an MCP client may not read or edit an encrypted file's buffer. */
    static final String ENCRYPTED =
            "This buffer is an encrypted file; Editora does not share its decrypted text with MCP clients.";

    /**
     * Runs {@code task} on the FX thread and blocks (with a timeout) for its result. A task the FX thread has
     * not started when the wait ends is cancelled — a call answered with a timeout is not applied afterwards
     * — and the failure says which of the two it was (see {@link FxCall}).
     */
    static <T> T mcpOnFx(java.util.function.Supplier<T> task) {
        try {
            return FxCall.call(task, FX_TIMEOUT_MILLIS);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public java.util.List<OpenFile> listOpenFiles() {
        return mcpOnFx(() -> {
            EditorBuffer active = host.activeBuffer();
            java.util.List<OpenFile> out = new java.util.ArrayList<>();
            for (Tab tab : host.editorArea().tabs()) {
                EditorBuffer b = host.bufferOf(tab);
                if (b == null) {
                    RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
                    if (placeholder != null) {
                        // A restored tab whose editor is not built yet is an open, clean file.
                        out.add(new OpenFile(
                                placeholder.path().toString(),
                                placeholder.title(),
                                com.editora.text.LanguageRegistry.forFileName(
                                        placeholder.path().toString()),
                                false,
                                false));
                    }
                    continue;
                }
                out.add(new OpenFile(
                        b.getPath() == null ? null : b.getPath().toString(),
                        b.getTitle(),
                        b.getLanguage(),
                        b.isDirty(),
                        b == active));
            }
            return out;
        });
    }

    @Override
    public java.util.Map<String, Long> bufferStamps() {
        return mcpOnFx(() -> {
            java.util.Map<String, Long> out = new java.util.TreeMap<>();
            for (Tab tab : host.editorArea().tabs()) {
                EditorBuffer b = host.bufferOf(tab);
                if (b == null) {
                    RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
                    if (placeholder != null) {
                        out.put(placeholder.path().toString(), 0L); // not built yet: unchanged since opened
                    }
                } else if (b.getPath() != null) {
                    // An encrypted file's edits are its own business: its stamp never moves (ADR 0017).
                    out.put(b.getPath().toString(), b.encryption().isEncrypted() ? 0L : b.docVersion());
                }
            }
            return out;
        });
    }

    @Override
    public BufferContent readBuffer(@Nullable String path) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return null;
            }
            if (b.encryption().isEncrypted()) {
                throw new IllegalStateException(ENCRYPTED); // ADR 0017: its text stays in Editora
            }
            String text = b.getContent();
            served.served(b, text);
            return new BufferContent(
                    b.getPath() == null ? null : b.getPath().toString(),
                    b.getTitle(),
                    b.getLanguage(),
                    b.isDirty(),
                    text);
        });
    }

    @Override
    public java.util.List<Diagnostic> getDiagnostics(@Nullable String path) {
        return mcpOnFx(() -> {
            Path target = path != null
                    ? Path.of(path)
                    : (host.activeBuffer() == null ? null : host.activeBuffer().getPath());
            if (target == null) {
                return java.util.List.<Diagnostic>of();
            }
            Path key = host.canonicalPath(target);
            java.util.List<Diagnostic> out = new java.util.ArrayList<>();
            for (var e : host.lspCoordinator().problems().entrySet()) {
                if (!com.editora.config.PathKeys.samePath(host.canonicalPath(e.getKey()), key)) {
                    continue;
                }
                for (com.editora.text.LspDiagnostic d : e.getValue()) {
                    out.add(new Diagnostic(
                            d.startLine() + 1, d.startCol() + 1, d.severity().name(), d.message(), d.origin()));
                }
            }
            return out;
        });
    }

    @Override
    public java.util.List<SearchMatch> findInFiles(
            String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
        com.editora.search.SearchQuery q = new com.editora.search.SearchQuery(query, caseSensitive, regex, wholeWord);
        java.util.concurrent.CompletableFuture<com.editora.search.SearchService.Outcome> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            java.util.Map<Path, String> open = new java.util.HashMap<>();
            for (Tab tab : host.editorArea().tabs()) {
                EditorBuffer b = host.bufferOf(tab);
                if (b != null && b.getPath() != null && !b.encryption().isEncrypted()) {
                    open.put(b.getPath().toAbsolutePath().normalize(), b.getContent()); // never decrypted text
                }
            }
            // This window's project, as its todo_scan and its own Find in Files use: the ProjectManager's
            // "active" project is whichever window was focused last.
            Path root = host.projectRoot();
            // Detached: an MCP call must neither drop the user's own search nor be dropped by it (or by a
            // second, parallel MCP call) and then wait out the timeout for an answer nobody will send.
            host.searchCoordinator().service().searchDetached(q, root, open, fut::complete, () -> fut.cancel(false));
        });
        com.editora.search.SearchService.Outcome outcome;
        try {
            outcome = fut.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.List<SearchMatch> out = new java.util.ArrayList<>();
        for (com.editora.search.FileResult fr : outcome.files()) {
            for (com.editora.search.LineMatch lm : fr.matches()) {
                out.add(new SearchMatch(fr.file().toString(), lm.line(), lm.col(), lm.lineText()));
            }
        }
        return out;
    }

    @Override
    public java.util.List<CommandInfo> listCommands() {
        return mcpOnFx(() -> {
            java.util.List<CommandInfo> out = new java.util.ArrayList<>();
            for (com.editora.command.Command c : host.registry().all()) {
                if (McpAccess.commandRefusal(c.id()) == null) {
                    out.add(new CommandInfo(c.id(), c.title(), c.description()));
                }
            }
            return out;
        });
    }

    @Override
    public boolean executeCommand(String id) {
        if (McpAccess.commandRefusal(id) != null) {
            return false; // McpTools asks commandRefusal first; this is the backstop
        }
        return mcpOnFx(() -> host.registry().run(id));
    }

    @Override
    public @Nullable String commandRefusal(String id) {
        return McpAccess.commandRefusal(id);
    }

    @Override
    public String openFile(String path, int line, int col) {
        Path file = Path.of(path);
        // What the window has, read on the FX thread; the containment check itself resolves links on disk
        // and stays here, on the MCP worker.
        record Scope(@Nullable Path root, boolean alreadyOpen) {}
        Scope scope = mcpOnFx(() -> new Scope(host.projectRoot(), tabShowing(file)));
        String refusal = McpAccess.openRefusal(scope.root(), file, scope.alreadyOpen());
        if (refusal != null) {
            return refusal; // before the existence check: "no such file" would say what is on the disk
        }
        if (!java.nio.file.Files.exists(file)) {
            return "No such file: " + path;
        }
        return mcpOnFx(() -> {
            host.fileWorkflows().openPath(file);
            // Navigate only for text buffers — an image/hex/binary tab has no caret to move.
            if (line > 0 && openBufferForPath(file.toString()) != null) {
                host.sessions().gotoInFile(file, line, Math.max(col, 1));
            }
            return null;
        });
    }

    @Override
    public @Nullable String editBuffer(@Nullable String path, String oldText, String newText, boolean replaceAll) {
        if (oldText == null || oldText.isEmpty()) {
            // Never "then replace everything": that takes replaceBuffer, which the client must ask for by name.
            return "old_text is required for a targeted edit.";
        }
        String replacement = newText == null ? "" : newText;
        // The match is searched for here, on the MCP thread, in the text taken on the FX thread at a version; the
        // FX thread applies the edit only to that version, else it is searched for again (PERF-01, Pauta 6.2.0).
        for (int tries = EDIT_TRIES; tries > 0; tries--) {
            Taken taken = mcpOnFx(() -> take(path));
            if (taken.refusal() != null) {
                return taken.refusal();
            }
            // read_buffer serves the whole file, so the match is made against the whole file — also when the
            // buffer is narrowed: matching the accessible region only made "exactly once" and replace_all
            // mean something else than the text the client had read.
            String text = taken.text();
            boolean known = served.knows(taken.buffer(), text);
            int first = text.indexOf(oldText);
            if (first < 0) {
                return "old_text not found in the buffer.";
            }
            if (!replaceAll && text.indexOf(oldText, first + 1) >= 0) {
                return "old_text occurs more than once; pass replace_all or a longer, unique old_text.";
            }
            String next = replaceAll
                    ? text.replace(oldText, replacement)
                    : text.substring(0, first) + replacement + text.substring(first + oldText.length());
            String nextLf = com.editora.editops.LineEndings.toLf(next);
            EditorBuffer.PreparedEdit prepared = EditorBuffer.PreparedEdit.between(taken.version(), text, nextLf);
            Applied applied = mcpOnFx(() -> {
                EditorBuffer b = taken.buffer();
                if (b.isDisposed() || b.docVersion() != taken.version()) {
                    return Applied.AGAIN;
                }
                // A buffer without undo (large-file mode) keeps a Local History copy first, or refuses.
                NoUndoGuard.Verdict verdict = NoUndoGuard.check(b, tr("noUndo.op.agent"));
                if (!verdict.allowed()) {
                    return new Applied(verdict.message());
                }
                recordChange(b, text);
                CodeArea area = b.getArea();
                int at = first - b.narrowStart();
                if (!replaceAll && at >= 0 && at + oldText.length() <= area.getLength()) {
                    area.replaceText(at, at + oldText.length(), replacement); // inside the accessible text
                } else {
                    // replace_all, or in the part narrowing holds aside (or across its edge): the whole document.
                    b.applyPreparedWholeText(prepared, nextLf);
                }
                if (known) {
                    served.served(b, nextLf); // the client knows its own edit of text it had read
                }
                return Applied.DONE;
            });
            if (applied != Applied.AGAIN) {
                return applied.refusal();
            }
        }
        return "The buffer kept changing while the edit was applied; read it again and retry.";
    }

    /** Keeps what {@code b} was ({@code before}) in the MCP agent's change set, before an edit lands. FX thread. */
    private void recordChange(EditorBuffer b, String before) {
        AgentChangeSets changes = host.agentChanges();
        if (changes != null && b.getPath() != null) {
            changes.beforeMcpEdit(tr("agent.changes.mcpAgent"), b.getPath(), before);
        }
    }

    /** How long a proposed edit waits for the user before the tool answers {@code timeout} (within the relay's 5 min). */
    static final long PROPOSAL_WAIT_MILLIS = 240_000;

    /** {@link #PROPOSAL_WAIT_MILLIS}, shorter in tests. */
    private volatile long proposalWaitMillis = PROPOSAL_WAIT_MILLIS;

    void setProposalWaitMillisForTest(long millis) {
        proposalWaitMillis = millis;
    }

    @Override
    public boolean editsNeedApproval() {
        return mcpOnFx(host::editsNeedApproval);
    }

    @Override
    public ProposalOutcome proposeEdit(
            @Nullable String path,
            @Nullable String oldText,
            String newText,
            boolean replaceAll,
            boolean wholeBuffer,
            @Nullable String why,
            String agent) {
        Taken taken = mcpOnFx(() -> take(path));
        if (taken.refusal() != null) {
            return new ProposalOutcome("refused", taken.refusal());
        }
        String text = taken.text();
        String replacement = newText == null ? "" : newText;
        String next;
        if (wholeBuffer) {
            next = com.editora.editops.LineEndings.toLf(replacement);
        } else {
            if (oldText == null || oldText.isEmpty()) {
                return new ProposalOutcome("refused", "old_text is required unless replace_whole_buffer is true.");
            }
            int first = text.indexOf(oldText);
            if (first < 0) {
                return new ProposalOutcome("refused", "old_text not found in the buffer.");
            }
            if (!replaceAll && text.indexOf(oldText, first + 1) >= 0) {
                return new ProposalOutcome(
                        "refused", "old_text occurs more than once; pass replace_all or a longer, unique old_text.");
            }
            next = com.editora.editops.LineEndings.toLf(
                    replaceAll
                            ? text.replace(oldText, replacement)
                            : text.substring(0, first) + replacement + text.substring(first + oldText.length()));
        }
        if (next.equals(text)) {
            return new ProposalOutcome(
                    "refused", "The proposed text is the buffer's text: there is nothing to change.");
        }
        java.util.concurrent.CompletableFuture<ProposalPane.Decision> decided =
                new java.util.concurrent.CompletableFuture<>();
        String proposed = next;
        // The diff is worked out here, off the FX thread (PERF-01); the FX thread only builds the tab.
        com.editora.diff.DiffModels.DiffModel model =
                com.editora.diff.DiffEngine.compute(text, proposed, com.editora.diff.DiffEngine.DiffOptions.DEFAULT);
        mcpOnFx(() -> {
            host.openProposal(taken.buffer(), text, proposed, model, why, agent, decided);
            return null;
        });
        ProposalPane.Decision d;
        try {
            d = decided.get(proposalWaitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            decided.complete(ProposalPane.Decision.timeout()); // closes the tab: the answer came too late
            return new ProposalOutcome("timeout", "The user did not answer in time; nothing was changed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProposalOutcome("refused", "Interrupted while waiting for the user.");
        } catch (java.util.concurrent.ExecutionException e) {
            return new ProposalOutcome("refused", String.valueOf(e.getCause()));
        }
        if (!d.accepted()) {
            return new ProposalOutcome(d.timedOut() ? "timeout" : "rejected", d.reason());
        }
        EditorBuffer.PreparedEdit prepared = EditorBuffer.PreparedEdit.between(taken.version(), text, proposed);
        Applied applied = mcpOnFx(() -> {
            EditorBuffer b = taken.buffer();
            if (b.isDisposed() || b.docVersion() != taken.version()) {
                return new Applied("The buffer changed while the user looked at the proposal; nothing was applied."
                        + " Read it again and propose again.");
            }
            NoUndoGuard.Verdict verdict = NoUndoGuard.check(b, tr("noUndo.op.agent"));
            if (!verdict.allowed()) {
                return new Applied(verdict.message());
            }
            recordChange(b, text); // an accepted proposal is the MCP agent's change too
            b.applyPreparedWholeText(prepared, proposed); // one undo step for the span that differs
            served.served(b, proposed);
            return Applied.DONE;
        });
        return applied.refusal() == null
                ? new ProposalOutcome("accepted", "")
                : new ProposalOutcome("refused", applied.refusal());
    }

    // --- code intelligence and Local History (read-only) ---------------------------------------------

    /** How long a read waits for a language server. */
    private static final long LSP_WAIT_SECONDS = 10;

    /** FX thread: the open buffer a read names (the active one for null) and its server's path, or why not. */
    private record Readable(
            @Nullable EditorBuffer buffer,
            @Nullable Path lspPath,
            @Nullable String refusal) {}

    private Readable readable(@Nullable String path, boolean needsServer) {
        EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
        if (b == null) {
            return new Readable(null, null, path == null ? "No active buffer." : "No open buffer for: " + path);
        }
        if (b.encryption().isEncrypted()) {
            return new Readable(null, null, ENCRYPTED);
        }
        if (!needsServer) {
            return new Readable(b, b.getPath(), null);
        }
        Path lsp = host.lspCoordinator().lspPath(b);
        if (!host.lspEnabled() || lsp == null || !host.lspManager().isManaged(lsp)) {
            return new Readable(null, null, "No language server serves this file.");
        }
        LspCoordinator.syncText(host.lspManager(), b, lsp); // the server answers for the text as it is now
        return new Readable(b, lsp, null);
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> f, String what) {
        try {
            return f.get(LSP_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("The language server did not answer " + what + " in time.");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(String.valueOf(cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted.");
        }
    }

    @Override
    public java.util.List<Location> navigate(@Nullable String path, int line, int column, boolean references) {
        java.util.concurrent.CompletableFuture<java.util.List<com.editora.lsp.LspManager.Target>> f =
                new java.util.concurrent.CompletableFuture<>();
        Readable r = mcpOnFx(() -> readable(path, true));
        if (r.refusal() != null) {
            throw new IllegalStateException(r.refusal());
        }
        Path lsp = java.util.Objects.requireNonNull(r.lspPath());
        mcpOnFx(() -> {
            java.util.function.Consumer<String> failed = why -> f.completeExceptionally(new IllegalStateException(why));
            if (references) {
                host.lspManager().references(lsp, line - 1, Math.max(0, column - 1), f::complete, failed);
            } else {
                host.lspManager().definition(lsp, line - 1, Math.max(0, column - 1), f::complete, failed);
            }
            return null;
        });
        java.util.List<Location> out = new java.util.ArrayList<>();
        for (com.editora.lsp.LspManager.Target t : await(f, references ? "references" : "the definition")) {
            if (t.file() != null) {
                out.add(new Location(t.file().toString(), t.line() + 1, t.character() + 1));
            } else if (t.classFileUri() != null) {
                out.add(new Location(t.classFileUri(), t.line() + 1, t.character() + 1));
            }
        }
        return out;
    }

    @Override
    public String hover(@Nullable String path, int line, int column) {
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
        Readable r = mcpOnFx(() -> readable(path, true));
        if (r.refusal() != null) {
            throw new IllegalStateException(r.refusal());
        }
        Path lsp = java.util.Objects.requireNonNull(r.lspPath());
        mcpOnFx(() -> {
            host.lspManager()
                    .hoverAt(
                            lsp,
                            null,
                            line - 1,
                            Math.max(0, column - 1),
                            text -> f.complete(text == null ? "" : text),
                            why -> f.completeExceptionally(new IllegalStateException(why)));
            return null;
        });
        return await(f, "the hover");
    }

    @Override
    public java.util.List<WorkspaceSymbol> workspaceSymbols(String query) {
        java.util.concurrent.CompletableFuture<java.util.List<com.editora.lsp.LspManager.WorkspaceSymbolMatch>> f =
                new java.util.concurrent.CompletableFuture<>();
        Readable r = mcpOnFx(() -> readable(null, true)); // the active file's server answers for its workspace
        if (r.refusal() != null) {
            throw new IllegalStateException(
                    "workspace_symbols asks the language server of the active file: " + r.refusal());
        }
        Path lsp = java.util.Objects.requireNonNull(r.lspPath());
        mcpOnFx(() -> {
            host.lspManager().workspaceSymbols(lsp, query, f::complete);
            return null;
        });
        java.util.List<WorkspaceSymbol> out = new java.util.ArrayList<>();
        for (com.editora.lsp.LspManager.WorkspaceSymbolMatch s : await(f, "the symbols")) {
            out.add(new WorkspaceSymbol(
                    s.name(),
                    s.container(),
                    s.kind(),
                    s.file() == null ? "" : s.file().toString(),
                    s.line() + 1));
        }
        return out;
    }

    /** FX thread: {@code path}'s Local History revisions, or why they cannot be read. */
    private java.util.List<com.editora.config.HistoryRevision> revisions(String path) {
        Readable r = readable(path, false);
        if (r.refusal() != null) {
            throw new IllegalStateException(r.refusal());
        }
        HistoryCoordinator history = host.historyCoordinator();
        Path file = r.lspPath();
        if (history == null || file == null) {
            throw new IllegalStateException("Local History is not available.");
        }
        return history.revisionsFor(file);
    }

    @Override
    public java.util.List<HistoryEntry> localHistory(String path) {
        java.util.List<com.editora.config.HistoryRevision> revs = mcpOnFx(() -> revisions(path));
        java.util.List<HistoryEntry> out = new java.util.ArrayList<>();
        for (int i = 0; i < revs.size(); i++) {
            com.editora.config.HistoryRevision rev = revs.get(i);
            out.add(new HistoryEntry(i, rev.timestamp(), rev.reason(), rev.label(), rev.sizeBytes()));
        }
        return out;
    }

    @Override
    public String localHistoryText(String path, int index) {
        java.util.List<com.editora.config.HistoryRevision> revs = mcpOnFx(() -> revisions(path));
        if (index < 0 || index >= revs.size()) {
            throw new IllegalStateException("No revision " + index + ": the file has " + revs.size() + ".");
        }
        com.editora.history.HistoryService service = host.historyService();
        if (service == null) {
            throw new IllegalStateException("Local History is not available.");
        }
        java.util.concurrent.CompletableFuture<String> f = new java.util.concurrent.CompletableFuture<>();
        mcpOnFx(() -> {
            service.content(revs.get(index), text -> f.complete(text == null ? "" : text));
            return null;
        });
        try {
            return f.get(LSP_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The revision could not be read.");
        }
    }

    /** How many times an edit is searched for again when the buffer changed before it was applied. */
    private static final int EDIT_TRIES = 3;

    /** A buffer's whole text at a version, taken on the FX thread for an edit; or why it may not be edited. */
    private record Taken(
            @Nullable EditorBuffer buffer,
            String text,
            long version,
            @Nullable String refusal) {

        static Taken refused(String why) {
            return new Taken(null, "", -1, why);
        }

        @Override
        public EditorBuffer buffer() {
            return java.util.Objects.requireNonNull(buffer);
        }
    }

    /** What applying on the FX thread came to: {@code refusal} null when it went through. */
    private record Applied(@Nullable String refusal) {
        static final Applied DONE = new Applied(null);
        static final Applied AGAIN = new Applied("again");
    }

    /** FX thread: the buffer an edit names, and its whole text, or why it may not be edited. No search. */
    private Taken take(@Nullable String path) {
        EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
        if (b == null) {
            return Taken.refused(path == null ? "No active buffer." : "No open buffer for: " + path);
        }
        if (!b.isEditable()) {
            return Taken.refused("Buffer is read-only.");
        }
        String protectedFile = McpAccess.writeRefusal(host.configDirectory(), b.getPath());
        if (protectedFile != null) {
            return Taken.refused(protectedFile);
        }
        if (b.encryption().isEncrypted()) {
            return Taken.refused(ENCRYPTED); // a match would tell the client what the text says
        }
        return new Taken(b, b.getContent(), b.docVersion(), null);
    }

    @Override
    public @Nullable String replaceBuffer(@Nullable String path, String newText) {
        String nextLf = com.editora.editops.LineEndings.toLf(newText == null ? "" : newText);
        for (int tries = EDIT_TRIES; tries > 0; tries--) {
            Taken taken = mcpOnFx(() -> take(path));
            if (taken.refusal() != null) {
                return taken.refusal();
            }
            // The client sends the whole text as it believes it to be. What was typed since its read_buffer —
            // or unsaved text it never read — would be replaced without notice.
            EditorBuffer.PreparedEdit prepared =
                    EditorBuffer.PreparedEdit.between(taken.version(), taken.text(), nextLf);
            Applied applied = mcpOnFx(() -> {
                EditorBuffer b = taken.buffer();
                if (b.isDisposed() || b.docVersion() != taken.version()) {
                    return Applied.AGAIN;
                }
                String stale = served.check(b, taken.text(), b.isDirty())
                        .refusal(path == null ? "The active buffer" : path, "read_buffer");
                if (stale != null) {
                    return new Applied(stale);
                }
                NoUndoGuard.Verdict verdict = NoUndoGuard.check(b, tr("noUndo.op.agent"));
                if (!verdict.allowed()) {
                    return new Applied(verdict.message());
                }
                recordChange(b, taken.text());
                // Widens first, then one undo step that records only the span that differs.
                b.applyPreparedWholeText(prepared, nextLf);
                served.served(b, nextLf);
                return Applied.DONE;
            });
            if (applied != Applied.AGAIN) {
                return applied.refusal();
            }
        }
        return "The buffer kept changing while the text was applied; read it again and retry.";
    }

    @Override
    public @Nullable String saveBuffer(@Nullable String path) {
        // FX thread: start the save. The write itself runs on the save executor, so its outcome is awaited
        // here, on the MCP worker — "accepted for writing" is not "saved".
        Object started = mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (b.getPath() == null) {
                // save() would open a Save-As dialog — never pop UI from an agent call.
                return "Untitled buffer has no file path; Save As must be done in the editor.";
            }
            String protectedFile = McpAccess.writeRefusal(host.configDirectory(), b.getPath());
            if (protectedFile != null) {
                return protectedFile; // also for text the user typed there: theirs to save, not the client's
            }
            return host.fileWorkflows().saveReportingOutcome(b);
        });
        if (started instanceof String refusal) {
            return refusal;
        }
        java.util.concurrent.CompletableFuture<?> written = (java.util.concurrent.CompletableFuture<?>) started;
        try {
            return Boolean.TRUE.equals(written.get(SAVE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS))
                    ? null
                    : "Not saved: the buffer is still unsaved and the file on disk was not updated (the file"
                            + " changed on disk, is not writable without elevation, or the write failed).";
        } catch (java.util.concurrent.TimeoutException pending) {
            return "The save has not finished after " + (SAVE_TIMEOUT_MILLIS / 1000) + " s (the editor may be"
                    + " waiting for the user to resolve a conflict). Its outcome is unknown: check 'dirty' in"
                    + " list_open_files before relying on the file.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the save; its outcome is unknown.";
        } catch (java.util.concurrent.ExecutionException e) {
            return "Not saved: " + e.getCause();
        }
    }

    @Override
    public Selection getSelection() {
        return mcpOnFx(() -> {
            EditorBuffer b = host.activeBuffer();
            if (b == null) {
                return null;
            }
            CodeArea area = b.getFocusedArea() != null ? b.getFocusedArea() : b.getArea();
            var fwd = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
            var sel = area.getSelection();
            var sp = area.offsetToPosition(sel.getStart(), fwd);
            var ep = area.offsetToPosition(sel.getEnd(), fwd);
            return new Selection(
                    b.getPath() == null ? null : b.getPath().toString(),
                    b.getTitle(),
                    area.getCurrentParagraph() + 1,
                    area.getCaretColumn() + 1,
                    sp.getMajor() + 1,
                    sp.getMinor() + 1,
                    ep.getMajor() + 1,
                    ep.getMinor() + 1,
                    area.getSelectedText());
        });
    }

    @Override
    public java.util.List<Symbol> documentSymbols(@Nullable String path) {
        java.util.concurrent.CompletableFuture<java.util.List<com.editora.lsp.SymbolNode>> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            Path target = path != null
                    ? Path.of(path)
                    : (host.activeBuffer() == null ? null : host.activeBuffer().getPath());
            if (target == null
                    || !host.lspEnabled()
                    || !host.lspManager().isManaged(target)
                    || !host.lspManager().supportsDocumentSymbols(target)) {
                fut.complete(java.util.List.of());
                return;
            }
            host.lspManager().documentSymbols(target, fut::complete);
        });
        try {
            return mapMcpSymbols(fut.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Maps the LSP outline to the bridge's neutral records, shifting lines to 1-based. */
    static java.util.List<Symbol> mapMcpSymbols(java.util.List<com.editora.lsp.SymbolNode> in) {
        java.util.List<Symbol> out = new java.util.ArrayList<>(in.size());
        for (com.editora.lsp.SymbolNode n : in) {
            out.add(new Symbol(
                    n.name(), n.detail(), n.kind(), n.line() + 1, n.endLine() + 1, mapMcpSymbols(n.children())));
        }
        return out;
    }

    @Override
    public GitState gitStatus() {
        java.util.concurrent.CompletableFuture<com.editora.git.GitService.RepoState> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            Path context = host.git().isEnabled() ? host.git().contextPath() : null;
            if (context == null) {
                fut.complete(com.editora.git.GitService.RepoState.NONE);
                return;
            }
            host.git().service().status(context, fut::complete);
        });
        com.editora.git.GitService.RepoState state;
        try {
            state = fut.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Path root = state.root(); // set for a repo
        if (!state.isRepo() || root == null) {
            return new GitState(false, null, null, null, 0, 0, java.util.List.of());
        }
        com.editora.git.GitStatus st = state.status();
        java.util.List<GitFileState> files = new java.util.ArrayList<>();
        for (com.editora.git.GitStatus.FileEntry f : st.files()) {
            files.add(
                    new GitFileState(f.path(), String.valueOf(f.index()), String.valueOf(f.worktree()), f.origPath()));
        }
        return new GitState(true, root.toString(), st.branch(), st.upstream(), st.ahead(), st.behind(), files);
    }

    @Override
    public java.util.List<TabInfo> listTabs() {
        return mcpOnFx(() -> {
            Tab active = host.editorArea().selectedTab();
            java.util.List<TabInfo> out = new java.util.ArrayList<>();
            for (Tab tab : host.editorArea().tabs()) {
                Path p = host.tabPath(tab);
                out.add(new TabInfo(
                        tabType(tab),
                        host.navigation().bufferTitle(tab),
                        p == null ? null : p.toString(),
                        tab == active));
            }
            return out;
        });
    }

    @Override
    public java.util.List<TodoItem> todoScan() {
        java.util.concurrent.CompletableFuture<com.editora.todo.TodoService.Outcome> fut =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> host.todoCoordinator().scanForMcp(fut::complete));
        com.editora.todo.TodoService.Outcome outcome;
        try {
            outcome = fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        java.util.List<TodoItem> out = new java.util.ArrayList<>();
        for (com.editora.todo.TodoService.FileTodos ft : outcome.files()) {
            String file = ft.file().toString();
            for (com.editora.todo.TodoMatch m : ft.matches()) {
                com.editora.todo.TodoComment c = m.parsed();
                out.add(new TodoItem(
                        file,
                        m.line(), // a TodoMatch is 1-based already, unlike an LSP position
                        m.col(),
                        c == null ? m.patternName() : c.keyword(),
                        c == null ? null : c.tag(),
                        c == null ? null : c.priority(),
                        m.lineText()));
            }
        }
        return out;
    }

    /** The MCP {@code type} label for a tab: {@code editor}/{@code image}/{@code hex}/{@code diff}/
     *  {@code merge}/{@code welcome}/{@code other}. */
    String tabType(Tab tab) {
        if (host.bufferOf(tab) != null || RestorePlaceholder.of(tab) != null) {
            return "editor"; // a restored tab not built yet is an editor tab whose text is on disk
        }
        if (host.imagePaneOf(tab) != null) {
            return "image";
        }
        if (host.pdfPaneOf(tab) != null) {
            return "pdf";
        }
        if (host.hexPaneOf(tab) != null) {
            return "hex";
        }
        Object data = tab == null ? null : tab.getUserData();
        if (data instanceof DiffViewerPane) {
            return "diff";
        }
        if (data instanceof MergeViewerPane) {
            return "merge";
        }
        if (data instanceof WelcomePane) {
            return "welcome";
        }
        if (data instanceof DoctorPane) {
            return "doctor";
        }
        return "other";
    }

    /** Whether a tab of this window — an editor or a viewer — shows {@code file}. FX thread. */
    private boolean tabShowing(Path file) {
        Path key = host.canonicalPath(file);
        for (Tab tab : host.editorArea().tabs()) {
            Path shown = host.tabPath(tab);
            if (shown != null && com.editora.config.PathKeys.samePath(host.canonicalPath(shown), key)) {
                return true;
            }
        }
        return false;
    }

    /** Finds the open buffer whose file matches {@code path} (by canonical path), or null. */
    @Nullable
    EditorBuffer openBufferForPath(String path) {
        Path key = host.canonicalPath(Path.of(path));
        for (Tab tab : host.editorArea().tabs()) {
            Path shown = host.tabPath(tab);
            if (shown != null
                    && com.editora.config.PathKeys.samePath(host.canonicalPath(shown), key)
                    && (host.bufferOf(tab) != null || RestorePlaceholder.of(tab) != null)) {
                return host.ensureBuffer(tab); // a restored tab not built yet: the caller asked for its buffer
            }
        }
        return null;
    }

    // --- review comments --------------------------------------------------------------------------
    // The window's repository is the one its Git follows (the active file's, or the project's). Paths are made
    // canonical, and files read, here on the MCP worker; only the threads and the open buffers are read on FX.

    /** The window's repository as a review place for {@code file} (its path in it), or for the repository alone. */
    private ReviewCoordinator.Place reviewPlace(@Nullable Path file) {
        Path root = mcpOnFx(() -> host.git().isEnabled() ? host.git().repoRoot() : null);
        if (root == null) {
            throw new IllegalStateException(
                    "The window has no Git repository: review comments are kept per repository.");
        }
        String rel = "";
        if (file != null) {
            String r = com.editora.review.ReviewKeys.relativePath(root, file);
            if (r == null) {
                throw new IllegalStateException("Not a file of the window's repository (" + root + "): " + file);
            }
            rel = r;
        }
        ReviewCoordinator.Place place =
                new ReviewCoordinator.Place(root, com.editora.review.ReviewKeys.repoKey(root), rel);
        ReviewCoordinator reviews = host.reviewCoordinator();
        if (!mcpOnFx(() -> reviews.isLoaded(place))) {
            java.util.List<com.editora.config.ReviewThread> read = com.editora.config.ProjectReviewFile.read(root);
            mcpOnFx(() -> {
                reviews.loaded(place, read);
                return null;
            });
        }
        return place;
    }

    /** {@code file}'s text as the editor has it (an open buffer, unsaved changes included), else as on disk. */
    private @Nullable String reviewText(Path file) {
        record Open(boolean open, @Nullable String text) {}
        Open open = mcpOnFx(() -> {
            EditorBuffer b = openBufferForPath(file.toString());
            if (b == null) {
                return new Open(false, null);
            }
            if (b.encryption().isEncrypted()) {
                throw new IllegalStateException(ENCRYPTED);
            }
            return new Open(true, b.getContent());
        });
        if (open.open()) {
            return open.text();
        }
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return null;
        }
    }

    @Override
    public java.util.List<ReviewThreadInfo> listReviews(@Nullable String path, boolean all) {
        Path file = path == null ? null : Path.of(path);
        ReviewCoordinator.Place place = reviewPlace(file);
        String only = file == null ? null : place.path();
        java.util.List<com.editora.config.ReviewThread> threads =
                mcpOnFx(() -> host.reviewCoordinator().threadsOf(place.repoKey()));
        java.util.Map<String, String> texts = new java.util.HashMap<>();
        java.util.List<ReviewThreadInfo> out = new java.util.ArrayList<>();
        for (com.editora.config.ReviewThread t : threads) {
            if ((only != null && !only.equals(t.path())) || (!all && !t.open())) {
                continue;
            }
            Path abs = com.editora.review.ReviewKeys.resolve(place.root(), t.path());
            String text = texts.computeIfAbsent(t.path(), k -> {
                String read = reviewText(abs);
                return read == null ? "" : read;
            });
            var at = com.editora.review.ReviewAnchors.locate(t, text);
            java.util.List<ReviewCommentInfo> comments = new java.util.ArrayList<>();
            for (com.editora.config.ReviewComment c : t.comments()) {
                comments.add(new ReviewCommentInfo(
                        c.id(),
                        c.author().kind().name().toLowerCase(java.util.Locale.ROOT),
                        c.author().name(),
                        c.body(),
                        c.suggestion(),
                        c.createdAt()));
            }
            out.add(new ReviewThreadInfo(
                    t.id(),
                    abs.toString(),
                    at.startLine() + 1,
                    at.endLine() + 1,
                    at.outdated(),
                    t.state().name().toLowerCase(java.util.Locale.ROOT),
                    t.stateNote(),
                    t.severity() == null ? null : t.severity().name().toLowerCase(java.util.Locale.ROOT),
                    t.category(),
                    comments));
        }
        return out;
    }

    @Override
    public ReviewAdded addReview(
            String path,
            int startLine,
            int endLine,
            String body,
            @Nullable String suggestion,
            @Nullable String severity,
            @Nullable String category,
            String agent) {
        Path file = Path.of(path);
        record Scope(@Nullable Path root, boolean alreadyOpen) {}
        Scope scope = mcpOnFx(() -> new Scope(host.projectRoot(), tabShowing(file)));
        String refusal = McpAccess.openRefusal(scope.root(), file, scope.alreadyOpen());
        if (refusal != null) {
            return new ReviewAdded(null, refusal, 0, 0);
        }
        com.editora.config.ReviewThread.Severity level = null;
        if (severity != null) {
            try {
                level = com.editora.config.ReviewThread.Severity.valueOf(severity.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return new ReviewAdded(null, "severity must be one of blocker, important, minor, nit.", 0, 0);
            }
        }
        ReviewCoordinator.Place place = reviewPlace(file);
        String text = reviewText(file);
        if (text == null) {
            return new ReviewAdded(null, "Cannot read " + path, 0, 0);
        }
        int lines = text.split("\n", -1).length;
        int last = endLine <= 0 ? startLine : endLine;
        if (startLine < 1 || last < startLine || last > lines) {
            return new ReviewAdded(
                    null, "Lines " + startLine + "-" + last + " are not in the file (it has " + lines + ").", 0, 0);
        }
        com.editora.config.TextAnchor anchor = com.editora.review.ReviewAnchors.capture(text, startLine - 1, last - 1);
        com.editora.config.ReviewThread.Severity sev = level;
        if (sev != null) {
            String dismissed = mcpOnFx(() -> host.reviewCoordinator().dismissedOn(place, anchor));
            if (dismissed != null) {
                return new ReviewAdded(null, dismissed, 0, 0);
            }
        }
        String id = mcpOnFx(
                () -> host.reviewCoordinator().agentStart(place, anchor, body, suggestion, sev, category, agent));
        return new ReviewAdded(id, null, startLine, last);
    }

    @Override
    public @Nullable String replyReview(String threadId, String body, @Nullable String suggestion, String agent) {
        ReviewCoordinator.Place place = reviewPlace(null);
        return mcpOnFx(() -> host.reviewCoordinator().agentReply(place, threadId, body, suggestion, agent));
    }

    @Override
    public @Nullable String resolveReview(String threadId, String note, String agent) {
        ReviewCoordinator.Place place = reviewPlace(null);
        return mcpOnFx(() -> host.reviewCoordinator().agentResolve(place, threadId, note, agent));
    }
}
