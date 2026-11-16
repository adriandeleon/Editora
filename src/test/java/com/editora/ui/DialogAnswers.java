package com.editora.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import javafx.scene.control.ButtonBar;

/**
 * Answers every dialog shown through {@link Dialogs#showAndWait} for the length of a {@code try} block, with no
 * window, and keeps what each one asked so a test can assert the words, the buttons and that nothing was asked
 * twice. Closing it shows dialogs again. A question asked through {@link Confirm} comes here too, unless
 * {@link ConfirmAnswers} is answering those.
 *
 * <p>An answer is named by its role ({@code OK_DONE} for a question's action, {@code CANCEL_CLOSE} for its Cancel),
 * by its label, or by a function for a test that must first set something in the dialog (tick a check box). A
 * dialog that has no such answer fails the test rather than being answered some other way.
 */
final class DialogAnswers implements AutoCloseable {

    private final List<Dialogs.Asked> asked = new CopyOnWriteArrayList<>();
    private volatile Function<Dialogs.Asked, Dialogs.Answer> answer;

    private DialogAnswers(Function<Dialogs.Asked, Dialogs.Answer> answer) {
        this.answer = answer;
        Dialogs.answerWith(question -> {
            asked.add(question);
            return this.answer.apply(question);
        });
    }

    /** Every dialog from now on is answered by {@code answer}: one of its answers, or null to close it. */
    static DialogAnswers with(Function<Dialogs.Asked, Dialogs.Answer> answer) {
        return new DialogAnswers(answer);
    }

    /** Every dialog from now on is answered with its button of kind {@code role}. */
    static DialogAnswers pressing(ButtonBar.ButtonData role) {
        return new DialogAnswers(byRole(role));
    }

    /** Every dialog from now on is answered with its button labelled {@code label}. */
    static DialogAnswers pressing(String label) {
        return new DialogAnswers(byLabel(label));
    }

    /** Every dialog from now on is closed without an answer, as Escape or the window's close button does. */
    static DialogAnswers closing() {
        return new DialogAnswers(question -> null);
    }

    /** Later dialogs are answered by {@code next}. */
    void nowWith(Function<Dialogs.Asked, Dialogs.Answer> next) {
        answer = next;
    }

    /** Later dialogs are answered with their button of kind {@code role}. */
    void nowPressing(ButtonBar.ButtonData role) {
        answer = byRole(role);
    }

    /** Later dialogs are answered with their button labelled {@code label}. */
    void nowPressing(String label) {
        answer = byLabel(label);
    }

    /** Later dialogs are closed without an answer. */
    void nowClosing() {
        answer = question -> null;
    }

    /** What each dialog asked so far, in order. */
    List<Dialogs.Asked> asked() {
        return List.copyOf(asked);
    }

    /** The one dialog asked so far; fails when there was none or more than one. */
    Dialogs.Asked only() {
        if (asked.size() != 1) {
            throw new AssertionError("expected one dialog, got " + asked.size() + ": " + asked);
        }
        return asked.get(0);
    }

    /** Forgets the dialogs asked so far. */
    void clear() {
        asked.clear();
    }

    @Override
    public void close() {
        Dialogs.answerWith(null);
    }

    private static Function<Dialogs.Asked, Dialogs.Answer> byRole(ButtonBar.ButtonData role) {
        return question -> {
            Dialogs.Answer found = question.answer(role);
            if (found == null) {
                throw new AssertionError("no " + role + " answer in the dialog: " + question);
            }
            return found;
        };
    }

    private static Function<Dialogs.Asked, Dialogs.Answer> byLabel(String label) {
        return question -> {
            Dialogs.Answer found = question.answer(label);
            if (found == null) {
                throw new AssertionError("no answer labelled \"" + label + "\" in the dialog: " + question);
            }
            return found;
        };
    }
}
