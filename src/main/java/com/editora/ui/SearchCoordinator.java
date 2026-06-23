package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.editor.EditorBuffer;
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

        /** Records a run query into the persistent search history. */
        void recordSearch(String query);

        /** The current persistent search-history entries (most-recent-first), for the query dropdown. */
        javafx.collections.ObservableList<String> searchHistory();

        /** Updates the Settings → Search found/not-found status row after an rg probe. */
        void syncRipgrepStatus(boolean found);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final SearchService service = new SearchService();
    private final SearchPanel panel;

    private List<String> ripgrepProbedCommand = null;
    private volatile boolean ripgrepAvailable = false;

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

    /** Runs a multi-file search: open buffers (in-memory) + the active project root, results to the panel. */
    private void runFileSearch(SearchQuery query, String includeGlobs, String excludeGlobs) {
        Map<Path, String> open = new HashMap<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
            }
        });
        // Scope to THIS window's project root, else the active file's folder ("Current Folder").
        Path root = searchScopeRoot();
        refreshScope(); // keep the toolbar's "searching in" label in step with what we search
        host.setStatus(tr("search.searching"));
        List<String> include = Globs.split(includeGlobs);
        List<String> exclude = Globs.split(excludeGlobs);
        service.search(query, root, open, include, exclude, outcome -> {
            panel.setResults(outcome);
            host.setStatus(
                    outcome.totalMatches() == 0
                            ? tr("search.none")
                            : tr("search.summary", outcome.totalMatches(), outcome.fileCount()));
        });
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
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && (full.equals(home) || full.startsWith(home + File.separator))
                ? "~" + full.substring(home.length())
                : full;
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
        for (Path file : files) {
            try {
                EditorBuffer buffer = ops.bufferForPath(file);
                if (buffer != null) {
                    var r = MultiFileSearch.replaceAll(buffer.getContent(), query, replacement);
                    if (r.count() > 0) {
                        buffer.setContent(r.text());
                        total += r.count();
                        changedFiles++;
                    }
                } else {
                    String text = Files.readString(file);
                    var r = MultiFileSearch.replaceAll(text, query, replacement);
                    if (r.count() > 0) {
                        Files.writeString(file, r.text());
                        total += r.count();
                        changedFiles++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                host.setStatus(tr("search.replaceFailed", String.valueOf(file.getFileName())));
            }
        }
        host.setStatus(tr("search.replaced", total, changedFiles));
        panel.refresh(); // re-run with the panel's current query + globs to refresh the results
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
            panel.setBackendActive(effective);
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
    }
}
