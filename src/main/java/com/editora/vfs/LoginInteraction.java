package com.editora.vfs;

import java.util.ArrayList;
import java.util.List;

import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.session.ClientSession;

/**
 * The shared client's answers to the server's login questions, put to the person through the {@link LoginPrompt}
 * of the window that owns the session: the questions of keyboard-interactive (where 2FA codes come from), and a
 * password once any given before the login has been refused.
 */
final class LoginInteraction implements UserInteraction {

    /** Passwords asked for before giving up, as {@code ssh}'s NumberOfPasswordPrompts. */
    static final int PASSWORD_ATTEMPTS = 3;

    static final LoginInteraction INSTANCE = new LoginInteraction();

    private LoginInteraction() {}

    @Override
    public boolean isInteractionAllowed(ClientSession session) {
        return LoginQuestions.of(session).canAsk(session);
    }

    @Override
    public String[] interactive(
            ClientSession session, String name, String instruction, String lang, String[] prompt, boolean[] echo) {
        if (prompt == null || prompt.length == 0) {
            return new String[0]; // an information-only round (a banner, "code accepted"): nothing to answer
        }
        List<LoginPrompt.Question> questions = new ArrayList<>();
        for (int i = 0; i < prompt.length; i++) {
            questions.add(new LoginPrompt.Question(prompt[i], echo != null && i < echo.length && echo[i]));
        }
        LoginPrompt.Challenge challenge = new LoginPrompt.Challenge(
                LoginPrompt.Kind.INTERACTIVE,
                LoginQuestions.subject(session),
                name == null ? "" : name,
                instruction == null ? "" : instruction,
                questions,
                LoginQuestions.next(session, LoginQuestions.ROUNDS));
        char[][] answers = LoginQuestions.of(session).ask(session, challenge);
        if (answers == null) {
            LoginQuestions.cancel(session);
            return null; // the method fails, and with nothing more asked the login ends as cancelled
        }
        return strings(answers);
    }

    /**
     * A password for the next try at the password method, once any given before the login has been used: asked
     * for, up to {@link #PASSWORD_ATTEMPTS} in all. Null ends the method.
     */
    @Override
    public String resolveAuthPasswordAttempt(ClientSession session) {
        LoginQuestions questions = LoginQuestions.of(session);
        if (!questions.canAsk(session)) {
            return null;
        }
        int attempt = LoginQuestions.next(session, LoginQuestions.PASSWORDS);
        if (attempt > PASSWORD_ATTEMPTS) {
            return null;
        }
        char[][] answers = questions.ask(
                session,
                new LoginPrompt.Challenge(
                        LoginPrompt.Kind.PASSWORD,
                        LoginQuestions.subject(session),
                        "",
                        "",
                        List.of(new LoginPrompt.Question("", false)),
                        attempt));
        if (answers == null) {
            LoginQuestions.cancel(session);
            return null;
        }
        return strings(answers)[0];
    }

    /** A server asking for a new password (an expired one) is not supported: the login fails, as it says. */
    @Override
    public String getUpdatedPassword(ClientSession session, String prompt, String lang) {
        return null;
    }

    /**
     * MINA's API takes Strings, so a copy the caller's wipe cannot reach is unavoidable here; the char arrays are
     * wiped at once, and MINA drops the Strings when the round is sent.
     */
    private static String[] strings(char[][] answers) {
        String[] out = new String[answers.length];
        for (int i = 0; i < answers.length; i++) {
            out[i] = new String(answers[i]);
        }
        LoginQuestions.wipe(answers);
        return out;
    }
}
