package com.editora.ui;

import java.util.function.BooleanSupplier;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the right-click rows of the Git windows that no command reached (KBD-01 and MENU-05, Pauta
 * 6.2.0). Each does to the selected row what its menu row does to the row right-clicked, which a right-click selects
 * first, so the row and the command call the same method. With no row selected, or one the row would not be offered
 * for, the status line says so; nothing is opened for it. Confirmations stay where the rows have them.
 */
final class GitRowCommands {

    private GitRowCommands() {}

    /** Registers the Git windows' row commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        // The Commit window's changed files.
        commit(mc, "git.openSelected", "status.git.selectChangedFile", p -> p.openSelectedFile());
        commit(mc, "git.diffSelected", "status.git.selectChangedFile", p -> p.diffSelected());
        commit(mc, "git.discardSelected", "status.git.nothingToDiscardSelected", p -> p.discardSelected());
        commit(mc, "git.resolveSelected", "status.git.selectConflictedFile", p -> p.resolveSelected());
        commit(mc, "git.acceptOursSelected", "status.git.selectConflictedFile", p -> p.acceptSideOfSelected(true));
        commit(mc, "git.acceptTheirsSelected", "status.git.selectConflictedFile", p -> p.acceptSideOfSelected(false));
        // The Stash list's rows name a stash: these reach each row's action through a picker of the stashes, as
        // git.unstash and git.stashDrop reach Apply and Drop.
        stash(mc, "git.stashShow", GitStashCoordinator::pickToShow);
        stash(mc, "git.stashPopChosen", GitStashCoordinator::pickToPop);
        stash(mc, "git.stashBranch", GitStashCoordinator::pickToBranch);
        stash(mc, "git.stashCopyName", GitStashCoordinator::pickToCopyName);
        // The Git Log's changed files (and, in a file's history, that file at the selected commit).
        log(mc, "git.log.showFileDiff", p -> p.showSelectedFileDiff());
        log(mc, "git.log.compareFileWithWorking", p -> p.compareSelectedFileWithWorking());
        log(mc, "git.log.openFile", p -> p.openSelectedChangedFile());
        log(mc, "git.log.fileHistoryOfFile", p -> p.selectedFileHistory());
        log(mc, "git.log.copyFilePath", p -> p.copySelectedFilePath());
    }

    /**
     * Registers {@code id}: {@code action} on the visible Commit window's selection, or {@code statusKey} when it did
     * nothing. Never on a hidden selection: with the window closed, Discard would act on rows nobody can see.
     */
    private static void commit(
            MainController mc, String id, String statusKey, java.util.function.Predicate<GitPanel> action) {
        register(mc, id, statusKey, () -> mc.toolWindows.isOpen(mc.commitToolWindow) && action.test(mc.gitPanel()));
    }

    /** Registers {@code id}: {@code picker} of the active repository's stashes; Git must be on. */
    private static void stash(MainController mc, String id, java.util.function.Consumer<GitStashCoordinator> picker) {
        mc.registry.register(Command.of(id, () -> mc.git.ifEnabled(() -> picker.accept(mc.git.stashes()))));
    }

    /** Registers {@code id}: {@code action} on the visible Git Log's selected file; never on a hidden selection. */
    private static void log(MainController mc, String id, java.util.function.Predicate<GitLogPanel> action) {
        register(
                mc,
                id,
                "status.git.log.selectFile",
                () -> mc.toolWindows.isOpen(mc.gitLogToolWindow) && action.test(mc.gitLogPanel()));
    }

    /** Registers {@code id}: {@code action}, which answers false when it had nothing to act on; Git must be on. */
    static void register(MainController mc, String id, String statusKey, BooleanSupplier action) {
        mc.registry.register(Command.of(
                id,
                () -> mc.git.ifEnabled(() -> {
                    if (!action.getAsBoolean()) {
                        mc.setStatus(tr(statusKey));
                    }
                })));
    }
}
