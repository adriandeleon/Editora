package com.editora.vfs;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.AbstractUserAuth;
import org.apache.sshd.server.auth.AbstractUserAuthFactory;
import org.apache.sshd.server.auth.UserAuth;
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge;
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login's questions, asked while it runs: a server's own questions (keyboard-interactive, where a 2FA code
 * comes from), a password, and an encrypted key's passphrase — and what a connect that fails says about why.
 */
@Tag("fx")
class LoginPromptFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private SshServer server;
    private RemoteFileSystems engine;

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null) {
            engine.shutdown();
        }
        if (server != null) {
            server.stop(true);
        }
    }

    /** A server that asks for a password and then a verification code, and wants "secret" and "123456". */
    private void startTwoFactorServer() throws Exception {
        server = baseServer();
        server.setKeyboardInteractiveAuthenticator(new KeyboardInteractiveAuthenticator() {
            @Override
            public InteractiveChallenge generateChallenge(
                    ServerSession session, String username, String lang, String subMethods) {
                InteractiveChallenge c = new InteractiveChallenge();
                c.setInteractionName("Two-step login");
                c.setInteractionInstruction("Enter your password and the code from your phone.");
                c.addPrompt("Password: ", false);
                c.addPrompt("Verification code: ", true);
                return c;
            }

            @Override
            public boolean authenticate(ServerSession session, String username, List<String> responses) {
                return responses.equals(List.of("secret", "123456"));
            }
        });
        server.start();
    }

    private SshServer baseServer() throws Exception {
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host-key.ser")));
        Path served = Files.createDirectories(dir.resolve("served"));
        Files.writeString(served.resolve("hello.txt"), "hi\n");
        s.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(served));
        s.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        return s;
    }

    private RemoteFileSystems engine(LoginPrompt login, Duration authTimeout) {
        engine = new RemoteFileSystems(
                (h, p, t, f) -> true, login, dir.resolve("known_hosts"), Duration.ofSeconds(20), authTimeout);
        return engine;
    }

    private RemoteConnection conn(RemoteConnection.AuthMethod auth, String keyPath) {
        return new RemoteConnection("127.0.0.1", server.getPort(), "me", auth, keyPath, "t", "/");
    }

    private static RemoteFileSystems.Result connect(RemoteFileSystems engine, RemoteConnection conn, char[] secret)
            throws InterruptedException {
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(conn, secret, r -> {
            result.set(r);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the connect never finished");
        return result.get();
    }

    /** Answers each challenge with {@code answer}, recording what was asked. */
    private static final class Recorder implements LoginPrompt {
        final List<Challenge> asked = new CopyOnWriteArrayList<>();
        final Function<Challenge, char[][]> answer;

        Recorder(Function<Challenge, char[][]> answer) {
            this.answer = answer;
        }

        @Override
        public char[][] answer(Challenge challenge) {
            asked.add(challenge);
            return answer.apply(challenge);
        }
    }

    private static char[][] answers(String... values) {
        char[][] out = new char[values.length][];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].toCharArray();
        }
        return out;
    }

    @Test
    void theServersQuestionsAreAskedWithItsOwnWordsAndAnswered() throws Exception {
        startTwoFactorServer();
        Recorder login = new Recorder(c -> answers("secret", "123456"));

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertTrue(r.ok(), r.error());
        assertEquals(1, login.asked.size(), "one round: both questions together");
        LoginPrompt.Challenge c = login.asked.getFirst();
        assertEquals(LoginPrompt.Kind.INTERACTIVE, c.kind());
        assertEquals("Two-step login", c.name());
        assertEquals("Enter your password and the code from your phone.", c.instruction());
        assertEquals(
                List.of(
                        new LoginPrompt.Question("Password: ", false),
                        new LoginPrompt.Question("Verification code: ", true)),
                c.questions(),
                "the code may be shown as it is typed; the password not");
        assertEquals("me@127.0.0.1:" + server.getPort(), c.subject());
        assertEquals("hi\n", Files.readString(r.root().resolve("hello.txt")));
    }

    @Test
    void aWrongAnswerIsAskedForAgain() throws Exception {
        startTwoFactorServer();
        Recorder login =
                new Recorder(c -> c.attempt() == 1 ? answers("secret", "000000") : answers("secret", "123456"));

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertTrue(r.ok(), r.error());
        assertEquals(
                List.of(1, 2),
                login.asked.stream().map(LoginPrompt.Challenge::attempt).toList());
    }

    /**
     * A server that asks in two rounds, as PAM with a second factor does: "Password: ", then, once that is right,
     * "Verification code: ". It wants "secret" and "123456"; a wrong answer fails the method, as PAM does.
     */
    private void startTwoRoundServer() throws Exception {
        server = baseServer();
        server.setUserAuthFactories(List.of(new AbstractUserAuthFactory("keyboard-interactive") {
            @Override
            public UserAuth createUserAuth(ServerSession session) {
                return new TwoRoundAuth();
            }
        }));
        server.start();
    }

    private static final class TwoRoundAuth extends AbstractUserAuth {
        private int round;

        TwoRoundAuth() {
            super("keyboard-interactive");
        }

        @Override
        protected Boolean doAuth(Buffer buffer, boolean init) throws Exception {
            if (init) {
                ask("Password: ", false);
                return null;
            }
            if (buffer.getUByte() != SshConstants.SSH_MSG_USERAUTH_INFO_RESPONSE || buffer.getInt() != 1) {
                return false;
            }
            String answer = buffer.getString();
            if (round == 1) {
                if (!answer.equals("secret")) {
                    return false;
                }
                ask("Verification code: ", true);
                return null;
            }
            return answer.equals("123456");
        }

        private void ask(String question, boolean echo) throws Exception {
            round++;
            Buffer request = getServerSession().createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST);
            request.putString("");
            request.putString("");
            request.putString("");
            request.putInt(1);
            request.putString(question);
            request.putBoolean(echo);
            getServerSession().writePacket(request);
        }
    }

    @Test
    void aSecondRoundIsNotTakenForARetry() throws Exception {
        startTwoRoundServer();
        Recorder login = new Recorder(
                c -> c.questions().getFirst().text().startsWith("Password") ? answers("secret") : answers("123456"));

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertTrue(r.ok(), r.error());
        assertEquals(
                List.of("Password: ", "Verification code: "),
                login.asked.stream().map(c -> c.questions().getFirst().text()).toList());
        assertEquals(
                List.of(1, 1),
                login.asked.stream().map(LoginPrompt.Challenge::attempt).toList(),
                "the code is a new question, not the password asked again");
    }

    @Test
    void aQuestionAskedAgainAfterAWrongAnswerIsARetry() throws Exception {
        startTwoRoundServer();
        AtomicInteger codes = new AtomicInteger();
        Recorder login = new Recorder(c -> c.questions().getFirst().text().startsWith("Password")
                ? answers("secret")
                : answers(codes.incrementAndGet() == 1 ? "000000" : "123456"));

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertTrue(r.ok(), r.error());
        assertEquals(
                List.of(1, 1, 2, 2),
                login.asked.stream().map(LoginPrompt.Challenge::attempt).toList(),
                "after the wrong code the server starts over: both questions are asked again, as retries");
    }

    @Test
    void cancellingEndsTheLoginAsCancelledWithoutAskingMore() throws Exception {
        startTwoFactorServer();
        Recorder login = new Recorder(c -> null);

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertFalse(r.ok());
        assertEquals(ConnectFailure.Kind.CANCELLED, r.failure().kind(), r.error());
        assertEquals(1, login.asked.size(), "nothing more is asked once cancelled");
    }

    @Test
    void timeSpentAnsweringDoesNotCountAgainstTheLoginDeadline() throws Exception {
        startTwoFactorServer();
        Recorder login = new Recorder(c -> {
            try {
                Thread.sleep(2500); // reading the code off a phone
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return answers("secret", "123456");
        });

        RemoteFileSystems.Result r =
                connect(engine(login, Duration.ofSeconds(1)), conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertTrue(r.ok(), "a slow answer timed the login out: " + r.error());
    }

    @Test
    void aPasswordIsAskedForWhenNoneWasGivenAndAgainWhenItIsWrong() throws Exception {
        server = baseServer();
        server.setKeyboardInteractiveAuthenticator(null); // password only
        server.setPasswordAuthenticator((user, password, session) -> password.equals("pw"));
        server.start();
        Recorder login = new Recorder(c -> c.attempt() < 3 ? answers("nope") : answers("pw"));

        RemoteFileSystems.Result r = connect(
                engine(login, Duration.ofSeconds(20)),
                conn(RemoteConnection.AuthMethod.PASSWORD, ""),
                "given-and-wrong".toCharArray());

        assertTrue(r.ok(), r.error());
        assertTrue(login.asked.stream().allMatch(c -> c.kind() == LoginPrompt.Kind.PASSWORD));
        assertEquals(
                List.of(2, 3),
                login.asked.stream().map(LoginPrompt.Challenge::attempt).toList(),
                "the password given before the login was the first try");
    }

    @Test
    void anEncryptedKeysPassphraseIsAskedForOnlyWhenTheKeyIsUsed() throws Exception {
        KeyPair userKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        Path keyFile = writeEncryptedKey(userKey, "pp");
        server = baseServer();
        server.setPublickeyAuthenticator(
                (user, key, session) -> KeyUtils.compareKeys(key, (PublicKey) userKey.getPublic()));
        server.setKeyboardInteractiveAuthenticator(null);
        server.start();
        Recorder login = new Recorder(c -> c.attempt() == 1 ? answers("wrong") : answers("pp"));

        RemoteFileSystems.Result r = connect(
                engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.KEY, keyFile.toString()), null);

        assertTrue(r.ok(), r.error());
        assertEquals(
                List.of(1, 2),
                login.asked.stream().map(LoginPrompt.Challenge::attempt).toList());
        assertTrue(login.asked.stream().allMatch(c -> c.kind() == LoginPrompt.Kind.PASSPHRASE));
        assertTrue(
                login.asked.getFirst().subject().endsWith("id_ed25519"),
                login.asked.getFirst().subject());
    }

    @Test
    void cancellingAPassphraseSkipsTheKeyAndTheLoginGoesOn() throws Exception {
        KeyPair userKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        Path keyFile = writeEncryptedKey(userKey, "pp");
        server = baseServer();
        server.setPublickeyAuthenticator(
                (user, key, session) -> KeyUtils.compareKeys(key, (PublicKey) userKey.getPublic()));
        server.setKeyboardInteractiveAuthenticator(null);
        server.setPasswordAuthenticator((user, password, session) -> password.equals("pw"));
        server.start();
        Recorder login = new Recorder(c -> c.kind() == LoginPrompt.Kind.PASSPHRASE ? null : answers("pw"));

        RemoteFileSystems.Result r = connect(
                engine(login, Duration.ofSeconds(20)), conn(RemoteConnection.AuthMethod.KEY, keyFile.toString()), null);

        assertTrue(r.ok(), r.error());
        assertEquals(
                List.of(LoginPrompt.Kind.PASSPHRASE, LoginPrompt.Kind.PASSWORD),
                login.asked.stream().map(LoginPrompt.Challenge::kind).toList(),
                "the key is skipped, and the server's password login is offered next, as ssh does");
    }

    @Test
    void aLoginTheServerRefusesSaysSo() throws Exception {
        server = baseServer();
        server.setKeyboardInteractiveAuthenticator(null);
        server.setPasswordAuthenticator((user, password, session) -> false);
        server.start();

        RemoteFileSystems.Result r = connect(
                engine(null, Duration.ofSeconds(20)),
                conn(RemoteConnection.AuthMethod.PASSWORD, ""),
                "x".toCharArray());

        assertFalse(r.ok());
        assertEquals(ConnectFailure.Kind.AUTH_FAILED, r.failure().kind(), r.error());
    }

    @Test
    void aHostNobodyAnswersAtIsUnreachable() throws Exception {
        server = baseServer();
        server.start();
        int closed = server.getPort();
        server.stop(true);
        server = null;

        RemoteFileSystems.Result r = connect(
                engine(null, Duration.ofSeconds(20)),
                new RemoteConnection("127.0.0.1", closed, "me", RemoteConnection.AuthMethod.PASSWORD, "", "t", "/"),
                null);

        assertFalse(r.ok());
        assertEquals(ConnectFailure.Kind.UNREACHABLE, r.failure().kind(), r.error());
    }

    @Test
    void aDeclinedHostKeyIsTheUsersOwnChoiceAndAChangedOneIsSaidToBe() throws Exception {
        startTwoFactorServer();
        engine = new RemoteFileSystems((h, p, t, f) -> false, dir.resolve("known_hosts"));

        RemoteFileSystems.Result declined = connect(engine, conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertEquals(ConnectFailure.Kind.HOST_KEY_DECLINED, declined.failure().kind(), declined.error());

        KeyPair other = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        Files.writeString(
                dir.resolve("known_hosts"),
                "[127.0.0.1]:" + server.getPort() + " "
                        + org.apache.sshd.common.config.keys.PublicKeyEntry.toString(other.getPublic()) + "\n");
        engine.shutdown();
        engine = new RemoteFileSystems((h, p, t, f) -> true, dir.resolve("known_hosts"));

        RemoteFileSystems.Result changed = connect(engine, conn(RemoteConnection.AuthMethod.PASSWORD, ""), null);

        assertEquals(ConnectFailure.Kind.HOST_KEY_CHANGED, changed.failure().kind(), changed.error());
    }

    private Path writeEncryptedKey(KeyPair key, String passphrase) throws Exception {
        Path keyFile = dir.resolve("id_ed25519");
        OpenSSHKeyEncryptionContext encryption = new OpenSSHKeyEncryptionContext();
        encryption.setCipherName("AES");
        encryption.setCipherMode("CTR");
        encryption.setCipherType("256");
        encryption.setPassword(passphrase);
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "editora-test", encryption, out);
        }
        return keyFile;
    }
}
