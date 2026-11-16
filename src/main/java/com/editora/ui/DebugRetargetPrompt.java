package com.editora.ui;

import java.util.List;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The question before F5 in another tab ends the live debug launch: Debug Both (where a second session can run
 * beside the live one), Stop and Debug, or Cancel. Its words are pure; {@link #ask} builds and shows the dialog.
 */
final class DebugRetargetPrompt {

    private DebugRetargetPrompt() {}

    /**
     * The question's sentence: {@code label} is the live launch, {@code file} the file asked to be debugged, and
     * {@code retargetKey} the catalog key for the live launch's phase, used where Debug Both is not offered.
     */
    static String question(boolean bothOffered, String retargetKey, String label, @Nullable Object file) {
        return tr(bothOffered ? "dialog.debug.retargetBothQuestion" : retargetKey, label, file);
    }

    /**
     * The words of the dialog that asks {@code question}. Its answers: where {@code bothOffered}, Debug Both (0) and
     * Stop and Debug (1); otherwise Stop and Debug (0) alone.
     */
    static Confirm.Choice words(String question, boolean bothOffered) {
        String stop = tr("dialog.debug.retargetStop");
        return bothOffered
                ? Confirm.choice(
                        tr("dialog.debug.retargetBothTitle"), null, question, tr("dialog.debug.retargetBoth"), stop)
                : Confirm.choice(tr("dialog.debug.retargetTitle"), null, question, stop);
    }

    /** Asks {@code question} over {@code owner} and waits. Closing the dialog is Cancel. */
    static DebugCoordinator.Retarget ask(@Nullable Window owner, String question, boolean bothOffered) {
        Confirm.Choice words = words(question, bothOffered);
        ButtonType both = bothOffered ? new ButtonType(words.answer(0), ButtonBar.ButtonData.OK_DONE) : null;
        ButtonType stop = new ButtonType(
                words.answer(bothOffered ? 1 : 0),
                bothOffered ? ButtonBar.ButtonData.OTHER : ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = Dialogs.cancel();
        List<ButtonType> buttons = both != null ? List.of(both, stop, cancel) : List.of(stop, cancel);
        Alert alert = Dialogs.styled(
                new Alert(Alert.AlertType.CONFIRMATION, words.message(), buttons.toArray(ButtonType[]::new)));
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        if (owner != null) {
            alert.initOwner(owner);
        }
        // Stopping ends the program being debugged: its button is red and never the default. Where running
        // both is offered, that safe answer stays the default; otherwise Cancel is.
        Dialogs.destructive(alert, stop);
        if (both != null) {
            Dialogs.defaultTo(alert, both);
        }
        ButtonType answer = Dialogs.showAndWait(alert).orElse(cancel);
        return answer == both
                ? DebugCoordinator.Retarget.BOTH
                : answer == stop ? DebugCoordinator.Retarget.STOP : DebugCoordinator.Retarget.CANCEL;
    }
}
