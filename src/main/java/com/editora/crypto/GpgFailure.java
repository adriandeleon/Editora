package com.editora.crypto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Why a gpg run failed, in the terms the user acts on: each {@link Kind} has its own sentence and its own next
 * step in the UI. Classified from the status lines (and, only for the agent's "no way to ask" failures that gpg
 * reports as a system error, from the error text), never from gpg's exit code alone. Pure.
 *
 * @param keyIds the keys the failure is about (a recipient gpg refused, the keys a message needs), may be empty
 * @param detail gpg's own messages, for a Details view
 */
public record GpgFailure(
        Kind kind, List<String> keyIds, @Nullable String detail) {

    public enum Kind {
        /** gpg could not be started: not installed, or the configured path is wrong. */
        NOT_INSTALLED,
        /** The user dismissed the passphrase prompt. */
        CANCELLED,
        BAD_PASSPHRASE,
        /** None of the user's secret keys can open the message. */
        NO_SECRET_KEY,
        /** gpg-agent has no way to ask for the passphrase (no graphical pinentry, no terminal). */
        NO_PINENTRY,
        /** A recipient's public key is not in the keyring. */
        MISSING_PUBLIC_KEY,
        /** A recipient's key exists but cannot encrypt (expired, revoked, disabled, sign-only). */
        UNUSABLE_RECIPIENT,
        /** The input is not an OpenPGP message (or is damaged). */
        NOT_ENCRYPTED,
        /** The output was larger than Editora reads into an editor. */
        TOO_LARGE,
        TIMED_OUT,
        OTHER
    }

    public GpgFailure {
        keyIds = List.copyOf(keyIds);
        detail = detail == null ? "" : detail.strip();
    }

    /** gpg error codes (libgpg-error), without their source. */
    static final int ERR_BAD_PASSPHRASE = 11;

    static final int ERR_NO_PUBKEY = 9;
    static final int ERR_NO_SECKEY = 17;
    static final int ERR_NO_DATA = 58;
    static final int ERR_NO_PIN_ENTRY = 85;
    static final int ERR_CANCELED = 99;
    static final int ERR_FULLY_CANCELED = 198;
    /** {@code GPG_ERR_ENOTTY}: the agent tried a terminal pinentry and there is no terminal. */
    static final int ERR_ENOTTY = 0x8000 | 25;

    /**
     * Classifies a finished run that did not succeed.
     *
     * @param exit       the process exit code ({@code -1} when it never ran or was stopped)
     * @param stderr     everything gpg wrote to stderr (status lines included)
     * @param startError the error text when the process could not be started, else null
     * @param timedOut   the run was killed at its timeout
     */
    public static GpgFailure classify(int exit, String stderr, @Nullable String startError, boolean timedOut) {

        GpgStatus status = GpgStatus.parse(stderr);
        String detail = startError != null ? startError : status.humanText();
        if (startError != null) {
            return new GpgFailure(Kind.NOT_INSTALLED, List.of(), detail);
        }
        if (timedOut) {
            return new GpgFailure(Kind.TIMED_OUT, List.of(), detail);
        }
        List<Integer> codes = status.errorCodes();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (status.has("BAD_PASSPHRASE") || codes.contains(ERR_BAD_PASSPHRASE)) {
            return new GpgFailure(Kind.BAD_PASSPHRASE, List.of(), detail);
        }
        if (codes.contains(ERR_NO_PIN_ENTRY)
                || codes.contains(ERR_ENOTTY)
                || lower.contains("inappropriate ioctl")
                || lower.contains("no pinentry")) {
            return new GpgFailure(Kind.NO_PINENTRY, List.of(), detail);
        }
        if (codes.contains(ERR_CANCELED) || codes.contains(ERR_FULLY_CANCELED)) {
            return new GpgFailure(Kind.CANCELLED, List.of(), detail);
        }
        List<GpgStatus.Line> invalid = status.all("INV_RECP");
        if (!invalid.isEmpty()) {
            boolean missing = false;
            List<String> ids = new ArrayList<>();
            for (GpgStatus.Line l : invalid) {
                missing |= "1".equals(l.arg(0)); // reason 1: "not found"
                ids.add(l.arg(1));
            }
            return new GpgFailure(missing ? Kind.MISSING_PUBLIC_KEY : Kind.UNUSABLE_RECIPIENT, ids, detail);
        }
        if (codes.contains(ERR_NO_PUBKEY)) {
            return new GpgFailure(Kind.MISSING_PUBLIC_KEY, List.of(), detail);
        }
        if (status.has("NO_SECKEY") && !status.has("DECRYPTION_OKAY")) {
            List<String> ids = new ArrayList<>();
            status.all("NO_SECKEY").forEach(l -> ids.add(l.arg(0)));
            return new GpgFailure(Kind.NO_SECRET_KEY, ids, detail);
        }
        if (codes.contains(ERR_NO_SECKEY)) {
            return new GpgFailure(Kind.NO_SECRET_KEY, status.encryptedTo(), detail);
        }
        if (status.has("NODATA") || codes.contains(ERR_NO_DATA)) {
            return new GpgFailure(Kind.NOT_ENCRYPTED, List.of(), detail);
        }
        return new GpgFailure(Kind.OTHER, List.of(), detail.isEmpty() ? "gpg exit " + exit : detail);
    }
}
