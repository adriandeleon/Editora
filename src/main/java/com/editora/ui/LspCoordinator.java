package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import com.editora.editor.MarkdownRenderer;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The whole Language Server Protocol integration, extracted from {@link MainController} via the
 * {@link CoordinatorHost} pattern. Owns:
 * <ul>
 *   <li>the on-demand <em>navigation/format</em> flows — go-to-definition, find-references, hover, and
 *       document formatting (the {@code lsp.*} commands + editor right-click items) plus the hover popup;
 *   <li>the <em>diagnostics routing</em> — the per-open-file {@code problems} map, the {@link ProblemsPanel},
 *       and the {@code publishDiagnostics} callback ({@link #onDiagnostics});
 *   <li>the <em>configure/detect/gating + per-buffer lifecycle</em> — {@link #applySupport} (mirrors
 *       {@link MermaidCoordinator}), per-server detection ({@code serverAvailable}/{@code SERVER_IDS}), the
 *       per-server enable/command switches, {@link #syncBuffer} (open/activate/close), the status-bar
 *       {@code LSP:} segment ({@link #updateStatusBar}), the Structure outline ({@link #requestStructureSymbols}),
 *       semantic tokens, the server-ready callback ({@link #onServerStatus}), the {@link #wireBuffer} hook set,
 *       and the {@code lsp.toggleServer}/{@code lsp.setServerCommand} pickers.
 * </ul>
 *
 * <p>The {@link LspManager} is <em>not</em> owned here: it stays constructed in {@code MainController} because
 * the Debug (DAP) integration layers on the same jdtls session and the MCP bridge reads its diagnostics, so
 * the manager must remain reachable from both. The coordinator takes it as a constructor argument. The
 * {@code lspManager}'s {@code publishDiagnostics}/{@code status} callbacks route through thin
 * {@code MainController.onLspDiagnostics}/{@code onLspServerStatus} method-ref delegates (to dodge an illegal
 * forward reference at the manager's field initializer). {@code MainController} also keeps the {@code lspEnabled}
 * predicate (used widely by Run/Debug gating), the {@code ifLsp}/{@code toggleLsp} command glue, the Problems
 * {@code ToolWindow} (built with {@link #problemsPanel()}), and {@code canonicalPath}/{@code tabForPath}.
 */
final class LspCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that the LSP flows need. */
    interface Ops {
        /** Opens {@code file} (if needed) and moves the caret to a 0-based LSP line/column. */
        void openAndGoto(Path file, int line0, int col0);

        /** Whether the active buffer is editable (formatting is a no-op on a read-only/huge buffer). */
        boolean activeEditable();

        /** Whether the LSP feature is effectively on (off in Simple UI mode); diagnostics are dropped when off. */
        boolean lspFeatureEnabled();

        /** Shows/hides the status-bar indeterminate loading bar (a server is starting). */
        void setLspLoading(boolean loading);

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        EditorBuffer bufferForPath(Path file);

        /** Sets (or clears, when {@code null}) the status-bar {@code LSP: <server>} segment label. */
        void setStatusBarLsp(String label);

        /** Shows/hides the Problems tool-window stripe button (active file is server-managed). */
        void setProblemsAvailable(boolean available);

        /** Opens (shows + focuses) the References tool window after a multi-result Find References. */
        void openReferencesWindow();

        /** Pushes the server's document-symbol outline (or {@code null} to fall back to the heuristic). */
        void setStructureSymbols(EditorBuffer buffer, java.util.List<com.editora.lsp.SymbolNode> symbols);

        /** Refreshes the toolbar Run button (the Run affordance is gated by the LSP feature). */
        void refreshRunButton();

        /** Base dir for per-project jdtls Eclipse workspaces ({@code <configDir>/jdtls-workspaces}). */
        Path jdtlsWorkspaceBase();

        /** The active project's root for LSP root resolution (null when Projects is off / no project). */
        Path lspProjectRoot();

        /** Detection finished updating — re-evaluate the active buffer's install banner. */
        void onDetectionSettled();

        /**
         * The canonical (symlink-resolved) form of {@code file}, so every consumer of the diagnostics map keys
         * agrees. A server reports diagnostics under whatever URI it chose (some canonicalize, some echo the
         * sent path), while {@code setProblemsActiveFile} is given the canonical active path — canonicalizing
         * the key here is what lets the active-file-first sort (and tab-close clear) actually match.
         */
        Path canonicalize(Path file);
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final Ops ops;

    /** Diagnostics by file, <b>scoped to open files only</b> (a server publishes project-wide). */
    private final Map<Path, List<LspDiagnostic>> problems = new LinkedHashMap<>();

    private final ProblemsPanel problemsPanel;
    private final ReferencesPanel referencesPanel;

    /** serverId → whether that server's command was found on this machine (per-server availability). */
    private final Map<String, Boolean> serverAvailable = new java.util.HashMap<>();

    /** Known LSP server ids (the configure/detect/gating loops iterate these). */
    private static final String[] SERVER_IDS = {
        "java",
        "typescript",
        "python",
        "xml",
        "json",
        "bash",
        "yaml",
        "go",
        "rust",
        "php",
        "ruby",
        "clangd",
        "html",
        "css",
        "kotlin",
        "lua",
        "dockerfile",
        "sql",
        "terraform",
        "toml",
        "csharp",
        "typst"
    };

    /** Lines of over-scan above/below the viewport when requesting semantic tokens (small scrolls stay covered). */
    private static final int SEMANTIC_WINDOW_PAD = 200;

    /** The currently-showing LSP hover popup (dismissable), or null. */
    private Popup hoverPopup;

    LspCoordinator(CoordinatorHost host, LspManager lspManager, Ops ops) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.problemsPanel = new ProblemsPanel(ops::openAndGoto);
        this.referencesPanel = new ReferencesPanel(ops::openAndGoto);
    }

    /** The Problems tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    ProblemsPanel problemsPanel() {
        return problemsPanel;
    }

    /** The References tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    ReferencesPanel referencesPanel() {
        return referencesPanel;
    }

    /** Live diagnostics map for the MCP bridge's {@code getDiagnostics} (read on the FX thread). */
    Map<Path, List<LspDiagnostic>> problems() {
        return problems;
    }

    /** Sorts the Problems tree so the active file's group is on top (pass the canonical path; on tab switch). */
    void setProblemsActiveFile(Path canonicalActive) {
        problemsPanel.setActiveFile(canonicalActive);
    }

    /** Diagnostics callback from the manager (already on the FX thread): store + paint + refresh Problems. */
    void onDiagnostics(Path file, List<LspDiagnostic> diagnostics) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        ops.setLspLoading(false); // diagnostics flowing ⇒ the server is up; stop the loading bar
        // A language server publishes diagnostics project-wide (jdtls especially), but we only surface
        // problems for files actually OPEN in Editora — otherwise the Problems window fills with whole-
        // workspace noise from a single open file.
        EditorBuffer buffer = ops.bufferForPath(file);
        // A jdtls whose compliance predates JDK 25 flags a compact source file's implicit class as a
        // preview/unsupported feature — pure noise for a file the JDK 25 launcher runs fine. Drop just
        // those complaints (real errors in the file still surface).
        if (buffer != null && "java".equals(buffer.getLanguage()) && buffer.isRunnable()) {
            diagnostics = diagnostics.stream()
                    .filter(d -> !isCompactSourceNoise(d.message()))
                    .toList();
        }
        if (buffer != null) {
            buffer.setLspDiagnostics(diagnostics);
        }
        // Key the map by the canonical path so it agrees with setProblemsActiveFile (given the canonical
        // active path) and clearDiagnostics (given the buffer's path) — the server may report a symlink URI.
        Path key = ops.canonicalize(file);
        if (buffer == null || diagnostics.isEmpty()) {
            problems.remove(key);
        } else {
            problems.put(key, diagnostics);
        }
        refreshProblems();
    }

    /** Drops {@code file}'s diagnostics (a tab closed / its LSP session ended) + refreshes the panel. */
    void clearDiagnostics(Path file) {
        problems.remove(ops.canonicalize(file));
        refreshProblems();
    }

    /** Clears every file's diagnostics (LSP disabled / servers restarted) + refreshes the panel. */
    void clearAllDiagnostics() {
        problems.clear();
        refreshProblems();
    }

    private void refreshProblems() {
        problemsPanel.setProblems(problems);
    }

    /** Whether an LSP diagnostic on a compact source file is implicit-class noise from a server whose
     *  Java compliance predates JDK 25 (JEP 512 final). Pure — tested. */
    static boolean isCompactSourceNoise(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("implicitly declared class") // JDK 23+ JDT wording (incl. preview gating)
                || m.contains("unnamed class") // the JDK 21/22 preview-era wording
                || m.contains("instance main method"); // "...Instance Main Methods is a preview feature"
    }

    // --- gating + lifecycle (the configure/detect/per-buffer-sync machine) ----------------------------

    /** Whether {@code serverId}'s command was found on this machine (read by the DAP debug gating for java). */
    boolean isServerAvailable(String serverId) {
        return Boolean.TRUE.equals(serverAvailable.get(serverId));
    }

    /**
     * Whether {@code serverId} was <em>probed and found absent</em> — distinct from "not probed yet". The
     * install banner uses this (not {@code !isServerAvailable}) so a server isn't reported missing during the
     * startup detection window, when the map has no entry yet.
     */
    boolean isServerMissing(String serverId) {
        return serverAvailable.containsKey(serverId) && Boolean.FALSE.equals(serverAvailable.get(serverId));
    }

    /** Whether a live LSP session is currently serving {@code path} (⇒ its server is demonstrably present). */
    boolean isManaged(java.nio.file.Path path) {
        return path != null && lspManager.isManaged(path);
    }

    /**
     * Reconciles the LSP feature with its setting (mirrors {@link MermaidCoordinator}). Configures the
     * manager + Problems window, then (when enabled) detects each server and gates per-buffer LSP. Runs at
     * init and on every settings apply.
     */
    void applySupport() {
        var s = host.settings();
        boolean on = ops.lspFeatureEnabled(); // effective: off in Simple UI mode
        // Give jdtls a per-project Eclipse workspace under the config dir (it otherwise shares one default
        // workspace and deadlocks on its .lock — the server then never finishes initialize / completion).
        lspManager.setJdtlsWorkspaceBase(ops.jdtlsWorkspaceBase());
        lspManager.configure(
                on,
                Map.ofEntries(
                        Map.entry("java", s.getJavaLspCommand()),
                        Map.entry("typescript", s.getTypescriptLspCommand()),
                        Map.entry("python", s.getPythonLspCommand()),
                        Map.entry("xml", s.getXmlLspCommand()),
                        Map.entry("json", s.getJsonLspCommand()),
                        Map.entry("bash", s.getBashLspCommand()),
                        Map.entry("yaml", s.getYamlLspCommand()),
                        Map.entry("go", s.getGoLspCommand()),
                        Map.entry("rust", s.getRustLspCommand()),
                        Map.entry("php", s.getPhpLspCommand()),
                        Map.entry("ruby", s.getRubyLspCommand()),
                        Map.entry("clangd", s.getClangdLspCommand()),
                        Map.entry("html", s.getHtmlLspCommand()),
                        Map.entry("css", s.getCssLspCommand()),
                        Map.entry("kotlin", s.getKotlinLspCommand()),
                        Map.entry("lua", s.getLuaLspCommand()),
                        Map.entry("dockerfile", s.getDockerfileLspCommand()),
                        Map.entry("sql", s.getSqlLspCommand()),
                        Map.entry("terraform", s.getTerraformLspCommand()),
                        Map.entry("toml", s.getTomlLspCommand()),
                        Map.entry("csharp", s.getCsharpLspCommand()),
                        Map.entry("typst", s.getTypstLspCommand())));
        updateProblemsAvailability();
        // The Run affordance (compact source files) is gated by the LSP feature: toggle every buffer's
        // Run detection, then refresh the active buffer's Run tool-window availability.
        boolean shellRun = on && s.isBashLspEnabled();
        host.forEachBuffer(b -> {
            b.setRunEnabled(on);
            b.setShellRunEnabled(shellRun); // shell Run glyph gated by the Bash LSP toggle
        });
        ops.refreshRunButton();
        if (!on) {
            serverAvailable.clear();
            clearAllDiagnostics();
            host.forEachBuffer(b -> b.setLspActive(false));
            ops.setLspLoading(false);
            updateStatusBar();
            return;
        }
        for (String serverId : SERVER_IDS) {
            // Stop any server whose per-server toggle is off (frees its process); buffers deactivate below.
            if (!serverEnabled(serverId)) {
                // Clear this server's buffers' diagnostics BEFORE shutting it down. After shutdown
                // isManaged() is false, so syncBuffer's else-branch clear (guarded on isManaged) is skipped —
                // and with no server left to re-publish an empty list, the Problems window would strand this
                // server's diagnostics forever (#469).
                clearDiagnosticsForServer(serverId);
                lspManager.shutdownServer(serverId);
            }
            // Probe each known server independently (one may be installed and another not).
            lspManager.detect(serverId, ok -> {
                serverAvailable.put(serverId, ok);
                applyGating();
            });
        }
    }

    /** Clears the diagnostics (Problems entry + editor overlay) of every open buffer served by {@code serverId}
     *  and closes its document — used when that server is being disabled, before it is shut down. */
    private void clearDiagnosticsForServer(String serverId) {
        host.forEachBuffer(b -> {
            Path p = b.getPath();
            if (p == null || !serverId.equals(com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage()))) {
                return;
            }
            b.setLspActive(false); // drop the editor squiggle overlay/stripes immediately
            if (lspManager.isManaged(p)) {
                lspManager.closeDocument(p);
            }
            clearDiagnostics(p); // remove from the Problems map (canonical key) + refresh the panel
        });
    }

    /** Applies the detection-dependent gate to every open buffer (per the file's own server). */
    void applyGating() {
        host.forEachBuffer(this::syncBuffer);
        updateStatusBar();
        ops.onDetectionSettled(); // re-evaluate the install banner now that detection has updated
    }

    /** Whether a server's own enable toggle is on (under the global LSP enable). */
    boolean serverEnabled(String serverId) {
        var s = host.settings();
        return switch (serverId) {
            case "typescript" -> s.isTypescriptLspEnabled();
            case "python" -> s.isPythonLspEnabled();
            case "xml" -> s.isXmlLspEnabled();
            case "json" -> s.isJsonLspEnabled();
            case "bash" -> s.isBashLspEnabled();
            case "yaml" -> s.isYamlLspEnabled();
            case "go" -> s.isGoLspEnabled();
            case "rust" -> s.isRustLspEnabled();
            case "php" -> s.isPhpLspEnabled();
            case "ruby" -> s.isRubyLspEnabled();
            case "clangd" -> s.isClangdLspEnabled();
            case "html" -> s.isHtmlLspEnabled();
            case "css" -> s.isCssLspEnabled();
            case "kotlin" -> s.isKotlinLspEnabled();
            case "lua" -> s.isLuaLspEnabled();
            case "dockerfile" -> s.isDockerfileLspEnabled();
            case "sql" -> s.isSqlLspEnabled();
            case "terraform" -> s.isTerraformLspEnabled();
            case "toml" -> s.isTomlLspEnabled();
            case "csharp" -> s.isCsharpLspEnabled();
            case "typst" -> s.isTypstLspEnabled();
            default -> s.isJavaLspEnabled();
        };
    }

    /** The configured command for a server id (blank ⇒ the server's default). */
    private String serverCommand(String serverId) {
        var s = host.settings();
        return switch (serverId) {
            case "typescript" -> s.getTypescriptLspCommand();
            case "python" -> s.getPythonLspCommand();
            case "xml" -> s.getXmlLspCommand();
            case "json" -> s.getJsonLspCommand();
            case "bash" -> s.getBashLspCommand();
            case "yaml" -> s.getYamlLspCommand();
            case "go" -> s.getGoLspCommand();
            case "rust" -> s.getRustLspCommand();
            case "php" -> s.getPhpLspCommand();
            case "ruby" -> s.getRubyLspCommand();
            case "clangd" -> s.getClangdLspCommand();
            case "html" -> s.getHtmlLspCommand();
            case "css" -> s.getCssLspCommand();
            case "kotlin" -> s.getKotlinLspCommand();
            case "lua" -> s.getLuaLspCommand();
            case "dockerfile" -> s.getDockerfileLspCommand();
            case "sql" -> s.getSqlLspCommand();
            case "terraform" -> s.getTerraformLspCommand();
            case "toml" -> s.getTomlLspCommand();
            case "csharp" -> s.getCsharpLspCommand();
            case "typst" -> s.getTypstLspCommand();
            default -> s.getJavaLspCommand();
        };
    }

    /** Sets a server's per-server enable toggle (mirrors {@link #serverEnabled}). */
    private void setServerEnabled(String serverId, boolean on) {
        var s = host.settings();
        switch (serverId) {
            case "typescript" -> s.setTypescriptLspEnabled(on);
            case "python" -> s.setPythonLspEnabled(on);
            case "xml" -> s.setXmlLspEnabled(on);
            case "json" -> s.setJsonLspEnabled(on);
            case "bash" -> s.setBashLspEnabled(on);
            case "yaml" -> s.setYamlLspEnabled(on);
            case "go" -> s.setGoLspEnabled(on);
            case "rust" -> s.setRustLspEnabled(on);
            case "php" -> s.setPhpLspEnabled(on);
            case "ruby" -> s.setRubyLspEnabled(on);
            case "clangd" -> s.setClangdLspEnabled(on);
            case "html" -> s.setHtmlLspEnabled(on);
            case "css" -> s.setCssLspEnabled(on);
            case "kotlin" -> s.setKotlinLspEnabled(on);
            case "lua" -> s.setLuaLspEnabled(on);
            case "dockerfile" -> s.setDockerfileLspEnabled(on);
            case "sql" -> s.setSqlLspEnabled(on);
            case "terraform" -> s.setTerraformLspEnabled(on);
            case "toml" -> s.setTomlLspEnabled(on);
            case "csharp" -> s.setCsharpLspEnabled(on);
            case "typst" -> s.setTypstLspEnabled(on);
            default -> s.setJavaLspEnabled(on);
        }
    }

    /** Sets a server's configured command (blank ⇒ the server's default); mirrors {@link #serverCommand}. */
    private void setServerCommand(String serverId, String command) {
        var s = host.settings();
        switch (serverId) {
            case "typescript" -> s.setTypescriptLspCommand(command);
            case "python" -> s.setPythonLspCommand(command);
            case "xml" -> s.setXmlLspCommand(command);
            case "json" -> s.setJsonLspCommand(command);
            case "bash" -> s.setBashLspCommand(command);
            case "yaml" -> s.setYamlLspCommand(command);
            case "go" -> s.setGoLspCommand(command);
            case "rust" -> s.setRustLspCommand(command);
            case "php" -> s.setPhpLspCommand(command);
            case "ruby" -> s.setRubyLspCommand(command);
            case "clangd" -> s.setClangdLspCommand(command);
            case "html" -> s.setHtmlLspCommand(command);
            case "css" -> s.setCssLspCommand(command);
            case "kotlin" -> s.setKotlinLspCommand(command);
            case "lua" -> s.setLuaLspCommand(command);
            case "dockerfile" -> s.setDockerfileLspCommand(command);
            case "sql" -> s.setSqlLspCommand(command);
            case "terraform" -> s.setTerraformLspCommand(command);
            case "toml" -> s.setTomlLspCommand(command);
            case "csharp" -> s.setCsharpLspCommand(command);
            case "typst" -> s.setTypstLspCommand(command);
            default -> s.setJavaLspCommand(command);
        }
    }

    /**
     * Requests semantic tokens for {@code buffer}'s visible region (padded by {@link #SEMANTIC_WINDOW_PAD})
     * and pushes them into the buffer when the response lands — but only if it's still the active buffer
     * (a background tab's tokens would overlay nothing useful and waste an apply).
     */
    void requestSemanticTokens(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (path == null || !buffer.isSemanticActive() || !lspManager.isManaged(path)) {
            return;
        }
        int[] window = buffer.visibleLineWindow();
        long gen = buffer.semanticGen(); // capture now; the reply is dropped if the doc changes before it lands
        lspManager.requestSemanticTokens(
                path, window[0] - SEMANTIC_WINDOW_PAD, window[1] + SEMANTIC_WINDOW_PAD, tokens -> {
                    if (buffer == host.activeBuffer()) {
                        buffer.setSemanticTokens(tokens, gen);
                    }
                });
    }

    /**
     * Re-gates LSP semantic highlighting against {@code Settings.semanticHighlight} for every open buffer
     * (the palette toggle's apply). Doesn't disturb the sessions — just flips each managed buffer's overlay
     * on/off and fetches tokens when turning on.
     */
    void applySemanticHighlight() {
        boolean want = host.settings().isSemanticHighlight();
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            boolean on = want && lspManager.isManaged(b.getPath()) && lspManager.supportsSemanticTokens(b.getPath());
            b.setSemanticActive(on);
            if (on) {
                requestSemanticTokens(b);
            }
        });
    }

    /**
     * Refreshes the Structure tool window's outline for {@code buffer} from the language server
     * ({@code textDocument/documentSymbol}) when supported; otherwise clears the LSP outline so the panel
     * falls back to the TextMate/fold heuristic. Only acts for the active buffer; an empty result also
     * falls back to the heuristic.
     */
    void requestStructureSymbols(EditorBuffer buffer) {
        if (buffer == null || buffer != host.activeBuffer()) {
            return;
        }
        Path path = buffer.getPath();
        if (path != null && lspManager.isManaged(path) && lspManager.supportsDocumentSymbols(path)) {
            lspManager.documentSymbols(path, syms -> ops.setStructureSymbols(buffer, syms.isEmpty() ? null : syms));
        } else {
            ops.setStructureSymbols(buffer, null);
        }
    }

    /** Opens+activates an eligible buffer on its language's server, or deactivates+closes it otherwise. */
    void syncBuffer(EditorBuffer buffer) {
        Path path = buffer.getPath();
        String lang = buffer.getLanguage();
        String serverId = com.editora.lsp.LspServerRegistry.serverIdFor(lang);
        boolean eligible = ops.lspFeatureEnabled()
                && path != null
                && com.editora.vfs.Vfs.isLocal(path)
                && !buffer.isLargeFile() // 5+ MB files skip LSP (like highlighting/minimap/git) — see setLspActive
                && !buffer.isHeavyFile() // intermediate large-source tier also skips LSP (keeps highlighting)
                && serverId != null
                && serverEnabled(serverId)
                && Boolean.TRUE.equals(serverAvailable.get(serverId));
        if (eligible) {
            if (!lspManager.isManaged(path)) {
                host.setStatus(tr("status.lsp.starting", serverLabel(serverId)));
                ops.setLspLoading(true); // show the loading bar until the server reports ready
                lspManager.openDocument(path, lspRootFor(buffer), lang, buffer.text());
            }
            buffer.setLspActive(true);
            // Push the server's completion trigger characters + request initial pull diagnostics. Both are
            // no-ops until the server's initialize completes (then onServerStatus "ready" refreshes them),
            // and effective immediately when the server for this root is already running (a 2nd file).
            buffer.setLspTriggerChars(lspManager.triggerCharacters(path));
            buffer.setLspFormatAvailable(lspManager.supportsFormatting(path));
            buffer.setLspRangeFormatAvailable(lspManager.supportsRangeFormatting(path));
            lspManager.pullDiagnostics(path);
            // Semantic highlighting: gate on the setting + the server's capability; request the initial
            // viewport (a no-op until the server reports ready, then onServerStatus refreshes it).
            boolean semantic = host.settings().isSemanticHighlight() && lspManager.supportsSemanticTokens(path);
            buffer.setSemanticActive(semantic);
            if (semantic) {
                requestSemanticTokens(buffer);
            }
        } else {
            buffer.setLspActive(false);
            buffer.setLspTriggerChars(java.util.Set.of());
            buffer.setLspFormatAvailable(false);
            buffer.setLspRangeFormatAvailable(false);
            buffer.setSemanticActive(false);
            if (path != null && lspManager.isManaged(path)) {
                lspManager.closeDocument(path);
                clearDiagnostics(path);
            }
        }
    }

    /** Workspace root for a buffer: active project (if Projects on), else nearest build file, else dir. */
    private Path lspRootFor(EditorBuffer buffer) {
        return com.editora.lsp.RootResolver.resolve(
                ops.lspProjectRoot(),
                buffer.getPath(),
                com.editora.lsp.LspServerRegistry.rootMarkersFor(buffer.getLanguage()));
    }

    /** The accept hook for a completion item: resolve it + apply any additional edits (a TypeScript
     *  auto-import's {@code import} line). Returns null when the item can't carry extra edits. */
    private Runnable autoImportAccept(EditorBuffer buffer, org.eclipse.lsp4j.CompletionItem item) {
        if (!com.editora.lsp.CompletionMapper.mayHaveAdditionalEdits(item)) {
            return null;
        }
        return () -> {
            if (buffer.getPath() == null) {
                return;
            }
            // The resolve is a round-trip; its edits carry positions computed against the document as it is
            // right now (this runs just after the accept's own edit). If the document moves meanwhile — the
            // user undoes the accept, or edits above the insert point — those positions are stale and
            // applying them blind writes an import into the wrong place (or for a symbol that's gone).
            long version = buffer.docVersion();
            lspManager.resolveCompletion(buffer.getPath(), item, edits -> {
                if (buffer.docVersion() == version) {
                    buffer.applyLspEdits(edits);
                }
            });
        };
    }

    /** Updates the status-bar LSP segment: "LSP: &lt;server&gt;" when the active file is managed, else hidden. */
    void updateStatusBar() {
        EditorBuffer b = host.activeBuffer();
        boolean managed = b != null && b.getPath() != null && lspManager.isManaged(b.getPath());
        String serverId = managed ? com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage()) : null;
        ops.setStatusBarLsp(serverId != null ? serverLabel(serverId) : null);
        updateProblemsAvailability(); // the Problems window tracks the same active-file LSP-managed condition
    }

    /**
     * The Problems tool-window stripe button is shown only when the active file is served by a language
     * server (LSP on + managed) — the same condition as the status-bar {@code LSP:} segment. So it's hidden
     * on a Welcome/Markdown/plain tab even if another open file has diagnostics.
     */
    private void updateProblemsAvailability() {
        EditorBuffer b = host.activeBuffer();
        boolean available =
                ops.lspFeatureEnabled() && b != null && b.getPath() != null && lspManager.isManaged(b.getPath());
        ops.setProblemsAvailable(available);
    }

    /** The short server name shown in the status bar — the configured command's basename for {@code serverId}. */
    private String serverLabel(String serverId) {
        String configured = serverCommand(serverId);
        String cmd = configured == null || configured.isBlank()
                ? com.editora.lsp.LspServerRegistry.defaultCommandFor(serverId)
                : configured;
        List<String> toks = com.editora.lsp.LspServerRegistry.tokenize(cmd);
        String exe = toks.isEmpty() ? serverId : toks.get(0);
        try {
            return Path.of(exe).getFileName().toString();
        } catch (RuntimeException e) {
            return exe;
        }
    }

    /**
     * Shows a language server's status/log message in the echo area and drives the status-bar loading
     * bar: a "ServiceReady"/"Ready" (or "Error") status stops it. {@code type} is the JDT LS
     * {@code language/status} type (or "Message"/"Error").
     */
    void onServerStatus(String type, String message) {
        if (!ops.lspFeatureEnabled()) {
            return;
        }
        if (message != null && !message.isBlank()) {
            host.setStatus(tr("status.lsp.server", message));
        }
        if (type != null) {
            String t = type.toLowerCase(Locale.ROOT);
            if (t.contains("ready") || t.contains("error")) {
                ops.setLspLoading(false); // server finished starting (or failed)
            }
            if (t.contains("ready")) {
                // A server just finished initializing — its capabilities are now known. Push completion
                // trigger characters to every open managed buffer and pull initial diagnostics (the
                // pull-model servers don't publish until asked).
                host.forEachBuffer(b -> {
                    if (b.getPath() != null && lspManager.isManaged(b.getPath())) {
                        b.setLspTriggerChars(lspManager.triggerCharacters(b.getPath()));
                        b.setLspFormatAvailable(lspManager.supportsFormatting(b.getPath()));
                        b.setLspRangeFormatAvailable(lspManager.supportsRangeFormatting(b.getPath()));
                        lspManager.pullDiagnostics(b.getPath());
                        // Capabilities are known now — (re)gate semantic highlighting + fetch initial tokens.
                        boolean sem =
                                host.settings().isSemanticHighlight() && lspManager.supportsSemanticTokens(b.getPath());
                        b.setSemanticActive(sem);
                        if (sem) {
                            requestSemanticTokens(b);
                        }
                    }
                });
                requestStructureSymbols(host.activeBuffer()); // the outline can now be populated from the server
            }
        }
    }

    /** Restarts every running server, clears diagnostics, then re-gates each buffer ({@code lsp.restartServers}). */
    void restartServers() {
        lspManager.shutdownAll();
        clearAllDiagnostics();
        applyGating();
        host.setStatus(tr("status.lsp.restarted"));
    }

    /** Notifies the server of a save (didSave) for a managed file + refreshes pull-model diagnostics. */
    void notifyDocumentSaved(EditorBuffer buffer) {
        if (buffer != null && buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
            lspManager.saveDocument(buffer.getPath());
            lspManager.pullDiagnostics(buffer.getPath()); // no-op for push-only servers
        }
    }

    /** Wires every LSP hook onto a freshly-added buffer (didChange/diagnostics/completion/format/nav), then
     *  opens+activates it if eligible. Called from {@code MainController.addBuffer}. */
    void wireBuffer(EditorBuffer buffer) {
        // Debounced didChange sink + keep the Structure outline live as the document changes.
        buffer.setLspChangeListener(text -> {
            if (buffer.getPath() != null) {
                lspManager.changeDocument(buffer.getPath(), text);
                requestStructureSymbols(buffer);
            }
        });
        // Pull-model diagnostics (fired on the same debounce as didChange; no-op for push-only servers).
        buffer.setLspDiagnosticsRequester(() -> {
            if (buffer.getPath() != null) {
                lspManager.pullDiagnostics(buffer.getPath());
            }
        });
        // Semantic tokens re-request (fired on the same debounce as didChange + on scroll-settle).
        buffer.setSemanticTokensRequester(() -> requestSemanticTokens(buffer));
        buffer.setLspCompletionProvider((pos, cb) -> {
            if (buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
                lspManager.completion(
                        buffer.getPath(),
                        pos[0],
                        pos[1],
                        items -> cb.accept(
                                com.editora.lsp.CompletionMapper.map(items, item -> autoImportAccept(buffer, item))));
            } else {
                cb.accept(java.util.List.of());
            }
        });
        // Lazy documentation for the completion doc side-popup: resolve the item's docs on demand.
        buffer.setCompletionDocResolver((token, cb) -> {
            if (buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
                lspManager.resolveCompletionDoc(buffer.getPath(), token, cb);
            } else {
                cb.accept(null);
            }
        });
        buffer.setCompletionDocEnabled(host.settings().isCompletionDoc());
        // Tab re-indents the current line to the server's convention via range formatting (when supported).
        buffer.setLspRangeFormatter((sl, sc, el, ec, cb) -> {
            if (buffer.getPath() != null && lspManager.isManaged(buffer.getPath())) {
                int tabSize = host.settings().getTabSize();
                lspManager.rangeFormatting(
                        buffer.getPath(), sl, sc, el, ec, tabSize, buffer.detectInsertSpaces(tabSize), cb);
            } else {
                cb.accept(java.util.List.of());
            }
        });
        buffer.setLspNavActions(this::gotoDefinition, this::findReferences, this::showHover, this::formatDocument);
        syncBuffer(buffer);
    }

    /** Palette picker over the LSP servers: toggles the chosen server's per-server enable ({@code lsp.toggleServer}). */
    void chooseServerToggle() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.lsp.toggleServer"),
                tr("palette.setting.pick"),
                () -> List.of(SERVER_IDS),
                id -> id + "  —  " + tr(serverEnabled(id) ? "common.on" : "common.off"),
                this::serverLabel,
                id -> {
                    if (id == null) {
                        return;
                    }
                    boolean next = !serverEnabled(id);
                    setServerEnabled(id, next);
                    host.requestSave();
                    applySupport();
                    host.syncSettingsWindow();
                    host.setStatus(tr("status.settingToggled", id, tr(next ? "common.on" : "common.off")));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Palette picker over the LSP servers, then prompts for the chosen server's command ({@code lsp.setServerCommand}). */
    void chooseServerCommand() {
        QuickOpen<String> picker = new QuickOpen<>(
                tr("command.lsp.setServerCommand"),
                tr("palette.setting.pick"),
                () -> List.of(SERVER_IDS),
                id -> id,
                this::serverLabel,
                id -> {
                    if (id == null) {
                        return;
                    }
                    host.promptText(id, tr("palette.setting.value"), serverCommand(id), v -> {
                        String value = v.trim();
                        setServerCommand(id, value);
                        host.requestSave();
                        applySupport();
                        host.syncSettingsWindow();
                        host.setStatus(tr("status.settingChanged", id, value));
                    });
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** The active buffer if it is LSP-managed, reporting + returning null otherwise. */
    private EditorBuffer activeLspBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !lspManager.isManaged(b.getPath())) {
            host.setStatus(tr("status.lsp.unavailable"));
            return null;
        }
        return b;
    }

    void gotoDefinition() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.definition(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noDefinition"));
            } else {
                LspManager.Target t = targets.get(0);
                ops.openAndGoto(t.file(), t.line(), t.character());
            }
        });
    }

    void findReferences() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.references(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), targets -> {
            if (targets.isEmpty()) {
                host.setStatus(tr("status.lsp.noReferences"));
                return;
            }
            if (targets.size() == 1) {
                LspManager.Target t = targets.get(0); // a lone reference: jump straight there (IDE behavior)
                ops.openAndGoto(t.file(), t.line(), t.character());
                return;
            }
            List<ReferencesPanel.Reference> refs = new java.util.ArrayList<>(targets.size());
            for (LspManager.Target t : targets) {
                refs.add(new ReferencesPanel.Reference(t.file(), t.line(), t.character(), previewLine(t)));
            }
            referencesPanel.setReferences(refs);
            ops.openReferencesWindow();
        });
    }

    /** A one-line preview of a reference — from the file's open buffer when one holds it (cheap, FX-safe,
     *  reflects unsaved edits), else empty (closed files show just the line number; no disk I/O on the FX thread). */
    private String previewLine(LspManager.Target t) {
        EditorBuffer buffer = ops.bufferForPath(t.file());
        if (buffer == null) {
            return "";
        }
        String[] lines = buffer.getContent().split("\n", -1);
        return t.line() >= 0 && t.line() < lines.length ? lines[t.line()].strip() : "";
    }

    /**
     * Opens the "Go to Symbol in Workspace" popup ({@code workspace/symbol}) — an incremental picker that
     * re-queries the active file's language server as you type, jumping to the chosen symbol across files.
     * Seeds the query from a single-line selection when present.
     */
    void gotoSymbolInWorkspace() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        if (!lspManager.supportsWorkspaceSymbols(b.getPath())) {
            host.setStatus(tr("status.lsp.workspaceSymbolsUnsupported"));
            return;
        }
        Path anchor = b.getPath();
        String sel = b.getFocusedArea().getSelectedText();
        String seed = sel != null && !sel.isBlank() && !sel.contains("\n") ? sel.trim() : "";
        WorkspaceSymbolPopup popup = new WorkspaceSymbolPopup(host.overlayHost(), new WorkspaceSymbolPopup.Ops() {
            @Override
            public void query(
                    String text, java.util.function.Consumer<java.util.List<LspManager.WorkspaceSymbolMatch>> cb) {
                lspManager.workspaceSymbols(anchor, text, cb);
            }

            @Override
            public void open(Path file, int line, int character) {
                ops.openAndGoto(file, line, character);
            }
        });
        popup.show(seed);
    }

    void showHover() {
        EditorBuffer b = activeLspBuffer();
        if (b == null) {
            return;
        }
        CodeArea area = b.getFocusedArea();
        lspManager.changeDocument(b.getPath(), b.text()); // sync latest text before the request
        lspManager.hover(b.getPath(), area.getCurrentParagraph(), area.getCaretColumn(), text -> {
            if (text == null || text.isBlank()) {
                host.setStatus(tr("status.lsp.noHover"));
            } else {
                showHoverPopup(area, text);
            }
        });
    }

    /** Reformats the whole active file via its language server ({@code textDocument/formatting}), if the
     *  server is running and advertises formatting. Edits apply through the undoable buffer. */
    void formatDocument() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || buffer.getPath() == null || !ops.activeEditable()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        Path path = buffer.getPath();
        if (!lspManager.isManaged(path) || !lspManager.supportsFormatting(path)) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return;
        }
        int tabSize = host.settings().getTabSize();
        host.setStatus(tr("status.lsp.formatting"));
        // The server computes whole-document edits (line/col based) against the text as it is NOW. If the
        // user edits during the async round-trip, those offsets no longer line up — and applyLspEdits only
        // clamps + swallows, so a stale format silently corrupts the file (every line mis-formatted/shifted).
        // Snapshot the text and drop the reply if it changed, mirroring tryLspReindentLine's line guard.
        String snapshot = buffer.getContent();
        lspManager.formatDocument(path, tabSize, buffer.detectInsertSpaces(tabSize), edits -> {
            if (buffer != host.activeBuffer()) {
                return; // user switched tabs before the server replied
            }
            if (!buffer.getContent().equals(snapshot)) {
                host.setStatus(tr("status.lsp.formatStale")); // edited mid-format — a re-run formats cleanly
                return;
            }
            if (edits.isEmpty()) {
                host.setStatus(tr("status.lsp.formatNoChange"));
                return;
            }
            buffer.applyLspEdits(edits);
            host.setStatus(tr("status.lsp.formatted"));
        });
    }

    /**
     * Shows LSP hover markdown in a dismissable popup at the caret (rendered via the Markdown renderer).
     * Closes on Escape, a click elsewhere (auto-hide), caret movement, scrolling, or another hover.
     */
    private void showHoverPopup(CodeArea area, String markdown) {
        hideHoverPopup();
        Node content;
        try {
            content = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(markdown), null);
        } catch (RuntimeException e) {
            Label label = new Label(markdown);
            label.setWrapText(true);
            content = label;
        }
        VBox box = new VBox(content);
        box.getStyleClass().add("lsp-hover-popup");
        box.setMaxWidth(560);
        box.getStylesheets()
                .addAll(
                        getClass().getResource("/com/editora/styles/app.css").toExternalForm(),
                        getClass().getResource("/com/editora/styles/syntax.css").toExternalForm());

        Popup popup = new Popup();
        popup.setAutoHide(true); // click outside / focus loss dismisses it
        popup.setConsumeAutoHidingEvents(false);
        popup.getContent().add(box);
        hoverPopup = popup;

        // Dismiss on Escape, caret movement, or scroll — all detached again when the popup hides.
        EventHandler<KeyEvent> esc = ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideHoverPopup();
                ev.consume();
            }
        };
        ChangeListener<Object> dismiss = (o, a, b) -> hideHoverPopup();
        area.addEventFilter(KeyEvent.KEY_PRESSED, esc);
        area.caretPositionProperty().addListener(dismiss);
        area.estimatedScrollYProperty().addListener(dismiss);
        popup.setOnHidden(ev -> {
            area.removeEventFilter(KeyEvent.KEY_PRESSED, esc);
            area.caretPositionProperty().removeListener(dismiss);
            area.estimatedScrollYProperty().removeListener(dismiss);
            if (hoverPopup == popup) {
                hoverPopup = null;
            }
        });

        var bounds = area.getCaretBounds().orElse(null);
        if (bounds != null) {
            popup.show(area, bounds.getMinX(), bounds.getMaxY());
        } else {
            popup.show(area, 0, 0);
        }
    }

    /** Hides the LSP hover popup if one is showing. */
    private void hideHoverPopup() {
        if (hoverPopup != null) {
            hoverPopup.hide();
            hoverPopup = null;
        }
    }
}
