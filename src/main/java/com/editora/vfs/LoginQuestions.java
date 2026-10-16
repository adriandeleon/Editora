package com.editora.vfs;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.session.Session;

/**
 * One engine's way of putting a {@link LoginPrompt} question during a login, and what the login remembers about
 * the answers: whether the person cancelled (then nothing more is asked and the login ends as cancelled) and
 * how many passwords and server rounds have been asked for.
 */
final class LoginQuestions {

    /** Set when the person cancelled a question that ends the login. */
    static final AttributeRepository.AttributeKey<Boolean> CANCELLED = new AttributeRepository.AttributeKey<>();
    /** {@code user@host}, as the questions name it. */
    static final AttributeRepository.AttributeKey<String> SUBJECT = new AttributeRepository.AttributeKey<>();
    /** Passwords tried so far: a password given before the login counts as the first. */
    static final AttributeRepository.AttributeKey<AtomicInteger> PASSWORDS = new AttributeRepository.AttributeKey<>();
    /** Keyboard-interactive rounds asked so far. */
    static final AttributeRepository.AttributeKey<AtomicInteger> ROUNDS = new AttributeRepository.AttributeKey<>();

    /** No questions can be asked: a login without a person (tests, a probe). */
    static final LoginQuestions NONE = new LoginQuestions(null, new PromptClock());

    private final LoginPrompt prompt;
    private final PromptClock clock;
    /**
     * The same, for this engine's current connect: a session's attributes are cleared when it closes, and MINA
     * may close it as soon as the last way of logging in fails. Reset per connect, which the engine serializes.
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelledThisConnect =
            new java.util.concurrent.atomic.AtomicBoolean();

    LoginQuestions(LoginPrompt prompt, PromptClock clock) {
        this.prompt = prompt;
        this.clock = clock;
    }

    /** The questions of the engine that owns {@code session}; {@link #NONE} for one no engine owns. */
    static LoginQuestions of(Session session) {
        RemoteFileSystems engine = SshClients.engineOf(session);
        return engine == null ? NONE : engine.loginQuestions();
    }

    /** Whether a question could be put now: there is someone to ask, and they have not cancelled. */
    boolean canAsk(Session session) {
        return prompt != null && !cancelled(session);
    }

    /** The answers, one per question, or null: cancelled, the wrong number of answers, or nobody to ask. */
    char[][] ask(Session session, LoginPrompt.Challenge challenge) {
        if (!canAsk(session)) {
            return null;
        }
        char[][] answers = clock.timed(() -> prompt.answer(challenge));
        if (answers != null && answers.length != challenge.questions().size()) {
            wipe(answers);
            return null;
        }
        return answers;
    }

    /** A new connect: nothing cancelled yet. */
    void reset() {
        cancelledThisConnect.set(false);
    }

    /** The person cancelled a question that ends the login: ask nothing more. */
    static void cancel(Session session) {
        session.setAttribute(CANCELLED, Boolean.TRUE);
        LoginQuestions questions = of(session);
        if (questions != NONE) {
            questions.cancelledThisConnect.set(true);
        }
    }

    static boolean cancelled(Session session) {
        return Boolean.TRUE.equals(session.getAttribute(CANCELLED))
                || of(session).cancelledThisConnect.get();
    }

    /** The next value of the {@code key} counter on {@code session}, counting from 1. */
    static int next(Session session, AttributeRepository.AttributeKey<AtomicInteger> key) {
        return session.computeAttributeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    static String subject(Session session) {
        String subject = session.getAttribute(SUBJECT);
        return subject == null ? session.getUsername() : subject;
    }

    static void wipe(char[][] answers) {
        if (answers != null) {
            for (char[] a : answers) {
                if (a != null) {
                    Arrays.fill(a, '\0');
                }
            }
        }
    }
}
