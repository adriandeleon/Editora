package com.editora.lsp;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression for #407: the language-server fork was moved off the JavaFX Application Thread. The subtle half of
 * that fix is that {@link LanguageServerSession#start()} must <b>not</b> be {@code synchronized} — it now runs on
 * the {@code lsp-start} executor and blocks for the JVM/Node fork, so holding the {@code this} monitor across it
 * would block {@code whenReady()}'s {@code synchronized} check on the FX thread (which {@code didOpen}/
 * {@code didChange} go through), silently re-introducing the very startup stall. This guards that property so a
 * future edit can't quietly bring the monitor back.
 */
class LspStartNotSynchronizedTest {

    @Test
    void startMustNotBeSynchronizedSoTheForkDoesNotBlockTheFxThread() throws Exception {
        Method start = LanguageServerSession.class.getDeclaredMethod("start");
        assertFalse(
                Modifier.isSynchronized(start.getModifiers()),
                "LanguageServerSession.start() must not be synchronized: it forks off the FX thread (#407) and "
                        + "holding the this-monitor across the fork would block whenReady() on the FX thread.");
    }
}
