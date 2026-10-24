package com.editora.editor.crypto;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What an encrypted buffer knows about its file's encryption: who can read it, how it is written, and whether
 * its text is here yet. A buffer with an {@code EncryptionState} is <em>sensitive</em>: every feature that would
 * copy its text somewhere (crash recovery, Local History, a language server, an agent) asks first. Saving
 * re-encrypts for the same recipients in the same format. FX thread only.
 *
 * <p>For a passphrase-only file the passphrase stays here while the tab is open, so a save does not ask for it
 * again; it is wiped when the tab closes ({@link #wipe}).
 */
public final class EncryptionState {

    /** How the file is encrypted. */
    public enum Mode {
        /** For one or more people's public keys. */
        PUBLIC_KEY,
        /** With a passphrase only. */
        PASSPHRASE
    }

    /** Why a locked buffer has no text yet; null for an unlocked one. */
    public enum Lock {
        /** Restored from the last session: waits for the user to ask. */
        RESTORED,
        /** Decryption is running (the passphrase prompt may be up). */
        DECRYPTING,
        CANCELLED,
        BAD_PASSPHRASE,
        NO_SECRET_KEY,
        NO_PINENTRY,
        NOT_INSTALLED,
        /** The decrypted content is not text. */
        NOT_TEXT,
        TOO_LARGE,
        FAILED
    }

    /**
     * One person the file is encrypted for.
     *
     * @param id       the key's fingerprint, or the key id from the file when the key is not in the keyring
     * @param name     the key's name, or empty when it is unknown
     * @param you      a secret key for it is on this machine
     * @param verified gpg considers the key its owner's (full or ultimate validity)
     * @param known    the public key is in the keyring (a save can encrypt for it)
     */
    public record Recipient(String id, String name, String email, boolean you, boolean verified, boolean known) {
        /** The name to show: the key's name, else {@code 0x} and the last sixteen digits of its id. */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            String tail = id.length() > 16 ? id.substring(id.length() - 16) : id;
            return "0x" + tail;
        }
    }

    private Mode mode;
    private List<Recipient> recipients;
    private boolean armored;
    private char @Nullable [] passphrase;
    private @Nullable String signature;
    private boolean recipientsPending;
    private @Nullable Lock lock;
    private String lockDetail = "";

    public EncryptionState(Mode mode, List<Recipient> recipients, boolean armored) {
        this.mode = mode;
        this.recipients = List.copyOf(recipients);
        this.armored = armored;
    }

    public Mode mode() {
        return mode;
    }

    public List<Recipient> recipients() {
        return recipients;
    }

    /** Whether the file is written ASCII-armored (text) rather than binary. */
    public boolean armored() {
        return armored;
    }

    public void setArmored(boolean armored) {
        this.armored = armored;
    }

    /**
     * New recipients (or a switch to a passphrase), applied on the next save; until then
     * {@link #recipientsPending()} is true so the status bar can say "unsaved".
     */
    public void changeRecipients(Mode newMode, List<Recipient> newRecipients, char @Nullable [] newPassphrase) {
        this.mode = newMode;
        this.recipients = List.copyOf(newRecipients);
        setPassphrase(newMode == Mode.PASSPHRASE ? newPassphrase : null);
        this.recipientsPending = true;
    }

    /**
     * What the file on disk says about itself (read from it without decrypting), or recipients whose keys were
     * just imported: not a change the user made, so nothing is pending.
     */
    public void describe(Mode fileMode, List<Recipient> fileRecipients) {
        this.mode = fileMode;
        this.recipients = List.copyOf(fileRecipients);
    }

    public boolean recipientsPending() {
        return recipientsPending;
    }

    /** The pending change is on disk now. */
    public void recipientsSaved() {
        this.recipientsPending = false;
    }

    /** The passphrase of a passphrase-only file (a copy is kept), or null to forget it. */
    public void setPassphrase(char @Nullable [] value) {
        wipe();
        this.passphrase = value == null ? null : value.clone();
    }

    /** A copy of the kept passphrase (the caller wipes it after use), or null. */
    public char @Nullable [] passphrase() {
        return passphrase == null ? null : passphrase.clone();
    }

    public boolean hasPassphrase() {
        return passphrase != null;
    }

    /** "Signed by …" text when the decrypted file carried a signature, else null. */
    public @Nullable String signature() {
        return signature;
    }

    public void setSignature(@Nullable String signature) {
        this.signature = signature;
    }

    /** Why the buffer has no text yet, or null once it is decrypted. */
    public @Nullable Lock lock() {
        return lock;
    }

    public boolean locked() {
        return lock != null;
    }

    /** gpg's own message about the lock, for a Details view. */
    public String lockDetail() {
        return lockDetail;
    }

    public void setLock(@Nullable Lock lock, String detail) {
        this.lock = lock;
        this.lockDetail = detail == null ? "" : detail;
    }

    /** Recipients whose public key is not in the keyring: a save cannot encrypt for them. */
    public List<Recipient> missingKeys() {
        return recipients.stream().filter(r -> !r.known()).toList();
    }

    /** Whether one of the recipients is the user, so the file stays readable for them. */
    public boolean includesYou() {
        return recipients.stream().anyMatch(Recipient::you);
    }

    /** Forgets the kept passphrase, overwriting it in memory. */
    public void wipe() {
        if (passphrase != null) {
            Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }
}
