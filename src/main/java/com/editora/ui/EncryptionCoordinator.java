package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.stage.FileChooser;

import com.editora.crypto.EncryptedFiles;
import com.editora.crypto.Gpg;
import com.editora.crypto.GpgFailure;
import com.editora.crypto.GpgIdFiles;
import com.editora.crypto.GpgKey;
import com.editora.crypto.GpgStatus;
import com.editora.crypto.PacketInfo;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EncryptionState;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Encrypted files (ADR 0017): an OpenPGP-encrypted file ({@code .gpg}, {@code .asc}, {@code .pgp}) opens as the
 * text inside it and saves encrypted again, for the same people and in the same format, through the {@code gpg}
 * command line run on this machine.
 *
 * <p>The file workflow hands over at three points. A read of an encrypted file comes back <em>locked</em>
 * ({@link #lockedLoad}): no text, its ciphertext kept here, the tab showing a placeholder that says what is going
 * on — decrypting, a cancelled prompt, no key that opens it, no GnuPG. A save asks {@link #sealFor} first: an
 * encrypted file is given a {@link Seal} that encrypts on the save worker, before anything reaches the disk, and
 * a save that cannot be encrypted is refused — never written in plain text. Save As asks {@link #beforeSaveAs}:
 * leaving encryption is confirmed, and a new encrypted file chooses its recipients first.
 *
 * <p>Passphrases belong to gpg-agent's own prompt unless it cannot show one (or Settings say Editora asks); then
 * Editora's prompt hands them to gpg by loopback and forgets them, except a passphrase-only file's, which its
 * buffer keeps until the tab closes so saving does not ask again.
 */
final class EncryptionCoordinator {

    static final String ENCRYPT_FILE = "crypto.encryptFile";
    static final String CHANGE_RECIPIENTS = "crypto.changeRecipients";
    static final String REMOVE_ENCRYPTION = "crypto.removeEncryption";
    static final String LOCK_FILE = "crypto.lockFile";
    static final String SHOW_INFO = "crypto.showInfo";
    static final String ENCRYPT_SELECTION = "crypto.encryptSelection";
    static final String DECRYPT_BLOCK = "crypto.decryptBlock";
    static final String IMPORT_KEY = "crypto.importPublicKey";
    static final String DECRYPT_TO_FILE = "crypto.decryptToFile";
    static final String CLEAR_SIGN = "crypto.clearSign";
    static final String SIGN_DETACHED = "crypto.signDetached";
    static final String VERIFY = "crypto.verifySignature";

    /** Every command, for registration and the menu. */
    static final List<String> COMMANDS = List.of(
            ENCRYPT_FILE,
            CHANGE_RECIPIENTS,
            REMOVE_ENCRYPTION,
            LOCK_FILE,
            SHOW_INFO,
            ENCRYPT_SELECTION,
            DECRYPT_BLOCK,
            IMPORT_KEY,
            DECRYPT_TO_FILE,
            CLEAR_SIGN,
            SIGN_DETACHED,
            VERIFY);

    /**
     * The largest encrypted file decrypted into an editor. gpg's output is read into memory (never a temporary
     * file), and compressed plaintext can be several times its ciphertext; a larger file is decrypted to a file.
     */
    static final long MAX_DECRYPT_BYTES = 8L * 1024 * 1024;

    /** Extra environment for every gpg run ({@code GNUPGHOME} in tests). */
    static volatile Map<String, String> gpgEnvironment = Map.of();

    private final FileWorkflowCoordinator.Host host;
    private final FileWorkflowCoordinator files;

    /** A locked buffer's file as read: the ciphertext, and who it is for once that is known. */
    private static final class Locked {
        final Path file;
        final byte @Nullable [] ciphertext;
        final long size;
        final long mtime;
        final @Nullable String fingerprint;
        final CompletableFuture<PacketInfo> packets = new CompletableFuture<>();
        ProcessRunner.@Nullable Cancellation cancel;

        @Nullable
        String shownText;

        Locked(Path file, byte @Nullable [] ciphertext, long size, long mtime, @Nullable String fingerprint) {
            this.file = file;
            this.ciphertext = ciphertext;
            this.size = size;
            this.mtime = mtime;
            this.fingerprint = fingerprint;
        }
    }

    private final Map<EditorBuffer, Locked> locked = new WeakHashMap<>();
    /** Buffers whose plain original is still on disk after Encrypt File: the banner offers to trash it. */
    private final Map<EditorBuffer, Path> originals = new WeakHashMap<>();
    /** Buffers whose Save As to a plain file the user has just confirmed (Remove Encryption asks once). */
    private final Set<EditorBuffer> leavingConfirmed = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    /** Plain buffers already told that their encrypted name needs recipients (autosave would repeat it). */
    private final Set<EditorBuffer> toldChooseFirst = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** The keyring as last read (off the FX thread); refreshed whenever a picker opens. */
    private volatile List<GpgKey> keys = List.of();

    /** Asked before a Save As writes an encrypted file's text unencrypted. Replaced by tests. */
    volatile Predicate<Path> confirmLeaveEncryption = this::askLeaveEncryption;

    /** Shows the recipient picker. Replaced by tests, which choose without a card. */
    volatile BiConsumer<RecipientPicker.Request, Consumer<RecipientPicker.Choice>> picker = this::showPicker;

    /** Asks for a passphrase. Replaced by tests: {@code (title, attempt)} with the cancel run on refusal. */
    volatile PassphraseAsker passphraseAsker = this::showPassphrasePrompt;

    /** How Editora asks for a passphrase (the card, or a test's stand-in). */
    @FunctionalInterface
    interface PassphraseAsker {
        void ask(
                String message,
                @Nullable String note,
                String okLabel,
                PassphrasePrompt.Attempt attempt,
                Runnable onCancel);
    }

    EncryptionCoordinator(FileWorkflowCoordinator.Host host, FileWorkflowCoordinator files) {
        this.host = host;
        this.files = files;
        List<String> retired = com.editora.plugin.RetiredPlugins.takeNotice();
        if (retired != null && !retired.isEmpty()) {
            // The first window says it once: GnuPG moved into Editora, and the plugin was removed.
            Platform.runLater(() -> host.setStatus(tr("encryption.status.pluginRetired")));
        }
    }

    // ---- gpg -------------------------------------------------------------------------------------------------

    /** The gpg to run, resolved now (off the FX thread: the lookup can read the login shell's PATH). */
    Gpg gpg() {
        return new Gpg(Gpg.resolve(host.config().getSettings().getEncryptionGpgPath()), gpgEnvironment);
    }

    private static void background(String name, Runnable work) {
        Thread.ofVirtual().name(name).start(work);
    }

    /** Reads the keyring off the FX thread, then hands it to {@code then} on the FX thread. */
    void withKeys(Consumer<List<GpgKey>> then) {
        background("gpg-keys", () -> {
            List<GpgKey> read = gpg().keys();
            keys = read;
            Platform.runLater(() -> then.accept(read));
        });
    }

    /** Whether {@code buffer} holds an encrypted file's text — the privacy guard's question. */
    static boolean sensitive(@Nullable EditorBuffer buffer) {
        return buffer != null && buffer.encryption().isEncrypted();
    }

    // ---- opening -----------------------------------------------------------------------------------------------

    /**
     * The locked load for an encrypted {@code file} (its extension says encrypted and its bytes agree), or null
     * when it is an ordinary file after all (a {@code .asc} public key). Reads the file on the caller's thread —
     * the file-load worker. Never decrypts.
     */
    static FileWorkflowCoordinator.@Nullable PreparedLoad lockedLoad(
            Path file, long size, long mtime, com.editora.editorconfig.EditorConfigProperties editorConfig)
            throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(1024);
        }
        if (!EncryptedFiles.isEncryptedFile(String.valueOf(file.getFileName()), head)) {
            return null;
        }
        byte[] bytes = size <= MAX_DECRYPT_BYTES ? Files.readAllBytes(file) : null;
        String fingerprint = bytes != null ? FileWorkflowCoordinator.fingerprint(bytes) : fingerprintOf(file);
        return FileWorkflowCoordinator.PreparedLoad.locked(file, size, mtime, editorConfig, bytes, fingerprint);
    }

    /** SHA-256 of a file too large to hold, streamed (the same form as {@code FileWorkflowCoordinator}'s). */
    private static String fingerprintOf(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Puts a locked load into {@code buffer}: the placeholder instead of the editor, its ciphertext kept here,
     * who it is for read in the background. A buffer that was showing the file's text (a reload after the file
     * changed on disk) is decrypted again straight away; a new tab waits for {@link #openedInteractively} and
     * a restored one for the user. FX thread.
     */
    String applyLocked(EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        EncryptionState state = buffer.encryption().state();
        boolean wasOpen = state != null && !state.locked();
        applyLocked(buffer, load, wasOpen);
        return "";
    }

    private void applyLocked(EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load, boolean decryptNow) {
        buffer.setDiskSnapshot(load.mtime(), load.size(), load.fingerprint());
        buffer.setTruncatedLoad(false);
        Locked file = new Locked(load.file(), load.sourceBytes(), load.size(), load.mtime(), load.fingerprint());
        Locked previous = locked.put(buffer, file);
        if (previous != null && previous.cancel != null) {
            previous.cancel.cancel();
        }
        EncryptionState state = buffer.encryption().state();
        boolean armored =
                file.ciphertext != null && EncryptedFiles.sniff(file.ciphertext) == EncryptedFiles.Kind.ARMORED_MESSAGE;
        if (state == null) {
            state = new EncryptionState(EncryptionState.Mode.PUBLIC_KEY, List.of(), armored);
            buffer.encryption().setState(state);
        } else {
            state.setArmored(armored);
        }
        state.setLock(file.ciphertext == null ? EncryptionState.Lock.TOO_LARGE : EncryptionState.Lock.RESTORED, "");
        showLocked(buffer);
        if (file.ciphertext != null) {
            byte[] message = file.ciphertext;
            background("gpg-inspect", () -> {
                PacketInfo info = gpg().inspect(message);
                List<GpgKey> read = gpg().keys();
                keys = read;
                Platform.runLater(() -> {
                    if (locked.get(buffer) == file && buffer.encryption().state() != null) {
                        buffer.encryption().state().describe(modeOf(info), recipientsOf(info, read));
                        showLocked(buffer);
                        refreshChrome(buffer);
                    }
                    file.packets.complete(info);
                });
            });
        } else {
            file.packets.complete(new PacketInfo(List.of(), false));
        }
        refreshChrome(buffer);
        if (decryptNow) {
            decrypt(buffer);
        }
    }

    /**
     * A tab the user just opened: an encrypted file decrypts now (a restored tab waits to be asked), and a
     * {@code .asc} public key offers to import itself.
     */
    void opened(EditorBuffer buffer) {
        EncryptionState state = buffer == null ? null : buffer.encryption().state();
        if (state != null && state.lock() == EncryptionState.Lock.RESTORED) {
            decrypt(buffer);
        } else if (buffer != null) {
            offerKeyImport(buffer);
        }
    }

    private static EncryptionState.Mode modeOf(PacketInfo info) {
        return info.passphraseOnly() ? EncryptionState.Mode.PASSPHRASE : EncryptionState.Mode.PUBLIC_KEY;
    }

    /** The people a message names, matched to the keyring (an unknown key keeps its id). */
    static List<EncryptionState.Recipient> recipientsOf(PacketInfo info, List<GpgKey> keyring) {
        List<EncryptionState.Recipient> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String id : info.namedKeyIds()) {
            GpgKey key = keyring.stream().filter(k -> k.matches(id)).findFirst().orElse(null);
            String ident = key == null ? id : key.fingerprint();
            if (seen.add(ident)) {
                out.add(
                        key == null
                                ? new EncryptionState.Recipient(id, "", "", false, false, false)
                                : recipientOf(key));
            }
        }
        return out;
    }

    static EncryptionState.Recipient recipientOf(GpgKey key) {
        return new EncryptionState.Recipient(
                key.fingerprint(), key.name(), key.email(), key.secret(), key.verified(), true);
    }

    /** Shows (or rebuilds) the locked placeholder for {@code buffer}'s state. */
    private void showLocked(EditorBuffer buffer) {
        EncryptionState state = buffer.encryption().state();
        Locked file = locked.get(buffer);
        if (state == null || !state.locked() || file == null) {
            return;
        }
        boolean armored = state.armored();
        EncryptionViews.LockedActions actions = new EncryptionViews.LockedActions(
                () -> decrypt(buffer),
                () -> {
                    if (file.cancel != null) {
                        file.cancel.cancel();
                    }
                },
                file.ciphertext == null ? null : () -> showEncryptedText(buffer, file, armored),
                () -> decryptToFile(buffer),
                () -> host.runCommand("view.doctor"),
                () -> askGpgPath(buffer));
        buffer.encryption()
                .setLockedView(EncryptionViews.lockedView(
                        String.valueOf(file.file.getFileName()), state, actions, file.shownText));
    }

    private void showEncryptedText(EditorBuffer buffer, Locked file, boolean armored) {
        if (!armored) {
            files.openHexTab(file.file, true); // binary ciphertext: bytes, not garbage text
            return;
        }
        file.shownText = new String(file.ciphertext, java.nio.charset.StandardCharsets.US_ASCII);
        showLocked(buffer);
    }

    private void askGpgPath(EditorBuffer buffer) {
        host.promptText(
                tr("encryption.action.setGpgPath"),
                tr("settings.encryption.gpgPath"),
                host.config().getSettings().getEncryptionGpgPath(),
                value -> {
                    host.config().getSettings().setEncryptionGpgPath(value);
                    host.requestSave();
                    host.services().syncSettingsWindow();
                    decrypt(buffer);
                });
    }

    /** Decrypts a locked buffer: gpg-agent asks for the passphrase, or Editora does when it must. FX thread. */
    void decrypt(EditorBuffer buffer) {
        Locked file = locked.get(buffer);
        EncryptionState state = buffer.encryption().state();
        if (file == null || state == null || file.ciphertext == null) {
            return;
        }
        if (state.lock() == EncryptionState.Lock.DECRYPTING) {
            return;
        }
        setLock(buffer, EncryptionState.Lock.DECRYPTING, "");
        file.packets.thenAccept(info -> Platform.runLater(() -> {
            if (locked.get(buffer) != file || buffer.isDisposed()) {
                return;
            }
            String name = String.valueOf(file.file.getFileName());
            String message = info.passphraseOnly()
                    ? tr("encryption.passphrase.forFile", name)
                    : tr("encryption.passphrase.forKey", name);
            withSecret(
                    message,
                    tr("encryption.action.decrypt"),
                    info.passphraseOnly(),
                    passphrase -> {
                        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
                        file.cancel = cancel;
                        return gpg().decrypt(file.ciphertext, passphrase, cancel);
                    },
                    (outcome, passphrase) -> background("gpg-decrypted", () -> {
                        FileWorkflowCoordinator.PreparedLoad load = files.prepareDecrypted(
                                file.file, outcome.out(), file.size, file.mtime, file.fingerprint);
                        Arrays.fill(outcome.out(), (byte) 0); // the text lives in the document now
                        Platform.runLater(() -> {
                            if (locked.get(buffer) == file && !buffer.isDisposed()) {
                                applyDecrypted(
                                        buffer, load, outcome.status(), info.passphraseOnly() ? passphrase : null);
                            }
                            wipe(passphrase);
                        });
                    }),
                    failure -> {
                        if (locked.get(buffer) == file) {
                            setLock(buffer, lockFor(failure), failure.detail());
                        }
                    });
        }));
    }

    static EncryptionState.Lock lockFor(GpgFailure failure) {
        return switch (failure.kind()) {
            case CANCELLED -> EncryptionState.Lock.CANCELLED;
            case BAD_PASSPHRASE -> EncryptionState.Lock.BAD_PASSPHRASE;
            case NO_SECRET_KEY -> EncryptionState.Lock.NO_SECRET_KEY;
            case NO_PINENTRY -> EncryptionState.Lock.NO_PINENTRY;
            case NOT_INSTALLED -> EncryptionState.Lock.NOT_INSTALLED;
            case TOO_LARGE -> EncryptionState.Lock.TOO_LARGE;
            default -> EncryptionState.Lock.FAILED;
        };
    }

    private void setLock(EditorBuffer buffer, EncryptionState.Lock lock, @Nullable String detail) {
        EncryptionState state = buffer.encryption().state();
        if (state == null || buffer.isDisposed()) {
            return;
        }
        state.setLock(lock, detail == null ? "" : detail);
        showLocked(buffer);
        refreshChrome(buffer);
    }

    /** The decrypted text is in place: the editor comes back, and the tab and status bar say so. */
    private void applyDecrypted(
            EditorBuffer buffer,
            FileWorkflowCoordinator.PreparedLoad load,
            GpgStatus status,
            char @Nullable [] passphrase) {
        EncryptionState state = buffer.encryption().state();
        if (state == null) {
            return;
        }
        if (load.binary() || load.content() == null) {
            setLock(buffer, EncryptionState.Lock.NOT_TEXT, "");
            return;
        }
        String note = files.applyPreparedLoad(buffer, load);
        state.setLock(null, "");
        if (passphrase != null) {
            state.setPassphrase(passphrase); // a passphrase-only file: saving uses it, without asking again
        }
        GpgStatus.Signature signature = status.signature();
        state.setSignature(signature == null ? null : signatureText(signature));
        locked.remove(buffer); // the ciphertext is no longer needed here: the disk snapshot remembers the file
        buffer.encryption().setLockedView(null);
        buffer.markClean();
        host.previews().ensurePreviewControls(buffer);
        host.services().restoreMarkdownMode(buffer);
        host.lspCoordinator().syncBufferWhenShown(buffer); // only when Settings let language servers read it
        buffer.goToStart();
        refreshChrome(buffer);
        if (buffer == host.activeBuffer()) {
            buffer.getArea().requestFocus();
        }
        host.setStatus(
                note.isEmpty()
                        ? tr(
                                "encryption.status.decrypted",
                                String.valueOf(load.file().getFileName()))
                        : note);
    }

    /** "Good signature from Dana Ruiz", "Bad signature from …" — for the details card and Verify. */
    static String signatureText(GpgStatus.Signature s) {
        String who = s.signer().isBlank() ? "0x" + s.keyId() : s.signer();
        return switch (s.state()) {
            case GOOD ->
                s.verified() ? tr("encryption.sig.goodVerified", who) : tr("encryption.sig.goodUnverified", who);
            case BAD -> tr("encryption.sig.bad", who);
            case EXPIRED_SIGNATURE -> tr("encryption.sig.expiredSignature", who);
            case EXPIRED_KEY -> tr("encryption.sig.expiredKey", who);
            case REVOKED_KEY -> tr("encryption.sig.revokedKey", who);
            case UNKNOWN_KEY -> tr("encryption.sig.unknownKey", who);
        };
    }

    /** Re-renders the tab header and the status bar after a buffer's encryption changed. */
    private void refreshChrome(EditorBuffer buffer) {
        Tab tab = host.tabFor(buffer);
        if (tab != null) {
            host.updateTabMeta(tab, buffer);
        }
        if (buffer == host.activeBuffer()) {
            host.refreshStatusBar();
        }
    }

    // ---- passphrases -------------------------------------------------------------------------------------------

    /**
     * Runs {@code op} — a gpg run that may need a passphrase — off the FX thread. With gpg-agent asking (the
     * default) it runs with no passphrase first; when the agent has no way to ask, or Settings say Editora asks,
     * or the message opens only with a passphrase, Editora's prompt asks and stays open on a wrong passphrase.
     * {@code onSuccess} gets the outcome and a copy of the passphrase used (null when the agent asked), which it
     * wipes or keeps; {@code onFailure} any other failure, including a cancelled prompt. Callbacks on FX, except
     * that {@code onSuccess} runs where the outcome arrives — FX — and may start background work itself.
     */
    void withSecret(
            String message,
            String okLabel,
            boolean passphraseOnly,
            Function<char @Nullable [], Gpg.Outcome> op,
            BiConsumer<Gpg.Outcome, char @Nullable []> onSuccess,
            Consumer<GpgFailure> onFailure) {
        boolean editoraAsks = passphraseOnly
                || com.editora.config.Settings.PASSPHRASE_PROMPT_EDITORA.equals(
                        host.config().getSettings().getEncryptionPassphrasePrompt());
        if (editoraAsks) {
            askAndRun(message, okLabel, null, op, onSuccess, onFailure);
            return;
        }
        background("gpg-secret", () -> {
            Gpg.Outcome outcome = op.apply(null);
            Platform.runLater(() -> {
                GpgFailure failure = outcome.failure();
                if (failure == null) {
                    onSuccess.accept(outcome, null);
                } else if (failure.kind() == GpgFailure.Kind.NO_PINENTRY) {
                    // gpg-agent cannot ask here (a terminal pinentry, no terminal): Editora asks instead.
                    askAndRun(message, okLabel, tr("encryption.passphrase.noPinentry"), op, onSuccess, onFailure);
                } else {
                    onFailure.accept(failure);
                }
            });
        });
    }

    private void askAndRun(
            String message,
            String okLabel,
            @Nullable String note,
            Function<char @Nullable [], Gpg.Outcome> op,
            BiConsumer<Gpg.Outcome, char @Nullable []> onSuccess,
            Consumer<GpgFailure> onFailure) {
        boolean[] finished = {false};
        passphraseAsker.ask(
                message,
                note,
                okLabel,
                (passphrase, result) -> {
                    char[] mine = passphrase.clone();
                    background("gpg-secret", () -> {
                        char[] forRun = mine.clone();
                        Gpg.Outcome outcome;
                        try {
                            outcome = op.apply(forRun);
                        } finally {
                            wipe(forRun);
                        }
                        Platform.runLater(() -> {
                            GpgFailure failure = outcome.failure();
                            if (failure == null) {
                                finished[0] = true;
                                result.done();
                                onSuccess.accept(outcome, mine);
                            } else if (failure.kind() == GpgFailure.Kind.BAD_PASSPHRASE) {
                                wipe(mine);
                                result.failed(tr("encryption.passphrase.wrong"));
                            } else {
                                wipe(mine);
                                finished[0] = true;
                                result.done();
                                onFailure.accept(failure);
                            }
                        });
                    });
                },
                () -> {
                    if (!finished[0]) {
                        onFailure.accept(new GpgFailure(GpgFailure.Kind.CANCELLED, List.of(), ""));
                    }
                });
    }

    private void showPassphrasePrompt(
            String message,
            @Nullable String note,
            String okLabel,
            PassphrasePrompt.Attempt attempt,
            Runnable onCancel) {
        PassphrasePrompt.show(
                host.services().overlayHost(),
                tr("encryption.passphrase.title"),
                message,
                note,
                okLabel,
                attempt,
                onCancel);
    }

    static void wipe(char @Nullable [] secret) {
        if (secret != null) {
            Arrays.fill(secret, '\0');
        }
    }

    // ---- saving -----------------------------------------------------------------------------------------------

    /** A save failed to encrypt: nothing was written. Carries gpg's reason. */
    static final class SealFailure extends IOException {
        private final transient GpgFailure failure;

        SealFailure(GpgFailure failure) {
            super(messageFor(failure));
            this.failure = failure;
        }

        GpgFailure failure() {
            return failure;
        }
    }

    /**
     * How one save encrypts: for whom (or with which passphrase) and in which format. Built on the FX thread,
     * {@link #seal sealed} on the save worker before anything touches the disk; the write then uses
     * {@link #sealed}. A seal that fails, or produces nothing, throws — the file is never written in plain text.
     */
    static final class Seal {
        /** The save must not happen (the reason is already on screen). */
        static final Seal REFUSED = new Seal(null, EncryptionState.Mode.PUBLIC_KEY, List.of(), null, false);

        private final @Nullable Supplier<Gpg> gpg;
        private final EncryptionState.Mode mode;
        private final List<String> recipients;
        private final boolean armor;
        private char @Nullable [] passphrase;
        private volatile byte @Nullable [] sealed;

        Seal(
                @Nullable Supplier<Gpg> gpg,
                EncryptionState.Mode mode,
                List<String> recipients,
                char @Nullable [] passphrase,
                boolean armor) {
            this.gpg = gpg;
            this.mode = mode;
            this.recipients = List.copyOf(recipients);
            this.passphrase = passphrase;
            this.armor = armor;
        }

        List<String> recipients() {
            return recipients;
        }

        /** Encrypts {@code plain}. Blocking: the save worker. */
        void seal(byte[] plain) throws SealFailure {
            try {
                Supplier<Gpg> source = gpg;
                if (source == null) {
                    throw new IllegalStateException("a refused save is never sealed");
                }
                Gpg g = source.get();
                Gpg.Outcome outcome = mode == EncryptionState.Mode.PASSPHRASE
                        ? g.encryptSymmetric(plain, passphrase, armor)
                        : g.encrypt(plain, recipients, armor);
                GpgFailure failure = outcome.failure();
                if (failure != null) {
                    throw new SealFailure(failure);
                }
                if (outcome.out().length == 0) {
                    throw new SealFailure(new GpgFailure(GpgFailure.Kind.OTHER, List.of(), "gpg wrote nothing"));
                }
                sealed = outcome.out();
            } finally {
                wipe(passphrase);
                passphrase = null;
            }
        }

        /** The ciphertext to write. Only after {@link #seal}: a write without it would be a plain-text write. */
        byte[] sealed() {
            byte[] out = sealed;
            if (out == null) {
                throw new IllegalStateException("an encrypted save was not sealed");
            }
            return out;
        }
    }

    /**
     * How {@code buffer} is written to {@code target}: null for plain text, a {@link Seal} for an encrypted
     * file, or {@link Seal#REFUSED} when it must not be written now (locked, no passphrase, a recipient whose key
     * is missing, a new encrypted file without recipients). The one choke point every save passes. FX thread.
     */
    @Nullable
    Seal sealFor(EditorBuffer buffer, @Nullable Path target) {
        String name = target == null ? "" : String.valueOf(target.getFileName());
        boolean encryptedName = EncryptedFiles.hasEncryptionExtension(name);
        EncryptionState state = buffer.encryption().state();
        if (state == null) {
            if (encryptedName && needsRecipients(buffer, name)) {
                if (toldChooseFirst.add(buffer)) {
                    host.setStatus(tr("encryption.status.chooseFirst", name));
                }
                return Seal.REFUSED;
            }
            return null;
        }
        if (!encryptedName) {
            return null; // Save As to a plain name, which the user confirmed (beforeSaveAs)
        }
        if (state.locked()) {
            return Seal.REFUSED;
        }
        if (state.mode() == EncryptionState.Mode.PASSPHRASE) {
            if (!state.hasPassphrase()) {
                refuse(buffer, tr("encryption.refuse.noPassphrase"), "");
                return Seal.REFUSED;
            }
            return new Seal(this::gpg, EncryptionState.Mode.PASSPHRASE, List.of(), state.passphrase(), state.armored());
        }
        List<EncryptionState.Recipient> missing = state.missingKeys();
        if (!missing.isEmpty()) {
            refuse(buffer, tr("encryption.refuse.missingKey", EncryptionViews.people(missing)), "");
            return Seal.REFUSED;
        }
        if (state.recipients().isEmpty()) {
            refuse(buffer, tr("encryption.refuse.noRecipients"), "");
            return Seal.REFUSED;
        }
        List<String> ids =
                state.recipients().stream().map(EncryptionState.Recipient::id).toList();
        return new Seal(this::gpg, EncryptionState.Mode.PUBLIC_KEY, ids, null, state.armored());
    }

    /**
     * Whether a plain buffer saved under an encrypted name must choose recipients first: a {@code .gpg} or
     * {@code .pgp} file always; a {@code .asc} file unless it holds armor of its own (a public key, a
     * signature), which is what a plain {@code .asc} file usually is.
     */
    private static boolean needsRecipients(EditorBuffer buffer, String name) {
        if (!"asc".equals(EncryptedFiles.extensionOf(name))) {
            return true;
        }
        return !buffer.getContent().stripLeading().startsWith("-----BEGIN PGP ");
    }

    /** Shows the "Not saved." banner with the next steps for {@code reason}. */
    private void refuse(EditorBuffer buffer, @Nullable String reason, @Nullable String detail) {
        List<EncryptionViews.BannerAction> actions = new ArrayList<>();
        actions.add(new EncryptionViews.BannerAction(
                tr("encryption.action.changeRecipients"), () -> changeRecipients(buffer)));
        actions.add(new EncryptionViews.BannerAction(tr("encryption.action.importKey"), () -> importPublicKey(null)));
        buffer.encryption()
                .setNotice(EncryptionViews.saveFailedBanner(
                        reason, actions, detail, () -> buffer.encryption().setNotice(null)));
        host.setStatus(tr("encryption.banner.notSaved") + " " + reason);
    }

    /** A sealed save failed (on the worker); the file is unchanged. FX thread. */
    void saveFailed(EditorBuffer buffer, SealFailure failure) {
        if (buffer.isDisposed()) {
            return;
        }
        refuse(buffer, failure.getMessage(), failure.failure().detail());
    }

    /** The sentence for a failed encryption, in the reader's language. */
    static String messageFor(GpgFailure failure) {
        return switch (failure.kind()) {
            case NOT_INSTALLED -> tr("encryption.fail.notInstalled");
            case MISSING_PUBLIC_KEY -> tr("encryption.fail.missingKey");
            case UNUSABLE_RECIPIENT -> tr("encryption.fail.unusableKey");
            case CANCELLED -> tr("encryption.fail.cancelled");
            case BAD_PASSPHRASE -> tr("encryption.passphrase.wrong");
            case NO_PINENTRY -> tr("encryption.locked.noPinentry");
            case NO_SECRET_KEY -> tr("encryption.locked.noSecretKey");
            case TIMED_OUT -> tr("encryption.fail.timedOut");
            case TOO_LARGE -> tr("encryption.locked.tooLarge");
            default -> tr("encryption.fail.other");
        };
    }

    /** A save reached the disk. FX thread. */
    void saved(EditorBuffer buffer, Path target, @Nullable Seal seal) {
        EncryptionState state = buffer.encryption().state();
        toldChooseFirst.remove(buffer);
        if (state == null || buffer.isDisposed()) {
            return;
        }
        if (!EncryptedFiles.hasEncryptionExtension(String.valueOf(target.getFileName()))) {
            // Saved as a plain file (confirmed): this buffer is an ordinary file from now on.
            buffer.encryption().setState(null);
            buffer.encryption().setNotice(null);
            host.lspCoordinator().syncBufferWhenShown(buffer);
            refreshChrome(buffer);
            return;
        }
        if (seal != null && seal != Seal.REFUSED) {
            state.recipientsSaved();
            buffer.encryption().setNotice(null);
            if (state.mode() == EncryptionState.Mode.PUBLIC_KEY
                    && !seal.recipients().isEmpty()) {
                host.config().getSettings().rememberEncryptionRecipients(seal.recipients());
                host.requestSave();
            }
        }
        Path original = originals.remove(buffer);
        if (original != null) {
            offerToTrashOriginal(buffer, original, state);
        }
        refreshChrome(buffer);
    }

    /**
     * Before an explicit Save: a plain buffer under an encrypted name (a new {@code notes.md.gpg}) chooses who it
     * is for first, then {@code retry} saves. False when the picker took over. FX thread.
     */
    boolean beforeSave(EditorBuffer buffer, Path target, Runnable retry) {
        if (buffer.encryption().isEncrypted() || target == null) {
            return true;
        }
        String name = String.valueOf(target.getFileName());
        if (!EncryptedFiles.hasEncryptionExtension(name) || !needsRecipients(buffer, name)) {
            return true;
        }
        chooseRecipients(
                buffer,
                tr("encryption.picker.title", EncryptedFiles.innerName(name)),
                false,
                EncryptedFiles.armoredByName(name),
                false,
                choice -> {
                    applyChoice(buffer, choice, false);
                    retry.run();
                });
        return false;
    }

    /**
     * Before a Save As: saving an encrypted file under a plain name is confirmed (its text would be written
     * unencrypted); a plain buffer saved under an encrypted name chooses recipients first, then {@code retry}.
     * False when the save must wait or not happen. FX thread.
     */
    boolean beforeSaveAs(EditorBuffer buffer, Path target, Runnable retry) {
        String name = String.valueOf(target.getFileName());
        boolean encryptedName = EncryptedFiles.hasEncryptionExtension(name);
        EncryptionState state = buffer.encryption().state();
        if (state != null) {
            if (!encryptedName) {
                if (leavingConfirmed.remove(buffer)) {
                    return true;
                }
                return confirmLeaveEncryption.test(target);
            }
            if (!target.equals(buffer.getPath())) {
                state.setArmored(EncryptedFiles.armoredByName(name)); // the new name says which format
            }
            return true;
        }
        if (!encryptedName || !needsRecipients(buffer, name)) {
            return true;
        }
        chooseRecipients(
                buffer,
                tr("encryption.picker.title", EncryptedFiles.innerName(name)),
                false,
                EncryptedFiles.armoredByName(name),
                false,
                choice -> {
                    applyChoice(buffer, choice, false);
                    retry.run();
                });
        return false;
    }

    private boolean askLeaveEncryption(Path target) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.stage());
        alert.setTitle(tr("encryption.leave.title"));
        alert.setHeaderText(tr("encryption.leave.header"));
        alert.setContentText(tr("encryption.leave.body", String.valueOf(target.getFileName())));
        ButtonType save = new ButtonType(tr("encryption.leave.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, save);
        // Cancel is the default: Enter must never be the key that writes a secret in plain text.
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(save)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        Dialogs.styled(alert);
        return alert.showAndWait().orElse(cancel) == save;
    }

    /**
     * Asked before an export, a print or an external tool writes or hands over an encrypted file's decrypted
     * text: once per tab. True to go ahead (always, for an ordinary file). FX thread.
     */
    boolean confirmPlaintextOut(EditorBuffer buffer, String what) {
        if (!sensitive(buffer) || plaintextOutConfirmed.contains(buffer)) {
            return true;
        }
        if (plaintextOutConfirm.test(what)) {
            plaintextOutConfirmed.add(buffer);
            return true;
        }
        return false;
    }

    private final Set<EditorBuffer> plaintextOutConfirmed = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** Asked by {@link #confirmPlaintextOut}. Replaced by tests. */
    volatile Predicate<String> plaintextOutConfirm = this::askPlaintextOut;

    private boolean askPlaintextOut(String what) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(host.stage());
        alert.setTitle(tr("encryption.out.title"));
        alert.setHeaderText(what);
        alert.setContentText(tr("encryption.out.body"));
        ButtonType go = new ButtonType(tr("encryption.out.continue"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(cancel, go);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(go)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        Dialogs.styled(alert);
        return alert.showAndWait().orElse(cancel) == go;
    }

    // ---- recipients -------------------------------------------------------------------------------------------

    /** The user's own key: the one Settings name, else their first secret key that can encrypt; or null. */
    @Nullable
    GpgKey selfKey(List<GpgKey> keyring) {
        String chosen = host.config().getSettings().getEncryptionDefaultKey();
        if (!chosen.isBlank()) {
            for (GpgKey k : keyring) {
                if (k.secret() && k.canEncrypt() && k.matches(chosen)) {
                    return k;
                }
            }
        }
        return keyring.stream()
                .filter(k -> k.secret() && k.canEncrypt())
                .findFirst()
                .orElse(null);
    }

    /**
     * Opens the picker for {@code buffer} (after reading the keyring): pre-filled with its recipients, or for a
     * file not encrypted yet the folder's {@code .gpg-id}, else the user's own key.
     */
    private void chooseRecipients(
            EditorBuffer buffer,
            String title,
            boolean applyOnSave,
            boolean armor,
            boolean formatShown,
            Consumer<RecipientPicker.Choice> onChoice) {
        Path dir = buffer.getPath() == null ? null : buffer.getPath().getParent();
        background("gpg-picker", () -> {
            List<GpgKey> keyring = gpg().keys();
            keys = keyring;
            List<String> folderIds = dir == null ? List.of() : GpgIdFiles.find(dir);
            Platform.runLater(() -> {
                EncryptionState state = buffer.encryption().state();
                Set<String> pre = new LinkedHashSet<>();
                if (state != null && !state.recipients().isEmpty()) {
                    state.recipients()
                            .forEach(r -> keyring.stream()
                                    .filter(k -> k.matches(r.id()))
                                    .findFirst()
                                    .ifPresent(k -> pre.add(k.fingerprint())));
                } else {
                    for (String id : folderIds) {
                        keyring.stream()
                                .filter(k -> k.matches(id))
                                .findFirst()
                                .ifPresent(k -> pre.add(k.fingerprint()));
                    }
                    GpgKey self = selfKey(keyring);
                    if (self != null && host.config().getSettings().isEncryptionIncludeSelf()) {
                        pre.add(self.fingerprint());
                    }
                }
                EncryptionState.Mode mode = state == null ? EncryptionState.Mode.PUBLIC_KEY : state.mode();
                String folder = dir == null || dir.getFileName() == null || !com.editora.vfs.Vfs.isLocal(dir)
                        ? null
                        : dir.getFileName() + "/";
                picker.accept(
                        new RecipientPicker.Request(
                                title,
                                keyring,
                                pre,
                                host.config().getSettings().getEncryptionRecentRecipients(),
                                mode,
                                applyOnSave,
                                formatShown,
                                armor,
                                folder),
                        choice -> {
                            if (choice.rememberForFolder() && dir != null) {
                                writeGpgId(dir, choice.keys());
                            }
                            onChoice.accept(choice);
                        });
            });
        });
    }

    private void showPicker(RecipientPicker.Request request, Consumer<RecipientPicker.Choice> onChoice) {
        RecipientPicker.show(
                host.services().overlayHost(),
                request,
                onChoice,
                () -> importPublicKey(() -> showPicker(request.withKeys(keys), onChoice)),
                null);
    }

    private void writeGpgId(Path dir, List<GpgKey> chosen) {
        List<String> ids = chosen.stream().map(GpgKey::fingerprint).toList();
        background("gpg-id", () -> {
            try {
                GpgIdFiles.write(dir, ids);
                Platform.runLater(() -> host.setStatus(tr("encryption.status.gpgIdWritten", dir.getFileName())));
            } catch (IOException e) {
                Platform.runLater(() -> host.services().setError(tr("encryption.status.gpgIdFailed", e.getMessage())));
            }
        });
    }

    /** Gives {@code buffer} the encryption the user chose (a pending change until the next save). */
    private void applyChoice(EditorBuffer buffer, RecipientPicker.Choice choice, boolean markDirty) {
        List<EncryptionState.Recipient> people =
                choice.keys().stream().map(EncryptionCoordinator::recipientOf).toList();
        EncryptionState state = buffer.encryption().state();
        if (state == null) {
            state = new EncryptionState(choice.mode(), people, choice.armor());
            buffer.encryption().setState(state);
        }
        state.changeRecipients(choice.mode(), people, choice.passphrase());
        wipe(choice.passphrase());
        buffer.encryption().setNotice(null);
        if (markDirty) {
            buffer.markUnsaved();
        }
        refreshChrome(buffer);
    }

    // ---- commands ---------------------------------------------------------------------------------------------

    /** {@code crypto.showInfo}: the details card, anchored to the status-bar item. */
    void showInfo() {
        EditorBuffer buffer = host.activeBuffer();
        EncryptionState state = buffer == null ? null : buffer.encryption().state();
        if (state == null) {
            host.setStatus(tr("encryption.status.notEncrypted"));
            return;
        }
        OverlayHost overlay = host.services().overlayHost();
        javafx.scene.layout.VBox card = EncryptionViews.infoCard(
                state,
                new EncryptionViews.InfoActions(
                        () -> changeRecipients(buffer), () -> removeEncryption(buffer), () -> lockFile(buffer)),
                overlay::hide);
        javafx.scene.Node first = (javafx.scene.Node) card.getProperties().get("editora.firstFocus");
        javafx.scene.Node anchor = host.encryptionStatusItem();
        Runnable focus = () -> {
            if (first != null) {
                first.requestFocus();
            }
        };
        if (anchor != null && anchor.isVisible() && anchor.getScene() != null) {
            overlay.show(card, anchor, focus, () -> {});
        } else {
            overlay.show(card, true, focus, () -> {});
        }
    }

    /** {@code crypto.encryptFile}: encrypts the active file into {@code name.gpg} beside it. */
    void encryptFile() {
        encryptBuffer(host.activeBuffer());
    }

    /** The Project tree's Encrypt…: opens the file, then encrypts it. */
    void encryptFile(Path file) {
        files.openThen(file, () -> encryptBuffer(host.activeBuffer()));
    }

    private void encryptBuffer(@Nullable EditorBuffer buffer) {
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        if (buffer.encryption().isEncrypted()) {
            changeRecipients(buffer); // already encrypted: the next useful thing is who it is for
            return;
        }
        if (files.refuseUnsavable(buffer)) {
            return;
        }
        Path original = buffer.getPath();
        if (original == null) {
            // Untitled: the name decides. Save As to a .gpg/.asc name opens the picker (beforeSaveAs).
            host.setStatus(tr("encryption.status.untitled"));
            files.saveAsPrompt(buffer);
            return;
        }
        String name = String.valueOf(original.getFileName());
        chooseRecipients(buffer, tr("encryption.picker.title", name), false, false, true, choice -> {
            Path target = EncryptedFiles.encryptedSibling(original, choice.armor() ? "asc" : "gpg");
            if (Files.exists(target) && !files.confirmOverwrite(target)) {
                wipe(choice.passphrase());
                return;
            }
            applyChoice(buffer, choice, false);
            originals.put(buffer, original);
            if (!files.applySaveAsTarget(buffer, target)) {
                // Refused before anything was written (the target is open elsewhere): still a plain file.
                originals.remove(buffer);
                buffer.encryption().setState(null);
                refreshChrome(buffer);
            }
        });
    }

    private void offerToTrashOriginal(EditorBuffer buffer, Path original, EncryptionState state) {
        if (!Files.exists(original)) {
            return;
        }
        background("gpg-original", () -> {
            boolean tracked = isTrackedByGit(original);
            Platform.runLater(() -> {
                String message = tr(
                                "encryption.original.message",
                                EncryptionViews.readableBy(state),
                                String.valueOf(original.getFileName()))
                        + (tracked ? " " + tr("encryption.original.git") : "");
                List<EncryptionViews.BannerAction> actions = List.of(
                        new EncryptionViews.BannerAction(tr("encryption.original.trash"), () -> {
                            buffer.encryption().setNotice(null);
                            trashOriginal(original);
                        }),
                        new EncryptionViews.BannerAction(
                                tr("encryption.original.keep"),
                                () -> buffer.encryption().setNotice(null)));
                buffer.encryption()
                        .setNotice(EncryptionViews.originalStillOnDiskBanner(
                                message, actions, () -> buffer.encryption().setNotice(null)));
            });
        });
    }

    /** The trash the plain original goes to; a test puts its own in. */
    volatile com.editora.io.Trash.@Nullable Bin trash = null;

    private void trashOriginal(Path original) {
        com.editora.io.Trash.Bin bin = trash != null ? trash : com.editora.io.Trash.system();
        background("gpg-trash", () -> {
            try {
                bin.trash(original);
                Platform.runLater(() -> host.setStatus(tr("encryption.original.trashed", original.getFileName())));
            } catch (IOException e) {
                Platform.runLater(
                        () -> host.services().setError(tr("encryption.original.trashFailed", e.getMessage())));
            }
        });
    }

    private static boolean isTrackedByGit(Path file) {
        if (!com.editora.vfs.Vfs.isLocal(file) || file.getParent() == null) {
            return false;
        }
        ProcessRunner.Result r = ProcessRunner.run(
                file.getParent(),
                java.time.Duration.ofSeconds(10),
                List.of("git", "ls-files", "--error-unmatch", "--", String.valueOf(file.getFileName())));
        return r.ok();
    }

    /** {@code crypto.changeRecipients}: who the active encrypted file is for, applied on the next save. */
    void changeRecipients() {
        changeRecipients(host.activeBuffer());
    }

    void changeRecipients(@Nullable EditorBuffer buffer) {
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        EncryptionState state = buffer.encryption().state();
        if (state == null) {
            encryptBuffer(buffer);
            return;
        }
        if (state.locked()) {
            host.setStatus(tr("status.encryption.lockedNoSave", buffer.getTitle()));
            return;
        }
        String name = buffer.getPath() == null
                ? buffer.getTitle()
                : String.valueOf(buffer.getPath().getFileName());
        chooseRecipients(
                buffer,
                tr("encryption.picker.recipientsTitle", name),
                true,
                state.armored(),
                false,
                choice -> applyChoice(buffer, choice, true));
    }

    /** {@code crypto.removeEncryption}: saves the active encrypted file's text as a plain file beside it. */
    void removeEncryption() {
        removeEncryption(host.activeBuffer());
    }

    void removeEncryption(@Nullable EditorBuffer buffer) {
        if (buffer == null || !sensitive(buffer)) {
            host.setStatus(tr("encryption.status.notEncrypted"));
            return;
        }
        if (files.refuseUnsavable(buffer) || buffer.getPath() == null) {
            return;
        }
        Path target = EncryptedFiles.plainSibling(buffer.getPath());
        if (!confirmLeaveEncryption.test(target)) {
            return;
        }
        if (Files.exists(target) && !files.confirmOverwrite(target)) {
            return;
        }
        leavingConfirmed.add(buffer);
        files.applySaveAsTarget(buffer, target);
    }

    /** {@code crypto.lockFile}: puts the active encrypted file away — its text leaves memory; Decrypt brings it back. */
    void lockFile() {
        lockFile(host.activeBuffer());
    }

    void lockFile(@Nullable EditorBuffer buffer) {
        if (buffer == null || !sensitive(buffer) || buffer.getPath() == null) {
            host.setStatus(tr("encryption.status.notEncrypted"));
            return;
        }
        if (buffer.encryption().isLocked()) {
            return;
        }
        if (buffer.isDirty()) {
            host.setStatus(tr("encryption.status.saveBeforeLock", buffer.getTitle()));
            return;
        }
        Path file = buffer.getPath();
        background("gpg-lock", () -> {
            try {
                FileWorkflowCoordinator.PreparedLoad load = files.prepareLoad(file, false);
                Platform.runLater(() -> {
                    if (buffer.isDisposed() || !load.encrypted()) {
                        return;
                    }
                    buffer.setInitialContent(""); // the text (and its undo history) leaves the document
                    buffer.markClean();
                    applyLocked(buffer, load, false);
                    host.lspCoordinator().syncBufferWhenShown(buffer);
                    host.setStatus(tr("encryption.status.locked", file.getFileName()));
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(
                        () -> host.services().setError(tr("status.failedReload", file.getFileName(), e.getMessage())));
            }
        });
    }

    /** {@code crypto.encryptSelection}: replaces the selection with an armored message for the chosen people. */
    void encryptSelection() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isEditable()) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        var area = buffer.getArea();
        String selected = area.getSelectedText();
        if (selected.isEmpty()) {
            host.setStatus(tr("encryption.status.selectFirst"));
            return;
        }
        int start = area.getSelection().getStart();
        int end = area.getSelection().getEnd();
        long version = buffer.docVersion();
        chooseRecipientsFor(buffer, tr("encryption.picker.selectionTitle"), choice -> {
            char[] passphrase = choice.passphrase();
            List<String> ids = choice.keys().stream().map(GpgKey::fingerprint).toList();
            byte[] plain = selected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            background("gpg-encrypt-selection", () -> {
                Gpg.Outcome o = choice.mode() == EncryptionState.Mode.PASSPHRASE
                        ? gpg().encryptSymmetric(plain, passphrase, true)
                        : gpg().encrypt(plain, ids, true);
                wipe(passphrase);
                Platform.runLater(() -> {
                    GpgFailure failure = o.failure();
                    if (failure != null) {
                        host.services().setError(messageFor(failure));
                    } else if (buffer.docVersion() != version) {
                        host.setStatus(tr("encryption.status.changedMeanwhile"));
                    } else {
                        area.replaceText(start, end, o.text());
                        host.setStatus(tr("encryption.status.selectionEncrypted"));
                    }
                });
            });
        });
    }

    private void chooseRecipientsFor(EditorBuffer buffer, String title, Consumer<RecipientPicker.Choice> onChoice) {
        background("gpg-picker", () -> {
            List<GpgKey> keyring = gpg().keys();
            keys = keyring;
            Platform.runLater(() -> {
                Set<String> pre = new LinkedHashSet<>();
                GpgKey self = selfKey(keyring);
                if (self != null && host.config().getSettings().isEncryptionIncludeSelf()) {
                    pre.add(self.fingerprint());
                }
                picker.accept(
                        new RecipientPicker.Request(
                                title,
                                keyring,
                                pre,
                                host.config().getSettings().getEncryptionRecentRecipients(),
                                EncryptionState.Mode.PUBLIC_KEY,
                                false,
                                false,
                                true,
                                null),
                        onChoice);
            });
        });
    }

    /**
     * {@code crypto.decryptBlock}: decrypts the armored message at the caret and shows it in a read-only card
     * (Copy, Replace Block with Text). Decrypting in place would put plain text into a plain file that the next
     * save writes, so replacing is the deliberate second step.
     */
    void decryptBlock() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        String text = buffer.getContent();
        EncryptedFiles.Block block =
                EncryptedFiles.blockAt(text, buffer.getArea().getCaretPosition());
        if (block == null) {
            host.setStatus(tr("encryption.status.noBlock"));
            return;
        }
        String armored = text.substring(block.start(), block.end());
        byte[] message = armored.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        long version = buffer.docVersion();
        background("gpg-block", () -> {
            PacketInfo info = gpg().inspect(message);
            Platform.runLater(() -> withSecret(
                    tr("encryption.passphrase.forBlock"),
                    tr("encryption.action.decrypt"),
                    info.passphraseOnly(),
                    passphrase -> gpg().decrypt(message, passphrase, null),
                    (outcome, passphrase) -> {
                        wipe(passphrase);
                        String plain = new String(outcome.out(), java.nio.charset.StandardCharsets.UTF_8);
                        Arrays.fill(outcome.out(), (byte) 0);
                        showPeek(buffer, block, version, plain, outcome.status());
                    },
                    failure -> {
                        if (failure.kind() != GpgFailure.Kind.CANCELLED) {
                            host.services().setError(messageFor(failure));
                        }
                    }));
        });
    }

    private void showPeek(
            EditorBuffer buffer, EncryptedFiles.Block block, long version, String plain, GpgStatus status) {
        OverlayHost overlay = host.services().overlayHost();
        javafx.scene.control.Label title = new javafx.scene.control.Label(tr("encryption.peek.title"));
        title.getStyleClass().add("palette-title");
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(plain);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(Math.min(16, Math.max(4, plain.split("\n", -1).length)));
        area.getStyleClass().add("encryption-armor");
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(10, title);
        GpgStatus.Signature signature = status.signature();
        if (signature != null) {
            card.getChildren().add(new javafx.scene.control.Label(signatureText(signature)));
        }
        javafx.scene.control.Button copy = new javafx.scene.control.Button(tr("encryption.peek.copy"));
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
            c.putString(plain);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            host.setStatus(tr("encryption.peek.copied"));
        });
        javafx.scene.control.Button replace = new javafx.scene.control.Button(tr("encryption.peek.replace"));
        replace.setDisable(!buffer.isEditable());
        replace.setOnAction(e -> {
            overlay.hide();
            if (buffer.docVersion() != version) {
                host.setStatus(tr("encryption.status.changedMeanwhile"));
                return;
            }
            buffer.getArea().replaceText(block.start(), block.end(), plain);
            host.setStatus(sensitive(buffer) ? tr("encryption.peek.replaced") : tr("encryption.peek.replacedPlain"));
        });
        javafx.scene.control.Button close = new javafx.scene.control.Button(tr("encryption.peek.close"));
        close.getStyleClass().add("accent");
        close.setOnAction(e -> overlay.hide());
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(8, replace, spacer, copy, close);
        card.getChildren().addAll(area, footer);
        card.getStyleClass().addAll("command-palette", "overlay-form", "encryption-peek");
        card.setPadding(new javafx.geometry.Insets(14));
        card.setPrefWidth(560);
        card.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        overlay.show(card, true, close::requestFocus, () -> area.clear());
    }

    /** {@code crypto.importPublicKey}: imports keys from a file into the keyring; {@code then} runs after. */
    void importPublicKey(@Nullable Runnable then) {
        EditorBuffer active = host.activeBuffer();
        if (active != null && !sensitive(active) && EncryptedFiles.isArmoredPublicKey(active.getContent())) {
            importKeyBytes(active.getContent().getBytes(java.nio.charset.StandardCharsets.US_ASCII), then);
            return; // the open file is a public key: import that one
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("command." + IMPORT_KEY));
        chooser.getExtensionFilters()
                .addAll(
                        new FileChooser.ExtensionFilter(
                                tr("encryption.import.keyFiles"), "*.asc", "*.gpg", "*.pgp", "*.key"),
                        new FileChooser.ExtensionFilter(tr("encryption.import.allFiles"), "*"));
        Path chosen = host.pathOf(chooser.showOpenDialog(host.stage()));
        if (chosen == null) {
            return;
        }
        background("gpg-import-read", () -> {
            try {
                byte[] bytes = Files.readAllBytes(chosen);
                Platform.runLater(() -> importKeyBytes(bytes, then));
            } catch (IOException e) {
                Platform.runLater(() -> host.services().setError(tr("encryption.import.failed", e.getMessage())));
            }
        });
    }

    void importKeyBytes(byte[] bytes, @Nullable Runnable then) {
        background("gpg-import", () -> {
            Gpg.Outcome o = gpg().importKeys(bytes);
            List<GpgKey> keyring = gpg().keys();
            keys = keyring;
            GpgStatus.Line res = o.status().first("IMPORT_RES");
            Platform.runLater(() -> {
                if (res == null) {
                    host.services().setError(tr("encryption.import.none"));
                } else {
                    String imported = res.arg(2);
                    String unchanged = res.arg(4);
                    host.setStatus(
                            "0".equals(imported)
                                    ? tr("encryption.import.unchanged", unchanged)
                                    : tr("encryption.import.done", imported));
                }
                refreshKnownKeys(keyring);
                if (then != null) {
                    then.run();
                }
            });
        });
    }

    /** After the keyring changed: open encrypted files learn which recipients' keys are here now. */
    private void refreshKnownKeys(List<GpgKey> keyring) {
        host.services().forEachBuffer(b -> {
            EncryptionState state = b.encryption().state();
            if (state == null || state.recipients().isEmpty()) {
                return;
            }
            List<EncryptionState.Recipient> updated = new ArrayList<>();
            for (EncryptionState.Recipient r : state.recipients()) {
                GpgKey key = keyring.stream()
                        .filter(k -> k.matches(r.id()))
                        .findFirst()
                        .orElse(null);
                updated.add(key == null ? r : recipientOf(key));
            }
            state.describe(state.mode(), updated);
            if (state.missingKeys().isEmpty()) {
                b.encryption().setNotice(null);
            }
            refreshChrome(b);
        });
    }

    /** The details card's "This is a public key" notice for an opened {@code .asc} key file. */
    void offerKeyImport(EditorBuffer buffer) {
        if (buffer.getPath() == null
                || sensitive(buffer)
                || !"asc"
                        .equals(EncryptedFiles.extensionOf(
                                String.valueOf(buffer.getPath().getFileName())))
                || !EncryptedFiles.isArmoredPublicKey(buffer.getContent())) {
            return;
        }
        buffer.showFileNotice(
                tr("encryption.import.offer"),
                tr("encryption.import.offerAction"),
                tr("install.banner.dismiss"),
                () -> {
                    buffer.hideFileNotice();
                    importKeyBytes(buffer.getContent().getBytes(java.nio.charset.StandardCharsets.US_ASCII), null);
                });
    }

    /**
     * {@code crypto.decryptToFile}: writes the active encrypted file's decrypted content to a file the user
     * chooses — for content that is not text, or too large for the editor. Plain text on disk, by choice.
     */
    void decryptToFile() {
        decryptToFile(host.activeBuffer());
    }

    void decryptToFile(@Nullable EditorBuffer buffer) {
        if (buffer == null || !sensitive(buffer) || buffer.getPath() == null) {
            host.setStatus(tr("encryption.status.notEncrypted"));
            return;
        }
        Path source = buffer.getPath();
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("command." + DECRYPT_TO_FILE));
        chooser.setInitialFileName(EncryptedFiles.innerName(String.valueOf(source.getFileName())));
        if (com.editora.vfs.Vfs.isLocal(source) && source.getParent() != null) {
            chooser.setInitialDirectory(source.getParent().toFile());
        }
        Path target = host.pathOf(chooser.showSaveDialog(host.stage()));
        if (target == null) {
            return;
        }
        background("gpg-decrypt-file-read", () -> {
            boolean local = com.editora.vfs.Vfs.isLocal(source);
            Platform.runLater(() -> withSecret(
                    tr("encryption.passphrase.forFile", source.getFileName()),
                    tr("encryption.action.decrypt"),
                    passphraseOnly(buffer),
                    passphrase -> gpg().decryptToFile(
                                    local ? source : null, local ? null : readQuietly(source), target, passphrase),
                    (outcome, passphrase) -> {
                        wipe(passphrase);
                        host.setStatus(tr("encryption.status.decryptedTo", target.getFileName()));
                    },
                    failure -> {
                        if (failure.kind() != GpgFailure.Kind.CANCELLED) {
                            host.services().setError(messageFor(failure));
                        }
                    }));
        });
    }

    /** Whether {@code buffer}'s file opens with a passphrase only; false once it is no longer encrypted. */
    private static boolean passphraseOnly(EditorBuffer buffer) {
        EncryptionState state = buffer.encryption().state();
        return state != null && state.mode() == EncryptionState.Mode.PASSPHRASE;
    }

    private static byte[] readQuietly(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** {@code crypto.clearSign}: wraps the active buffer's text in a clear-text signature (one undo step). */
    void clearSign() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isEditable()) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        String text = buffer.getContent();
        if (EncryptedFiles.isClearSigned(text)) {
            host.setStatus(tr("encryption.status.alreadySigned"));
            return;
        }
        long version = buffer.docVersion();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signer = host.config().getSettings().getEncryptionDefaultKey();
        withSecret(
                tr("encryption.passphrase.forSigning"),
                tr("encryption.action.sign"),
                false,
                passphrase -> gpg().clearSign(bytes, signer.isBlank() ? null : signer, passphrase),
                (outcome, passphrase) -> {
                    wipe(passphrase);
                    if (buffer.docVersion() != version) {
                        host.setStatus(tr("encryption.status.changedMeanwhile"));
                        return;
                    }
                    buffer.getArea().replaceText(0, buffer.getArea().getLength(), outcome.text());
                    host.setStatus(tr("encryption.status.signed"));
                },
                failure -> {
                    if (failure.kind() != GpgFailure.Kind.CANCELLED) {
                        host.services().setError(messageFor(failure));
                    }
                });
    }

    /**
     * {@code crypto.signDetached}: writes {@code file.asc}, a detached signature over the active file <em>as
     * saved</em> — the bytes on disk, which a signature must match — so the file must be saved first.
     */
    void signDetached() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        if (buffer.isDirty()) {
            host.setStatus(tr("encryption.status.saveBeforeSigning"));
            return;
        }
        Path file = buffer.getPath();
        Path target = file.resolveSibling(file.getFileName() + ".asc");
        if (Files.exists(target) && !files.confirmOverwrite(target)) {
            return;
        }
        String signer = host.config().getSettings().getEncryptionDefaultKey();
        background("gpg-sign-read", () -> {
            byte[] data = readQuietly(file);
            Platform.runLater(() -> withSecret(
                    tr("encryption.passphrase.forSigning"),
                    tr("encryption.action.sign"),
                    false,
                    passphrase -> gpg().detachSign(data, signer.isBlank() ? null : signer, passphrase, true),
                    (outcome, passphrase) -> {
                        wipe(passphrase);
                        byte[] signature = outcome.out();
                        background("gpg-sign-write", () -> {
                            try {
                                Files.write(target, signature);
                                Platform.runLater(
                                        () -> host.setStatus(tr("encryption.status.signedTo", target.getFileName())));
                            } catch (IOException e) {
                                Platform.runLater(() -> host.services().setError(e.getMessage()));
                            }
                        });
                    },
                    failure -> {
                        if (failure.kind() != GpgFailure.Kind.CANCELLED) {
                            host.services().setError(messageFor(failure));
                        }
                    }));
        });
    }

    /**
     * {@code crypto.verifySignature}: checks the active file's signature — a clear-signed text, the file's
     * detached {@code .asc}/{@code .sig} beside it, or, for an open signature file, the file it signs.
     */
    void verifySignature() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            host.setStatus(tr("status.noFileOpen"));
            return;
        }
        String text = buffer.getContent();
        Path path = buffer.getPath();
        background("gpg-verify", () -> {
            Gpg.Outcome o = null;
            try {
                if (EncryptedFiles.isClearSigned(text)) {
                    o = gpg().verify(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else if (path != null) {
                    Path[] pair = detachedPair(path, text);
                    if (pair != null) {
                        o = gpg().verifyDetached(Files.readAllBytes(pair[0]), Files.readAllBytes(pair[1]));
                    }
                }
            } catch (IOException e) {
                Platform.runLater(() -> host.services().setError(e.getMessage()));
                return;
            }
            Gpg.Outcome result = o;
            Platform.runLater(() -> {
                if (result == null) {
                    host.setStatus(tr("encryption.status.noSignature"));
                    return;
                }
                GpgStatus.Signature s = result.status().signature();
                if (s == null) {
                    host.services()
                            .setError(
                                    result.failure() == null
                                            ? tr("encryption.status.noSignature")
                                            : messageFor(result.failure()));
                } else if (s.state() == GpgStatus.Signature.State.GOOD) {
                    host.setStatus(signatureText(s));
                } else {
                    host.services().setError(signatureText(s));
                }
            });
        });
    }

    /** {signature, data} for a detached signature next to {@code path} or of it; null when there is none. */
    private static Path @Nullable [] detachedPair(Path path, String text) {
        String name = String.valueOf(path.getFileName());
        boolean signatureFile = name.endsWith(".sig")
                || name.endsWith(".asc") && text.stripLeading().startsWith("-----BEGIN PGP SIGNATURE");
        if (signatureFile) {
            Path data = path.resolveSibling(name.substring(0, name.lastIndexOf('.')));
            return Files.isRegularFile(data) ? new Path[] {path, data} : null;
        }
        for (String ext : List.of(".asc", ".sig")) {
            Path sig = path.resolveSibling(name + ext);
            if (Files.isRegularFile(sig)) {
                return new Path[] {sig, path};
            }
        }
        return null;
    }
}
