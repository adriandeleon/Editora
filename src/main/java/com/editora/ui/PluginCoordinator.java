package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

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
import com.editora.plugin.PluginRegistry;
import com.editora.plugin.RegistryEntry;
import com.editora.plugin.ToolWindowSide;
import com.editora.process.ProcessRunner;
import com.editora.snippet.SnippetManager;
import com.editora.template.TemplateRegistry;

import static com.editora.i18n.Messages.tr;

/**
 * Plugin support — discovery/apply/install/uninstall + the per-window {@link PluginContext} adapter that
 * lets a plugin contribute commands, keybindings, tool windows, status-bar segments, and editor menu items.
 * Extracted from {@link MainController} via the {@link CoordinatorHost} pattern. The {@link PluginManager}
 * itself is shared (owned by {@link WindowManager}, classes load once) and passed in; this coordinator owns
 * the per-window {@link PluginRegistry}/{@link PluginInstaller}, the started-plugin instances, the
 * plugin-contributed tool windows + editor menu items, and the "Browse Plugins" picker. {@code MainController}
 * keeps the {@code pluginManager} field (set by {@code WindowManager} before init + passed here) and thin
 * delegates: {@code disposePlugins()} (the window-close contract) and {@code pluginsEnabled()}.
 */
final class PluginCoordinator {

    private static final Logger LOG = Logger.getLogger(PluginCoordinator.class.getName());

    /** A plugin-contributed editor menu item: a label + an action over the {@link ActiveEditor}. */
    private record EditorMenuContribution(String label, Consumer<ActiveEditor> action) {}

    /** Window hooks beyond the injected collaborators that the plugin flows need. */
    interface Ops {
        /** Opens (or focuses) the tab for {@code file}. */
        void openPath(Path file);

        /** Shows a scrollable error dialog (summary + multi-line detail) — reuses the Git error dialog. */
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
    private final PluginManager pluginManager;
    private final Ops ops;

    private final PluginRegistry pluginRegistry;
    private final PluginInstaller pluginInstaller;
    private final List<Plugin> startedPlugins = new ArrayList<>();
    private final List<EditorMenuContribution> pluginMenuItems = new ArrayList<>();
    private final List<ToolWindow> pluginToolWindows = new ArrayList<>();

    private final QuickOpen<RegistryEntry> browsePalette;
    private List<RegistryEntry> browseEntries = List.of();
    private boolean browseSigned;

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
            PluginManager pluginManager,
            Ops ops) {
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
        // Per-window registry/installer over the shared manager (mirrors the old setPluginManager wiring).
        this.pluginRegistry = pluginManager != null ? new PluginRegistry() : null;
        this.pluginInstaller = pluginManager != null ? new PluginInstaller(pluginManager) : null;
        this.browsePalette = new QuickOpen<>(
                tr("plugins.browseTitle"),
                tr("plugins.browsePrompt"),
                () -> browseEntries,
                this::browseEntryLabel,
                e -> e.description == null ? "" : e.description,
                e -> (e.name == null ? "" : e.name) + " " + e.id + " "
                        + (e.description == null ? "" : e.description), // search name+id+desc
                this::confirmAndInstall);
        this.browsePalette.setPreferredSize(960, 10); // wide — registry rows carry a name + a long description
        this.browsePalette.setOverlayHost(host.overlayHost());
    }

    /** Whether plugins may load — the master gate (also off in Simple UI mode). */
    boolean isEnabled() {
        return pluginManager != null && config.getSettings().isPluginSupport() && !host.simpleModeActive();
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
        for (EditorMenuContribution c : pluginMenuItems) {
            MenuItem mi = new MenuItem(c.label());
            mi.setGraphic(Icons.plugin());
            mi.setOnAction(e -> {
                try {
                    c.action().accept(new ActiveEditorImpl(buffer));
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "Plugin menu action failed", t);
                }
            });
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

    /**
     * Applies every enabled, error-free plugin to <em>this</em> window: declarative snippet/template source
     * dirs + keymap bindings + external-command palette entries, then each Java plugin's {@code start(ctx)}.
     * Runs once during init (off no hot path). One {@code try/catch} per plugin → DebugLog, so a misbehaving
     * plugin never breaks the window.
     */
    void applyPlugins() {
        if (!isEnabled()) {
            return;
        }
        boolean addedAssets = false;
        for (PluginDescriptor d : pluginManager.descriptors()) {
            if (!d.enabled() || d.loadError() != null) {
                continue;
            }
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
                if (d.manifest().keymap != null && !d.manifest().keymap.isEmpty()) {
                    keymap.applyOverrides(d.manifest().keymap); // shared keymap → applies to every window
                }
                // Declarative external commands → palette commands.
                for (PluginManifest.DeclaredCommand c : d.manifest().commands) {
                    if (c == null || c.id == null || c.id.isBlank()) {
                        continue;
                    }
                    String cid = "plugin." + d.id() + "." + c.id;
                    String title = c.title == null || c.title.isBlank() ? cid : c.title;
                    registry.register(Command.of(cid, title, () -> runDeclaredCommand(d, c)));
                }
                // Java plugin.
                if (d.hasJavaEntry() && d.classLoader() != null) {
                    Plugin plugin = pluginManager.instantiate(d);
                    if (plugin != null) {
                        plugin.start(new PluginContextImpl(d));
                        startedPlugins.add(plugin);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Plugin " + d.id() + " failed to apply", t);
                host.setStatus(tr("status.plugins.failed", d.id()));
            }
        }
        if (addedAssets) {
            snippets.reload();
            templates.reload();
        }
    }

    /** Runs a plugin's declared external command via the subprocess runner; reports the result. */
    private void runDeclaredCommand(PluginDescriptor d, PluginManifest.DeclaredCommand c) {
        if (c.run == null || c.run.isEmpty()) {
            return;
        }
        Path cwd = (c.dir == null || c.dir.isBlank())
                ? d.dir()
                : d.dir().resolve(c.dir).normalize();
        host.setStatus(tr("status.plugins.running", c.title == null || c.title.isBlank() ? c.id : c.title));
        Thread t = new Thread(
                () -> {
                    ProcessRunner.Result r;
                    try {
                        r = ProcessRunner.run(cwd, Duration.ofSeconds(120), new ArrayList<>(c.run), Map.of());
                    } catch (RuntimeException e) {
                        Platform.runLater(() -> host.setStatus(tr("status.plugins.cmdFailed", e.getMessage())));
                        return;
                    }
                    String out = (r.out() + "\n" + r.err()).strip();
                    if (!out.isBlank()) {
                        LOG.info("[plugin " + d.id() + "] " + out);
                    }
                    Platform.runLater(() -> host.setStatus(
                            r.ok()
                                    ? tr("status.plugins.cmdDone", c.id)
                                    : tr("status.plugins.cmdFailed", "exit " + r.exit())));
                },
                "plugin-cmd-" + d.id());
        t.setDaemon(true); // created off the non-daemon FX thread; don't let a running command block JVM exit
        t.start();
    }

    // --- plugin registry (browse / install / uninstall) -----------------------------------------

    /**
     * Fetches the configured registry index off-thread and shows the "Browse Plugins" picker. No-op (with a
     * status hint) when plugins are disabled or no registry URL is set.
     */
    void browse() {
        if (!isEnabled() || pluginRegistry == null) {
            host.setStatus(tr("status.plugins.disabled"));
            return;
        }
        String url = config.getSettings().getPluginRegistryUrl();
        host.setStatus(tr("status.plugins.fetching"));
        boolean requireSig = config.getSettings().isPluginRequireSignature();
        pluginRegistry.fetch(url, r -> {
            if (!r.ok()) {
                host.setStatus(tr("status.plugins.fetchFailed", r.error()));
                return;
            }
            // Signature gate: when "require signed plugins" is on, refuse an unsigned/unverified registry.
            if (requireSig && !r.signed()) {
                ops.showError(tr("status.plugins.unsigned"), tr("dialog.plugins.unsignedDetail"));
                return;
            }
            browseSigned = r.signed();
            browseEntries = r.entries();
            if (browseEntries.isEmpty()) {
                host.setStatus(tr("status.plugins.empty"));
                return;
            }
            if (!browseSigned) {
                host.setStatus(tr("status.plugins.unsignedAllowed"));
            }
            browsePalette.show(host.window());
        });
    }

    /** The picker label: "Name  version — Installed/Update available/Install/Requires Editora ≥ x". */
    private String browseEntryLabel(RegistryEntry e) {
        String ver = e.version == null ? "" : e.version;
        return (e.name == null || e.name.isBlank() ? e.id : e.name) + (ver.isBlank() ? "" : "  " + ver) + " — "
                + browseEntryStatus(e);
    }

    /** Installed / Update available / Install / Requires-newer, comparing to the installed descriptor. */
    private String browseEntryStatus(RegistryEntry e) {
        if (!meetsMinEditora(e)) {
            return tr("plugins.status.requiresNewer", e.minEditoraVersion);
        }
        String installed = installedVersion(e.id);
        if (installed == null) {
            return tr("plugins.status.install");
        }
        int cmp = PluginInstaller.compareVersions(e.version, installed);
        return cmp > 0 ? tr("plugins.status.updateAvailable") : tr("plugins.status.installed");
    }

    /** The installed plugin's manifest version, or null when not installed. */
    private String installedVersion(String id) {
        if (pluginManager == null || id == null) {
            return null;
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

    /** Confirms (showing name/version/author/source + the trust warning) then installs from the registry. */
    private void confirmAndInstall(RegistryEntry e) {
        if (e == null) {
            return;
        }
        if (!meetsMinEditora(e)) {
            host.setStatus(tr("plugins.status.requiresNewer", e.minEditoraVersion));
            return;
        }
        String body = tr(
                "dialog.plugins.installBody",
                (e.name == null || e.name.isBlank() ? e.id : e.name),
                (e.version == null ? "" : e.version),
                (e.author == null ? "" : e.author),
                e.download);
        if (!browseSigned) {
            body = tr("dialog.plugins.unsignedWarn") + "\n\n" + body; // reached only when the gate is off
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.plugins.installTitle"));
        confirm.setHeaderText(tr("dialog.plugins.installHeader"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        host.setStatus(tr("status.plugins.installing", e.name == null || e.name.isBlank() ? e.id : e.name));
        pluginInstaller.installFromUrl(e, this::onPluginInstalled);
    }

    /** Install-from-disk: pick a {@code .zip}, then install it (no checksum — the user chose the file). */
    void installFromDisk() {
        if (!isEnabled() || pluginInstaller == null) {
            host.setStatus(tr("status.plugins.disabled"));
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle(tr("dialog.plugins.installFileTitle"));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plugin zip", "*.zip"));
        File f = fc.showOpenDialog(host.window());
        if (f == null) {
            return;
        }
        host.setStatus(tr("status.plugins.installing", f.getName()));
        pluginInstaller.installFromZip(f.toPath(), this::onPluginInstalled);
    }

    /** Common post-install handling: disclose capabilities + confirm enable, persist, refresh, report. */
    private void onPluginInstalled(PluginInstaller.Result r) {
        if (!r.ok()) {
            ops.showError(tr("status.plugins.installFailed", r.error() == null ? "" : r.error()), r.error());
            return;
        }
        pluginRegistry.invalidate(); // status labels (Installed/Update) may change
        // Arming gate: the plugin is now on disk; show exactly what it can do before enabling it.
        if (confirmEnablePlugin(r.id())) {
            config.getPluginStore().setEnabled(r.id(), true);
            config.savePlugins();
            host.setStatus(tr("status.plugins.installed", r.name()));
        } else {
            // Declining must DISABLE it: installBytes already moved the new code into place before this gate,
            // so an *update* whose new capabilities the user rejected would otherwise stay enabled and run the
            // rejected code on the next launch.
            config.getPluginStore().setEnabled(r.id(), false);
            config.savePlugins();
            host.setStatus(tr("status.plugins.notEnabled", r.name()));
        }
        settingsWindow.syncPluginsCheck(); // rebuilds the per-plugin list
    }

    /**
     * Shows a capability-disclosure confirm before a plugin is <em>enabled</em> (the real arming point —
     * code loads on next launch): whether it ships executable code, the external commands it declares, and
     * any keybindings it remaps. Returns whether the user accepted. Falls back to enabling if the descriptor
     * can't be found.
     */
    private boolean confirmEnablePlugin(String id) {
        PluginDescriptor d = null;
        if (pluginManager != null) {
            for (PluginDescriptor c : pluginManager.descriptors()) {
                if (c.id().equals(id)) {
                    d = c;
                    break;
                }
            }
        }
        if (d == null) {
            return true;
        }
        String name = d.manifest().name == null || d.manifest().name.isBlank() ? d.id() : d.manifest().name;
        String body = tr(
                "dialog.plugins.enableBody",
                name,
                d.manifest().version == null ? "" : d.manifest().version,
                pluginCapabilitySummary(d.manifest(), d.hasJavaEntry()));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.plugins.enableTitle"));
        confirm.setHeaderText(tr("dialog.plugins.enableHeader"));
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * A human-readable, localized list of what a plugin can do, from its manifest: executable code (a jar),
     * declared external commands (with their argv), and keybinding remaps. Used by the enable-confirm dialogs
     * (here and in {@link SettingsWindow}). Pure aside from {@code tr(...)}.
     */
    public static String pluginCapabilitySummary(PluginManifest m, boolean hasJar) {
        StringBuilder sb = new StringBuilder();
        if (hasJar) {
            sb.append(tr("plugins.cap.code")).append('\n');
        }
        if (m.commands != null && !m.commands.isEmpty()) {
            sb.append(tr("plugins.cap.commands")).append('\n');
            for (PluginManifest.DeclaredCommand c : m.commands) {
                String argv = c.run == null ? "" : String.join(" ", c.run);
                sb.append("    ").append(argv).append('\n');
            }
        }
        if (m.keymap != null && !m.keymap.isEmpty()) {
            sb.append(tr("plugins.cap.keymap")).append('\n');
            m.keymap.forEach((chord, cmd) ->
                    sb.append("    ").append(chord).append(" → ").append(cmd).append('\n'));
        }
        if (sb.length() == 0) {
            sb.append(tr("plugins.cap.assetsOnly"));
        }
        return sb.toString().strip();
    }

    /** Uninstalls a plugin: deletes its folder + drops it from the enabled store (Settings-page Remove). */
    void uninstall(String id) {
        if (pluginManager == null || id == null || id.isBlank()) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.plugins.uninstallBody", id), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.plugins.uninstallTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        Path dir = pluginManager.pluginsDir().resolve(id);
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException e) {
            ops.showError(tr("status.plugins.uninstallFailed", id), e.getMessage());
            return;
        }
        config.getPluginStore().setEnabled(id, false);
        config.savePlugins();
        pluginManager.discover();
        settingsWindow.syncPluginsCheck();
        host.setStatus(tr("status.plugins.uninstalled", id));
    }

    /** A window-scoped {@link PluginContext}; one per plugin per window. */
    private final class PluginContextImpl implements PluginContext {
        private final PluginDescriptor desc;

        PluginContextImpl(PluginDescriptor desc) {
            this.desc = desc;
        }

        /** Namespaces a bare command id (no dot) to this plugin; a dotted id (a built-in) is used as-is. */
        private String fullId(String id) {
            return id != null && id.indexOf('.') < 0 ? "plugin." + desc.id() + "." + id : id;
        }

        @Override
        public void registerCommand(String id, String title, Runnable action) {
            String cid = fullId(id);
            registry.register(Command.of(cid, title == null || title.isBlank() ? cid : title, action));
        }

        @Override
        public void bindKey(String chord, String commandId) {
            if (chord != null && !chord.isBlank() && commandId != null) {
                keymap.applyOverrides(Map.of(chord, fullId(commandId)));
            }
        }

        @Override
        public void registerToolWindow(
                String id,
                String title,
                ToolWindowSide side,
                Region content,
                String commandId,
                Supplier<Node> icon,
                boolean needsBuffer) {
            String twId = fullId(id);
            String cmd = commandId == null || commandId.isBlank() ? twId : fullId(commandId);
            ToolWindow.Side s =
                    switch (side == null ? ToolWindowSide.BOTTOM : side) {
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
            ToolWindow tw = new ToolWindow(twId, title == null ? twId : title, s, iconFactory, content, cmd);
            toolWindows.register(tw);
            if (needsBuffer) {
                // Acts on the active editor — track it so updateBufferToolWindows() keeps its stripe gated on
                // an open buffer (hidden on a non-buffer tab like Welcome).
                pluginToolWindows.add(tw);
                toolWindows.setAvailable(tw, host.activeBuffer() != null);
            }
            // needsBuffer == false: a self-contained tool window (scratchpad, calculator, …) — never gated.
            registry.register(Command.of(cmd, title == null ? twId : title, () -> toolWindows.toggle(tw)));
        }

        @Override
        public void addEditorMenuItem(String label, Consumer<ActiveEditor> action) {
            if (label != null && action != null) {
                pluginMenuItems.add(new EditorMenuContribution(label, action));
            }
        }

        @Override
        public void addStatusBarSegment(String label, String commandId) {
            statusBar.addPluginSegment(label, commandId == null ? null : fullId(commandId));
        }

        @Override
        public ActiveEditor activeEditor() {
            return new ActiveEditorImpl(null); // tracks the live active buffer
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
            host.setStatus(message);
        }

        @Override
        public void openUrl(String url) {
            if (url != null && !url.isBlank()) {
                host.openExternalUrl(url);
            }
        }
    }

    /** An {@link ActiveEditor} over a fixed buffer, or the live active buffer when null. */
    private final class ActiveEditorImpl implements ActiveEditor {
        private final EditorBuffer fixed;

        ActiveEditorImpl(EditorBuffer fixed) {
            this.fixed = fixed;
        }

        private EditorBuffer buf() {
            return fixed != null ? fixed : host.activeBuffer();
        }

        @Override
        public Path filePath() {
            EditorBuffer b = buf();
            return b == null ? null : b.getPath();
        }

        @Override
        public String text() {
            EditorBuffer b = buf();
            return b == null ? "" : b.getContent();
        }

        @Override
        public String selectedText() {
            EditorBuffer b = buf();
            return b == null ? "" : b.getArea().getSelectedText();
        }

        @Override
        public int caretLine() {
            EditorBuffer b = buf();
            return b == null ? -1 : b.getArea().getCurrentParagraph() + 1; // 1-based
        }

        @Override
        public void replaceSelection(String replacement) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && replacement != null) {
                b.getArea().replaceSelection(replacement);
            }
        }

        @Override
        public void insertAtCaret(String text) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && text != null) {
                b.getArea().insertText(b.getArea().getCaretPosition(), text);
            }
        }

        @Override
        public void setText(String text) {
            EditorBuffer b = buf();
            if (b != null && b.isEditable() && text != null) {
                b.getArea().replaceText(text); // whole-document replace (undoable, marks dirty)
            }
        }

        @Override
        public void openPath(Path path) {
            if (path != null) {
                ops.openPath(path);
            }
        }
    }
}
