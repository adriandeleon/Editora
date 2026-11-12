package com.editora.ui;

import java.util.Optional;
import java.util.function.Function;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * The one question before a destructive action: before something is removed, deleted, discarded or
 * overwritten, a running program is ended, or a command that cannot be taken back is run.
 *
 * <p>The text names the thing and what happens to it. The confirming button carries the action's own verb
 * (Delete, Discard, Replace), never OK or Yes, in the red {@code danger} style. Cancel is worded from the
 * catalog and has the {@code CANCEL_CLOSE} role, so Escape and the window's close button both answer it, and
 * it is the default button: it holds the focus when the question opens, so Enter keeps what the user has and
 * the red action runs only from its own button. Anything but that button is a Cancel.
 *
 * <p>A question that needs more than this (a checkbox, a third answer) builds its own dialog and marks it
 * with {@link Dialogs#destructive}.
 */
final class Confirm {

    /**
     * What is asked: the window title, an optional header over the question, the question itself and the
     * confirming button's label. Pure data, so a question's words can be tested with no dialog.
     */
    record Question(String title, @Nullable String header, String message, String action) {}

    /** Answers questions in place of the dialog; null (the default) shows it. See {@link #answerWith}. */
    private static volatile @Nullable Function<Question, Boolean> capture;

    private Confirm() {}

    /** Asks and waits; true only when the action button was pressed. */
    static boolean ask(@Nullable Window owner, String title, String message, String action) {
        return ask(owner, new Question(title, null, message, action));
    }

    /** Asks {@code question} and waits; true only when the action button was pressed. */
    static boolean ask(@Nullable Window owner, Question question) {
        Function<Question, Boolean> answer = capture;
        if (answer != null) {
            return Boolean.TRUE.equals(answer.apply(question));
        }
        Alert alert = dialog(owner, question);
        return confirmed(alert, alert.showAndWait());
    }

    /** The dialog for {@code question}, not yet shown. */
    static Alert dialog(@Nullable Window owner, Question question) {
        ButtonType action = Dialogs.action(question.action());
        Alert alert =
                Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, question.message(), action, Dialogs.cancel()));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(question.title());
        alert.setHeaderText(question.header());
        return Dialogs.destructive(alert, action);
    }

    /** Whether the dialog's answer is its action: a close by any other route is Cancel. */
    static boolean confirmed(Alert alert, Optional<ButtonType> answer) {
        return answer.orElse(Dialogs.cancelOf(alert)).getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /**
     * For tests: answers every question with {@code answer} without showing a dialog, so a test asserts the
     * words and decides the outcome with no native button to press. Pass null to show dialogs again.
     */
    static void answerWith(@Nullable Function<Question, Boolean> answer) {
        capture = answer;
    }
}
