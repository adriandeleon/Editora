package com.editora.ui;

import java.util.Locale;

import static com.editora.i18n.Messages.tr;

/**
 * Recognises an authentication failure in git's error output and says what to do about it.
 *
 * <p>Editora runs git with prompting disabled — a password prompt would have nowhere to appear — so a remote
 * that needs credentials fails with git's own terse message ("terminal prompts disabled", "Permission denied
 * (publickey)"). Not prompting is deliberate; leaving the user with only that line was the defect. The
 * classifier is pure (git is run in the C locale, so its wording is stable).
 */
final class GitAuthFailure {

    /** What kind of sign-in problem git reported. */
    enum Kind {
        NONE(null),
        /** HTTPS wanted a user name / password / token and had no way to get one, or the one it had was refused. */
        HTTPS("git.auth.hint.https"),
        /** The SSH server refused every key it was offered (or none was offered). */
        SSH("git.auth.hint.ssh"),
        /** SSH did not recognise the server's host key and could not ask whether to trust it. */
        HOST_KEY("git.auth.hint.hostKey");

        private final String hintKey;

        Kind(String hintKey) {
            this.hintKey = hintKey;
        }
    }

    private GitAuthFailure() {}

    private static final String[] HOST_KEY_MARKS = {"host key verification failed"};

    private static final String[] SSH_MARKS = {
        "permission denied (publickey", "permission denied (keyboard-interactive", "permission denied (password"
    };

    private static final String[] HTTPS_MARKS = {
        "terminal prompts disabled",
        "could not read username",
        "could not read password",
        "authentication failed for",
        "invalid username or password",
        "invalid username or token",
        "http basic: access denied",
        "the requested url returned error: 401",
        "the requested url returned error: 403",
        "password authentication was removed",
        "support for password authentication was removed"
    };

    /** Classifies git's stderr; {@link Kind#NONE} for anything that is not a sign-in failure. */
    static Kind classify(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return Kind.NONE;
        }
        String text = stderr.toLowerCase(Locale.ROOT);
        if (containsAny(text, HOST_KEY_MARKS)) {
            return Kind.HOST_KEY;
        }
        if (containsAny(text, SSH_MARKS)) {
            return Kind.SSH;
        }
        return containsAny(text, HTTPS_MARKS) ? Kind.HTTPS : Kind.NONE;
    }

    private static boolean containsAny(String text, String[] marks) {
        for (String mark : marks) {
            if (text.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    /** {@code detail} followed by the next step for its kind of sign-in failure; unchanged for any other error. */
    static String withGuidance(String detail) {
        Kind kind = classify(detail);
        return kind == Kind.NONE ? detail : detail.strip() + "\n\n" + tr(kind.hintKey);
    }
}
