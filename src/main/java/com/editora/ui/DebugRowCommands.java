package com.editora.ui;

import java.util.List;

import com.editora.command.Command;

/**
 * The commands behind the Debug window's right-click rows that no command reached (KBD-01 and MENU-05, Pauta 6.0.1):
 * the Variables view's (watches, values, marks, View As), the Threads view's, the call stack's and the console's.
 * Each runs its row for the window's selection, as choosing it would ({@link DebugPanel#runMenuRow}), so it is
 * offered exactly where the row is; elsewhere the status line says so. Copy Value, Copy Name and Add to Watches act
 * first on a row of the Evaluate window or of a value popup that has the keyboard ({@link FocusCommands#runWhereFocused}),
 * whose rows are tagged with them.
 */
final class DebugRowCommands {

    /** The commands, in palette order. */
    static final List<String> IDS = List.of(
            "debug.editSelectedWatch",
            "debug.removeSelectedWatch",
            "debug.pauseSelectedWatch",
            "debug.evaluateSelectedWatchOnce",
            "debug.removeAllWatches",
            "debug.copySelectedValue",
            "debug.copySelectedName",
            "debug.copySelectedExpression",
            "debug.watchSelected",
            "debug.clearResult",
            "debug.selectedValueHistory",
            "debug.viewSelectedMemory",
            "debug.watchSelectedField",
            "debug.jumpToSelectedType",
            "debug.customizeSelectedRendering",
            "debug.markSelectedObject",
            "debug.unmarkSelectedObject",
            "debug.unmarkAllObjects",
            "debug.viewSelectedAsJson",
            "debug.viewSelectedAsXml",
            "debug.viewSelectedAsText",
            "debug.viewSelectedAsTable",
            "debug.showSelectedAsDefault",
            "debug.showSelectedAsDecimal",
            "debug.showSelectedAsHex",
            "debug.showSelectedAsBinary",
            "debug.showSelectedAsOctal",
            "debug.showSelectedAsCharacter",
            "debug.goToSelectedIndex",
            "debug.historyLive",
            "debug.showSelectedThreadFrames",
            "debug.resumeSelectedThread",
            "debug.pauseSelectedThread",
            "debug.copyCallStack",
            "debug.clearConsole");

    private DebugRowCommands() {}

    /** Registers the Debug window's row commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        for (String id : IDS) {
            // A row tagged with the command where the keyboard is (the Evaluate window's tree, a value popup's rows)
            // is the one it fires; elsewhere the Debug window's.
            mc.registry.register(Command.of(id, () -> {
                if (!FocusCommands.runWhereFocused(id, mc::setStatus, javafx.stage.Window.getWindows())) {
                    mc.debugCoordinator.ifDebug(() -> mc.debugCoordinator.runPanelRow(id));
                }
            }));
        }
    }
}
