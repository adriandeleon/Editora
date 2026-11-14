package com.editora.ui;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the rows of the Project tree's file menu (KBD-01 and MENU-05, Pauta 6.0.1). Each does to the
 * selected row what its menu row does to the row right-clicked, which a right-click selects first, so the row and the
 * command run the same {@code ProjectPanel} method on the same file. In the Project Map window the commands act on
 * the Map's selected row while the keyboard is on the Map's rows, as the Map's copy of the menu does.
 *
 * <p>With no row selected, or a row the menu would not offer the action for (a folder for Delete…, the root for
 * Rename…, a file outside Git for the Git rows), the status line says so and nothing happens. Confirmations stay
 * where the menu rows have them: Delete… and Revert… still ask.
 */
final class ProjectRowCommands {

    /** A row of the file menu, its command, and where it applies. */
    enum Row {
        NEW_FILE("project.newFileHere", Applies.FOLDER, false),
        NEW_FOLDER("project.newFolderHere", Applies.FOLDER, true),
        NEW_FROM_TEMPLATE("project.newFromTemplateHere", Applies.FOLDER, false),
        NEW_MAVEN_PROJECT("project.newMavenProjectHere", Applies.FOLDER, false),
        RENAME("project.renameSelected", Applies.BOTH, true),
        UNDO_MOVE("project.undoMove", Applies.BOTH, true),
        DELETE("project.deleteSelected", Applies.FILE, true),
        ENCRYPT("project.encryptSelected", Applies.FILE, false),
        BOOKMARK("project.bookmarkSelected", Applies.BOTH, false),
        NOTE("project.noteOnSelected", Applies.BOTH, false),
        REVEAL_IN_MAP("project.showSelectedOnMap", Applies.BOTH, false),
        REVEAL_IN_FILE_MANAGER("project.revealSelected", Applies.BOTH, true),
        OPEN_TERMINAL("project.openTerminalAtSelected", Applies.BOTH, false),
        FIND_IN_FOLDER("project.findInSelectedFolder", Applies.FOLDER, true),
        LOCAL_HISTORY("project.localHistoryOfSelected", Applies.BOTH, false),
        GIT_STAGE("project.stageSelected", Applies.BOTH, false),
        GIT_UNSTAGE("project.unstageSelected", Applies.FILE, false),
        GIT_REVERT("project.revertSelected", Applies.BOTH, false),
        GIT_IGNORE("project.ignoreSelected", Applies.FILE, false),
        COMPARE_HEAD("project.compareSelectedWithHead", Applies.BOTH, false),
        COMPARE_BRANCH("project.compareSelectedWithBranch", Applies.BOTH, false),
        COMPARE_TAG("project.compareSelectedWithTag", Applies.BOTH, false),
        COMPARE_REVISION("project.compareSelectedWithRevision", Applies.BOTH, false),
        ANNOTATE("project.annotateSelected", Applies.FILE, false),
        GIT_HISTORY("project.gitHistoryOfSelected", Applies.BOTH, false);

        private final String id;
        private final Applies applies;
        private final boolean staysInMap;

        Row(String id, Applies applies, boolean staysInMap) {
            this.id = id;
            this.applies = applies;
            this.staysInMap = staysInMap;
        }

        /** The command's id. */
        String id() {
            return id;
        }

        /** Whether the menu offers this row on a file only. */
        boolean fileOnly() {
            return applies == Applies.FILE;
        }

        /** Whether the menu offers this row on a folder only. */
        boolean folderOnly() {
            return applies == Applies.FOLDER;
        }

        /**
         * Whether the row does its work in the Map window when run there (a prompt over the Map, or nothing to
         * show), rather than in the editor window, which is brought forward first ({@code ProjectPanel.STAYS_IN_MAP}).
         */
        boolean staysInMap() {
            return staysInMap;
        }
    }

    /** Which rows a menu row is offered on. */
    private enum Applies {
        FILE,
        FOLDER,
        BOTH
    }

    private ProjectRowCommands() {}

    /** Registers the file menu's commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        for (Row row : Row.values()) {
            mc.registry.register(Command.of(row.id(), () -> run(mc, row)));
        }
    }

    /** Runs {@code row} on the selected row, or says why it cannot. */
    private static void run(MainController mc, Row row) {
        if (!mc.projectsEnabled()) {
            mc.setStatus(tr("palette.disabled.featureOff", tr("command.view.toggleProjects")));
            return;
        }
        ProjectPanel panel = mc.projectPanel;
        if (panel == null) { // never built: nothing is selected in it
            mc.setStatus(tr(row == Row.UNDO_MOVE ? "status.project.noMoveToUndo" : "status.project.selectRow"));
            return;
        }
        String refusal = panel.runRowCommand(row);
        if (refusal != null) {
            mc.setStatus(refusal);
        }
    }
}
