package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.crypto.EncryptedFiles;
import com.editora.crypto.Gpg;
import com.editora.crypto.GpgKey;
import com.editora.crypto.GpgTestHome;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EncryptionState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Encrypted files end to end in a real window, against the real gpg in a throwaway keyring: an encrypted file
 * opens as its text and saves encrypted again; a save that cannot be encrypted writes nothing; leaving
 * encryption is asked; a passphrase-only file asks once; a restored tab stays locked; Encrypt File writes the
 * encrypted copy beside the plain one. The picker and the prompts are stood in for; gpg is not.
 */
@Tag("fx")
class EncryptedFilesFxTest {

    private static GpgTestHome home;
    private static String adrian;

    @BeforeAll
    static void boot() throws Exception {
        assumeTrue(GpgTestHome.available(), "gpg is not installed");
        FxTestSupport.bootToolkit();
        home = GpgTestHome.create();
        adrian = home.newKey("Adrian Test <adrian@example.com>");
        EncryptionCoordinator.gpgEnvironment = home.env();
    }

    @AfterAll
    static void cleanUp() throws Exception {
        EncryptionCoordinator.gpgEnvironment = java.util.Map.of();
        if (home != null) {
            home.close();
        }
    }

    private static byte[] encryptFor(String text, List<String> recipients, boolean armor) {
        Gpg.Outcome o = home.gpg().encrypt(text.getBytes(StandardCharsets.UTF_8), recipients, armor);
        assertTrue(o.ok(), () -> String.valueOf(o.failure()));
        return o.out();
    }

    private static String decrypt(Path file) throws Exception {
        Gpg.Outcome o = home.gpg().decrypt(Files.readAllBytes(file), null, null);
        assertTrue(o.ok(), () -> String.valueOf(o.failure()));
        return o.text();
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!FxTestSupport.callOnFx(condition)) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for: " + what);
            Thread.sleep(10);
            FxTestSupport.drainFx();
        }
    }

    /** The buffer of {@code file}'s tab, or null (FX thread). */
    private static EditorBuffer bufferFor(FxWindowFixture fx, Path file) {
        Object tab = FxTestSupport.call(fx.controller, "tabForPath", new Class<?>[] {Path.class}, file);
        return tab == null
                ? null
                : (EditorBuffer) FxTestSupport.call(
                        fx.controller, "bufferOf", new Class<?>[] {javafx.scene.control.Tab.class}, tab);
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = fx.controller.fileWorkflows;
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        await("a tab for " + file.getFileName(), () -> bufferFor(fx, file) != null);
        return FxTestSupport.callOnFx(() -> bufferFor(fx, file));
    }

    private static void awaitUnlocked(EditorBuffer buffer) throws Exception {
        await(
                "decrypted",
                () -> buffer.encryption().state() != null
                        && !buffer.encryption().state().locked()
                        && !buffer.encryption().isLocked());
    }

    private static void awaitSaved(FxWindowFixture fx, EditorBuffer buffer) throws Exception {
        await("saved", () -> !fx.controller.fileWorkflows.hasPendingSave(buffer) && !buffer.isDirty());
    }

    @Test
    void anEncryptedFileOpensAsItsTextAndSavesEncryptedAgain(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = dir.resolve("notes.md.gpg");
            Files.write(file, encryptFor("# Notes\n\nsecret\n", List.of(adrian), false));
            EditorBuffer buffer = open(fx, file);
            awaitUnlocked(buffer);
            assertEquals("# Notes\n\nsecret\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals("markdown", FxTestSupport.callOnFx(buffer::getLanguage), "the inner name decides");
            EncryptionState state =
                    FxTestSupport.callOnFx(() -> buffer.encryption().state());
            assertEquals(EncryptionState.Mode.PUBLIC_KEY, state.mode());
            assertEquals(1, state.recipients().size());
            assertTrue(state.recipients().get(0).you());
            assertEquals(adrian, state.recipients().get(0).id());
            assertTrue(FxTestSupport.callOnFx(buffer::isEditable));
            String chip = FxTestSupport.callOnFx(
                    () -> ((javafx.scene.control.Label) fx.controller.statusBar.encryptionNode()).getText());
            assertEquals(com.editora.i18n.Messages.tr("encryption.chip.count", "1"), chip);

            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("more\n");
                fx.controller.fileWorkflows.save(buffer);
            });
            awaitSaved(fx, buffer);
            byte[] onDisk = Files.readAllBytes(file);
            assertEquals(EncryptedFiles.Kind.BINARY_MESSAGE, EncryptedFiles.sniff(onDisk), "still binary, as it was");
            assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("secret"), "no plaintext on disk");
            assertEquals("# Notes\n\nsecret\nmore\n", decrypt(file));
            assertTrue(FxTestSupport.callOnFx(() -> buffer.encryption().isEncrypted()));
        }
    }

    @Test
    void aSaveThatCannotBeEncryptedWritesNothing(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope();
                GpgTestHome other = GpgTestHome.create()) {
            // Carol's key lives only in another keyring: this one cannot encrypt for her.
            String carol = other.newKey("Carol Other <carol@example.com>");
            other.gpg().importKeys(home.exportKey(adrian));
            Gpg.Outcome enc =
                    other.gpg().encrypt("for two\n".getBytes(StandardCharsets.UTF_8), List.of(adrian, carol), true);
            assertTrue(enc.ok(), () -> String.valueOf(enc.failure()));
            Path file = Files.write(dir.resolve("shared.txt.asc"), enc.out());
            byte[] before = Files.readAllBytes(file);

            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx, file);
            awaitUnlocked(buffer);
            EncryptionState state =
                    FxTestSupport.callOnFx(() -> buffer.encryption().state());
            assertTrue(state.armored(), "an armored file stays armored");
            assertEquals(1, state.missingKeys().size(), "Carol's key is not here");
            boolean saved = FxTestSupport.callOnFx(() -> {
                buffer.getArea().appendText("edit\n");
                return fx.controller.fileWorkflows.save(buffer);
            });
            assertFalse(saved);
            FxTestSupport.drainFx();
            assertNotNull(FxTestSupport.callOnFx(() -> buffer.encryption().notice()), "the Not saved banner");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the edits stay");
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the file is untouched");
        }
    }

    @Test
    void savingWithoutEncryptionIsAskedAndThenTheFileIsPlain(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("todo.txt.gpg"), encryptFor("buy milk\n", List.of(adrian), false));
            EditorBuffer buffer = open(fx, file);
            awaitUnlocked(buffer);
            EncryptionCoordinator encryption = fx.controller.fileWorkflows.encryption();
            List<Path> asked = new ArrayList<>();
            encryption.confirmLeaveEncryption = target -> {
                asked.add(target);
                return false;
            };
            Path plain = dir.resolve("todo.txt");
            assertFalse(FxTestSupport.callOnFx(() -> fx.controller.fileWorkflows.applySaveAsTarget(buffer, plain)));
            assertEquals(List.of(plain), asked);
            assertFalse(Files.exists(plain), "declined: nothing written");
            assertSame(file, FxTestSupport.callOnFx(buffer::getPath), "and the tab is still the encrypted file");

            encryption.confirmLeaveEncryption = target -> true;
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.applySaveAsTarget(buffer, plain));
            awaitSaved(fx, buffer);
            assertEquals("buy milk\n", Files.readString(plain));
            assertFalse(FxTestSupport.callOnFx(() -> buffer.encryption().isEncrypted()), "an ordinary file now");
            assertEquals(EncryptedFiles.Kind.BINARY_MESSAGE, EncryptedFiles.sniff(Files.readAllBytes(file)));
        }
    }

    @Test
    void aPassphraseFileAsksOnceAndSavesWithTheSamePassphrase(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Gpg.Outcome enc = home.gpg()
                    .encryptSymmetric("diary\n".getBytes(StandardCharsets.UTF_8), "open sesame".toCharArray(), true);
            assertTrue(enc.ok());
            Path file = Files.write(dir.resolve("diary.txt.asc"), enc.out());
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EncryptionCoordinator encryption = fx.controller.fileWorkflows.encryption();
            AtomicInteger asks = new AtomicInteger();
            List<String> said = new ArrayList<>();
            encryption.passphraseAsker = (message, note, ok, attempt, onCancel) -> {
                asks.incrementAndGet();
                // A wrong passphrase first: the prompt says so and asks again.
                attempt.submit("wrong".toCharArray(), new PassphrasePrompt.Result() {
                    @Override
                    public void done() {
                        said.add("done");
                    }

                    @Override
                    public void failed(String why) {
                        said.add(why);
                        attempt.submit("open sesame".toCharArray(), new PassphrasePrompt.Result() {
                            @Override
                            public void done() {
                                said.add("done");
                            }

                            @Override
                            public void failed(String again) {
                                said.add(again);
                            }
                        });
                    }
                });
            };
            EditorBuffer buffer = open(fx, file);
            awaitUnlocked(buffer);
            assertEquals("diary\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(List.of(com.editora.i18n.Messages.tr("encryption.passphrase.wrong"), "done"), said);
            EncryptionState state =
                    FxTestSupport.callOnFx(() -> buffer.encryption().state());
            assertEquals(EncryptionState.Mode.PASSPHRASE, state.mode());
            assertTrue(state.hasPassphrase());

            FxTestSupport.runOnFx(() -> {
                buffer.getArea().appendText("day two\n");
                fx.controller.fileWorkflows.save(buffer);
            });
            awaitSaved(fx, buffer);
            assertEquals(1, asks.get(), "saving did not ask again");
            Gpg.Outcome back = home.gpg().decrypt(Files.readAllBytes(file), "open sesame".toCharArray(), null);
            assertTrue(back.ok(), () -> String.valueOf(back.failure()));
            assertEquals("diary\nday two\n", back.text());
            assertEquals(EncryptedFiles.Kind.ARMORED_MESSAGE, EncryptedFiles.sniff(Files.readAllBytes(file)));
        }
    }

    @Test
    void aRestoredTabStaysLockedUntilAsked(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("plan.md.gpg"), encryptFor("plan\n", List.of(adrian), false));
            FileWorkflowCoordinator workflows = fx.controller.fileWorkflows;
            // As the session restore does: read, then apply — no interactive open.
            FileWorkflowCoordinator.PreparedLoad load = workflows.prepareLoad(file, true);
            assertTrue(load.encrypted());
            assertNull(load.content(), "a read never decrypts");
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                workflows.applyPreparedLoad(b, load);
                return b;
            });
            FxTestSupport.drainFx();
            assertEquals(
                    EncryptionState.Lock.RESTORED,
                    FxTestSupport.callOnFx(() -> buffer.encryption().state().lock()));
            assertTrue(FxTestSupport.callOnFx(() -> buffer.encryption().isLocked()));
            assertFalse(FxTestSupport.callOnFx(buffer::isEditable), "nothing can be typed into the empty document");
            assertEquals(SaveRefusal.LOCKED, FxTestSupport.callOnFx(() -> workflows.saveRefusal(buffer)));

            FxTestSupport.runOnFx(() -> workflows.encryption().decrypt(buffer));
            awaitUnlocked(buffer);
            assertEquals("plan\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void encryptFileWritesTheEncryptedCopyBesideThePlainOne(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path plain = Files.writeString(dir.resolve("client.md"), "# Client\n");
            EditorBuffer buffer = open(fx, plain);
            await("loaded", () -> !buffer.isLoading());
            EncryptionCoordinator encryption = fx.controller.fileWorkflows.encryption();
            List<RecipientPicker.Request> shown = new ArrayList<>();
            encryption.picker = (request, choose) -> {
                shown.add(request);
                List<GpgKey> me = request.keys().stream()
                        .filter(k -> request.preselected().contains(k.fingerprint()))
                        .toList();
                choose.accept(new RecipientPicker.Choice(EncryptionState.Mode.PUBLIC_KEY, me, null, false, false));
            };
            FxTestSupport.runOnFx(encryption::encryptFile);
            Path encrypted = dir.resolve("client.md.gpg");
            await("written", () -> Files.exists(encrypted) && !fx.controller.fileWorkflows.hasPendingSave(buffer));
            assertEquals(1, shown.size());
            assertTrue(shown.get(0).preselected().contains(adrian), "your own key starts ticked");
            assertEquals("# Client\n", decrypt(encrypted));
            assertEquals(encrypted, FxTestSupport.callOnFx(buffer::getPath), "the tab is the encrypted file now");
            assertTrue(Files.exists(plain), "the original stays until the user says otherwise");
            await("the banner about the original", () -> buffer.encryption().notice() != null);
            assertNotEquals(
                    null, FxTestSupport.callOnFx(() -> buffer.encryption().state()));
        }
    }

    @Test
    void aNewEncryptedFileChoosesRecipientsBeforeItsFirstSave(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = dir.resolve("new.txt.gpg");
            EncryptionCoordinator encryption = fx.controller.fileWorkflows.encryption();
            AtomicInteger picked = new AtomicInteger();
            encryption.picker = (request, choose) -> {
                picked.incrementAndGet();
                choose.accept(new RecipientPicker.Choice(
                        EncryptionState.Mode.PUBLIC_KEY,
                        request.keys().stream().filter(GpgKey::secret).toList(),
                        null,
                        false,
                        false));
            };
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                assertTrue(fx.controller.fileWorkflows.openNewFileAt(file));
                EditorBuffer b = bufferFor(fx, file);
                b.getArea().appendText("fresh secret\n");
                return b;
            });
            // Autosave and every other path refuse: a plain buffer under an encrypted name has no recipients.
            assertEquals(
                    EncryptionCoordinator.Seal.REFUSED, FxTestSupport.callOnFx(() -> encryption.sealFor(buffer, file)));
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.save(buffer));
            awaitSaved(fx, buffer);
            assertEquals(1, picked.get());
            assertEquals("fresh secret\n", decrypt(file));
        }
    }
}
