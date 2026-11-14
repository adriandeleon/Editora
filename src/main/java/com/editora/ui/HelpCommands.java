package com.editora.ui;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * Two commands the Help menu and the keyboard need: the keyboard reference, and a way to the menu bar for a
 * keymap that binds F10 to something else.
 */
final class HelpCommands {

    private HelpCommands() {}

    static void register(MainController mc) {
        // The keyboard reference is the keybinding editor: every command with its keys, searchable.
        mc.registry.register(Command.of("help.keyboardShortcuts", () -> mc.settingsWindow.showKeymaps(mc.stage)));
        mc.registry.register(Command.of("view.focusMenuBar", () -> {
            if (!mc.menuBar.openFirstMenu()) {
                mc.setStatus(tr(mc.menuBar.onSystemMenuBar() ? "status.menuBar.system" : "status.menuBar.hidden"));
            }
        }));
    }
}
