package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.editor.EditorBuffer;
import com.editora.io.DocumentWriteSequencer;
import com.editora.search.Globs;
import com.editora.search.MultiFileSearch;
import com.editora.search.Ripgrep;
import com.editora.search.SearchQuery;
import com.editora.search.SearchService;

import static com.editora.i18n.Messages.tr;

/**
 * Find-in-Files feature (the multi-file search tool window + replace-in-files + the ripgrep/walker backend
 * selection), extracted from {@link MainController} via the {@link CoordinatorHost} pattern. Owns the
 * {@link SearchService} + the {@link SearchPanel}; {@code MainController} keeps the {@code ToolWindow}
 * (built with {@link #panel()}), the search history store, and the {@code search.*}/ripgrep command
 * registrations (which route their apply through {@link #applyRipgrepSupport()} / their action through
 * {@link #openToggle()}).
 */
final class SearchCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (project root, open-in-editor, the Search tool window). */
    interface Ops {
        /** This window's project root, or {@code null} for the no-project window (current-folder fallback). */
        Path projectRoot();

        /** Opens {@code file} and jumps to {@code line}/{@code col}; keeps focus in the results on a preview. */
        void openMatch(Path file, int line, int col, boolean focusEditor);

        boolean isToolWindowOpen();

        void openToolWindow();

        void closeToolWindow();

        /** The open buffer for {@code file}, or {@code null} if it isn't open (replace then rewrites disk). */
        EditorBuffer bufferForPath(Path file);

        /** True while {@code buffer}'s real content is still being loaded. */
        default boolean isBufferLoading(EditorBuffer buffer) {
            return false;
        }

        /** Captures local history before a closed file is rewritten. */
        default void recordHistory(Path file, String content, Consumer<Boolean> completion) {
            completion.accept(true);
        }

        /** Orders this rewrite with saves from every window. */
        DocumentWriteSequencer.Ticket beginDocumentWrite(Path file);

        /** Records a run query into the persistent search history. */
        void recordSearch(String query);

        /** The current persistent search-history entries (most-recent-first), for the query dropdown. */
        javafx.collections.ObservableList<String> searchHistory();

        /** Updates the Settings → Search found/not-found status row after an rg probe. */
        void syncRipgrepStatus(boolean found);
    }

    @FunctionalInterface
    interface MatchOpener {
        void open(Path file, int line, int col, boolean focusEditor);
    }

    @FunctionalInterface
    interface HistoryRecorder {
        void record(Path file, String content, Consumer<Boolean> completion);
    }

    record Navigation(
            Supplier<Path> projectRoot,
            MatchOpener openMatch,
            BooleanSupplier toolWindowOpen,
            Runnable openToolWindow,
            Runnable closeToolWindow) {}

    record ReplaceSupport(
            Function<Path, EditorBuffer> bufferForPath,
            Predicate<EditorBuffer> bufferLoading,
            HistoryRecorder recordHistory,
            Function<Path, DocumentWriteSequencer.Ticket> beginDocumentWrite) {}

    record Persistence(
            Consumer<String> recordSearch,
            Supplier<ObservableList<String>> searchHistory,
            Consumer<Boolean> syncRipgrepStatus) {}

    /** Builds the production adapter without another long anonymous class in {@link MainController}. */
    static Ops ops(Navigation navigation, ReplaceSupport replace, Persistence persistence) {
        return new Ops() {
            @Override
            public Path projectRoot() {
                return navigation.projectRoot().get();
            }

            @Override
            public void openMatch(Path file, int line, int col, boolean focusEditor) {
                navigation.openMatch().open(file, line, col, focusEditor);
            }

            @Override
            public boolean isToolWindowOpen() {
                return navigation.toolWindowOpen().getAsBoolean();
            }

            @Override
            public void openToolWindow() {
                navigation.openToolWindow().run();
            }

            @Override
            public void closeToolWindow() {
                navigation.closeToolWindow().run();
            }

            @Override
            public EditorBuffer bufferForPath(Path file) {
                return replace.bufferForPath().apply(file);
            }

            @Override
            public boolean isBufferLoading(EditorBuffer buffer) {
                return replace.bufferLoading().test(buffer);
            }

            @Override
            public void recordHistory(Path file, String content, Consumer<Boolean> completion) {
                replace.recordHistory().record(file, content, completion);
            }

            @Override
            public DocumentWriteSequencer.Ticket beginDocumentWrite(Path file) {
                return replace.beginDocumentWrite().apply(file);
            }

            @Override
            public void recordSearch(String query) {
                persistence.recordSearch().accept(query);
            }

            @Override
            public ObservableList<String> searchHistory() {
                return persistence.searchHistory().get();
            }

            @Override
            public void syncRipgrepStatus(boolean found) {
                persistence.syncRipgrepStatus().accept(found);
            }
        };
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final SearchService service = new SearchService();
    private final ExecutorService replaceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "replace-in-files");
        t.setDaemon(true);
        return t;
    });
    private final SearchPanel panel;
    private SearchInFilesPopup popup; // lazily built on first use of the popup command

    private List<String> ripgrepProbedCommand = null;
    private volatile boolean ripgrepAvailable = false;
    private boolean backendRipgrep = false; // effective backend, pushed to the panel + the popup's badge

    SearchCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new SearchPanel(new SearchPanel.Actions() {
            @Override
            public void search(SearchQuery query, String includeGlobs, String excludeGlobs) {
                runFileSearch(query, includeGlobs, excludeGlobs);
            }

            @Override
            public void openMatch(Path file, int line, int col, boolean focusEditor) {
                ops.openMatch(file, line, col, focusEditor);
            }

            @Override
            public void replaceAll(SearchQuery query, String replacement, List<Path> files) {
                replaceInFiles(query, replacement, files);
            }

            @Override
            public void recordSearch(String query) {
                ops.recordSearch(query);
            }
        });
    }

    SearchPanel panel() {
        return panel;
    }

    /** The off-thread multi-file search service (also used by the MCP {@code findInFiles} bridge). */
    SearchService service() {
        return service;
    }

    /** Binds the query dropdown to the persistent search history (called once history is loaded). */
    void refreshHistory() {
        panel.setHistory(ops.searchHistory());
    }

    /** Snapshots every open buffer's live text keyed by absolute path (unsaved edits win over disk). */
    private Map<Path, String> collectOpenBuffers() {
        Map<Path, String> open = new HashMap<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
            }
        });
        return open;
    }

    /**
     * Opens the keyboard-first Find-in-Files popup overlay (the {@code search.inFilesPopup} command). Built
     * lazily; the root field is re-seeded to the current scope on each show, and the query is pre-filled from
     * a single-line editor selection. Reuses the same {@link SearchService} as the tool window.
     */
    void showFindInFilesPopup() {
        if (host.overlayHost() == null) {
            return;
        }
        if (popup == null) {
            popup = new SearchInFilesPopup(host.overlayHost(), new SearchInFilesPopup.Ops() {
                @Override
                public Path defaultRoot() {
                    return searchScopeRoot();
                }

                @Override
                public void search(
                        SearchQuery query,
                        Path root,
                        String includeGlobs,
                        String excludeGlobs,
                        Consumer<SearchService.Outcome> onResult) {
                    service.search(
                            query,
                            root,
                            collectOpenBuffers(),
                            Globs.split(includeGlobs),
                            Globs.split(excludeGlobs),
                            onResult);
                }

                @Override
                public void openMatch(Path file, int line, int col) {
                    ops.openMatch(file, line, col, true);
                }

                @Override
                public void recordSearch(String query) {
                    ops.recordSearch(query);
                }
            });
        }
        popup.setBackendActive(backendRipgrep);
        popup.show(selectedLineForSearch());
    }

    /** Runs a multi-file search: open buffers (in-memory) + the active project root, results to the panel. */
    private void runFileSearch(SearchQuery query, String includeGlobs, String excludeGlobs) {
        Map<Path, String> open = collectOpenBuffers();
        // Scope to THIS window's project root, else the active file's folder ("Current Folder").
        Path root = searchScopeRoot();
        refreshScope(); // keep the toolbar's "searching in" label in step with what we search
        host.setStatus(tr("search.searching"));
        List<String> include = Globs.split(includeGlobs);
        List<String> exclude = Globs.split(excludeGlobs);
        // Registered so a sweep over a large tree reads as running work rather than going quiet (#770).
        AutoCloseable task = host.startBackgroundTask(tr("search.searching"));
        service.search(query, root, open, include, exclude, outcome -> {
            closeQuietly(task);
            panel.setResults(outcome);
            host.setStatus(
                    outcome.totalMatches() == 0
                            ? tr("search.none")
                            : tr("search.summary", outcome.totalMatches(), outcome.fileCount()));
        });
    }

    /** Closes a background-task handle; a bookkeeping slip must never break the callback around it. */
    private static void closeQuietly(AutoCloseable task) {
        try {
            task.close();
        } catch (Exception ignored) {
            // The handle only removes a map entry — nothing here is worth failing a search over.
        }
    }

    /**
     * The folder Find in Files searches on disk: this window's project root when a project is open, else the
     * active file's parent folder ("Current Folder"). {@code null} (no project, no saved file) ⇒ only the
     * open buffers are searched.
     */
    private Path searchScopeRoot() {
        Path root = ops.projectRoot();
        if (root != null) {
            return root;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.getPath() != null) {
            return b.getPath().toAbsolutePath().normalize().getParent();
        }
        return null;
    }

    /** Pushes the current search scope folder to the panel's "searching in" label (home-collapsed + tooltip). */
    void refreshScope() {
        Path root = searchScopeRoot();
        if (root == null) {
            panel.setScope(tr("search.scopeOpenFiles"), null);
            return;
        }
        String full = root.toString();
        panel.setScope(homeCollapsed(full), full);
    }

    /** Home-collapses an absolute folder path for a scope label (e.g. {@code ~/proj}). */
    private static String homeCollapsed(String full) {
        return com.editora.config.PathDisplay.collapseHome(full);
    }

    /** Toggles the Find-in-Files tool window: opens it (focusing its query field) when closed, closes it
     *  when already open — so the toolbar icon (and {@code C-S-f}) acts as an open/close toggle. */
    void openToggle() {
        if (ops.isToolWindowOpen()) {
            ops.closeToolWindow();
        } else {
            refreshScope(); // show the folder we'll search before the first query
            String selection = selectedLineForSearch();
            if (selection != null) {
                panel.setQuery(selection); // pre-fill (and run) from the editor selection
            }
            ops.openToolWindow();
        }
    }

    /**
     * The active buffer's selection when it is non-empty and stays on a single line, else {@code null}.
     * Used to pre-fill the find-in-files query from the selected text (the VS Code convention); a
     * multi-line selection isn't a sensible search term, so it returns {@code null} and the query is left
     * untouched.
     */
    private String selectedLineForSearch() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return null;
        }
        var area = buffer.getFocusedArea();
        if (area == null) {
            return null;
        }
        String sel = area.getSelectedText();
        if (sel == null || sel.isEmpty() || sel.indexOf('\n') >= 0 || sel.indexOf('\r') >= 0) {
            return null;
        }
        return sel;
    }

    /**
     * Replaces every match of {@code query} with {@code replacement} across {@code files}. Open buffers
     * are edited in-memory (undoable); closed files are rewritten on disk (UTF-8, line endings kept as
     * they live in the text). Asks for confirmation, then re-runs the search to refresh the panel.
     */
    private void replaceInFiles(SearchQuery query, String replacement, List<Path> files) {
        if (query == null || query.text() == null || query.text().isEmpty() || files.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("search.replaceConfirm", files.size()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        int total = 0;
        int changedFiles = 0;
        List<Path> failedFiles = new java.util.ArrayList<>();
        List<Path> closed = new java.util.ArrayList<>();
        for (Path file : files) {
            try {
                EditorBuffer buffer = ops.bufferForPath(file);
                if (buffer != null) {
                    ClosedReplace result = replaceOpenBuffer(buffer, query, replacement, ops.isBufferLoading(buffer));
                    total += result.count();
                    if (result.changed()) {
                        changedFiles++;
                    }
                    if (result.failed()) {
                        failedFiles.add(file);
                    }
                } else {
                    closed.add(file);
                }
            } catch (RuntimeException e) {
                failedFiles.add(file);
            }
        }
        if (closed.isEmpty()) {
            finishReplace(total, changedFiles, failedFiles);
            return;
        }
        int openTotal = total;
        int openChanged = changedFiles;
        List<Path> openFailed = List.copyOf(failedFiles);
        replaceExecutor.submit(() -> {
            int diskTotal = 0;
            int diskChanged = 0;
            List<Path> diskFailed = new java.util.ArrayList<>();
            for (Path file : closed) {
                ClosedReplace result;
                try (DocumentWriteSequencer.Ticket ticket = ops.beginDocumentWrite(file)) {
                    var outcome = ticket.runIfCurrent(() -> replaceClosedFile(
                            file,
                            query,
                            replacement,
                            original -> recordBeforeWrite(file, original),
                            ticket::isCurrent));
                    result = outcome.executed() ? outcome.value() : new ClosedReplace(0, false, true);
                } catch (IOException | RuntimeException e) {
                    result = new ClosedReplace(0, false, true);
                }
                diskTotal += result.count();
                if (result.changed()) {
                    diskChanged++;
                }
                if (result.failed()) {
                    diskFailed.add(file);
                }
            }
            int finalTotal = openTotal + diskTotal;
            int finalChanged = openChanged + diskChanged;
            List<Path> finalFailed = new java.util.ArrayList<>(openFailed);
            finalFailed.addAll(diskFailed);
            Platform.runLater(() -> finishReplace(finalTotal, finalChanged, finalFailed));
        });
    }

    private void recordBeforeWrite(Path file, String original) {
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicBoolean durable = new AtomicBoolean();
        Platform.runLater(() -> {
            try {
                ops.recordHistory(file, original, success -> {
                    durable.set(Boolean.TRUE.equals(success));
                    accepted.countDown();
                });
            } catch (RuntimeException failure) {
                accepted.countDown();
            }
        });
        try {
            if (!accepted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while recording pre-replace history");
            }
            if (!durable.get()) {
                throw new IllegalStateException("Could not make pre-replace history durable");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while recording pre-replace history", e);
        }
    }

    private void finishReplace(int total, int changedFiles, List<Path> failedFiles) {
        if (failedFiles.isEmpty()) {
            host.setStatus(tr("search.replaced", total, changedFiles));
        } else {
            String paths = failedFiles.stream()
                    .limit(8)
                    .map(Path::toString)
                    .collect(java.util.stream.Collectors.joining(", "));
            if (failedFiles.size() > 8) {
                paths += ", …";
            }
            host.setError(tr("search.replacePartial", total, changedFiles, failedFiles.size()) + ": " + paths);
        }
        panel.refresh();
    }

    record ClosedReplace(int count, boolean changed, boolean failed) {}

    static ClosedReplace replaceOpenBuffer(
            EditorBuffer buffer, SearchQuery query, String replacement, boolean loading) {
        if (buffer == null || loading || !buffer.isEditable() || buffer.isTruncatedLoad()) {
            return new ClosedReplace(0, false, true);
        }
        var result = MultiFileSearch.replaceAll(buffer.getContent(), query, replacement);
        if (result.count() == 0) {
            return new ClosedReplace(0, false, false);
        }
        buffer.replaceWholeDocument(result.text());
        return new ClosedReplace(result.count(), true, false);
    }

    static ClosedReplace replaceClosedFile(
            Path file, SearchQuery query, String replacement, Consumer<String> beforeWrite) {
        return replaceClosedFile(file, query, replacement, beforeWrite, () -> true);
    }

    static ClosedReplace replaceClosedFile(
            Path file, SearchQuery query, String replacement, Consumer<String> beforeWrite, BooleanSupplier commit) {
        try {
            String original = Files.readString(file);
            var result = MultiFileSearch.replaceAll(original, query, replacement);
            if (result.count() == 0) {
                return new ClosedReplace(0, false, false);
            }
            beforeWrite.accept(original);
            if (!com.editora.io.AtomicFileWrite.replaceIfUnchanged(
                    file,
                    original.getBytes(StandardCharsets.UTF_8),
                    result.text().getBytes(StandardCharsets.UTF_8),
                    commit)) {
                return new ClosedReplace(0, false, true);
            }
            return new ClosedReplace(result.count(), true, false);
        } catch (IOException | RuntimeException e) {
            return new ClosedReplace(0, false, true);
        }
    }

    /**
     * Configures the Find-in-Files backend: ripgrep when the setting is on AND rg is detected on PATH, else
     * the built-in Java walker. Detection runs off the FX thread (it spawns {@code rg --version}); cached per
     * command. Run at init + on every settings apply.
     */
    void applyRipgrepSupport() {
        var s = host.settings();
        List<String> cmd = Ripgrep.command(s.getRipgrepCommand());
        boolean enabled = s.isRipgrepSearch();
        if (cmd.equals(ripgrepProbedCommand)) {
            boolean effective = enabled && ripgrepAvailable;
            service.setBackend(effective, cmd, s.isSearchRespectGitignore());
            applyBackendBadge(effective);
            return;
        }
        Thread t = new Thread(
                () -> {
                    boolean ok = Ripgrep.detect(cmd);
                    Platform.runLater(() -> {
                        ripgrepProbedCommand = cmd;
                        ripgrepAvailable = ok;
                        boolean effective = s.isRipgrepSearch() && ok;
                        service.setBackend(effective, cmd, s.isSearchRespectGitignore());
                        panel.setBackendActive(effective);
                        ops.syncRipgrepStatus(ok);
                    });
                },
                "rg-detect");
        t.setDaemon(true);
        t.start();
    }

    /** Records the effective backend and pushes the "ripgrep" badge to the panel + the popup (if built). */
    private void applyBackendBadge(boolean effective) {
        backendRipgrep = effective;
        panel.setBackendActive(effective);
        if (popup != null) {
            popup.setBackendActive(effective);
        }
    }

    /** Probe rg availability off-thread for the current command, delivering the result on the FX thread. */
    void probeRipgrep(Consumer<Boolean> onResult) {
        List<String> cmd = Ripgrep.command(host.settings().getRipgrepCommand());
        Thread t = new Thread(
                () -> {
                    boolean ok = Ripgrep.detect(cmd);
                    Platform.runLater(() -> onResult.accept(ok));
                },
                "rg-detect");
        t.setDaemon(true);
        t.start();
    }

    void shutdown() {
        service.shutdown();
        replaceExecutor.shutdownNow();
    }
}
