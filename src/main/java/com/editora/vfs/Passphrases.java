package com.editora.vfs;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionContext;
import org.jspecify.annotations.Nullable;

/**
 * A key's passphrase, asked for when the key turns out to be encrypted, as {@code ssh} does: the one given
 * before the login first (if any), then the person, through the {@link LoginPrompt} of the window whose login
 * it is, up to {@link #ATTEMPTS} tries. Cancelling skips that key and the login goes on with the next way in.
 *
 * <p>One instance for every key the shared client loads — a key file named by the connection, a default key,
 * an {@code IdentityFile} of {@code ~/.ssh/config} — since all of them are loaded for some session.
 */
final class Passphrases implements FilePasswordProvider {

    /** Passphrases asked for per key before it is skipped, as {@code ssh}'s three. */
    static final int ATTEMPTS = 3;

    /** The passphrase given before the login, tried first; dropped when the login ends. */
    static final AttributeRepository.AttributeKey<String> GIVEN = new AttributeRepository.AttributeKey<>();

    private static final AttributeRepository.AttributeKey<Set<String>> SKIPPED =
            new AttributeRepository.AttributeKey<>();

    static final Passphrases INSTANCE = new Passphrases();

    private Passphrases() {}

    @Override
    public @Nullable String getPassword(SessionContext context, NamedResource resource, int retryIndex) {
        if (!(context instanceof Session session)) {
            return null; // loaded outside any login: nobody to ask
        }
        String given = session.getAttribute(GIVEN);
        if (retryIndex == 0 && given != null) {
            return given;
        }
        String name = resource == null ? "" : resource.getName();
        char[][] answers = LoginQuestions.of(session)
                .ask(
                        session,
                        new LoginPrompt.Challenge(
                                LoginPrompt.Kind.PASSPHRASE,
                                name,
                                "",
                                "",
                                List.of(new LoginPrompt.Question("", false)),
                                retryIndex + 1)); // above 1: an earlier passphrase, given or typed, was wrong
        if (answers == null) {
            skipped(session).add(name);
            return null;
        }
        String passphrase = new String(answers[0]);
        LoginQuestions.wipe(answers);
        return passphrase;
    }

    @Override
    public ResourceDecodeResult handleDecodeAttemptResult(
            SessionContext context, NamedResource resource, int retryIndex, String password, Exception err) {
        if (err == null) {
            return ResourceDecodeResult.TERMINATE; // decoded: nothing more to try
        }
        if (!(context instanceof Session session)) {
            return ResourceDecodeResult.IGNORE;
        }
        String name = resource == null ? "" : resource.getName();
        boolean given = session.getAttribute(GIVEN) != null;
        boolean more = retryIndex + 1 < ATTEMPTS + (given ? 1 : 0);
        return skipped(session).contains(name) || password == null || !more
                ? ResourceDecodeResult.IGNORE // skip this key; the login goes on without it
                : ResourceDecodeResult.RETRY;
    }

    private static Set<String> skipped(Session session) {
        return session.computeAttributeIfAbsent(SKIPPED, k -> ConcurrentHashMap.newKeySet());
    }
}
