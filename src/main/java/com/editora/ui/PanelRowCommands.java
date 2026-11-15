package com.editora.ui;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the right-click rows of the smaller tool windows that no command reached (KBD-01 and MENU-05,
 * Pauta 6.0.1): Local History, Bookmarks, Personal Notes and Test Results. Each does to the selected row of a window
 * that is open what its menu row does to the row right-clicked, which a right-click selects first; Bookmarks, Notes
 * and Test Results build the row's menu and fire the row ({@link MenuRows}), so a command is offered exactly where
 * its row is. With the window closed, or no row selected, the status line says so and nothing is opened for it.
 * Confirmations stay where the rows have them.
 */
final class PanelRowCommands {

    private PanelRowCommands() {}

    /** Registers the row commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        BooleanSupplier historyOpen = () -> mc.toolWindows.isOpen(mc.fileHistoryToolWindow);
        row(
                mc,
                "history.restoreSelected",
                "status.history.selectRevision",
                () -> historyOpen.getAsBoolean()
                        && mc.historyCoordinator.panel().restoreSelected());
        row(
                mc,
                "history.labelSelected",
                "status.history.selectRevision",
                () -> historyOpen.getAsBoolean()
                        && mc.historyCoordinator.panel().labelSelected());
        row(
                mc,
                "localHistory.purgeSelectedFile",
                "status.history.selectFile",
                () -> historyOpen.getAsBoolean()
                        && mc.historyCoordinator.panel().purgeSelectedFile());
        // Bookmarks, Personal Notes and Test Results: each command runs its row of the selected row's menu.
        for (String id : List.of(
                "bookmarks.editSelectedNote",
                "bookmarks.deleteSelected",
                "bookmarks.deleteAllInSelectedFile",
                "bookmarks.moveSelectedUp",
                "bookmarks.moveSelectedDown")) {
            menuRow(
                    mc,
                    id,
                    mc.bookmarksToolWindow,
                    () -> mc.bookmarkCoordinator.panel().runMenuRow(id));
        }
        for (String id : List.of(
                "notes.editSelected",
                "notes.toggleSelectedResolved",
                "notes.deleteSelected",
                "notes.deleteAllInSelectedFile")) {
            menuRow(
                    mc,
                    id,
                    mc.notesToolWindow,
                    () -> mc.notesCoordinator.panel().runMenuRow(id));
        }
        for (String id : List.of("test.goToSelected", "test.rerunSelected", "test.debugSelected")) {
            menuRow(
                    mc,
                    id,
                    mc.testResultsToolWindow,
                    () -> mc.testRunCoordinator.panel().runMenuRow(id));
        }
        PanelCommands.register(mc); // the rest of the panels' rows and buttons, found where the keyboard is
    }

    /**
     * Registers {@code id}: the row of {@code window}'s menu it stands for, run on the selected row by {@code run}
     * while the window is open; otherwise, or where the row is not offered, the status line says so.
     */
    private static void menuRow(MainController mc, String id, ToolWindow window, BooleanSupplier run) {
        mc.registry.register(Command.of(id, () -> {
            if (!(mc.toolWindows.isOpen(window) && run.getAsBoolean())) {
                mc.setStatus(tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command." + id))));
            }
        }));
    }

    /** Registers {@code id}: {@code action}, which answers false when it had nothing to act on, said by {@code statusKey}. */
    private static void row(MainController mc, String id, String statusKey, BooleanSupplier action) {
        mc.registry.register(Command.of(id, () -> {
            if (!action.getAsBoolean()) {
                mc.setStatus(tr(statusKey));
            }
        }));
    }
}
