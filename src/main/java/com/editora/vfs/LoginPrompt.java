package com.editora.vfs;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Asks the person at the screen for what a login needs, while the login runs, the way {@code ssh} asks at the
 * terminal: a password, a key's passphrase, or the questions a server puts through keyboard-interactive (a 2FA
 * code, a one-time password, a Duo push answer).
 *
 * <p>Called on an SSH thread and <b>must block</b> until answered. Returning {@code null} cancels: a cancelled
 * passphrase skips that key, a cancelled password or server question ends the login.
 */
@FunctionalInterface
public interface LoginPrompt {

    /** What is being asked. */
    enum Kind {
        /** The account's password; {@link Challenge#subject} is {@code user@host}. */
        PASSWORD,
        /** A private key's passphrase; {@link Challenge#subject} is the key file. */
        PASSPHRASE,
        /** The server's own questions (keyboard-interactive); {@link Challenge#subject} is {@code user@host}. */
        INTERACTIVE
    }

    /** One question, shown as typed ({@code echo}) or hidden like a password. */
    record Question(String text, boolean echo) {}

    /**
     * One round of questions. {@code name} and {@code instruction} are the server's (keyboard-interactive only,
     * often empty); {@code attempt} counts from 1 and is above 1 when an earlier answer was not accepted.
     */
    record Challenge(
            Kind kind, String subject, String name, String instruction, List<Question> questions, int attempt) {
        public Challenge {
            questions = List.copyOf(questions);
        }
    }

    /** The answers, one per question in order, or {@code null} to cancel. The caller wipes them. */
    char @Nullable [][] answer(Challenge challenge);
}
