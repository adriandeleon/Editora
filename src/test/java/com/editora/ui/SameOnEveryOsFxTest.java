package com.editora.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Stage;

import com.editora.Os;
import com.editora.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Editora is the same on Windows, macOS and Linux: run as each in turn (through {@link Os#override}), a window
 * registers the same commands, builds the same menu bar and lays out the same Settings rows.
 */
@Tag("fx")
class SameOnEveryOsFxTest {

    /** What one OS shows: its command ids, its menu bar's rows and its Settings rows. */
    private record Surface(
            TreeSet<String> commands,
            List<String> menuRows,
            List<String> settingsRows,
            boolean extendedWindowOffered) {}

    @AfterEach
    void reset() {
        Os.override(null);
    }

    private static Surface surfaceAs(Os.Kind kind) throws Exception {
        FxTestSupport.bootToolkit();
        Os.override(kind);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            return FxTestSupport.callOnFx(() -> {
                TreeSet<String> commands = new TreeSet<>();
                for (Command command : fx.controller.registry.all()) {
                    commands.add(command.id());
                }
                List<String> menuRows = new ArrayList<>();
                for (MenuBarModel.MenuSpec menu : MenuBarModel.menus()) {
                    menuRows.add(menu.titleKey() + " " + menu.allEntries());
                }
                MenuBar bar = FxTestSupport.<MainMenuBar>field(fx.controller, "menuBar")
                        .node();
                for (Menu menu : bar.getMenus()) {
                    menuRows.add("built: " + menu.getText() + " " + shape(menu));
                }
                SettingsWindow settings = fx.controller.settingsWindow;
                settings.show(FxTestSupport.<Stage>field(fx.controller, "stage"));
                List<String> settingsRows = new ArrayList<>();
                for (SettingsSearch.Row row : settings.rows) {
                    settingsRows.add(
                            row.page() + " | " + row.keywords() + (row.node().isManaged() ? "" : " | hidden"));
                }
                // A row that is there on every OS and disabled where the OS cannot serve it: on macOS the menu
                // belongs to the system bar, so the window cannot draw its own title bar.
                boolean offered = !FxTestSupport.<javafx.scene.Node>field(settings, "extendedWindowCheck")
                        .isDisable();
                FxTestSupport.<Stage>field(settings, "stage").hide();
                return new Surface(commands, menuRows, settingsRows, offered);
            });
        } finally {
            fx.dispose();
        }
    }

    /** A menu's rows as a shape: how many rows, and each submenu's shape in its place. */
    private static String shape(Menu menu) {
        StringBuilder out = new StringBuilder("[");
        int rows = 0;
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu sub) {
                out.append(shape(sub));
            } else if (!(item instanceof SeparatorMenuItem)) {
                rows++;
            }
        }
        return out.append(rows).append(']').toString();
    }

    @Test
    void theCommandsTheMenuBarAndTheSettingsRowsAreTheSameOnWindowsMacAndLinux() throws Exception {
        Map<Os.Kind, Surface> by = new EnumMap<>(Os.Kind.class);
        for (Os.Kind kind : Os.Kind.values()) {
            by.put(kind, surfaceAs(kind));
        }
        // First, that each window really was built as its OS: the same row, enabled or not by the OS.
        assertEquals(true, by.get(Os.Kind.LINUX).extendedWindowOffered());
        assertEquals(true, by.get(Os.Kind.WINDOWS).extendedWindowOffered());
        assertEquals(false, by.get(Os.Kind.MAC).extendedWindowOffered(), "disabled on macOS, and still a row");
        Surface linux = by.get(Os.Kind.LINUX);
        for (Os.Kind kind : List.of(Os.Kind.WINDOWS, Os.Kind.MAC)) {
            Surface other = by.get(kind);
            TreeSet<String> onlyLinux = new TreeSet<>(linux.commands());
            onlyLinux.removeAll(other.commands());
            TreeSet<String> onlyOther = new TreeSet<>(other.commands());
            onlyOther.removeAll(linux.commands());
            assertEquals(new TreeSet<String>(), onlyLinux, "commands on Linux and not on " + kind);
            assertEquals(new TreeSet<String>(), onlyOther, "commands on " + kind + " and not on Linux");
            assertEquals(linux.menuRows(), other.menuRows(), "the menu bar on " + kind);
            assertEquals(linux.settingsRows(), other.settingsRows(), "the Settings rows on " + kind);
        }
    }
}
