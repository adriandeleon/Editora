package com.editora.ui;

import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Plugins: the master switch, the installed list (one row per plugin with its state and Remove), the
 * marketplace buttons and the registry URL. The page's controls, its load and the injected plugin actions live
 * here; {@link SettingsWindow} builds it with the other pages and forwards its setters to it.
 */
final class SettingsPluginsPage {

    private final SettingsWindow w;
    private final ConfigManager config;

    SettingsPluginsPage(SettingsWindow w, ConfigManager config) {
        this.w = w;
        this.config = config;
    }

    @Initializer
    void buildControls() {
        pluginCheck = new CheckBox(tr("settings.enablePlugins"));
        pluginCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPluginSupport(now);
            w.apply();
        });
        pluginRequireSigCheck = new CheckBox(tr("settings.plugins.requireSignature"));
        pluginRequireSigCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPluginRequireSignature(now);
            w.apply();
        });
    }

    private com.editora.plugin.@Nullable PluginManager pluginManager; // shared, injected after construction
    private CheckBox pluginCheck;
    private CheckBox pluginRequireSigCheck;
    private @Nullable VBox pluginListBox; // rebuilt on each load() from the shared PluginManager's descriptors
    private @Nullable TextField pluginRegistryField;
    private @Nullable Label pluginRegistryWarn; // shown when the registry URL isn't the trusted default

    private @Nullable Runnable onBrowsePlugins; // → MainController.browsePlugins
    private @Nullable Runnable onInstallPluginFromFile; // → MainController.installPluginFromDisk
    private @Nullable Consumer<com.editora.plugin.PluginDescriptor> onUninstallPlugin; // the clicked row's plugin
    /** Ticking a row: asks the consent (what it can do) and stores it; false = not enabled. */
    private java.util.function.Predicate<com.editora.plugin.PluginDescriptor> onEnablePlugin = d -> false;
    /** Unticking a row: switches it off. */
    private Consumer<com.editora.plugin.PluginDescriptor> onDisablePlugin = d -> {};

    private @Nullable Label pluginStatusLabel; // what Browse / Install from file did, said where the user clicked
    /** What the installed list was last built from; an unchanged list is not rebuilt on every Settings open. */
    private @Nullable Object pluginListKey;

    /**
     * Injects the shared {@link com.editora.plugin.PluginManager} (set after construction by
     * {@code MainController}). The Plugins page lists its discovered descriptors; safe to call before
     * {@link SettingsWindow#show} builds the page lazily on first show.
     */
    public void setPluginManager(com.editora.plugin.@Nullable PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** Wires the Plugins-page actions to the controller (browse registry / install zip / uninstall). */
    public void setPluginActions(
            Runnable onBrowse,
            Runnable onInstallFromFile,
            Consumer<com.editora.plugin.PluginDescriptor> onUninstall,
            java.util.function.Predicate<com.editora.plugin.PluginDescriptor> onEnable,
            Consumer<com.editora.plugin.PluginDescriptor> onDisable) {
        this.onBrowsePlugins = onBrowse;
        this.onInstallPluginFromFile = onInstallFromFile;
        this.onUninstallPlugin = onUninstall;
        this.onEnablePlugin = onEnable == null ? d -> false : onEnable;
        this.onDisablePlugin = onDisable == null ? d -> {} : onDisable;
    }

    VBox build() {
        VBox p = w.page(tr("settings.cat.plugins"), tr("settings.plugins.subtitle"));
        Card mainCard = w.card(p, null);
        Label warn = SettingsWindow.note(tr("settings.plugins.note"));
        warn.getStyleClass().add("settings-experimental");
        warn.setWrapText(true);
        UiZoom.maxWidth(warn, 440);
        w.cardRow(mainCard, Category.PLUGINS, warn, "plugins extensions untrusted security warning");
        // The switch is read only at start-up, so its own row says so: a note elsewhere on the card is not
        // what a search for this setting shows.
        w.checkRow(
                mainCard,
                Category.PLUGINS,
                pluginCheck,
                tr("settings.plugins.restart"),
                "plugins extensions enable support restart apply");
        if (com.editora.plugin.PluginManager.disabledForSession()) {
            Label off = SettingsWindow.note(tr("settings.plugins.disabledForSession"));
            off.getStyleClass().add("settings-git-missing");
            off.setWrapText(true);
            UiZoom.maxWidth(off, 440);
            w.cardRow(mainCard, Category.PLUGINS, off, "plugins disable session safe mode command line");
        }

        // Installed Plugins first: they are what people come to this page for.
        Card installed = w.card(p, tr("settings.plugins.installed"));
        Button reload = new Button(tr("settings.plugins.reload"));
        reload.setOnAction(e -> {
            if (pluginManager != null) {
                pluginManager.discover(); // the list only: what runs is the launch's set until a restart
                pluginListKey = null;
                refreshPluginList();
            }
        });
        Label folderLabel = new Label(tr("settings.plugins.folder"));
        Label folderPath =
                new Label(pluginManager == null ? "" : config.getPluginsDir().toString());
        folderPath.getStyleClass().add("settings-hint");
        folderPath.setWrapText(true);
        UiZoom.maxWidth(folderPath, 380);
        HBox folderRow = UiZoom.spacing(new HBox(folderLabel, folderPath), 6);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox listHeader = UiZoom.spacing(new HBox(reload, folderRow), 8);
        listHeader.setAlignment(Pos.CENTER_LEFT);
        pluginListBox = UiZoom.spacing(new VBox(), 8);
        w.cardRow(
                installed,
                Category.PLUGINS,
                UiZoom.spacing(
                        new VBox(
                                SettingsDependents.note(pluginCheck.selectedProperty(), tr("settings.enablePlugins")),
                                listHeader,
                                pluginListBox),
                        6),
                "plugins installed list enable disable reload rescan folder directory");

        // --- Marketplace: a curated GitHub-hosted registry + install-from-disk.
        Card market = w.card(p, tr("settings.plugins.marketplace"));
        Button browse = new Button(tr("settings.plugins.browse"));
        browse.setOnAction(e -> {
            if (onBrowsePlugins != null) {
                onBrowsePlugins.run(); // the picker hides this window only once it opens (hideForMainWindowPicker)
            }
        });
        Button installFile = new Button(tr("settings.plugins.installFromFile"));
        installFile.setOnAction(e -> {
            if (onInstallPluginFromFile != null) {
                onInstallPluginFromFile.run();
            }
        });
        HBox marketButtons = UiZoom.spacing(new HBox(browse, installFile), 8);
        marketButtons.setAlignment(Pos.CENTER_LEFT);
        // Greyed with the master switch, like the rows: they did nothing visible while plugins were off.
        SettingsDependents.bind(pluginCheck.selectedProperty(), marketButtons);
        pluginStatusLabel = new Label();
        pluginStatusLabel.setWrapText(true);
        UiZoom.maxWidth(pluginStatusLabel, 440);
        pluginStatusLabel.getStyleClass().add("settings-hint");
        pluginStatusLabel.setVisible(false);
        pluginStatusLabel.managedProperty().bind(pluginStatusLabel.visibleProperty());
        w.cardRow(
                market,
                Category.PLUGINS,
                UiZoom.spacing(new VBox(marketButtons, pluginStatusLabel), 4),
                "plugins browse install file zip marketplace registry");
        TextField registryField = new TextField();
        pluginRegistryField = registryField;
        pluginRegistryField.setPromptText(com.editora.config.Settings.DEFAULT_PLUGIN_REGISTRY);
        // Wide enough to show a full registry URL (the default is ~75 chars); also grows with the page.
        pluginRegistryField.setPrefColumnCount(64);
        UiZoom.prefWidth(pluginRegistryField, 560);
        pluginRegistryField.setMaxWidth(Double.MAX_VALUE);
        pluginRegistryWarn = new Label();
        pluginRegistryWarn.getStyleClass().add("settings-git-missing"); // amber/red "caution" styling
        pluginRegistryWarn.setWrapText(true);
        UiZoom.maxWidth(pluginRegistryWarn, 440);
        w.commitOnEnterOrBlur(
                pluginRegistryField,
                () -> SettingsWindow.shownOrBlank(
                        registryField,
                        config.getSettings().getPluginRegistryUrl(),
                        config.getSettings().getPluginRegistryUrlRaw()),
                now -> {
                    if (!com.editora.config.Settings.isAcceptableRegistryUrl(now)) {
                        return; // not https: the field is red and says why; the registry in use stays
                    }
                    config.getSettings().setPluginRegistryUrl(now);
                    w.apply();
                    updateRegistryWarn();
                });
        pluginRegistryField.textProperty().addListener((o, was, now) -> {
            boolean bad = !now.isBlank() && !com.editora.plugin.PluginRegistry.isHttps(now);
            registryField.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, bad);
            registryField.setTooltip(bad ? ControlNames.tooltip(tr("settings.plugins.registryNotHttps")) : null);
        });
        Label regNote = SettingsWindow.note(tr("settings.plugins.registryNote"));
        regNote.setWrapText(true);
        UiZoom.maxWidth(regNote, 440);
        VBox regBox = UiZoom.spacing(new VBox(pluginRegistryField, pluginRegistryWarn, regNote), 4);
        w.controlRow(
                market,
                Category.PLUGINS,
                tr("settings.plugins.registryUrl"),
                null,
                regBox,
                "plugins registry url index marketplace github browse");
        w.checkRow(
                market,
                Category.PLUGINS,
                pluginRequireSigCheck,
                tr("settings.plugins.requireSignatureNote"),
                "plugins signature signed verify registry security trust");
        refreshPluginList();
        return p;
    }

    /** Says what a plugin action started here did, under the Browse buttons (error: in the warning colour). */
    void showPluginStatus(String message, boolean error) {
        if (pluginStatusLabel == null) {
            return;
        }
        boolean shown = message != null && !message.isBlank();
        pluginStatusLabel.setText(shown ? message : "");
        pluginStatusLabel.setVisible(shown);
        pluginStatusLabel.getStyleClass().removeAll("settings-hint", "settings-git-missing");
        pluginStatusLabel.getStyleClass().add(error ? "settings-git-missing" : "settings-hint");
    }

    /**
     * Rebuilds the per-plugin list from the shared {@link com.editora.plugin.PluginManager}: each row says what
     * it is (name, version, id, a load or start error) and how it differs from what this session runs — the
     * launch set — with a Restart now. A row that failed to load cannot be ticked: what it would do cannot be
     * shown, so it cannot be consented to. Skipped when neither the list nor the store changed since.
     */
    private void refreshPluginList() {
        if (pluginListBox == null) {
            return;
        }
        java.util.List<com.editora.plugin.PluginDescriptor> ds =
                pluginManager == null ? java.util.List.of() : pluginManager.descriptors();
        com.editora.config.PluginStore store = config.getPluginStore();
        Object key =
                java.util.List.of(ds, Map.copyOf(store.enabled), Map.copyOf(store.consent), pluginCheck.isSelected());
        if (key.equals(pluginListKey)) {
            return; // D6: re-opening Settings no longer rebuilds an unchanged list
        }
        pluginListKey = key;
        pluginListBox.getChildren().clear();
        if (ds.isEmpty() || pluginManager == null) { // no manager, no descriptors
            Label empty = SettingsWindow.note(tr("settings.plugins.none"));
            empty.setWrapText(true);
            pluginListBox.getChildren().add(empty);
            return;
        }
        for (com.editora.plugin.PluginDescriptor d : ds) {
            pluginListBox.getChildren().add(pluginRow(pluginManager, d, store));
        }
    }

    private Node pluginRow(
            com.editora.plugin.PluginManager pluginManager,
            com.editora.plugin.PluginDescriptor d,
            com.editora.config.PluginStore store) {
        String name = d.displayName();
        String ver = d.manifest().version == null ? "" : d.manifest().version.strip();
        com.editora.plugin.PluginDescriptor runningNow = pluginManager.running(d.id());
        boolean wanted = store.allows(d.id(), d.digest());
        PluginRowState.Pill pill = PluginRowState.pill(
                wanted,
                store.isEnabled(d.id()),
                runningNow != null,
                runningNow == null ? null : runningNow.manifest().version,
                ver,
                pluginManager.pendingUpdate(d.id()),
                pluginManager.pendingRemoval(d.id()));
        CheckBox cb = new CheckBox(PluginDisclosure.clean(ver.isBlank() ? name : name + "  " + ver, 120));
        cb.setSelected(wanted || pill == PluginRowState.Pill.UPDATE_PENDING);
        cb.setMinWidth(0);
        cb.setTooltip(ControlNames.tooltip(name + (ver.isBlank() ? "" : " " + ver) + " (" + d.id() + ")"));
        SettingsDependents.bind(pluginCheck.selectedProperty(), cb); // follows the switch as it is flipped
        if (d.loadError() != null || pill == PluginRowState.Pill.REMOVAL_PENDING) {
            SettingsDependents.lockOf(cb).set(true); // a broken (or departing) plugin cannot be ticked at all
        }
        cb.selectedProperty().addListener((obs, was, now) -> {
            if (w.loading) {
                return;
            }
            // Enabling arms code on the next launch — disclose capabilities + confirm. Disabling is free.
            if (now) {
                if (!onEnablePlugin.test(d)) {
                    cb.setSelected(false); // declined: revert (fires again with now=false, was=true → off)
                    return;
                }
            } else if (was && store.isEnabled(d.id())) {
                onDisablePlugin.accept(d);
            }
            pluginListKey = null;
            javafx.application.Platform.runLater(this::refreshPluginList); // the row's pill follows
        });
        Label id = new Label(d.id());
        id.getStyleClass().add("settings-hint");
        id.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button remove = new Button(tr("settings.plugins.remove"));
        remove.getStyleClass().add("settings-link-button");
        remove.setMinWidth(Region.USE_PREF_SIZE); // a long name elides the label, never the button
        remove.setAccessibleText(tr("settings.plugins.removeNamed", name));
        remove.setDisable(pill == PluginRowState.Pill.REMOVAL_PENDING);
        remove.setOnAction(ev -> {
            if (onUninstallPlugin != null) {
                onUninstallPlugin.accept(d);
            }
        });
        HBox header = UiZoom.spacing(new HBox(cb, id, spacer, remove), 8);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cb, Priority.SOMETIMES);
        VBox entry = UiZoom.spacing(new VBox(header), 2);
        String pillText = PluginRowState.text(pill, pluginManager.pendingUpdate(d.id()));
        if (pillText != null) {
            Label restartPill = new Label(pillText);
            restartPill.getStyleClass().add("settings-git-status");
            HBox pillRow = UiZoom.spacing(new HBox(restartPill), 8);
            pillRow.setAlignment(Pos.CENTER_LEFT);
            Runnable restart = w.onRestart;
            if (pill != PluginRowState.Pill.CHANGED && restart != null) {
                Button restartNow = new Button(tr("settings.restart.now"));
                restartNow.getStyleClass().add("settings-link-button");
                restartNow.setOnAction(e -> restart.run());
                pillRow.getChildren().add(restartNow);
            }
            UiZoom.padding(pillRow, 0, 0, 0, 20);
            entry.getChildren().add(pillRow);
        }
        com.editora.plugin.PluginDescriptor.LoadProblem runtime = pluginManager.runtimeProblem(d.id());
        String problemText = d.loadError() != null
                ? PluginLoadProblems.text(d)
                : runtime != null ? PluginLoadProblems.text(runtime) : null;
        if (problemText != null) {
            Label err = new Label(problemText);
            String detail = d.loadError() != null ? PluginLoadProblems.detail(d) : PluginLoadProblems.detail(runtime);
            err.setTooltip(detail == null ? null : ControlNames.tooltip(detail));
            err.getStyleClass().add("settings-git-missing");
            err.setWrapText(true);
            UiZoom.maxWidth(err, 420);
            UiZoom.padding(err, 0, 0, 0, 20);
            entry.getChildren().add(err);
        }
        return entry;
    }

    /** Warns (with the host) when the registry URL isn't the bundled default — a phishing-vector guard. */
    private void updateRegistryWarn() {
        if (pluginRegistryWarn == null) {
            return;
        }
        String url = config.getSettings().getPluginRegistryUrl();
        boolean custom = url != null
                && !url.isBlank()
                && !url.strip().equals(com.editora.config.Settings.DEFAULT_PLUGIN_REGISTRY);
        String host = "";
        if (custom) {
            try {
                host = java.net.URI.create(url.strip()).getHost();
            } catch (RuntimeException ignored) {
                host = url.strip();
            }
        }
        pluginRegistryWarn.setText(custom ? tr("settings.plugins.customRegistry", host == null ? "?" : host) : "");
        pluginRegistryWarn.setVisible(custom);
        pluginRegistryWarn.setManaged(custom);
    }

    void load(Settings settings) {
        pluginCheck.setSelected(settings.isPluginSupport());
        if (pluginRequireSigCheck != null) {
            pluginRequireSigCheck.setSelected(settings.isPluginRequireSignature());
        }
        if (pluginRegistryField != null) {
            pluginRegistryField.setText(settings.getPluginRegistryUrl());
            updateRegistryWarn();
        }
        refreshPluginList(); // re-read enabled state + reflect the master gate
    }

    void syncCheck() {
        pluginCheck.setSelected(config.getSettings().isPluginSupport());
        refreshPluginList();
    }

    /** The installed list is rebuilt on the next refresh even if nothing it shows changed. */
    void forgetListKey() {
        pluginListKey = null;
    }
}
