package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import com.editora.AppInfo;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.plugin.ActiveEditor;
import com.editora.plugin.Plugin;
import com.editora.plugin.PluginContext;
import com.editora.plugin.PluginDescriptor;
import com.editora.plugin.PluginInstaller;
import com.editora.plugin.PluginManager;
import com.editora.plugin.PluginManifest;
import com.editora.plugin.PluginProblem;
import com.editora.plugin.PluginRegistry;
import com.editora.plugin.RegistryEntry;
import com.editora.plugin.ToolWindowSide;
import com.editora.process.ProcessRunner;
import com.editora.snippet.SnippetManager;
import com.editora.template.TemplateRegistry;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Plugin support — apply/install/uninstall + the per-window {@link PluginContext} adapter that lets a plugin
 * contribute commands, keybindings, tool windows, status-bar segments, and editor menu items. Extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. The {@link PluginManager} itself is shared
 * (owned by {@link WindowManager}, classes load once) and passed in; this coordinator owns the per-window
 * {@link PluginRegistry}/{@link PluginInstaller}, the started-plugin instances, the plugin-contributed tool
 * windows + editor menu items, and the "Browse Plugins" picker.
 *
 * <p>What runs: {@link #applyPlugins} applies the session's {@linkplain PluginManager#launchSet() launch set},
 * so nothing installed, updated, enabled or disabled during the session runs before a restart. Each plugin's
 * contributions are recorded, and taken back when its {@code start()} throws. Installing goes stage → consent →
 * commit: the archive is unpacked beside the plugins, the user is shown what it can do, and only an accepted
 * install replaces anything (see {@link PluginInstaller}).
 */
final class PluginCoordinator {

    private static final Logger LOG = Logger.getLogger(PluginCoordinator.class.getName());

    /** A {@code start()} slower than this on the UI thread is named in the Message Log and on its Settings row. */
    static final long SLOW_START_MS = 250;

    /** A plugin-contributed editor menu item: a label + an action over the {@link ActiveEditor}. */
    private record EditorMenuContribution(String pluginName, String label, Consumer<ActiveEditor> action) {}

    /** Window hooks beyond the injected collaborators that the plugin flows need. */
    interface Ops {
        /** Opens (or focuses) the tab for {@code file}. */
        void openPath(Path file);

        /** Shows a scrollable error dialog (summary + multi-line detail), titled "Plugins". */
        void showError(String summary, String detail);
    }

    private final CoordinatorHost host;
    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final SnippetManager snippets;
    private final TemplateRegistry templates;
    private final ToolWindowManager toolWindows;
    private final StatusBar statusBar;
    private final SettingsWindow settingsWindow;
    private final ConfigManager config;
    private final @Nullable PluginManager pluginManager;
    private final Ops ops;

    private final @Nullable PluginRegistry pluginRegistry;
    private final @Nullable PluginInstaller pluginInstaller;
    private final List<Plugin> startedPlugins = new ArrayList<>();

    /** The plugin manager, on paths only reached while plugins exist in this window (install, a plugin's API). */
    private PluginManager manager() {
        if (pluginManager == null) {
            throw new IllegalStateException("plugins are off in this window");
        }
        return pluginManager;
    }

    /** The installer: it and the registry exist exactly when {@link #pluginManager} does. */
    private PluginInstaller installer() {
        if (pluginInstaller == null) {
            throw new IllegalStateException("plugins are off in this window");
        }
        return pluginInstaller;
    }

    private final List<EditorMenuContribution> pluginMenuItems = new ArrayList<>();
    private final List<ToolWindow> pluginToolWindows = new ArrayList<>();

    private final QuickOpen<RegistryEntry> browsePalette;
    private List<RegistryEntry> browseEntries = List.of();
    private boolean browseSigned;
    /** Settings stepped aside for the picker: shown again when the picker closes and the flow it started ends. */
    private boolean settingsHiddenForPicker;
    /** An install is between "staging" and its answer: a second one waits for it. */
    private boolean installing;

    PluginCoordinator(
            CoordinatorHost host,
            CommandRegistry registry,
            KeymapManager keymap,
            SnippetManager snippets,
            TemplateRegistry templates,
            ToolWindowManager toolWindows,
            StatusBar statusBar,
            SettingsWindow settingsWindow,
            ConfigManager config,
            @Nullable PluginManager pluginManager,
            Ops ops) {
        this(
                host,
                registry,
                keymap,
                snippets,
                templates,
                toolWindows,
                statusBar,
                settingsWindow,
                config,
                pluginManager,
                ops,
                pluginManager != null ? new PluginRegistry() : null,
                pluginManager != null ? new PluginInstaller(pluginManager) : null);
    }

    /**
     * As the constructor above, with the registry client and the installer supplied — the seam that lets a
     * test point the browse and install flows at a loopback server.
     */
    PluginCoordinator(
            CoordinatorHost host,
            CommandRegistry registry,
            KeymapManager keymap,
            SnippetManager snippets,
            TemplateRegistry templates,
            ToolWindowManager toolWindows,
            StatusBar statusBar,
            SettingsWindow settingsWindow,
            ConfigManager config,
            @Nullable PluginManager pluginManager,
            Ops ops,
            @Nullable PluginRegistry pluginRegistry,
            @Nullable PluginInstaller pluginInstaller) {
        this.host = host;
        this.registry = registry;
        this.keymap = keymap;
        this.snippets = snippets;
        this.templates = templates;
        this.toolWindows = toolWindows;
        this.statusBar = statusBar;
        this.settingsWindow = settingsWindow;
        this.config = config;
        this.pluginManager = pluginManager;
        this.ops = ops;
        this.pluginRegistry = pluginRegistry;
        this.pluginInstaller = pluginInstaller;
        this.browsePalette = new QuickOpen<>(
                tr("plugins.browseTitle"),
                tr("plugins.browsePrompt"),
                () -> browseEntries,
                this::browseEntryLabel,
                this::browseEntryDetail,
                e -> (e.name == null ? "" : e.name) + " " + e.id + " " + (e.author == null ? "" : e.author) + " "
                        + (e.description == null ? "" : e.description), // search name+id+author+desc
                this::confirmAndInstall);
        this.browsePalette.setPreferredSize(960, 10); // wide — registry rows carry a name + a description
        this.browsePalette.setOverlayHost(host.overlayHost());
        this.browsePalette.setItemStyleClass(e -> meetsMinEditora(e) ? null : "plugin-browse-unavailable");
        // Esc (no choice): Settings comes back if it stepped aside for the picker.
        this.browsePalette.setPreview(e -> {}, this::restoreSettings);
    }

    /** Whether Browse and Install may be used now — the master switch as set (also off in Simple UI mode). */
    boolean isEnabled() {
        return pluginManager != null && config.getSettings().isPluginSupport() && !host.simpleModeActive();
    }

    /** Why {@link #isEnabled()} is false, as the status to show. */
    private String disabledReason() {
        if (host.simpleModeActive()) {
            return tr("status.plugins.simpleMode");
        }
        return tr("status.plugins.disabled");
    }

    /** Stops this window's plugins (window close). Each {@code stop()} is isolated. */
    void disposePlugins() {
        for (Plugin p : startedPlugins) {
            try {
                p.stop();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Plugin stop failed", t);
            }
        }
        startedPlugins.clear();
        // The catalogue and install workers are this window's: without this each closed window left two
        // parked threads (and, once used, two HTTP clients) behind for the life of the process.
        if (pluginRegistry != null) {
            pluginRegistry.shutdown();
        }
        if (pluginInstaller != null) {
            pluginInstaller.shutdown();
        }
    }

    /** Gates the plugin-contributed tool windows on an open buffer (called from updateBufferToolWindows). */
    void gateToolWindows(boolean hasBuffer) {
        for (ToolWindow tw : pluginToolWindows) {
            toolWindows.setAvailable(tw, hasBuffer);
        }
    }

    /** Builds the plugin-contributed right-click items for {@code buffer} (empty when no plugin added any). */
    List<MenuItem> editorMenuItems(EditorBuffer buffer) {
        if (pluginMenuItems.isEmpty()) {
            return List.of();
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new javafx.scene.control.SeparatorMenuItem()); // apart from Print / Export to PDF above
        for (EditorMenuContribution c : pluginMenuItems) {
            MenuItem mi = new MenuItem(c.label());
            mi.setGraphic(Icons.plugin());
            mi.setOnAction(
                    e -> guarded(c.pluginName(), c.label(), () -> c.action().accept(new ActiveEditorImpl(buffer))));
            items.add(mi);
        }
        return items;
    }

    /** Toggles the "Enable plugins" master setting (palette {@code view.togglePlugins}); restart to apply. */
    void toggleSupport() {
        var s = config.getSettings();
        s.setPluginSupport(!s.isPluginSupport());
        host.requestSave();
        settingsWindow.syncPluginsCheck();
        host.setStatus(tr("status.toggle.plugins", tr(s.isPluginSupport() ? "common.on" : "common.off")));
    }

    // --- applying the launch set -----------------------------------------------------------------

    /** What one plugin added to this window, so a failed start can take it back. */
    private final class Contributions {
        final PluginDescriptor plugin;
        final List<String> commands = new ArrayList<>();
        final List<ToolWindow> toolWindowsAdded = new ArrayList<>();
        final List<EditorMenuContribution> menuItems = new ArrayList<>();
        final List<Node> segments = new ArrayList<>();
        /** chord → what it ran before this plugin bound it (null: nothing), to restore on a rollback. */
        final Map<String, String> replacedChords = new java.util.LinkedHashMap<>();

        Contributions(PluginDescriptor plugin) {
            this.plugin = plugin;
        }

        void rollBack() {
            commands.forEach(registry::remove);
            for (ToolWindow tw : toolWindowsAdded) {
                toolWindows.setAvailable(tw, false);
                pluginToolWindows.remove(tw);
            }
            pluginMenuItems.removeAll(menuItems);
            segments.forEach(statusBar::removePluginSegment);
            Map<String, String> restore = new java.util.LinkedHashMap<>();
            replacedChords.forEach((chord, previous) -> restore.put(chord, previous == null ? "" : previous));
            keymap.applyOverrides(restore);
        }
    }

    /**
     * Applies the session's launch set to <em>this</em> window: every enabled, error-free plugin's snippet and
     * template dirs, keymap (for the active keymap), declared external commands, then each Java plugin's
     * {@code start(ctx)}, timed. One {@code try/catch} per plugin: a plugin that cannot be built or whose
     * {@code start} throws is reported (Message Log, Settings row), its contributions are taken back and its
     * {@code stop()} is called, so a misbehaving plugin never breaks the window or leaves half of itself
     * behind. Simple UI mode applies nothing.
     */
    void applyPlugins() {
        if (pluginManager == null || host.simpleModeActive()) {
            return;
        }
        boolean addedAssets = false;
        for (PluginDescriptor d : pluginManager.launchSet()) {
            if (!d.enabled() || d.loadError() != null) {
                continue;
            }
            Contributions c = new Contributions(d);
            try {
                // Declarative: snippet/template source dirs (per-window registries) + keymap (shared).
                Path snDir = d.dir().resolve("snippets");
                if (Files.isDirectory(snDir)) {
                    snippets.addExtraSourceDir(snDir);
                    addedAssets = true;
                }
                Path tplDir = d.dir().resolve("templates");
                if (Files.isDirectory(tplDir)) {
                    templates.addExtraSourceDir(tplDir);
                    addedAssets = true;
                }
                Map<String, String> chords = d.manifest().qualifiedKeymapFor(keymap.activeName());
                for (Map.Entry<String, String> chord : chords.entrySet()) {
                    bindChord(c, chord.getKey(), chord.getValue());
                }
                // Declarative external commands → palette commands.
                for (PluginManifest.DeclaredCommand cmd : d.manifest().commands) {
                    if (cmd == null || cmd.id == null || cmd.id.isBlank()) {
                        continue;
                    }
                    String cid = ownId(d, cmd.id);
                    String title =
                            cmd.title == null || cmd.title.isBlank() ? d.displayName() + ": " + cmd.id : cmd.title;
                    register(c, cid, title, () -> runDeclaredCommand(d, cmd));
                }
                // Java plugin.
                if (d.hasJavaEntry() && d.classLoader() != null) {
                    Plugin plugin = pluginManager.instantiate(d);
                    if (plugin == null) {
                        // A wrong main class used to be logged and nothing else: a ticked, error-free row
                        // that did nothing. The manager recorded why; the Settings row says it.
                        c.rollBack();
                        host.setError(tr("status.plugins.failed", d.displayName()));
                        continue;
                    }
                    long t0 = System.nanoTime();
                    try {
                        plugin.start(new PluginContextImpl(d, c));
                    } catch (Throwable t) {
                        LOG.log(Level.WARNING, "Plugin " + d.id() + " start failed", t);
                        c.rollBack();
                        try {
                            plugin.stop(); // give it the chance to release what it opened before throwing
                        } catch (Throwable ignored) {
                            // already failing; the start failure is what is reported
                        }
                        pluginManager.recordRuntimeProblem(
                                d.id(),
                                new PluginDescriptor.LoadProblem(
                                        PluginDescriptor.LoadProblem.Kind.START_FAILED, List.of(), String.valueOf(t)));
                        host.setError(tr("status.plugins.startFailed", d.displayName(), String.valueOf(t)));
                        continue;
                    }
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (ms >= SLOW_START_MS) {
                        LOG.warning("Plugin " + d.id() + " start() took " + ms + " ms on the UI thread");
                        pluginManager.recordRuntimeProblem(
                                d.id(),
                                new PluginDescriptor.LoadProblem(
                                        PluginDescriptor.LoadProblem.Kind.SLOW_START, List.of(String.valueOf(ms)), ""));
                        host.setStatus(tr("status.plugins.slowStart", d.displayName(), ms));
                    }
                    startedPlugins.add(plugin);
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Plugin " + d.id() + " failed to apply", t);
                c.rollBack();
                pluginManager.recordRuntimeProblem(
                        d.id(),
                        new PluginDescriptor.LoadProblem(
                                PluginDescriptor.LoadProblem.Kind.START_FAILED, List.of(), String.valueOf(t)));
                host.setError(tr("status.plugins.failed", d.displayName()));
            }
        }
        if (addedAssets) {
            snippets.reload();
            templates.reload();
        }
        reapplyUserBindings();
    }

    /** {@code id} in plugin {@code d}'s namespace ({@code plugin.<id>.…}), unless it is there already. */
    static String ownId(PluginDescriptor d, String id) {
        String prefix = "plugin." + d.id() + ".";
        String bare = id == null ? "" : id.strip();
        return bare.startsWith(prefix) ? bare : prefix + bare;
    }

    /**
     * Registers a plugin command through one guard: a throwing action is reported with the plugin's name
     * (status + Message Log), not left to the uncaught-exception log.
     */
    private void register(Contributions c, String id, String title, Runnable action) {
        String name = c.plugin.displayName();
        registry.register(Command.of(id, title, () -> guarded(name, title, action)));
        c.commands.add(id);
    }

    /** Runs a plugin action; a failure is told to the user with the plugin and action named. */
    private void guarded(String pluginName, String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Plugin " + pluginName + " action " + what + " failed", t);
            host.setError(tr("status.plugins.actionFailed", pluginName, what, String.valueOf(t)));
        }
    }

    /**
     * Binds {@code chord} for plugin {@code c} in the shared keymap, remembering what it ran before (for a
     * rollback) and saying so in the Message Log when it takes a chord another command had.
     */
    private void bindChord(Contributions c, String chord, String commandId) {
        String before = keymap.commandFor(chord);
        if (!c.replacedChords.containsKey(chord)) {
            c.replacedChords.put(chord, before);
        }
        keymap.applyOverrides(Map.of(chord, commandId == null ? "" : commandId));
        boolean usersOwn = KeymapLayers.userBindings(
                        config.getSettings().keybindingsFor(com.editora.command.KeymapManager.isMac()))
                .containsKey(chord); // the user's binding is put back on top: nothing was taken from them
        if (before != null
                && !usersOwn
                && !before.equals(commandId)
                && !before.startsWith("plugin." + c.plugin.id() + ".")) {
            String title = registry.get(before).map(Command::title).orElse(before);
            host.setStatus(tr("status.plugins.chordReplaced", c.plugin.displayName(), keymap.display(chord), title));
        }
    }

    /**
     * Puts the user's own key bindings back on top of whatever plugins just bound. Plugin keymaps are applied
     * after the user's overrides, so without this a plugin took back any chord the user had rebound.
     */
    private void reapplyUserBindings() {
        keymap.applyOverrides(KeymapLayers.userBindings(
                config.getSettings().keybindingsFor(com.editora.command.KeymapManager.isMac())));
    }

    /** Runs a plugin's declared external command via the subprocess runner; reports the result. */
    private void runDeclaredCommand(PluginDescriptor d, PluginManifest.DeclaredCommand c) {
        if (c.run == null || c.run.isEmpty()) {
            return;
        }
        Path cwd = (c.dir == null || c.dir.isBlank())
                ? d.dir()
                : d.dir().resolve(c.dir).normalize();
        String title = c.title == null || c.title.isBlank() ? d.displayName() + ": " + c.id : c.title;
        host.setStatus(tr("status.plugins.running", title));
        Thread t = new Thread(
                () -> {
                    ProcessRunner.Result r;
                    try {
                        r = ProcessRunner.runInUserLocale(cwd, Duration.ofSeconds(120), new ArrayList<>(c.run));
                    } catch (RuntimeException e) {
                        Platform.runLater(() -> host.setError(tr("status.plugins.cmdFailed", e.getMessage())));
                        return;
                    }
                    String out = (r.out() + "\n" + r.err()).strip();
                    if (!out.isBlank()) {
                        LOG.info("[plugin " + d.id() + "] " + out);
                    }
                    // What it printed reaches the user: the last line in the status, all of it in the Message Log.
                    String shown = out.length() > 4000 ? out.substring(0, 4000) + "…" : out;
                    Platform.runLater(() -> {
                        if (r.ok()) {
                            host.setStatus(
                                    shown.isBlank()
                                            ? tr("status.plugins.cmdDone", title)
                                            : tr("status.plugins.cmdOutput", title, shown));
                        } else {
                            host.setError(tr("status.plugins.cmdFailedOutput", title, String.valueOf(r.exit()), shown));
                        }
                    });
                },
                "plugin-cmd-" + d.id());
        t.setDaemon(true); // created off the non-daemon FX thread; don't let a running command block JVM exit
        t.start();
    }

    // --- feedback where the user is ------------------------------------------------------------------

    /** A plugin-flow status: in the main window's status bar and, while Settings is open, on its Plugins page. */
    private void report(String message) {
        host.setStatus(message);
        if (settingsWindow != null) {
            settingsWindow.showPluginStatus(message, false);
        }
    }

    /** {@link #report} for a failure (flagged in the Message Log, red on the page). */
    private void reportError(String message) {
        host.setError(message);
        if (settingsWindow != null) {
            settingsWindow.showPluginStatus(message, true);
        }
    }

    /** Shows {@code problem} as an error dialog headed {@code summaryKey} (with the problem's sentence). */
    private void showProblem(String summaryKey, @Nullable PluginProblem problem) {
        String sentence = PluginProblems.text(problem);
        reportError(tr(summaryKey, sentence));
        String detail = problem == null || problem.detail().isBlank() ? sentence : sentence + "\n\n" + problem.detail();
        ops.showError(tr(summaryKey, sentence), detail);
    }

    /** Brings Settings back when it stepped aside for the picker and the flow the picker started is over. */
    private void restoreSettings() {
        if (settingsHiddenForPicker && !installing) {
            settingsHiddenForPicker = false;
            settingsWindow.restoreAfterPicker();
        }
    }

    // --- plugin registry (browse / install / uninstall) -----------------------------------------

    /**
     * Fetches the configured registry index off-thread and shows the "Browse Plugins" picker. No-op (with a
     * reason) when plugins are off or no registry URL is set; a second click while a fetch runs joins it.
     */
    void browse() {
        if (!isEnabled() || pluginRegistry == null) {
            report(disabledReason());
            return;
        }
        String url = config.getSettings().getPluginRegistryUrl();
        if (!com.editora.plugin.PluginRegistry.isHttps(url)) { // a hand-edited settings.json can still hold one
            report(tr("settings.plugins.registryNotHttps"));
            return;
        }
        if (pluginRegistry.fetching(url)) {
            return; // already on its way; the answer comes once
        }
        report(tr("status.plugins.fetching"));
        boolean requireSig = config.getSettings().isPluginRequireSignature();
        pluginRegistry.fetch(url, r -> {
            String error = r.error();
            if (error != null) { // !r.ok()
                PluginProblem p = r.problem() != null
                        ? r.problem()
                        : new PluginProblem(PluginProblem.Kind.FAILED, List.of(), error);
                reportError(tr("status.plugins.fetchFailed", PluginProblems.text(p)));
                return;
            }
            // Signature gate: when "require a signed registry" is on, refuse an unsigned/unverified registry.
            if (requireSig && !r.signed()) {
                reportError(tr("status.plugins.unsigned"));
                ops.showError(tr("status.plugins.unsigned"), tr("dialog.plugins.unsignedDetail"));
                return;
            }
            browseSigned = r.signed();
            browseEntries = r.entries();
            if (browseEntries.isEmpty()) {
                report(tr("status.plugins.empty"));
                return;
            }
            report(browseSigned ? "" : tr("status.plugins.unsignedAllowed"));
            if (settingsWindow != null && settingsWindow.hideForMainWindowPicker()) {
                settingsHiddenForPicker = true; // it would cover the picker; it comes back when the flow ends
            }
            browsePalette.show(host.window());
        });
    }

    /** The picker label: "Name  version — Installed/Update/Install/Requires Editora ≥ x". */
    private String browseEntryLabel(RegistryEntry e) {
        String ver = e.version == null ? "" : e.version;
        return PluginDisclosure.clean(e.name == null || e.name.isBlank() ? e.id : e.name, 80)
                + (ver.isBlank() ? "" : "  " + PluginDisclosure.clean(ver, 40)) + " — " + browseEntryStatus(e);
    }

    /** The picker detail: the author and a description short enough not to push the other rows' off-screen. */
    private String browseEntryDetail(RegistryEntry e) {
        String desc = e.description == null ? "" : e.description.strip();
        String deprecated = e.deprecated == null || e.deprecated.isBlank()
                ? ""
                : tr("plugins.status.deprecated", PluginDisclosure.clean(e.deprecated, 80)) + " ";
        String author = e.author == null || e.author.isBlank() ? "" : PluginDisclosure.clean(e.author, 40) + " · ";
        return deprecated + author + PluginDisclosure.clean(desc, 90);
    }

    /** Installed / Update available / Reinstall-able / Install / Requires-newer, against the installed copy. */
    private String browseEntryStatus(RegistryEntry e) {
        if (!meetsMinEditora(e)) {
            return tr("plugins.status.requiresNewer", e.minEditoraVersion);
        }
        String installed = installedVersion(e.id);
        if (installed == null) {
            return tr("plugins.status.install");
        }
        int cmp = PluginInstaller.compareVersions(e.version, installed);
        return cmp > 0
                ? tr("plugins.status.updateAvailable", installed)
                : cmp < 0 ? tr("plugins.status.newerInstalled", installed) : tr("plugins.status.installed");
    }

    /** The installed plugin's manifest version, or null when not installed. */
    private @Nullable String installedVersion(String id) {
        if (pluginManager == null || id == null) {
            return null;
        }
        String pending = pluginManager.pendingUpdate(id);
        if (pending != null) {
            return pending; // what will run after the restart
        }
        for (PluginDescriptor d : pluginManager.descriptors()) {
            if (id.equals(d.id())) {
                return d.manifest().version == null ? "" : d.manifest().version;
            }
        }
        return null;
    }

    /** Whether this Editora build satisfies the entry's {@code minEditoraVersion} (blank = any). */
    private static boolean meetsMinEditora(RegistryEntry e) {
        String min = e.minEditoraVersion;
        if (min == null || min.isBlank()) {
            return true;
        }
        return PluginInstaller.compareVersions(AppInfo.VERSION, min) >= 0;
    }

    /**
     * Confirms (name, version, author, source, the trust warning — and whether this is an update, a reinstall
     * or a downgrade) then downloads and stages the plugin; the capability consent follows.
     */
    private void confirmAndInstall(RegistryEntry e) {
        if (e == null) {
            restoreSettings();
            return;
        }
        if (!meetsMinEditora(e)) {
            report(tr("plugins.status.requiresNewer", e.minEditoraVersion));
            restoreSettings();
            return;
        }
        String name = PluginDisclosure.clean(e.name == null || e.name.isBlank() ? e.id : e.name, 80);
        String version = PluginDisclosure.clean(e.version == null ? "" : e.version, 40);
        String installed = installedVersion(e.id);
        String header;
        if (installed == null) {
            header = tr("dialog.plugins.installHeaderNew", name, version);
        } else {
            int cmp = PluginInstaller.compareVersions(e.version, installed);
            String from = PluginDisclosure.clean(installed, 40);
            header = cmp > 0
                    ? tr("dialog.plugins.installHeaderUpdate", name, from, version)
                    : cmp < 0
                            ? tr("dialog.plugins.installHeaderDowngrade", name, from, version)
                            : tr("dialog.plugins.installHeaderReinstall", name, version);
        }
        StringBuilder body = new StringBuilder();
        if (!browseSigned) {
            body.append(tr("dialog.plugins.unsignedWarn")).append("\n\n"); // reached only when the gate is off
        }
        if (e.deprecated != null && !e.deprecated.isBlank()) {
            body.append(tr("plugins.status.deprecated", PluginDisclosure.clean(e.deprecated, 300)))
                    .append("\n\n");
        }
        String author = e.author == null || e.author.isBlank() ? "" : PluginDisclosure.clean(e.author.strip(), 80);
        if (!author.isEmpty()) {
            body.append(tr("dialog.plugins.installAuthor", author)).append('\n');
        }
        body.append(tr("dialog.plugins.installFrom", PluginDisclosure.clean(e.download, 300)));
        String registryUrl = config.getSettings().getPluginRegistryUrl();
        if (registryUrl != null && !registryUrl.strip().equals(com.editora.config.Settings.DEFAULT_PLUGIN_REGISTRY)) {
            body.append('\n').append(tr("dialog.plugins.installRegistry", PluginProblem.hostOf(registryUrl)));
        }
        if (!confirm.test(
                new Question(Question.Kind.INSTALL, header, body.toString(), "", tr("dialog.plugins.download")))) {
            restoreSettings();
            return;
        }
        installing = true;
        report(tr("status.plugins.installing", name));
        installer().stageFromUrl(e, this::onStaged);
    }

    /** Install-from-disk: pick a {@code .zip}, then stage it (no checksum — the user chose the file). */
    void installFromDisk() {
        if (!isEnabled() || pluginInstaller == null) {
            report(disabledReason());
            return;
        }
        if (installing) {
            return;
        }
        File f = zipChooser.get();
        if (f == null) {
            return;
        }
        installing = true;
        report(tr("status.plugins.installing", f.getName()));
        pluginInstaller.stageFromZip(f.toPath(), this::onStaged);
    }

    private File chooseZipDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle(tr("dialog.plugins.installFileTitle"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(tr("dialog.plugins.zipFilter"), "*.zip"));
        return fc.showOpenDialog(dialogOwner());
    }

    /**
     * The archive is unpacked beside the plugins: show what it can do — and what it replaces — and put it in
     * place only on Enable. Declining leaves the plugins folder as it was, so a declined update keeps the
     * version that was installed (and enabled). A plugin running in this session is replaced at the next start.
     */
    private void onStaged(PluginInstaller.Staging s) {
        installing = false;
        PluginInstaller.Staged st = s.staged();
        if (st == null) { // !s.ok()
            showProblem("status.plugins.installFailed", s.problem());
            restoreSettings();
            return;
        }
        boolean running = manager().running(st.id()) != null;
        if (!confirm.test(consentQuestion(st))) {
            PluginInstaller.discard(st);
            report(
                    st.previous() == null
                            ? tr("status.plugins.notInstalled", st.name())
                            : tr("status.plugins.updateDeclined", st.name(), versionOf(st.previous())));
            restoreSettings();
            return;
        }
        PluginInstaller.Commit c = installer().commit(st, running);
        if (!c.ok()) {
            showProblem("status.plugins.installFailed", c.problem());
            restoreSettings();
            return;
        }
        config.getPluginStore().enable(st.id(), st.digest());
        config.savePlugins();
        if (pluginRegistry != null) { // set whenever the installer is
            pluginRegistry.invalidate(); // status labels (Installed/Update) changed
        }
        manager().discover(); // the Settings list; what runs waits for the restart
        report(
                c.pending()
                        ? tr("status.plugins.updatePending", st.name(), st.version())
                        : tr("status.plugins.installed", st.name()));
        settingsWindow.syncPluginsCheck(); // rebuilds the per-plugin list
        restoreSettings();
    }

    private static String versionOf(PluginManifest m) {
        return m == null || m.version == null ? "" : m.version.strip();
    }

    /** The consent for a staged install: an install, an update, a downgrade, a reinstall, or a replacement. */
    private Question consentQuestion(PluginInstaller.Staged st) {
        String name = PluginDisclosure.clean(st.name(), 80);
        String version = PluginDisclosure.clean(st.version(), 40);
        PluginManifest prev = st.previous();
        String header;
        String intro = tr("dialog.plugins.consentIntro");
        if (prev == null) {
            header = tr("dialog.plugins.enableHeaderNamed", name, version).strip();
        } else {
            String prevName =
                    PluginDisclosure.clean(prev.name == null || prev.name.isBlank() ? st.id() : prev.name.strip(), 80);
            String prevVersion = PluginDisclosure.clean(versionOf(prev), 40);
            if (!prevName.equalsIgnoreCase(name)) {
                // Another plugin under the same id: say so before it gets that one's place and saved data.
                header = tr("dialog.plugins.replaceHeader", prevName, prevVersion, name, version);
                intro = tr("dialog.plugins.replaceIntro", PluginDisclosure.clean(st.id(), 80)) + "\n\n" + intro;
            } else {
                int cmp = PluginInstaller.compareVersions(st.version(), versionOf(prev));
                header = cmp > 0
                        ? tr("dialog.plugins.updateHeader", name, prevVersion, version)
                        : cmp < 0
                                ? tr("dialog.plugins.downgradeHeader", name, prevVersion, version)
                                : tr("dialog.plugins.reinstallHeader", name, version);
            }
            if (manager().running(st.id()) != null) {
                intro = intro + "\n\n" + tr("dialog.plugins.afterRestart");
            }
        }
        String caps = PluginDisclosure.capabilities(
                st.manifest(),
                st.jars(),
                Files.isDirectory(st.root().resolve("snippets")),
                Files.isDirectory(st.root().resolve("templates")),
                keymap.activeName(),
                keys(st.manifest()));
        return new Question(Question.Kind.ENABLE, header, intro, caps, tr("dialog.plugins.enable"));
    }

    /**
     * Settings → Plugins: the user ticked {@code d}. Shows what it can do and, on Enable, stores the consent
     * with the fingerprint of what was shown. Refuses a plugin that failed to load: what it would do cannot
     * be shown, and a "no executable code" summary of an unreadable manifest is not consent.
     */
    boolean enableFromSettings(PluginDescriptor d) {
        if (d == null || d.loadError() != null) {
            return false;
        }
        String caps = PluginDisclosure.capabilities(
                d.manifest(),
                d.jars(),
                Files.isDirectory(d.dir().resolve("snippets")),
                Files.isDirectory(d.dir().resolve("templates")),
                keymap.activeName(),
                keys(d.manifest()));
        String version = PluginDisclosure.clean(d.manifest().version == null ? "" : d.manifest().version, 40);
        if (!confirm.test(new Question(
                Question.Kind.ENABLE,
                tr("dialog.plugins.enableHeaderNamed", PluginDisclosure.clean(d.displayName(), 80), version)
                        .strip(),
                tr("dialog.plugins.consentIntro"),
                caps,
                tr("dialog.plugins.enable")))) {
            return false;
        }
        config.getPluginStore().enable(d.id(), d.digest());
        config.savePlugins();
        return true;
    }

    /** Settings → Plugins: the user unticked {@code d} (no question: switching off is always safe). */
    void disableFromSettings(PluginDescriptor d) {
        config.getPluginStore().setEnabled(d.id(), false);
        config.savePlugins();
    }

    /** How the disclosure names chords and commands in this window. */
    private PluginDisclosure.Keys keys(PluginManifest m) {
        return new PluginDisclosure.Keys() {
            @Override
            public String display(String chord) {
                return keymap.display(chord);
            }

            @Override
            public @Nullable String boundTitle(String chord) {
                String now = keymap.commandFor(chord);
                return now == null
                        ? null
                        : registry.get(now).map(Command::title).orElse(now);
            }

            @Override
            public String commandTitle(String id) {
                for (PluginManifest.DeclaredCommand c : m.commands) {
                    if (c != null
                            && c.id != null
                            && PluginManifest.qualify(m.id, c.id).equals(id)) {
                        return c.title == null || c.title.isBlank() ? id : c.title;
                    }
                }
                return registry.get(id).map(Command::title).orElse(id);
            }
        };
    }

    /** The descriptor with this {@code id}, or null. Pure. */
    static @Nullable PluginDescriptor descriptorFor(List<PluginDescriptor> descriptors, String id) {
        for (PluginDescriptor c : descriptors) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /**
     * A question asked before a plugin is installed, enabled or removed: {@code header} (escaped manifest
     * values), {@code body} above the capability list, {@code capabilities} (may be blank), the accept button.
     */
    record Question(Kind kind, String header, String body, String capabilities, String acceptLabel) {
        enum Kind {
            INSTALL,
            ENABLE,
            UNINSTALL
        }

        /** Everything the dialog says, for tests. */
        String text() {
            return header + "\n" + body + "\n" + capabilities;
        }
    }

    /** Asks a {@link Question}; {@code true} = go ahead. Replaceable so a test can answer without a modal dialog. */
    private java.util.function.Predicate<Question> confirm = this::confirmDialog;

    /** Test seam for {@link #confirm}. */
    void setConfirmForTest(java.util.function.Predicate<Question> confirm) {
        this.confirm = java.util.Objects.requireNonNull(confirm, "confirm");
    }

    /** Picks the {@code .zip} to install from disk (null = cancelled). Replaceable, like {@link #confirm}. */
    private Supplier<File> zipChooser = this::chooseZipDialog;

    /** Test seam for {@link #zipChooser}. */
    void setZipChooserForTest(Supplier<File> chooser) {
        this.zipChooser = java.util.Objects.requireNonNull(chooser, "chooser");
    }

    /** Settings, when the user is working there; else this window. */
    private Window dialogOwner() {
        Window settings = settingsWindow == null ? null : settingsWindow.stageIfShowing();
        return settings != null ? settings : host.window();
    }

    private boolean confirmDialog(Question q) {
        if (q.kind() == Question.Kind.ENABLE) {
            return PluginDialogs.consent(dialogOwner(), q.header(), q.body(), q.capabilities(), q.acceptLabel());
        }
        return PluginDialogs.confirm(dialogOwner(), q.header(), q.body(), q.acceptLabel());
    }

    /** Uninstalls the plugin with this id (the palette path); see {@link #uninstall(PluginDescriptor)}. */
    void uninstall(String id) {
        if (pluginManager == null || id == null || id.isBlank()) {
            return;
        }
        PluginDescriptor d = descriptorFor(pluginManager.descriptors(), id);
        if (d != null) {
            uninstall(d);
        }
    }

    /**
     * Removes plugin {@code d} — the row the user clicked, not whatever plugin first declares its id — after
     * naming it and the saved data that goes with it. A plugin running in this session is removed at the next
     * start (its jar is open; Windows cannot delete it); it is switched off at once either way.
     */
    void uninstall(PluginDescriptor d) {
        if (pluginManager == null || d == null) {
            return;
        }
        String name = PluginDisclosure.clean(d.displayName(), 80);
        StringBuilder body =
                new StringBuilder(tr("dialog.plugins.uninstallBody", d.dir().toString()));
        if (Files.isDirectory(d.dir().resolve("data"))) {
            body.append("\n\n").append(tr("dialog.plugins.uninstallData"));
        }
        if (!confirm.test(new Question(
                Question.Kind.UNINSTALL,
                tr("dialog.plugins.uninstallHeader", name),
                body.toString(),
                "",
                tr("dialog.plugins.removeButton")))) {
            return;
        }
        boolean running = pluginManager.running(d.id()) != null;
        if (running) {
            if (!PluginInstaller.markForRemoval(pluginManager.pluginsDir(), d.id())) {
                reportError(tr("status.plugins.uninstallFailed", name));
                ops.showError(
                        tr("status.plugins.uninstallFailed", name), d.dir().toString());
                return;
            }
        } else if (!removeInstalled(pluginManager, d, name)) {
            return;
        }
        config.getPluginStore().forget(d.id());
        config.savePlugins();
        pluginManager.discover();
        settingsWindow.syncPluginsCheck();
        report(running ? tr("status.plugins.removalPending", name) : tr("status.plugins.uninstalled", name));
    }

    /**
     * Deletes the folder of plugin {@code plugin} — the directory it was discovered in, and only when that is a
     * direct child of the plugins folder (see {@link PluginInstaller#removableDir}).
     */
    private boolean removeInstalled(PluginManager pluginManager, PluginDescriptor plugin, String name) {
        Path dir = PluginInstaller.removableDir(pluginManager.pluginsDir(), plugin);
        if (dir == null) {
            reportError(tr("status.plugins.uninstallFailed", name));
            ops.showError(tr("status.plugins.uninstallFailed", name), String.valueOf(plugin.dir()));
            return false;
        }
        if (!PluginInstaller.deleteInstalled(pluginManager.pluginsDir(), plugin)) {
            reportError(tr("status.plugins.uninstallFailed", name));
            ops.showError(tr("status.plugins.uninstallFailed", name), dir.toString());
            pluginManager.discover(); // show what is left (its manifest is deleted last: still listed)
            settingsWindow.syncPluginsCheck();
            return false;
        }
        return true;
    }

    /** Whether the caller is on the FX thread; plugin API calls touch JavaFX state. */
    private static boolean onFx() {
        return Platform.isFxApplicationThread();
    }

    /** Runs {@code r} now on the FX thread, or posts it there from another thread. */
    private static void fx(Runnable r) {
        if (onFx()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** A read that must happen on the FX thread: from another thread it throws instead of racing the editor. */
    private static void requireFx(String method) {
        if (!onFx()) {
            throw new IllegalStateException(
                    method + " must be called on the JavaFX application thread" + " (use Platform.runLater)");
        }
    }

    /** A window-scoped {@link PluginContext}; one per plugin per window. */
    private final class PluginContextImpl implements PluginContext {
        private final PluginDescriptor desc;
        private final Contributions contributions;

        PluginContextImpl(PluginDescriptor desc, Contributions contributions) {
            this.desc = desc;
            this.contributions = contributions;
        }

        /**
         * The id a chord or a status segment targets: a bare id is this plugin's own command, a dotted one a
         * built-in (or another plugin's), as written.
         */
        private String target(String id) {
            return PluginManifest.qualify(desc.id(), id);
        }

        @Override
        public void registerCommand(String id, String title, Runnable action) {
            if (id == null || id.isBlank() || action == null) {
                return;
            }
            fx(() -> {
                // Always this plugin's namespace: a dotted id used to be taken as written, so a plugin could
                // replace a built-in command (or another plugin's) without a word.
                String cid = ownId(desc, id);
                register(
                        contributions,
                        cid,
                        title == null || title.isBlank() ? desc.displayName() + ": " + id : title,
                        action);
            });
        }

        @Override
        public void bindKey(String chord, String commandId) {
            if (chord == null || chord.isBlank() || commandId == null) {
                return;
            }
            fx(() -> {
                String target = target(commandId);
                bindChord(contributions, chord, target);
                manager().recordCodeBinding(desc.id(), chord, target); // kept by keymap rebuilds
                reapplyUserBindings();
            });
        }

        @Override
        public void registerToolWindow(
                String id,
                String title,
                ToolWindowSide side,
                Region content,
                String commandId,
                @Nullable Supplier<Node> icon,
                boolean needsBuffer) {
            if (id == null || id.isBlank()) {
                return;
            }
            fx(() -> {
                String twId = ownId(desc, id);
                String cmd = commandId == null || commandId.isBlank() ? twId : ownId(desc, commandId);
                ToolWindow.Side s = switch (side == null ? ToolWindowSide.BOTTOM : side) {
                    case LEFT -> ToolWindow.Side.LEFT;
                    case RIGHT -> ToolWindow.Side.RIGHT;
                    case BOTTOM -> ToolWindow.Side.BOTTOM;
                };
                // A null supplier (or one returning null) falls back to the default plugin (jigsaw) icon. The
                // factory is invoked per window/repaint, so it must yield a fresh node each time.
                Supplier<Node> iconFactory = icon == null
                        ? Icons::plugin
                        : () -> {
                            Node n = icon.get();
                            return n != null ? n : Icons.plugin();
                        };
                String shown = title == null || title.isBlank() ? desc.displayName() : title;
                ToolWindow tw = new ToolWindow(twId, shown, s, iconFactory, content, cmd);
                toolWindows.register(tw);
                contributions.toolWindowsAdded.add(tw);
                if (needsBuffer) {
                    // Acts on the active editor — track it so updateBufferToolWindows() keeps its stripe gated
                    // on an open buffer (hidden on a non-buffer tab like Welcome).
                    pluginToolWindows.add(tw);
                    toolWindows.setAvailable(tw, host.activeBuffer() != null);
                }
                // needsBuffer == false: a self-contained tool window (scratchpad, calculator, …) — never gated.
                // Titled like the built-in toggles ("Tool Window: Problems").
                register(contributions, cmd, tr("plugins.toolWindowCommand", shown), () -> toolWindows.toggle(tw));
            });
        }

        @Override
        public void addEditorMenuItem(String label, Consumer<ActiveEditor> action) {
            if (label != null && action != null) {
                fx(() -> {
                    EditorMenuContribution item = new EditorMenuContribution(desc.displayName(), label, action);
                    pluginMenuItems.add(item);
                    contributions.menuItems.add(item);
                });
            }
        }

        @Override
        public void addStatusBarSegment(String label, String commandId) {
            fx(() -> {
                String cmd = commandId == null || commandId.isBlank() ? null : target(commandId);
                String hint = cmd == null
                        ? desc.displayName()
                        : desc.displayName() + ": "
                                + registry.get(cmd).map(Command::title).orElse(cmd);
                contributions.segments.add(statusBar.addPluginSegment(label, cmd, hint));
            });
        }

        @Override
        public ActiveEditor activeEditor() {
            requireFx("PluginContext.activeEditor()");
            // Bound to the buffer active now: a plugin that finishes later (a formatter on a worker) writes
            // into the file it started on — or, if that tab is closed, nowhere — never the tab active then.
            return new ActiveEditorImpl(host.activeBuffer());
        }

        @Override
        public Path pluginDir() {
            return desc.dir();
        }

        @Override
        public Path dataDir() {
            Path data = desc.dir().resolve("data");
            try {
                Files.createDirectories(data);
            } catch (IOException ignored) {
                // best-effort
            }
            return data;
        }

        @Override
        public Path configDir() {
            return config.getConfigDir();
        }

        @Override
        public void log(String message) {
            LOG.info("[plugin " + desc.id() + "] " + message);
        }

        @Override
        public void setStatus(String message) {
            fx(() -> host.setStatus(message));
        }

        @Override
        public void openUrl(String url) {
            if (url != null && !url.isBlank()) {
                fx(() -> host.openExternalUrl(url));
            }
        }
    }

    /**
     * An {@link ActiveEditor} over one buffer: the one active when it was asked for (or the one a menu item
     * was opened on). Once that buffer is closed it reads as empty and ignores writes.
     */
    private final class ActiveEditorImpl implements ActiveEditor {
        private final @Nullable EditorBuffer bound;

        ActiveEditorImpl(@Nullable EditorBuffer bound) {
            this.bound = bound;
        }

        /** The buffer, while it is open; null after it was closed (or when there was none). */
        private @Nullable EditorBuffer buf() {
            return bound == null || bound.isDisposed() ? null : bound;
        }

        @Override
        public @Nullable Path filePath() {
            requireFx("ActiveEditor.filePath()");
            EditorBuffer b = buf();
            return b == null ? null : b.getPath();
        }

        @Override
        public String text() {
            requireFx("ActiveEditor.text()");
            EditorBuffer b = buf();
            return b == null ? "" : b.getContent();
        }

        @Override
        public String selectedText() {
            requireFx("ActiveEditor.selectedText()");
            EditorBuffer b = buf();
            return b == null ? "" : b.getArea().getSelectedText();
        }

        @Override
        public int caretLine() {
            requireFx("ActiveEditor.caretLine()");
            EditorBuffer b = buf();
            if (b == null) {
                return -1;
            }
            // Of the whole file, like text(): a narrowed buffer's area starts at its region's first line.
            int line = b.getArea().getCurrentParagraph() + 1;
            if (b.isNarrowed()) {
                String content = b.getContent();
                int start = Math.min(b.narrowStart(), content.length());
                line += (int) content.substring(0, start)
                        .chars()
                        .filter(ch -> ch == '\n')
                        .count();
            }
            return line;
        }

        @Override
        public void replaceSelection(String replacement) {
            fx(() -> {
                EditorBuffer b = buf();
                if (b != null
                        && b.isEditable()
                        && replacement != null
                        && NoUndoGuard.allow(b, tr("noUndo.op.plugin"))) {
                    b.getArea().replaceSelection(replacement);
                }
            });
        }

        @Override
        public void insertAtCaret(String text) {
            fx(() -> {
                EditorBuffer b = buf();
                if (b != null && b.isEditable() && text != null && NoUndoGuard.allow(b, tr("noUndo.op.plugin"))) {
                    b.getArea().insertText(b.getArea().getCaretPosition(), text);
                }
            });
        }

        @Override
        public void setText(String text) {
            fx(() -> {
                EditorBuffer b = buf();
                if (b != null && b.isEditable() && text != null && NoUndoGuard.allow(b, tr("noUndo.op.plugin"))) {
                    // text() is the whole file, so this widens a narrowed buffer first (undoable, marks dirty).
                    b.replaceWholeDocument(text);
                }
            });
        }

        @Override
        public void openPath(Path path) {
            if (path != null) {
                fx(() -> ops.openPath(path));
            }
        }

        @Override
        public boolean isEncrypted() {
            EditorBuffer b = buf();
            return b != null && b.encryption().isEncrypted();
        }
    }
}
