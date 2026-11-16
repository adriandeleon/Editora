package com.editora.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers {@link Confirm}'s questions for the length of a {@code try} block, with no dialog, and keeps what was
 * asked so a test can assert the words and that nothing was asked twice. Closing it shows dialogs again.
 */
final class ConfirmAnswers implements AutoCloseable {

    private final List<Confirm.Question> asked = new CopyOnWriteArrayList<>();
    private volatile boolean answer;

    private ConfirmAnswers(boolean answer) {
        this.answer = answer;
        Confirm.answerWith(question -> {
            asked.add(question);
            return this.answer;
        });
    }

    /** Every question from now on is answered with its action. */
    static ConfirmAnswers confirming() {
        return new ConfirmAnswers(true);
    }

    /** Every question from now on is answered with Cancel. */
    static ConfirmAnswers cancelling() {
        return new ConfirmAnswers(false);
    }

    /** Later questions are answered with their action ({@code true}) or with Cancel. */
    void answer(boolean confirm) {
        answer = confirm;
    }

    /** The questions asked so far, in order. */
    List<Confirm.Question> asked() {
        return List.copyOf(asked);
    }

    /** Forgets the questions asked so far. */
    void clear() {
        asked.clear();
    }

    @Override
    public void close() {
        Confirm.answerWith(null);
    }
}
