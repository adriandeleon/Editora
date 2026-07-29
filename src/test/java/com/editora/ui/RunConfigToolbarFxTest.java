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
        assertEquals(2, FxTestSupport.callOnFx(() -> combo.getItems().size()), "both are listed");
        assertEquals("Server", FxTestSupport.callOnFx(() -> combo.getValue().name()), "the first is selected");

        setConfigs(List.of());
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
