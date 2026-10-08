package com.editora.editor;

import java.util.ArrayDeque;

import javafx.application.Platform;

/**
 * Caret fix-ups that must wait for the edit that caused them to settle (an abbreviation expansion or an
 * auto-fill break, made from inside the triggering insertion's change listener — RichTextFX re-applies that
 * insertion's own caret once the listener returns, so the fix-up has to come after).
 *
 * <p>"After" used to mean {@code Platform.runLater}: the next event-loop turn. That is right for a person
 * typing, and wrong for a macro replay, which delivers its next key in the <em>same</em> turn — the fix-up
 * then arrived after the keys that depended on it, and the replayed text went to the wrong place. So the
 * fix-ups are queued here: they still run on the next turn, or as soon as {@link #flush} is called by a
 * caller that knows the insertion is over.
 */
final class DeferredFixes {

    private final ArrayDeque<Runnable> pending = new ArrayDeque<>(2);

    /** Runs {@code fix} on the next event-loop turn, or at the next {@link #flush} if that comes first. */
    void defer(Runnable fix) {
        pending.add(fix);
        Platform.runLater(this::flush);
    }

    /** Runs whatever is pending, in order. */
    void flush() {
        Runnable fix;
        while ((fix = pending.poll()) != null) {
            fix.run();
        }
    }
}
