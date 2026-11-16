package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import com.editora.config.SharedConfig;
import com.editora.io.SaveBackups;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Offers, at launch, the backups that unfinished in-place saves left behind.
 *
 * <p>A file that cannot be replaced by a staged copy (hard links, another owner, a folder that allows no new
 * entries) is overwritten in place, with its previous bytes held in {@code <config>/save-backups} meanwhile.
 * If the process is killed or the machine loses power during that write, the file is empty or partial and the
 * backup is the only complete copy — and nothing used to look for it. This asks, once per launch and per
 * backup, whether to put the previous contents back.
 */
public final class SaveBackupRecovery {

    /** The folder, under the config directory, that in-place saves keep their backups in. */
    static final String FOLDER = "save-backups";

    /** What to do with one leftover backup. */
    enum Action {
        /** Redundant and no running save can own it: remove it without asking. */
        DISCARD,
        /** Redundant, but possibly the backup of a save in progress elsewhere: leave it alone. */
        LEAVE,
        /** The file differs or is gone: ask whether to restore it. */
        OFFER_RESTORE,
        /** The file cannot be reached from here: say where the backup is. */
        REPORT
    }

    private record Offer(SaveBackups.Leftover leftover, SaveBackups.State state) {}

    private SaveBackupRecovery() {}

    static Action actionFor(SaveBackups.State state, boolean settled) {
        return switch (state) {
            case SAME -> settled ? Action.DISCARD : Action.LEAVE;
            case DIFFERENT, TARGET_MISSING -> Action.OFFER_RESTORE;
            case REMOTE, UNKNOWN_TARGET -> Action.REPORT;
        };
    }

    /**
     * What the button that deletes the backup is called. Only a file that is still there can be "kept": for
     * one that is gone the backup is the last copy of its contents, and the button has to say that pressing
     * it deletes that copy.
     */
    static String discardLabelKey(SaveBackups.State state) {
        return state == SaveBackups.State.DIFFERENT ? "dialog.saveBackup.keep" : "dialog.saveBackup.delete";
    }

    /**
     * Looks for leftover backups away from the FX thread and asks about each on it. Only the primary
     * instance on a config directory asks: a second process must not offer the backups of the first one's
     * running saves.
     */
    public static void offerAtStartup(SharedConfig shared, Stage owner) {
        if (shared == null || !shared.isPrimaryInstance()) {
            return;
        }
        Path dir = shared.getConfigDir().resolve(FOLDER);
        Thread.ofVirtual().name("save-backup-scan").start(() -> {
            Deque<Offer> offers = new ArrayDeque<>(pending(dir, Instant.now()));
            if (!offers.isEmpty()) {
                Platform.runLater(() -> askNext(offers, dir, owner));
            }
        });
    }

    /** The backups worth showing; redundant, settled ones are deleted on the way. */
    static List<Offer> pending(Path dir, Instant now) {
        List<Offer> offers = new java.util.ArrayList<>();
        for (SaveBackups.Leftover leftover : SaveBackups.scan(dir)) {
            SaveBackups.State state = SaveBackups.state(leftover);
            switch (actionFor(state, SaveBackups.settled(leftover, now))) {
                case DISCARD -> SaveBackups.discard(leftover);
                case LEAVE -> {}
                case OFFER_RESTORE, REPORT -> offers.add(new Offer(leftover, state));
            }
        }
        return offers;
    }

    private static void askNext(Deque<Offer> offers, Path dir, Stage owner) {
        Offer offer = offers.poll();
        if (offer == null) {
            return;
        }
        SaveBackups.Leftover leftover = offer.leftover();
        boolean restorable = actionFor(offer.state(), true) == Action.OFFER_RESTORE;
        String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(leftover.modified());
        Confirm.Choice words = question(offer.state(), leftover.target(), leftover.backup(), when);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.WARNING));
        if (owner != null && owner.isShowing()) {
            alert.initOwner(owner);
        }
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType later = new ButtonType(words.cancel(), ButtonBar.ButtonData.CANCEL_CLOSE);
        // Restore is in the dialog only where there is a file to restore the backup over.
        ButtonType restore = new ButtonType(restorable ? words.answer(0) : "", ButtonBar.ButtonData.OK_DONE);
        ButtonType delete = new ButtonType(words.answer(words.answers().size() - 1), ButtonBar.ButtonData.OTHER);
        if (restorable) {
            alert.getButtonTypes().setAll(restore, delete, later);
            // Deleting the backup is red and never the default. Restore, which keeps the earlier contents,
            // stays the default button, as Save does in the unsaved-changes question.
            Dialogs.destructive(alert, delete);
            Dialogs.defaultTo(alert, restore);
        } else {
            alert.getButtonTypes().setAll(later, delete);
            Dialogs.destructive(alert, delete); // red, never the default: Decide Later is the default button
        }
        // Not showAndWait: this runs while the windows are still being restored.
        Dialogs.show(alert, choice -> {
            if (choice == restore) {
                restore(leftover, dir, owner);
            } else if (choice == delete) {
                Thread.ofVirtual().start(() -> SaveBackups.discard(leftover));
            }
            askNext(offers, dir, owner);
        });
    }

    /**
     * The words of the question about one leftover backup, written {@code when}. Where the file it was taken from
     * differs or is gone ({@code state}) its answers are Restore (0) and the button that deletes the backup (1),
     * worded by {@link #discardLabelKey}; where the file is unknown or out of reach, that button alone (0). Its
     * Cancel is Decide Later.
     */
    static Confirm.Choice question(SaveBackups.State state, @Nullable String target, Path backup, String when) {
        String delete = tr(discardLabelKey(state));
        if (actionFor(state, true) == Action.OFFER_RESTORE) {
            return new Confirm.Choice(
                    tr("dialog.saveBackup.title"),
                    tr("dialog.saveBackup.header", target),
                    tr(
                            state == SaveBackups.State.TARGET_MISSING
                                    ? "dialog.saveBackup.contentMissing"
                                    : "dialog.saveBackup.content",
                            backup,
                            when),
                    List.of(tr("dialog.saveBackup.restore"), delete),
                    tr("dialog.saveBackup.later"));
        }
        return new Confirm.Choice(
                tr("dialog.saveBackup.title"),
                tr("dialog.saveBackup.headerUnknown"),
                tr("dialog.saveBackup.contentUnknown", target == null ? backup.getFileName() : target, backup, when),
                List.of(delete),
                tr("dialog.saveBackup.later"));
    }

    private static void restore(SaveBackups.Leftover leftover, Path dir, Stage owner) {
        Thread.ofVirtual().name("save-backup-restore").start(() -> {
            try {
                SaveBackups.restore(leftover, dir);
            } catch (IOException | RuntimeException failed) {
                Platform.runLater(() -> {
                    Alert error = Dialogs.styled(new Alert(Alert.AlertType.ERROR));
                    if (owner != null && owner.isShowing()) {
                        error.initOwner(owner);
                    }
                    error.setTitle(tr("dialog.saveBackup.title"));
                    error.setHeaderText(tr("dialog.saveBackup.restoreFailed", leftover.target()));
                    error.setContentText(tr(
                            "dialog.saveBackup.restoreFailedContent",
                            String.valueOf(failed.getMessage()),
                            leftover.backup()));
                    Dialogs.show(error);
                });
            }
        });
    }
}
