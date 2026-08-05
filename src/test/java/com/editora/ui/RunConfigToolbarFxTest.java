package com.editora.ui;

import java.util.List;

import javafx.scene.control.ComboBox;

import com.editora.command.CommandRegistry;
import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the toolbar run-configuration selector and its synthetic commands (#765 part 3).
 *
 * <p>The interesting failure is the synthetic commands going stale: a renamed or deleted configuration that
 * leaves its old {@code run.config.<slug>} behind gives the palette an entry pointing at something that no
 * longer exists, which nothing else would catch.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunConfigToolbarFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void setConfigs(List<RunConfiguration> configs) throws Exception {
        FxTestSupport.runOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            cfg.getWorkspaceState().setRunConfigurations(new java.util.ArrayList<>(configs));
            FxTestSupport.invoke(fx.controller, "refreshRunConfigs");
        });
    }

    @SuppressWarnings("unchecked")
    private ComboBox<RunConfiguration> combo() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "runConfigCombo"));
    }

    private static RunConfiguration java(String name) {
        return new RunConfiguration(name, "run", "com.example.App", "", "", "", "");
    }

    /**
     * The Settings page's edits must reach the toolbar selector.
     *
     * <p>The Run Configurations page edits {@code WorkspaceState}, not {@code Settings}, so it never goes
     * through {@code SettingsWindow.apply()} — and {@code apply()} → {@code onApply} is the only thing that
     * reaches {@code refreshRunConfigs()}. So a configuration added there was saved to disk and then simply
     * never appeared in the selector until the next launch, with nothing logged. This drives the hook the
     * page now fires; a no-op hook fails it.
     */
    @Test
    void aConfigurationAddedOnTheSettingsPageReachesTheSelector() throws Exception {
        setConfigs(List.of());

        SettingsWindow settings = FxTestSupport.field(fx.controller, "settingsWindow");
        Runnable pageEdited = FxTestSupport.field(settings, "onRunConfigsChanged");

        FxTestSupport.runOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            cfg.getWorkspaceState().setRunConfigurations(new java.util.ArrayList<>(List.of(java("demo"))));
            pageEdited.run(); // exactly what persistRunConfigs() does after writing the list
        });

        ComboBox<RunConfiguration> combo = combo();
        assertTrue(
                combo.getItems().stream().anyMatch(c -> c != null && "demo".equals(c.name())),
                "the added configuration is missing from the toolbar selector");
    }

    /**
     * Every catalog item the toolbar can show must resolve to a real node.
     *
     * <p>The gap this closes: a special widget declared in {@code ToolbarCatalog} but never mapped in
     * {@code MainController.toolbarBaseWidgets} is silently dropped when the toolbar is rebuilt from the
     * saved layout — no error, no log, the control simply is not there. That is how the run-configuration
     * selector went missing despite being declared in the FXML, injected, wired and covered by the other
     * tests here.
     */
    @Test
    void everySpecialToolbarWidgetIsMappedToANode() throws Exception {
        java.util.Map<String, javafx.scene.Node> pool = widgetPool();
        for (String id : com.editora.toolbar.ToolbarCatalog.SPECIAL_WIDGET_IDS) {
            assertTrue(pool.containsKey(id), id + " is in the catalog but has no widget, so a rebuild drops it");
            org.junit.jupiter.api.Assertions.assertNotNull(pool.get(id), id + " maps to a null node");
        }
    }

    /**
     * Every item in the shipped default layout resolves too — the default is what a first-run user sees, so
     * a stale id there is a hole in the toolbar nobody configured.
     */
    @Test
    void everyDefaultLayoutItemResolves() throws Exception {
        java.util.Map<String, javafx.scene.Node> pool = widgetPool();
        for (String id : com.editora.toolbar.ToolbarCatalog.defaultLayout()) {
            if (!com.editora.toolbar.ToolbarCatalog.isKnownId(id)) {
                continue; // a separator token
            }
            var item = com.editora.toolbar.ToolbarCatalog.item(id);
            boolean resolvable = item.commandId() != null || pool.get(id) != null;
            assertTrue(resolvable, id + " is on the default toolbar but resolves to neither a command nor a widget");
        }
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, javafx.scene.Node> widgetPool() throws Exception {
        return FxTestSupport.callOnFx(() -> (java.util.Map<String, javafx.scene.Node>)
                FxTestSupport.call(fx.controller, "toolbarBaseWidgets", new Class[] {}));
    }

    /**
     * The controls must actually be in the toolbar — not merely injected.
     *
     * <p>Added after a report that the selector was nowhere on screen. Everything else here passes with the
     * fields injected and wired, which is exactly the state that would still show nothing if the controls
     * never made it into the toolbar's items. Membership is unconditional; whether they are <em>shown</em> is
     * the separate gate below.
     */
    @Test
    void theControlsAreInTheToolbar() throws Exception {
        javafx.scene.control.ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");
        ComboBox<RunConfiguration> combo = combo();
        javafx.scene.control.Button run = FxTestSupport.field(fx.controller, "runConfigRunButton");

        assertTrue(
                FxTestSupport.callOnFx(() -> bar.getItems().contains(combo)),
                "the selector is one of the toolbar's items");
        assertTrue(FxTestSupport.callOnFx(() -> bar.getItems().contains(run)), "and so is the Run button");
    }

    /**
     * The reported case: a window with no project and nothing saved shows no run-configuration group at all,
     * rather than a dropdown that can never fill (#792).
     *
     * <p>Asserted on {@code managed} as well as {@code visible} — an invisible but managed control still
     * holds its slot, which would leave the gap and the orphaned separators this is meant to remove.
     */
    @Test
    void theGroupIsHiddenWithNoProjectAndNoConfigurations() throws Exception {
        setConfigs(List.of());

        ComboBox<RunConfiguration> combo = combo();
        javafx.scene.control.Button run = FxTestSupport.field(fx.controller, "runConfigRunButton");
        assertFalse(FxTestSupport.callOnFx(combo::isVisible), "selector hidden");
        assertFalse(FxTestSupport.callOnFx(combo::isManaged), "and takes no layout space");
        assertFalse(FxTestSupport.callOnFx(run::isVisible), "Run hidden");
        assertFalse(FxTestSupport.callOnFx(run::isManaged), "Run takes no layout space");
    }

    /**
     * Saving a configuration brings the group back even in a plain folder — otherwise the gate would strand
     * it, with no UI left that reaches it.
     */
    @Test
    void aSavedConfigurationBringsTheGroupBack() throws Exception {
        setConfigs(List.of(java("Server")));

        ComboBox<RunConfiguration> combo = combo();
        javafx.scene.control.Button run = FxTestSupport.field(fx.controller, "runConfigRunButton");
        assertTrue(FxTestSupport.callOnFx(combo::isVisible), "selector visible");
        assertTrue(FxTestSupport.callOnFx(combo::isManaged), "selector managed (takes layout space)");
        assertTrue(FxTestSupport.callOnFx(run::isVisible), "Run visible");
        assertTrue(FxTestSupport.callOnFx(run::isManaged), "Run managed");

        setConfigs(List.of());
        assertFalse(FxTestSupport.callOnFx(combo::isVisible), "and goes away again when the last one is deleted");
    }

    /**
     * The positive half, end to end through the controller: a project whose root holds a makefile is
     * launchable, so the group comes back with nothing saved.
     *
     * <p>Make is used rather than a {@code pom.xml} deliberately — build-tool detection is asynchronous (a
     * daemon thread plus a {@code Platform.runLater}), and a test that raced it would be the flaky kind. The
     * makefile probe is synchronous, and it exercises the same {@code projectOpen} plumbing.
     */
    @Test
    void aMakefileProjectShowsTheGroup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        boolean projectsWereEnabled = FxTestSupport.callOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            return cfg.getSettings().isProjectSupport();
        });
        setConfigs(List.of());
        ComboBox<RunConfiguration> combo = combo();
        assertFalse(FxTestSupport.callOnFx(combo::isVisible), "hidden to begin with");

        java.nio.file.Files.writeString(dir.resolve("Makefile"), "all:\n\techo hi\n");
        try {
            setProjectsEnabled(true);
            setProject(new com.editora.config.Project("t", "t", dir.toString()));
            assertTrue(FxTestSupport.callOnFx(combo::isVisible), "a makefile makes the project launchable");
        } finally {
            setProject(null);
            setProjectsEnabled(projectsWereEnabled);
        }
        assertFalse(FxTestSupport.callOnFx(combo::isVisible), "and it goes away with the project");
    }

    /**
     * A window can only have a project while the feature is on. Captured and restored rather than forced to a
     * literal, so flipping the shipped default does not silently leave the shared fixture on the wrong one.
     */
    private void setProjectsEnabled(boolean on) throws Exception {
        FxTestSupport.runOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            cfg.getSettings().setProjectSupport(on);
        });
    }

    /** Points the window at a project (or none), the way {@code WindowManager} does when it builds one. */
    private void setProject(com.editora.config.Project project) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller,
                "setWindowContext",
                new Class[] {WindowManager.class, com.editora.config.Project.class},
                null,
                project));
    }

    @Test
    void theSelectorListsTheSavedConfigurations() throws Exception {
        setConfigs(List.of(java("Server"), java("Client")));

        ComboBox<RunConfiguration> combo = combo();
        // Both configurations plus the trailing "Edit Configurations…" row.
        assertEquals(3, FxTestSupport.callOnFx(() -> combo.getItems().size()), "both are listed");
        assertEquals("Server", FxTestSupport.callOnFx(() -> combo.getValue().name()), "the first is selected");

        setConfigs(List.of());
    }

    /** The edit row is always offered — with no configurations it is how you create the first one. */
    @Test
    void theEditRowIsAlwaysTheLastItem() throws Exception {
        ComboBox<RunConfiguration> combo = combo();

        setConfigs(List.of());
        assertEquals(1, FxTestSupport.callOnFx(() -> combo.getItems().size()), "offered with none saved");
        assertSame(editRow(), FxTestSupport.callOnFx(() -> combo.getItems().get(0)));

        setConfigs(List.of(java("Server")));
        assertSame(
                editRow(),
                FxTestSupport.callOnFx(
                        () -> combo.getItems().get(combo.getItems().size() - 1)),
                "and stays last");

        setConfigs(List.of());
    }

    /**
     * Choosing the edit row must not become the selection.
     *
     * <p>It is an action in a list of values, so the failure to guard against is it sticking: the Run buttons
     * would act on it, and it would be persisted as the window's chosen configuration and restored next
     * launch.
     */
    @Test
    void choosingTheEditRowRevertsTheSelectionAndPersistsNothing() throws Exception {
        setConfigs(List.of(java("Server"), java("Client")));
        ComboBox<RunConfiguration> combo = combo();
        FxTestSupport.runOnFx(() -> combo.setValue(combo.getItems().get(1))); // Client
        assertEquals("Client", FxTestSupport.callOnFx(() -> combo.getValue().name()));

        // Substitute the action: opening the real Settings window builds every page, which timed out under
        // the full suite. The revert is the part with a failure mode; that it fires is asserted below.
        java.util.List<String> opened = new java.util.ArrayList<>();
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller,
                "setRunConfigEditorForTest",
                new Class[] {java.util.function.Consumer.class},
                (java.util.function.Consumer<String>) opened::add));
        try {
            RunConfiguration row = editRow(); // hoisted: runOnFx takes a Runnable, which cannot throw
            FxTestSupport.runOnFx(() -> combo.setValue(row));
            assertEquals(
                    List.of("Client"), opened, "fired once, on the configuration that was selected — not the sentinel");
        } finally {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "setRunConfigEditorForTest",
                    new Class[] {java.util.function.Consumer.class},
                    (java.util.function.Consumer<String>) null));
        }

        assertEquals("Client", FxTestSupport.callOnFx(() -> combo.getValue().name()), "selection put back");
        assertEquals(
                "Client",
                FxTestSupport.callOnFx(() -> {
                    com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
                    return cfg.getWorkspaceState().getSelectedRunConfig();
                }),
                "and the sentinel was never persisted as the choice");

        setConfigs(List.of());
    }

    /** The sentinel is compared by identity, so a configuration named like it stays a configuration. */
    @Test
    void aConfigurationNamedLikeTheEditRowIsStillAConfiguration() throws Exception {
        String label = com.editora.i18n.Messages.tr("toolbar.runConfig.edit");
        setConfigs(List.of(java(label)));

        ComboBox<RunConfiguration> combo = combo();
        assertEquals(label, FxTestSupport.callOnFx(() -> combo.getValue().name()), "selected as a normal value");
        assertNotSame(editRow(), FxTestSupport.callOnFx(combo::getValue));

        setConfigs(List.of());
    }

    /** The controller's sentinel instance. {@code Field.get} ignores the target for a static field. */
    private RunConfiguration editRow() throws Exception {
        return FxTestSupport.field(fx.controller, "EDIT_CONFIGS_ROW");
    }

    /** Each configuration becomes a real command, so it shows in the palette and can take a keybinding. */
    @Test
    void eachConfigurationBecomesABindableCommand() throws Exception {
        setConfigs(List.of(java("Integration Tests")));

        String id = RunConfiguration.commandIdFor("Integration Tests");
        assertEquals("run.config.integration-tests", id, "slugged into a stable id");
        assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), "registered");
        assertEquals(
                "Integration Tests",
                FxTestSupport.callOnFx(() -> registry.get(id).orElseThrow().title()),
                "titled by the configuration's own name");

        setConfigs(List.of());
    }

    /**
     * The failure worth guarding: a renamed or removed configuration must not leave its command behind. A
     * stale entry looks fine in the palette and does nothing when run.
     */
    @Test
    void renamingAConfigurationDoesNotStrandItsOldCommand() throws Exception {
        setConfigs(List.of(java("Old Name")));
        String oldId = RunConfiguration.commandIdFor("Old Name");
        assertTrue(FxTestSupport.callOnFx(() -> registry.get(oldId).isPresent()), "registered to begin with");

        setConfigs(List.of(java("New Name")));

        assertFalse(FxTestSupport.callOnFx(() -> registry.get(oldId).isPresent()), "the old command is gone");
        assertTrue(
                FxTestSupport.callOnFx(() ->
                        registry.get(RunConfiguration.commandIdFor("New Name")).isPresent()),
                "and the new one is there");

        setConfigs(List.of());
        assertFalse(
                FxTestSupport.callOnFx(() ->
                        registry.get(RunConfiguration.commandIdFor("New Name")).isPresent()),
                "deleting them all leaves none behind");
    }

    /**
     * Stop follows the run, not the configuration list.
     *
     * <p>Its enabled state is computed from {@code RunCoordinator.isRunning()}, but nothing told the toolbar
     * when that changed — {@code updateRunConfigButtons()} was reached only by a rebuild of the configuration
     * list or a change of its selection. So the button sat disabled (and therefore grey rather than red)
     * through the entire run it exists to interrupt, and no other test noticed because every one of them
     * asserts after a rebuild, which is exactly when the stale value happens to be right.
     *
     * <p>The "while running" half is asserted <em>inside the runnable that launched it</em>: the exit is
     * reported through {@code Platform.runLater}, so no queued callback can have run yet and a program that
     * finishes quickly cannot race the assertion. The program is this JVM's own {@code java -version} —
     * present on every platform the suite runs on, and it exits on its own if anything below fails.
     */
    @Test
    void theStopButtonIsEnabledWhileAProgramIsRunning(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        setConfigs(List.of(java("Server")));
        javafx.scene.control.Button stop = FxTestSupport.field(fx.controller, "runConfigStopButton");
        assertTrue(FxTestSupport.callOnFx(stop::isDisable), "disabled with nothing running");

        Object runCoordinator = FxTestSupport.field(fx.controller, "runCoordinator");
        List<String> command = List.of(javaExecutable(), "-version");
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(
                    runCoordinator,
                    "streamRun",
                    new Class[] {String.class, java.nio.file.Path.class, List.class},
                    "probe",
                    dir,
                    command);
            assertFalse(stop.isDisable(), "Stop should be enabled the moment a program starts");
        });

        assertTrue(awaitStopDisabled(stop), "and disabled again once it exits");
        setConfigs(List.of());
    }

    /** Polls the FX thread (which also drains the exit callback) until Stop goes back to disabled. */
    private boolean awaitStopDisabled(javafx.scene.control.Button stop) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(stop::isDisable)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    /** This JVM's own launcher — an absolute path, so it needs no PATH lookup and carries any .exe suffix. */
    private static String javaExecutable() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() -> java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString());
    }

    /** Run/Debug are meaningless with nothing selected, so they are disabled rather than silently no-op. */
    @Test
    void theButtonsDisableWithNoSelection() throws Exception {
        setConfigs(List.of());

        assertTrue(
                FxTestSupport.callOnFx(
                        () -> ((javafx.scene.control.Button) FxTestSupport.field(fx.controller, "runConfigRunButton"))
                                .isDisable()),
                "Run is disabled with no configuration");

        setConfigs(List.of(java("Server")));
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> ((javafx.scene.control.Button) FxTestSupport.field(fx.controller, "runConfigRunButton"))
                                .isDisable()),
                "and enabled once one is selected");

        setConfigs(List.of());
    }
}
