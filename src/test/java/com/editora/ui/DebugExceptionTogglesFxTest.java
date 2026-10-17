package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;

import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B10 / C12: the exception breakpoints were one command that toggled "uncaught" in memory — the state showed
 * only in a passing status line and was gone with the window, and caught exceptions could not be chosen from
 * the menu. Now Caught and Uncaught are two toggles, checked in the Run menu and in the Debug window, kept in
 * the project's workspace state.
 */
@Tag("fx")
class DebugExceptionTogglesFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static void debugOn(SharedConfig shared) {
        shared.getSettings().setDebugSupport(true);
    }

    /** The Run menu's item for {@code title}, as the user reads it once the menu has opened. */
    private String runMenuItem(MainController c, String title) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            MainMenuBar menuBar = FxTestSupport.field(c, "menuBar");
            menuBar.refreshEnablement(); // what opening a menu does
            MenuBar bar = FxTestSupport.field(menuBar, "bar");
            for (Menu menu : bar.getMenus()) {
                for (MenuItem item : menu.getItems()) {
                    String text = MainMenuBar.displayTextOf(item);
                    if (text != null && text.contains(title)) {
                        return text;
                    }
                }
            }
            return null;
        });
    }

    @Test
    void caughtIsCheckedInTheRunMenuAndTheDebugWindowAndKeptForTheProject() throws Exception {
        Path dir = Files.createTempDirectory("editora-exc");
        fx = FxWindowFixture.create(dir, DebugExceptionTogglesFxTest::debugOn);
        MainController c = fx.controller;
        String caughtTitle = com.editora.i18n.Messages.tr("command.debug.exceptions.caught");
        assertFalse(runMenuItem(c, caughtTitle).startsWith(MainMenuBar.CHECK), "off to begin with");

        FxTestSupport.runOnFx(() -> {
            com.editora.command.CommandRegistry reg = FxTestSupport.field(c, "registry");
            reg.run("debug.exceptions.caught");
        });

        assertTrue(runMenuItem(c, caughtTitle).startsWith(MainMenuBar.CHECK), runMenuItem(c, caughtTitle));
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        boolean shown = FxTestSupport.callOnFx(() -> {
            ToggleButton caught = FxTestSupport.field(debug.panel(), "caughtToggle");
            ToggleButton uncaught = FxTestSupport.field(debug.panel(), "uncaughtToggle");
            return caught.isSelected() && !uncaught.isSelected();
        });
        assertTrue(shown, "the Debug window's Caught toggle is on, Uncaught off");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        assertEquals(List.of("caught"), FxTestSupport.callOnFx(() -> dap.exceptionFiltersFor("java")));
        com.editora.config.ConfigManager config = FxTestSupport.field(c, "config");
        assertEquals(
                Map.of("java", List.of("caught")),
                FxTestSupport.callOnFx(() -> config.getWorkspaceState().getDebugExceptionFilters()));

        // the Debug window's toggle turns it off again
        FxTestSupport.runOnFx(() -> {
            ToggleButton caught = FxTestSupport.field(debug.panel(), "caughtToggle");
            caught.fire();
        });
        assertFalse(runMenuItem(c, caughtTitle).startsWith(MainMenuBar.CHECK));
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> dap.exceptionFiltersFor("java")));
    }

    @Test
    void aWindowStartsWithTheExceptionBreakpointsThisProjectKept() throws Exception {
        Path dir = Files.createTempDirectory("editora-exc");
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":19,\"debugExceptionFilters\":{\"java\":[\"uncaught\"]}}");
        fx = FxWindowFixture.create(dir, DebugExceptionTogglesFxTest::debugOn);
        MainController c = fx.controller;
        DapManager dap = FxTestSupport.field(c, "dapManager");

        assertEquals(List.of("uncaught"), FxTestSupport.callOnFx(() -> dap.exceptionFiltersFor("java")));
        String uncaught = com.editora.i18n.Messages.tr("command.debug.exceptions.uncaught");
        assertTrue(runMenuItem(c, uncaught).startsWith(MainMenuBar.CHECK), runMenuItem(c, uncaught));
    }
}
