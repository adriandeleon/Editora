package com.editora.ui;

import javafx.animation.PauseTransition;
import javafx.beans.value.ObservableValue;
import javafx.util.Duration;

/**
 * Applies a list editor's form as the user types, once typing pauses — the Settings pages have no Save button,
 * and a write of the store per keystroke would be wasteful (and would rename a snippet letter by letter). Enter
 * and a focus loss still commit at once; the commit itself decides whether anything changed.
 */
final class PauseCommit {

    /** How long typing must pause before the form is applied. */
    static final Duration DELAY = Duration.millis(600);

    private PauseCommit() {}

    /**
     * Runs {@code commit} {@link #DELAY} after the last change to any of {@code values}, and returns what restarts
     * that wait: a code editor's edit stream calls it (a listener on its text property builds the whole text per
     * change).
     */
    static Runnable watch(Runnable commit, ObservableValue<?>... values) {
        PauseTransition pause = new PauseTransition(DELAY);
        pause.setOnFinished(e -> commit.run());
        for (ObservableValue<?> v : values) {
            v.addListener((o, was, now) -> pause.playFromStart());
        }
        return pause::playFromStart;
    }
}
