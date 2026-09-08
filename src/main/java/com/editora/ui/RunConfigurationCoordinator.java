package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** Owns run-configuration selection, toolbar state and launch actions. */
final class RunConfigurationCoordinator {
    interface Host {
        WindowChromeCoordinator chrome();

        Button runConfigStopButton();

        Button runConfigDebugButton();

        Button runConfigRunButton();

        javafx.scene.control.ComboBox<com.editora.config.RunConfiguration> runConfigCombo();

        Stage stage();

        ConfigManager config();

        CommandRegistry registry();

        SettingsWindow settingsWindow();

        OverlayHost overlayHost();

        DebugCoordinator debugCoordinator();

        String homeCollapsed(String full);

        List<BuildCoordinator> buildCoordinators();

        RunCoordinator runCoordinator();

        String readGradleBuildFile(java.nio.file.Path root);

        void setStatus(String message);

        void setError(String message);

        EditorBuffer activeBuffer();

        boolean save(EditorBuffer buffer);

        void requestSave();

        String programArgsFor(Path path);

        String suggestedMainClass();

        Path windowProjectRoot();

        Path activeProjectRoot();

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);
    }

    private final Host host;

    RunConfigurationCoordinator(Host host) {
        this.host = host;
    }

    /**
     * The dropdown's trailing "Edit Configurations…" row.
     *
     * <p>A sentinel item rather than a fifth toolbar button: the dropdown is where you already are when you
     * want to change the thing you just picked, and it is where every IDE puts this. Compared by
     * <b>identity</b>, never by name — a real configuration a user happens to name "Edit Configurations…"
     * must still behave like a configuration.
     *
     * <p>It is not a selectable value. Choosing it reverts the selection and opens Settings, so
     * {@code host.runConfigCombo().getValue()} is never the sentinel by the time anything reads it.
     */
    static final com.editora.config.RunConfiguration EDIT_CONFIGS_ROW =
            new com.editora.config.RunConfiguration("", "", "", "", "", "");

    /** True while the value listener is reverting a click on {@link #EDIT_CONFIGS_ROW}, so it ignores itself. */
    boolean revertingRunConfigRow;

    /**
     * Opens the Run Configurations page on a named configuration (null ⇒ whatever was selected there).
     *
     * <p>The single indirection for all three routes to that page — the dropdown's
     * {@link #EDIT_CONFIGS_ROW}, and the Run and Debug guards that take you to a configuration too
     * incomplete to launch. Always {@link #openRunConfigEditor} in production.
     *
     * <p>A seam (the {@code *ForTest} shape used by {@code LanguageServerSession.attachForTest} and
     * {@code DoctorCoordinator.probeOverrideForTest}) because opening the real Settings window builds every
     * page: tests that drove it passed alone and timed out under the full suite. Measured, not assumed —
     * both times.
     */
    java.util.function.Consumer<String> runConfigEditor = this::openRunConfigEditor;

    void openRunConfigEditor(String name) {
        host.settingsWindow().showRunConfigs(name, host.stage());
    }

    /** Project root the makefile probe last ran for, so it runs once per root rather than per refresh. */
    Path makefileProbedRoot;

    /** Whether {@link #makefileProbedRoot} holds a makefile — the cached answer for that root. */
    boolean makefileAtProjectRoot;

    /**
     * True while {@link #refreshRunConfigs()} is repopulating the selector, so its value listener can tell a
     * programmatic reset from a user's choice.
     *
     * <p>Without this the listener persisted on every repopulation — including the one during {@code init},
     * which runs <em>before</em> {@code setWindowContext} points the config at this window's session file. It
     * therefore wrote the default, empty workspace state over the saved one and wiped the open-file list.
     * Caught by {@code NoSessionStartupFxTest}, which exists for exactly that class of clobber.
     */
    boolean populatingRunConfigs;

    /** Width of the toolbar's run-configuration selector — see {@code setupRunConfigCombo}. */
    static final double RUN_CONFIG_COMBO_WIDTH = 150;

    /** Toolbar run-configuration selector + its Run/Debug/Stop buttons (#765). */

    /**
     * A configuration's detail line in the pickers: what it actually launches.
     *
     * <p>This slot used to hold the {@code run}/{@code debug} tag. With that gone the useful thing to show is
     * the target itself — which also disambiguates two configurations that differ only in their arguments.
     * No i18n: a class name and a script path are technical identifiers, not prose.
     */
    static String runConfigDetail(com.editora.config.RunConfiguration cfg) {
        return cfg.isJava() ? cfg.mainClass() : cfg.target();
    }

    /** {@code run.config}: pick a saved configuration and run it. */
    void runSavedConfig() {
        if (host.config().getWorkspaceState().getRunConfigurations().isEmpty()) {
            host.setStatus(tr("status.run.noConfigs"));
            return;
        }
        QuickOpen<com.editora.config.RunConfiguration> picker = new QuickOpen<>(
                tr("run.config.title"),
                tr("run.config.prompt"),
                () -> List.copyOf(host.config().getWorkspaceState().getRunConfigurations()),
                com.editora.config.RunConfiguration::name,
                RunConfigurationCoordinator::runConfigDetail,
                cfg -> {
                    if (cfg != null) {
                        host.runCoordinator().runConfig(cfg);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** {@code debug.config}: pick a saved configuration and debug it — the same entries, the other verb. */
    void debugSavedConfig() {
        if (host.config().getWorkspaceState().getRunConfigurations().isEmpty()) {
            host.setStatus(tr("status.run.noConfigs"));
            return;
        }
        QuickOpen<com.editora.config.RunConfiguration> picker = new QuickOpen<>(
                tr("run.config.debugPickerTitle"),
                tr("run.config.prompt"),
                () -> List.copyOf(host.config().getWorkspaceState().getRunConfigurations()),
                com.editora.config.RunConfiguration::name,
                RunConfigurationCoordinator::runConfigDetail,
                cfg -> {
                    if (cfg != null) {
                        host.debugCoordinator().debugConfig(cfg);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /** Renders the selector's rows by name and remembers the choice across restarts. */
    void setupRunConfigCombo() {
        host.runConfigCombo().getStyleClass().add("run-config-combo");
        host.runConfigCombo().setPromptText(tr("toolbar.runConfig.none"));
        // Bounded, because the toolbar is saturated: JavaFX pushes what does not fit into the ">>" overflow
        // popup, and an unbounded ComboBox sized to its widest configuration name would evict several
        // buttons — or itself. A fixed, modest width keeps the whole run group on the bar at normal window
        // sizes; long names still show in full in the dropdown.
        host.runConfigCombo().setPrefWidth(RUN_CONFIG_COMBO_WIDTH);
        host.runConfigCombo().setMinWidth(RUN_CONFIG_COMBO_WIDTH);
        host.runConfigCombo().setMaxWidth(RUN_CONFIG_COMBO_WIDTH);
        host.runConfigCombo().setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(com.editora.config.RunConfiguration cfg) {
                if (cfg == null) {
                    return "";
                }
                if (cfg == EDIT_CONFIGS_ROW) {
                    return tr("toolbar.runConfig.edit");
                }
                // Just the name: the row no longer carries a run/debug tag, because the two buttons beside
                // this combo are what choose between them.
                return cfg.name();
            }

            @Override
            public com.editora.config.RunConfiguration fromString(String s) {
                return null; // not editable
            }
        });
        host.runConfigCombo().valueProperty().addListener((o, was, now) -> {
            if (revertingRunConfigRow) {
                return; // our own revert below, not a choice
            }
            if (populatingRunConfigs) {
                updateRunConfigButtons(); // a rebuild, not a choice: reflect it but persist nothing
                return;
            }
            if (now == EDIT_CONFIGS_ROW) {
                // Put the real selection back first, so nothing downstream — the Run buttons, the persisted
                // selectedRunConfig, a launch — ever sees the sentinel as the chosen configuration.
                revertingRunConfigRow = true;
                try {
                    host.runConfigCombo().setValue(was == EDIT_CONFIGS_ROW ? null : was);
                } finally {
                    revertingRunConfigRow = false;
                }
                editRunConfigs();
                return;
            }
            host.config().getWorkspaceState().setSelectedRunConfig(now == null ? "" : now.name());
            host.requestSave();
            updateRunConfigButtons();
        });
    }

    /**
     * Rebuilds the toolbar selector from the saved configurations and re-registers <b>two</b> synthetic
     * commands per configuration — {@code run.config.<slug>} and {@code debug.config.<slug>} — so each is
     * palette-visible and can be given a keybinding, the same shape macros ({@code macro.run.*}) and
     * external tools already use.
     *
     * <p>Two rather than one because a command id is what a keybinding binds to. A configuration used to
     * declare a {@code kind}, and the single command honoured it; that was the only way to bind a key that
     * debugged a named configuration, and it came at the cost of making such an entry impossible to plain-run
     * from the toolbar. A command each gives both without the trade.
     *
     * <p>Called after any change to the list: the save/delete commands, and every settings apply (the
     * Settings page edits the same list).
     */
    void refreshRunConfigs() {
        List<com.editora.config.RunConfiguration> configs =
                List.copyOf(host.config().getWorkspaceState().getRunConfigurations());

        // Drop stale synthetic commands before re-registering, or a renamed configuration would leave its old
        // id behind in the palette pointing at something that no longer exists. Both prefixes: missing one
        // here would strand every debug twin the moment a configuration was renamed.
        List<String> stale = new ArrayList<>();
        for (Command c : host.registry().all()) {
            if (c.id().startsWith(com.editora.config.RunConfiguration.COMMAND_PREFIX)
                    || c.id().startsWith(com.editora.config.RunConfiguration.DEBUG_COMMAND_PREFIX)) {
                stale.add(c.id());
            }
        }
        stale.forEach(host.registry()::remove);
        for (com.editora.config.RunConfiguration cfg : configs) {
            host.registry()
                    .register(Command.of(
                            com.editora.config.RunConfiguration.commandIdFor(cfg.name()),
                            tr("run.config.runCommandTitle", cfg.name()),
                            () -> host.runCoordinator().runConfig(cfg)));
            host.registry()
                    .register(Command.of(
                            com.editora.config.RunConfiguration.debugCommandIdFor(cfg.name()),
                            tr("run.config.debugCommandTitle", cfg.name()),
                            () -> host.debugCoordinator().debugConfig(cfg)));
        }

        if (host.runConfigCombo() == null) {
            return;
        }
        com.editora.config.RunConfiguration previous = host.runConfigCombo().getValue();
        populatingRunConfigs = true;
        try {
            repopulate(configs, previous);
        } finally {
            populatingRunConfigs = false;
        }
        updateRunConfigButtons();
        refreshRunConfigToolbar(); // saved configurations keep the group visible even in a plain folder
    }

    /** Replaces the selector's items, re-selecting the previous choice by name. */
    void repopulate(List<com.editora.config.RunConfiguration> configs, com.editora.config.RunConfiguration previous) {
        // One setAll, not setAll-then-add. The sentinel is always offered — including with no configurations
        // at all, where it is how you make the first — and building the row list first means the combo's items
        // never pass through a state that is missing it. Two mutations fire two change events, and the popup's
        // own ListView is a listener on them: a combo whose items shrink under a selection in flight is the
        // shape that makes JavaFX's ListViewBehavior read a stale added-range and throw
        // IndexOutOfBoundsException out to the uncaught handler (reproduced against JavaFX 26 in
        // ComboItemsMutationFxTest). Whether that is the crash seen in the wild is unproven — this closes the
        // one place in the app carrying the shape.
        List<com.editora.config.RunConfiguration> rows = new ArrayList<>(configs);
        rows.add(EDIT_CONFIGS_ROW);
        host.runConfigCombo().getItems().setAll(rows);
        // Re-select by name: the list is rebuilt from the store on every refresh, so the old instance is not
        // the same object even when the configuration is unchanged. Searches `configs`, not the combo's items,
        // so the sentinel can never be re-selected — it has a blank name and would otherwise match an unnamed
        // configuration.
        com.editora.config.RunConfiguration reselect = null;
        String wanted = previous != null && previous != EDIT_CONFIGS_ROW
                ? previous.name()
                : host.config().getWorkspaceState().getSelectedRunConfig();
        for (com.editora.config.RunConfiguration cfg : configs) {
            if (cfg.name().equals(wanted)) {
                reselect = cfg;
                break;
            }
        }
        host.runConfigCombo().setValue(reselect != null ? reselect : (configs.isEmpty() ? null : configs.get(0)));
    }

    /**
     * Shows or hides the toolbar's whole run-configuration group per {@link com.editora.run.RunConfigToolbar#visible}.
     *
     * <p>Re-run whenever any of its inputs can have moved: a build tool detected or lost (the
     * {@code BuildCoordinator.Ops} hook), the configuration list edited, the project switched, and every
     * chrome pass (which is also every toolbar rebuild, which re-adds the group to the tail).
     */
    void refreshRunConfigToolbar() {
        if (host.runConfigCombo() == null) {
            return; // called before the FXML is injected
        }
        boolean show = com.editora.run.RunConfigToolbar.visible(
                host.chrome().simpleModeActive(),
                isLaunchableContext(),
                host.config().getWorkspaceState().getRunConfigurations().size());
        for (Node n : new Node[] {
            host.runConfigCombo(), host.runConfigRunButton(), host.runConfigDebugButton(), host.runConfigStopButton()
        }) {
            if (n != null) {
                n.setVisible(show);
                n.setManaged(show);
            }
        }
        // The group lives in the tail, which has no separators to orphan — but this runs on the same passes
        // that hide other icons, and the walk is cheap and self-correcting.
        host.chrome().collapseToolbarSeparators();
    }

    /**
     * Whether this window's project root holds a makefile, cached per root.
     *
     * <p>The probe is a stat, but the visibility refresh runs on the tab-switch/focus/save cadence and the
     * root changes far more rarely than that — and an SFTP root would make it a network round trip, hence the
     * locality guard.
     */
    boolean projectHasMakefile() {
        Path root = host.windowProjectRoot();
        if (root == null) {
            makefileProbedRoot = null;
            return false;
        }
        if (!root.equals(makefileProbedRoot)) {
            makefileProbedRoot = root;
            makefileAtProjectRoot =
                    com.editora.vfs.Vfs.isLocal(root) && com.editora.run.RunConfigToolbar.hasMakefile(root);
        }
        return makefileAtProjectRoot;
    }

    /**
     * Whether this window is pointed at something launchable — see
     * {@link com.editora.run.RunConfigToolbar#launchable}.
     *
     * <p>Each build tool is asked separately rather than folding them through {@link #anyBuildDetected()},
     * because with a project open <em>where</em> the marker was found decides the answer, not merely that one
     * was.
     */
    boolean isLaunchableContext() {
        Path project = host.windowProjectRoot();
        boolean makefile = projectHasMakefile();
        for (BuildCoordinator c : host.buildCoordinators()) {
            Path marker = c.isEnabled() ? c.markerRoot() : null;
            if (com.editora.run.RunConfigToolbar.launchable(project, marker, makefile)) {
                return true;
            }
        }
        // No tool detected anything; a makefile at the project root still makes it launchable.
        return com.editora.run.RunConfigToolbar.launchable(project, null, makefile);
    }

    /** Enables the toolbar Run/Debug buttons only when a configuration is selected; Stop only while running. */
    void updateRunConfigButtons() {
        boolean hasSelection =
                host.runConfigCombo() != null && host.runConfigCombo().getValue() != null;
        if (host.runConfigRunButton() != null) {
            host.runConfigRunButton().setDisable(!hasSelection);
            host.runConfigDebugButton().setDisable(!hasSelection);
            host.runConfigStopButton().setDisable(!host.runCoordinator().isRunning());
        }
    }

    /**
     * Opens Settings → Run Configurations on the configuration the dropdown currently has selected.
     *
     * <p>Reached from the dropdown's "Edit Configurations…" row and the {@code run.editConfigs} command. With
     * nothing selected it opens the page, which is also how you create the first configuration.
     */
    void editRunConfigs() {
        com.editora.config.RunConfiguration selected =
                host.runConfigCombo() == null ? null : host.runConfigCombo().getValue();
        String name = selected == null || selected == EDIT_CONFIGS_ROW ? null : selected.name();
        runConfigEditor.accept(name);
    }

    /** The configuration the toolbar's Run/Debug buttons act on, or null with nothing selected. */
    com.editora.config.RunConfiguration selectedRunConfig() {
        return host.runConfigCombo() == null ? null : host.runConfigCombo().getValue();
    }

    /** Runs the selected configuration. The button chooses the verb; the configuration never does. */
    void onRunSelectedConfig() {
        com.editora.config.RunConfiguration cfg = selectedRunConfig();
        if (cfg != null) {
            host.runCoordinator().runConfig(cfg);
        }
    }

    /** Debugs the selected configuration — the same entry, the other verb. */
    void onDebugSelectedConfig() {
        com.editora.config.RunConfiguration cfg = selectedRunConfig();
        if (cfg != null) {
            host.debugCoordinator().debugConfig(cfg);
        }
    }

    void onStopRun() {
        host.runCoordinator().stopRun();
        updateRunConfigButtons();
    }

    void saveRunConfig() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.run.needJavaFile"));
            return;
        }
        String fqn = host.suggestedMainClass();
        if (fqn == null) {
            host.setStatus(tr("status.run.noMainInFile"));
            return;
        }
        String simple = com.editora.test.TestSourceLocator.simpleName(fqn);
        String args = host.programArgsFor(b.getPath());
        host.promptText(tr("run.config.saveTitle"), tr("run.config.saveName"), simple, name -> {
            if (name == null || name.isBlank()) {
                return;
            }
            List<com.editora.config.RunConfiguration> list =
                    new java.util.ArrayList<>(host.config().getWorkspaceState().getRunConfigurations());
            list.add(new com.editora.config.RunConfiguration(name.strip(), fqn, "", args, "", ""));
            host.config().getWorkspaceState().setRunConfigurations(list);
            host.config().save();
            refreshRunConfigs();
            host.setStatus(tr("status.run.configSaved", name.strip()));
        });
    }

    /**
     * {@code run.exportConfigs}: writes this window's run configurations into the project, so they can be
     * committed and shared. Needs a project — there is nowhere else they would belong.
     */
    void exportRunConfigs() {
        Path root = host.activeProjectRoot();
        if (root == null) {
            host.setStatus(tr("status.run.configsNeedProject"));
            return;
        }
        List<com.editora.config.RunConfiguration> configs =
                host.config().getWorkspaceState().getRunConfigurations();
        if (configs.isEmpty()) {
            host.setStatus(tr("status.run.noConfigs"));
            return;
        }
        try {
            com.editora.config.SharedRunConfigs.save(new com.fasterxml.jackson.databind.ObjectMapper(), root, configs);
            host.setStatus(tr(
                    "status.run.configsExported",
                    configs.size(),
                    host.homeCollapsed(
                            com.editora.config.SharedRunConfigs.fileFor(root).toString())));
        } catch (java.io.IOException e) {
            host.setError(tr("status.run.configsExportFailed", e.getMessage()));
        }
    }

    /**
     * {@code run.importConfigs}: merges the project's shared configurations into this window's, matching by
     * name so importing twice does not duplicate and a colleague's edit updates rather than doubles.
     */
    void importRunConfigs() {
        Path root = host.activeProjectRoot();
        if (root == null) {
            host.setStatus(tr("status.run.configsNeedProject"));
            return;
        }
        List<com.editora.config.RunConfiguration> incoming =
                com.editora.config.SharedRunConfigs.load(new com.fasterxml.jackson.databind.ObjectMapper(), root);
        if (incoming.isEmpty()) {
            host.setStatus(tr(
                    "status.run.noSharedConfigs",
                    host.homeCollapsed(
                            com.editora.config.SharedRunConfigs.fileFor(root).toString())));
            return;
        }
        List<com.editora.config.RunConfiguration> merged = com.editora.config.SharedRunConfigs.merge(
                host.config().getWorkspaceState().getRunConfigurations(), incoming);
        host.config().getWorkspaceState().setRunConfigurations(merged);
        host.config().save();
        refreshRunConfigs();
        host.setStatus(tr("status.run.configsImported", incoming.size()));
    }

    /** {@code run.deleteConfig}: pick a saved run configuration and remove it. */
    void deleteRunConfig() {
        if (host.config().getWorkspaceState().getRunConfigurations().isEmpty()) {
            host.setStatus(tr("status.run.noConfigs"));
            return;
        }
        QuickOpen<com.editora.config.RunConfiguration> picker = new QuickOpen<>(
                tr("run.config.deleteTitle"),
                tr("run.config.prompt"),
                () -> List.copyOf(host.config().getWorkspaceState().getRunConfigurations()),
                com.editora.config.RunConfiguration::name,
                RunConfigurationCoordinator::runConfigDetail,
                cfg -> {
                    if (cfg == null) {
                        return;
                    }
                    List<com.editora.config.RunConfiguration> list = new java.util.ArrayList<>(
                            host.config().getWorkspaceState().getRunConfigurations());
                    list.removeIf(
                            c -> c.name().equals(cfg.name()) && c.mainClass().equals(cfg.mainClass()));
                    host.config().getWorkspaceState().setRunConfigurations(list);
                    host.config().save();
                    refreshRunConfigs();
                    host.setStatus(tr("status.run.configDeleted", cfg.name()));
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.stage());
    }

    /**
     * {@code debug.viaBuild}: debug a Maven/Gradle app by launching it under a suspended JDWP agent via the
     * build tool, then attaching when the "Listening for transport…" banner appears. Complements jdtls's
     * launch — the clean path for Gradle ({@code run}/{@code bootRun} {@code --debug-jvm}) and Maven Spring
     * Boot ({@code spring-boot:run} + JDWP {@code jvmArguments}). Still needs the java-debug bundle (that's the
     * attach adapter); plain Maven {@code main} has no uniform build-tool debug mechanism (use Debug Main Class).
     */
    void debugViaBuild() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        if (!host.debugCoordinator().debugEffectiveFor("java")) {
            host.setStatus(tr("status.debug.unavailable"));
            return;
        }
        java.nio.file.Path routing = b.getPath();
        java.nio.file.Path root = JavaProjectRoot.find(routing);
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (b.isDirty() && !host.save(b)) {
            return;
        }
        BuildCoordinator gradle = detectedBuildCoordinator(BuildTool.GRADLE);
        if (gradle != null) {
            String task = com.editora.build.SpringBoot.gradleRunTask(host.readGradleBuildFile(root)); // run / bootRun
            armDebugAttach(gradle, routing);
            host.setStatus(tr("status.debug.viaBuildStarting"));
            gradle.runTask(com.editora.build.BuildDebug.gradleDebugArgs(task), List.of());
            return;
        }
        BuildCoordinator maven = detectedBuildCoordinator(BuildTool.MAVEN);
        if (maven != null
                && com.editora.build.BuildDebug.isSpringBootMavenPom(readTextOrEmpty(root.resolve("pom.xml")))) {
            armDebugAttach(maven, routing);
            host.setStatus(tr("status.debug.viaBuildStarting"));
            maven.runTask(com.editora.build.BuildDebug.mavenSpringBootDebugArgs(), List.of());
            return;
        }
        host.setStatus(tr("status.debug.viaBuildUnsupported"));
    }

    /** The enabled+detected build coordinator for {@code tool}, or null. */
    BuildCoordinator detectedBuildCoordinator(BuildTool tool) {
        for (BuildCoordinator c : host.buildCoordinators()) {
            if (c.tool() == tool && c.isEnabled() && c.isDetected()) {
                return c;
            }
        }
        return null;
    }

    /** Watches {@code c}'s next-run output for the JDWP banner, then attaches the debugger (one-shot). */
    void armDebugAttach(BuildCoordinator c, java.nio.file.Path routing) {
        c.setOutputWatcher((line, stderr) -> {
            int port = com.editora.test.TestDebug.jdwpPort(line);
            if (port > 0) {
                c.setOutputWatcher(null);
                host.debugCoordinator().attachToPort(routing, "localhost", port);
            }
        });
    }

    static String readTextOrEmpty(java.nio.file.Path f) {
        try {
            return java.nio.file.Files.isRegularFile(f) ? java.nio.file.Files.readString(f) : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
