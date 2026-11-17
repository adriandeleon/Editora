package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Tooltip;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-01 (Pauta 6.0.1): every control a person can reach with Tab or the mouse has a name a screen reader says.
 *
 * <p>The windows, dialogs, popups and tool windows the app has are the ones its commands open, so the sweep is
 * {@link CommandSweepFxTest}'s: every registered command runs in a project window on a Git repository with a
 * modified file open, and again in a window on a Markdown file. After each command (and each half of a toggle) every window on screen is walked, the main
 * window with whatever panel, overlay or bar the command opened; a dialog that blocks its command is walked as it
 * shows, before the sweep answers it. A right-click menu is checked row by row wherever a control carries one.
 * Then every tool window is opened in turn and every page of Settings is shown. A control, a filled cell or a
 * menu row that reads as nothing fails the test, once per place it sits ({@link AccessibleNames}).
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class NamedControlsSweepFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    // Over 3,000 commands in two windows: 260 to 300 s on a CI runner, past the suite's 5-minute default.
    @org.junit.jupiter.api.Timeout(value = 15, unit = java.util.concurrent.TimeUnit.MINUTES)
    void everyControlInEveryWindowHasAName(@TempDir Path dir) throws Exception {
        AccessibleNames names = new AccessibleNames();
        int[] settingsPages = {0};
        int[] toolWindows = {0};
        int[] menus = {0};
        CommandSweepFxTest.Observer observer = new CommandSweepFxTest.Observer() {
            @Override
            public void shown(String command, Window window) {
                look(names, window, command);
            }

            @Override
            public void ran(String command) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    look(names, window, command);
                }
            }

            @Override
            public void finished(MainController controller) {
                if (toolWindows[0] > 0) {
                    return; // the first state's window has shown them all
                }
                ToolWindowManager manager = FxTestSupport.field(controller, "toolWindows");
                Map<String, ToolWindow> byId = FxTestSupport.field(manager, "byId");
                Stage stage = FxTestSupport.field(controller, "stage");
                for (ToolWindow tw : List.copyOf(byId.values())) {
                    manager.open(tw, false);
                    toolWindows[0]++;
                    names.scene(stage.getScene(), "tool window " + tw.getId());
                    manager.close(tw);
                }
                // The menu bar's rows, each of its menus as it is about to show.
                stage.getScene().getRoot().applyCss();
                if (stage.getScene().getRoot().lookup(".menu-bar") instanceof MenuBar bar) {
                    for (Menu menu : bar.getMenus()) {
                        menus[0]++;
                        if (menu.getOnShowing() != null) {
                            menu.getOnShowing().handle(null);
                        }
                        names.rows(menu.getItems(), "menu " + menu.getText());
                    }
                }
                SettingsWindow settings = FxTestSupport.field(controller, "settingsWindow");
                settings.show(stage);
                ListView<Object> sidebar = FxTestSupport.field(settings, "sidebar");
                for (Object row : List.copyOf(sidebar.getItems())) {
                    if (row instanceof SettingsWindow.Category category) {
                        sidebar.getSelectionModel().select(category);
                        settingsPages[0]++;
                        names.scene(settings.stage.getScene(), "Settings " + category);
                    }
                }
                settings.stage.hide();
            }
        };
        int commands = 0;
        // A project window on a Git repository (the Git, project and build windows), then a Markdown file (its
        // preview and its tools).
        for (CommandSweepFxTest.State state :
                List.of(CommandSweepFxTest.State.GIT_PROJECT, CommandSweepFxTest.State.MARKDOWN)) {
            CommandSweepFxTest.Sweep sweep =
                    new CommandSweepFxTest.Sweep(state, Files.createDirectories(dir.resolve(state.name())), observer);
            try (CommandSweepFxTest.Outside outside = new CommandSweepFxTest.Outside()) {
                sweep.run();
            }
            assertTrue(sweep.ran > 1000, "the sweep ran the registry's commands: " + sweep.ran);
            commands += sweep.ran;
        }
        assertTrue(toolWindows[0] > 20, "every tool window was opened: " + toolWindows[0]);
        assertEquals(SettingsWindow.Category.values().length, settingsPages[0], "every Settings page was shown");
        assertTrue(menus[0] >= 8, "every menu of the menu bar was read: " + menus[0]);
        System.out.printf(
                "named-controls sweep: %d commands, %d scenes, %d controls, %d tool windows, %d Settings pages, %d menus%n",
                commands, names.scenes, names.controls, toolWindows[0], settingsPages[0], menus[0]);
        assertEquals(
                "",
                names.unnamed().entrySet().stream()
                        .map(e -> e.getKey() + "    (first seen after " + e.getValue() + ")")
                        .collect(Collectors.joining("\n")),
                names.unnamed().size() + " control(s) with no name for a screen reader");
    }

    /** One window on screen: a right-click menu's rows, or a scene. A tooltip is nothing a person reaches. */
    private static void look(AccessibleNames names, Window window, String command) {
        if (!window.isShowing() || window instanceof Tooltip) {
            return;
        }
        if (window instanceof ContextMenu menu) {
            names.rows(menu.getItems(), command + " (menu)");
        } else {
            names.scene(window.getScene(), command);
        }
    }
}
