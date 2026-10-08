package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Adapts MCP operations to the owning window. */
final class WindowMcpBridge implements com.editora.mcp.McpBridge {
    interface Host {
        WindowSessionCoordinator sessions();

        FileWorkflowCoordinator fileWorkflows();

        EditorArea editorArea();

        CommandRegistry registry();

        /** This window's project root, or null when it has no project open. */
        Path projectRoot();

        com.editora.lsp.LspManager lspManager();

        NavigationCoordinator navigation();

        GitCoordinator git();

        TodoCoordinator todoCoordinator();

        SearchCoordinator searchCoordinator();

        LspCoordinator lspCoordinator();

        boolean lspEnabled();

        EditorBuffer activeBuffer();

        ImageViewerPane imagePaneOf(Tab tab);

        HexViewerPane hexPaneOf(Tab tab);

        PdfViewerPane pdfPaneOf(Tab tab);

        EditorBuffer bufferOf(Tab tab);

        Path tabPath(Tab tab);

        Path canonicalPath(Path p);
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
    public BufferContent readBuffer(String path) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return null;
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
    public java.util.List<Diagnostic> getDiagnostics(String path) {
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
                for (com.editora.editor.LspDiagnostic d : e.getValue()) {
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
                if (b != null && b.getPath() != null) {
                    open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
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
                out.add(new CommandInfo(c.id(), c.title(), c.description()));
            }
            return out;
        });
    }

    @Override
    public boolean executeCommand(String id) {
        return mcpOnFx(() -> host.registry().run(id));
    }

    @Override
    public boolean openFile(String path, int line, int col) {
        Path file = Path.of(path);
        if (!java.nio.file.Files.exists(file)) {
            return false;
        }
        return mcpOnFx(() -> {
            host.fileWorkflows().openPath(file);
            // Navigate only for text buffers — an image/hex/binary tab has no caret to move.
            if (line > 0 && openBufferForPath(file.toString()) != null) {
                host.sessions().gotoInFile(file, line, Math.max(col, 1));
            }
            return true;
        });
    }

    @Override
    public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
        if (oldText == null || oldText.isEmpty()) {
            // Never "then replace everything": that takes replaceBuffer, which the client must ask for by name.
            return "old_text is required for a targeted edit.";
        }
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (!b.isEditable()) {
                return "Buffer is read-only.";
            }
            String replacement = newText == null ? "" : newText;
            // read_buffer serves the whole file, so the match is made against the whole file — also when the
            // buffer is narrowed: matching the accessible region only made "exactly once" and replace_all
            // mean something else than the text the client had read.
            String text = b.getContent();
            boolean known = served.knows(b, text);
            int first = text.indexOf(oldText);
            if (first < 0) {
                return "old_text not found in the buffer.";
            }
            if (!replaceAll && text.indexOf(oldText, first + 1) >= 0) {
                return "old_text occurs more than once; pass replace_all or a longer, unique old_text.";
            }
            // A buffer without undo (large-file mode) keeps a Local History copy first, or refuses.
            NoUndoGuard.Verdict verdict = NoUndoGuard.check(b, tr("noUndo.op.agent"));
            if (!verdict.allowed()) {
                return verdict.message();
            }
            if (replaceAll) {
                replaceWholeDocument(b, text.replace(oldText, replacement));
            } else {
                CodeArea area = b.getArea();
                int at = first - b.narrowStart();
                if (at >= 0 && at + oldText.length() <= area.getLength()) {
                    area.replaceText(at, at + oldText.length(), replacement); // inside the accessible text
                } else {
                    // In the part narrowing holds aside (or across its edge): reachable only by widening.
                    replaceWholeDocument(
                            b, text.substring(0, first) + replacement + text.substring(first + oldText.length()));
                }
            }
            if (known) {
                served.served(b, b.getContent()); // the client knows its own edit of text it had read
            }
            return null;
        });
    }

    @Override
    public String replaceBuffer(String path, String newText) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (!b.isEditable()) {
                return "Buffer is read-only.";
            }
            // The client sends the whole text as it believes it to be. What was typed since its read_buffer —
            // or unsaved text it never read — would be replaced without notice.
            String stale = served.check(b, b.getContent(), b.isDirty())
                    .refusal(path == null ? "The active buffer" : path, "read_buffer");
            if (stale != null) {
                return stale;
            }
            NoUndoGuard.Verdict verdict = NoUndoGuard.check(b, tr("noUndo.op.agent"));
            if (!verdict.allowed()) {
                return verdict.message();
            }
            replaceWholeDocument(b, newText == null ? "" : newText);
            served.served(b, b.getContent());
            return null;
        });
    }

    /**
     * FX thread: the one place an MCP call replaces a buffer's whole document (an explicit whole-buffer
     * replacement, {@code replace_all}, and a targeted edit outside a narrowed region). Widens first, then
     * one undo step that records only the span that differs.
     */
    private static void replaceWholeDocument(EditorBuffer buffer, String text) {
        buffer.replaceWholeDocument(text);
    }

    @Override
    public String saveBuffer(String path) {
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
    public java.util.List<Symbol> documentSymbols(String path) {
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
        if (!state.isRepo()) {
            return new GitState(false, null, null, null, 0, 0, java.util.List.of());
        }
        com.editora.git.GitStatus st = state.status();
        java.util.List<GitFileState> files = new java.util.ArrayList<>();
        for (com.editora.git.GitStatus.FileEntry f : st.files()) {
            files.add(
                    new GitFileState(f.path(), String.valueOf(f.index()), String.valueOf(f.worktree()), f.origPath()));
        }
        return new GitState(true, state.root().toString(), st.branch(), st.upstream(), st.ahead(), st.behind(), files);
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
        if (host.bufferOf(tab) != null) {
            return "editor";
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

    /** Finds the open buffer whose file matches {@code path} (by canonical path), or null. */
    EditorBuffer openBufferForPath(String path) {
        Path key = host.canonicalPath(Path.of(path));
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            if (b != null
                    && b.getPath() != null
                    && com.editora.config.PathKeys.samePath(host.canonicalPath(b.getPath()), key)) {
                return b;
            }
        }
        return null;
    }
}
