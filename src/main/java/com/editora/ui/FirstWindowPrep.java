package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.Settings;
import com.editora.config.SharedConfig;

/**
 * The disk work a window's set-up would otherwise do on the FX thread, done ahead by the launch on its
 * config-prefetch thread, which {@code App.start} waits for once (PERF-01, Pauta 6.0.1). Each piece leaves its
 * answer where the window looks for it: the user's themes in {@link Themes}, the zoomed stylesheets in
 * {@link com.editora.editor.UiZoom}, the recent files and search history in {@link SharedConfig}, and the
 * probes of the language servers', the debugger's and the configured tools' files in their caches.
 *
 * <p>Every piece is best-effort: one that fails is logged and the window does that work itself, as before.
 */
public final class FirstWindowPrep {

    private static final Logger LOG = Logger.getLogger(FirstWindowPrep.class.getName());

    private FirstWindowPrep() {}

    /** The stylesheets every window's scene is built with, besides the theme's: {@code app.css}, {@code syntax.css}. */
    static List<String> windowSheets() {
        return List.of(
                WindowManager.class.getResource("/com/editora/styles/app.css").toExternalForm(),
                WindowManager.class
                        .getResource("/com/editora/styles/syntax.css")
                        .toExternalForm());
    }

    /**
     * Does it all on the calling thread. {@code moreSheets} are the other sheets the first window is built with
     * (the UI font's, which {@code App} owns).
     */
    public static void prepare(SharedConfig shared, List<String> moreSheets) {
        Settings settings = shared.getSettings();
        Path configDir = shared.getConfigDir();
        // The user's themes, before any sheet is named: a user theme saved as the active one resolves on launch.
        step("user themes", () -> Themes.loadUserThemes(configDir));
        step("zoomed stylesheets", () -> {
            List<String> sheets = new ArrayList<>(windowSheets());
            sheets.addAll(moreSheets);
            String theme = Themes.stylesheetFor(settings.getTheme());
            if (theme != null) {
                sheets.add(theme);
            }
            com.editora.editor.UiZoom.prepare(sheets, settings.getUiZoom());
        });
        // The shared lists every window shows: read once, here, rather than when the window manager is made.
        step("recent files", shared::recentFiles);
        step("search history", shared::searchHistory);
        // What the language servers, the debugger and the configured tools find on disk (their caches).
        step("server copies", () -> LspServerCommands.prepareOwnCopies(settings, configDir));
        step("java-debug", () -> com.editora.dap.DapManager.prepareLocate(settings.getJavaDebugPluginPath()));
        step("xml schemas", () -> com.editora.lsp.XmlBundledSchemas.ensure(configDir));
        step(
                "loose-files root",
                () -> com.editora.lsp.LspManager.makeLooseRoot(configDir
                        .resolve(LspCoordinator.LOOSE_ROOT)
                        .toAbsolutePath()
                        .normalize()));
        step(
                "tool paths",
                () -> com.editora.process.ConfiguredCommand.prepare(java.util.Arrays.asList(
                        settings.getGitPath(),
                        settings.getGhPath(),
                        blankOr(settings.getMmdcPath(), com.editora.mermaid.MermaidService.DEFAULT_MMDC),
                        blankOr(settings.getMaidPath(), com.editora.mermaid.MermaidService.DEFAULT_MAID),
                        settings.getDotPath(),
                        settings.getPlantumlPath(),
                        settings.getTypstPath(),
                        blankOr(settings.getRipgrepCommand(), com.editora.search.Ripgrep.DEFAULT_COMMAND),
                        settings.getContainerEngine())));
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static void step(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not prepare " + what + " ahead of the first window", e);
        }
    }
}
