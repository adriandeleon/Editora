package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

/** Adapts MCP operations to the owning window. */
final class WindowMcpBridge implements com.editora.mcp.McpBridge {
    interface Host {
        WindowSessionCoordinator sessions();

        FileWorkflowCoordinator fileWorkflows();

        EditorArea editorArea();

        CommandRegistry registry();

        ProjectManager projects();

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

    /** Runs {@code task} on the FX thread and blocks (with a timeout) for its result. */
    static <T> T mcpOnFx(java.util.function.Supplier<T> task) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            return task.get();
        }
        java.util.concurrent.CompletableFuture<T> f = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
            return new BufferContent(
                    b.getPath() == null ? null : b.getPath().toString(),
                    b.getTitle(),
                    b.getLanguage(),
                    b.isDirty(),
                    b.getContent());
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
                if (!host.canonicalPath(e.getKey()).equals(key)) {
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
            Path root = null;
            Project p = host.projects() == null ? null : host.projects().active();
            if (p != null) {
                root = Path.of(p.root());
            }
            host.searchCoordinator().service().search(q, root, open, fut::complete);
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
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (!b.isEditable()) {
                return "Buffer is read-only.";
            }
            CodeArea area = b.getArea();
            String replacement = newText == null ? "" : newText;
            if (oldText == null || oldText.isEmpty()) {
                area.replaceText(replacement); // whole-buffer rewrite, one undo step
                return null;
            }
            String text = area.getText();
            int first = text.indexOf(oldText);
            if (first < 0) {
                return "old_text not found in the buffer.";
            }
            if (replaceAll) {
                area.replaceText(text.replace(oldText, replacement));
                return null;
            }
            if (text.indexOf(oldText, first + 1) >= 0) {
                return "old_text occurs more than once; pass replace_all or a longer, unique old_text.";
            }
            area.replaceText(first, first + oldText.length(), replacement);
            return null;
        });
    }

    @Override
    public String saveBuffer(String path) {
        return mcpOnFx(() -> {
            EditorBuffer b = path == null ? host.activeBuffer() : openBufferForPath(path);
            if (b == null) {
                return path == null ? "No active buffer." : "No open buffer for: " + path;
            }
            if (b.getPath() == null) {
                // save() would open a Save-As dialog — never pop UI from an agent call.
                return "Untitled buffer has no file path; Save As must be done in the editor.";
            }
            return host.fileWorkflows().save(b)
                    ? null
                    : "Save did not complete (elevated write pending or the write failed).";
        });
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
                        m.line() + 1,
                        m.col() + 1,
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
                    && host.canonicalPath(b.getPath()).equals(key)) {
                return b;
            }
        }
        return null;
    }
}
