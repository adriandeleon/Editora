package com.editora.ui;

/**
 * Pure, unit-tested decision logic for window chrome — extracted from {@link MainController} so it can be
 * tested without the JavaFX toolkit. Two concerns:
 *
 * <ul>
 *   <li><b>Effective visibility</b> of the chrome + editor view options under the two per-window overlays,
 *       <b>Zen</b> and <b>Simple UI</b> mode. Both hide UI <em>without mutating the saved prefs</em>, so the
 *       effective value is "saved pref AND not overridden". Centralizing the truth table here keeps the Zen
 *       and Simple semantics consistent and guards against regressions (Zen used to desync — see the Zen
 *       per-window fix).
 *   <li><b>Command-palette visibility</b>: which command ids are shown given each optional feature's enabled
 *       state (a feature's commands are hidden while it's off).
 * </ul>
 *
 * <p>No JavaFX dependency — {@link MainController} evaluates the live booleans and delegates the decisions.
 */
final class Chrome {

    private Chrome() {}

    // --- Effective chrome visibility. The "focus modes" (Zen and Expert) hide all of these; Simple
    //     additionally hides the breadcrumb + tool stripes (the toolbar/status/tab bar stay under Simple).
    //     {@code focusMode} = Zen OR Expert. The exceptions are {@link #statusBar} and {@link #lineNumbers},
    //     which Zen hides but Expert deliberately KEEPS, so those two stay keyed on the real {@code zen}. ---

    static boolean toolbar(boolean showToolbar, boolean focusMode) {
        return showToolbar && !focusMode;
    }

    /** The status bar is hidden by Zen but KEPT by Expert, so it stays keyed on the real {@code zen} flag. */
    static boolean statusBar(boolean showStatusBar, boolean zen) {
        return showStatusBar && !zen;
    }

    static boolean tabBar(boolean showTabBar, boolean focusMode) {
        return showTabBar && !focusMode;
    }

    static boolean breadcrumb(boolean showBreadcrumb, boolean focusMode, boolean simple) {
        return showBreadcrumb && !focusMode && !simple;
    }

    static boolean toolStripes(boolean showToolStripe, boolean focusMode, boolean simple) {
        return showToolStripe && !focusMode && !simple;
    }

    // --- Effective editor view options. Expert keeps the whole editor view (line numbers, ruler,
    //     current-line highlight, minimap), so only Zen (not focus) hides those — Expert strips just the
    //     surrounding window chrome. Whitespace guides are the one editor decoration a focus mode still
    //     hides. Simple additionally hides line numbers + minimap and removes the whole gutter. ---

    /** The column ruler is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag. */
    static boolean columnRuler(boolean show, boolean zen) {
        return show && !zen;
    }

    /** Current-line highlight is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag. */
    static boolean lineHighlight(boolean on, boolean zen) {
        return on && !zen;
    }

    static boolean whitespace(boolean show, boolean focusMode) {
        return show && !focusMode;
    }

    /** Line numbers are hidden by Zen but KEPT by Expert, so they stay keyed on the real {@code zen} flag. */
    static boolean lineNumbers(boolean show, boolean zen, boolean simple) {
        return show && !zen && !simple;
    }

    /** The minimap is hidden by Zen but KEPT by Expert, so it keys on the real {@code zen} flag (Simple also
     *  hides it). */
    static boolean minimap(boolean show, boolean zen, boolean simple) {
        return show && !zen && !simple;
    }

    /** The whole gutter strip (line numbers, fold chevrons, bookmark/note/run/breakpoint/git slots). */
    static boolean gutter(boolean simple) {
        return !simple;
    }

    // --- Command-palette visibility ---

    /**
     * The optional features whose commands are filtered out of the palette while the feature is off. Each
     * flag is the feature's <em>effective</em> enabled state (already folding in Simple-UI mode where
     * applicable), as evaluated by {@code MainController}.
     */
    record PaletteGates(
            boolean projects,
            boolean git,
            boolean notes,
            boolean mermaid,
            boolean diagram,
            boolean typst,
            java.util.Set<String> disabledBuildToolIds,
            boolean lsp,
            boolean http,
            boolean htmlPreview,
            boolean localHistory,
            boolean mcp,
            boolean plugins,
            boolean externalTools,
            boolean log) {}

    /**
     * Whether the command palette should show command {@code id} given the feature gates. A command whose
     * id belongs to a disabled feature (by id prefix or an exact tool-window id) is hidden; everything else
     * is shown.
     */
    static boolean paletteVisible(String id, PaletteGates g) {
        if (!g.projects() && (id.startsWith("project.") || id.equals("tool.project"))) {
            return false;
        }
        if (!g.git() && (id.startsWith("git.") || id.equals("tool.commit") || id.equals("tool.gitLog"))) {
            return false;
        }
        if (!g.notes() && (id.startsWith("notes.") || id.equals("tool.notes"))) {
            return false;
        }
        if (!g.mermaid() && id.startsWith("mermaid.")) {
            return false;
        }
        if (!g.diagram() && id.startsWith("diagram.")) {
            return false;
        }
        if (!g.typst() && id.startsWith("typst.")) {
            return false;
        }
        for (String buildToolId : g.disabledBuildToolIds()) {
            if (id.startsWith(buildToolId + ".") || id.equals("tool." + buildToolId)) {
                return false;
            }
        }
        if (!g.lsp() && (id.startsWith("lsp.") || id.equals("tool.problems"))) {
            return false;
        }
        if (!g.http() && (id.startsWith("http.") || id.equals("tool.http"))) {
            return false;
        }
        if (!g.htmlPreview() && id.startsWith("htmlPreview.")) {
            return false;
        }
        if (!g.localHistory() && id.equals("tool.fileHistory")) {
            return false;
        }
        if (!g.mcp() && id.startsWith("mcp.")) {
            return false;
        }
        if (!g.plugins() && id.startsWith("plugins.")) {
            return false;
        }
        if (!g.externalTools() && (id.startsWith("externalTool.") || id.equals("tool.externalTools"))) {
            return false;
        }
        // The master enable toggle uses the view.* prefix, so it is never hidden by this gate.
        if (!g.log() && id.startsWith("log.")) {
            return false;
        }
        return true;
    }
}
