package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.editora.crypto.GpgFailure;
import com.editora.crypto.GpgStatus;
import com.editora.editor.crypto.EncryptionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The encryption coordinator's words and decisions that need no window: failures, locks, signatures, pairs. */
class EncryptionCoordinatorTest {

    private static GpgFailure failure(GpgFailure.Kind kind) {
        return new GpgFailure(kind, List.of(), "detail");
    }

    @Test
    void everyFailureLocksTheBufferForItsOwnReason() {
        Map<GpgFailure.Kind, EncryptionState.Lock> want = new EnumMap<>(GpgFailure.Kind.class);
        want.put(GpgFailure.Kind.CANCELLED, EncryptionState.Lock.CANCELLED);
        want.put(GpgFailure.Kind.BAD_PASSPHRASE, EncryptionState.Lock.BAD_PASSPHRASE);
        want.put(GpgFailure.Kind.NO_SECRET_KEY, EncryptionState.Lock.NO_SECRET_KEY);
        want.put(GpgFailure.Kind.NO_PINENTRY, EncryptionState.Lock.NO_PINENTRY);
        want.put(GpgFailure.Kind.NOT_INSTALLED, EncryptionState.Lock.NOT_INSTALLED);
        want.put(GpgFailure.Kind.TOO_LARGE, EncryptionState.Lock.TOO_LARGE);
        for (GpgFailure.Kind kind : GpgFailure.Kind.values()) {
            assertEquals(
                    want.getOrDefault(kind, EncryptionState.Lock.FAILED),
                    EncryptionCoordinator.lockFor(failure(kind)),
                    kind.toString());
        }
    }

    @Test
    void everyFailureHasASentence() {
        Map<GpgFailure.Kind, String> want = new EnumMap<>(GpgFailure.Kind.class);
        want.put(GpgFailure.Kind.NOT_INSTALLED, "encryption.fail.notInstalled");
        want.put(GpgFailure.Kind.MISSING_PUBLIC_KEY, "encryption.fail.missingKey");
        want.put(GpgFailure.Kind.UNUSABLE_RECIPIENT, "encryption.fail.unusableKey");
        want.put(GpgFailure.Kind.CANCELLED, "encryption.fail.cancelled");
        want.put(GpgFailure.Kind.BAD_PASSPHRASE, "encryption.passphrase.wrong");
        want.put(GpgFailure.Kind.NO_PINENTRY, "encryption.locked.noPinentry");
        want.put(GpgFailure.Kind.NO_SECRET_KEY, "encryption.locked.noSecretKey");
        want.put(GpgFailure.Kind.TIMED_OUT, "encryption.fail.timedOut");
        want.put(GpgFailure.Kind.TOO_LARGE, "encryption.locked.tooLarge");
        for (GpgFailure.Kind kind : GpgFailure.Kind.values()) {
            assertEquals(
                    tr(want.getOrDefault(kind, "encryption.fail.other")),
                    EncryptionCoordinator.messageFor(failure(kind)),
                    kind.toString());
        }
    }

    @Test
    void aSignatureNamesItsSignerOrTheirKeyId() {
        GpgStatus.Signature good = new GpgStatus.Signature(GpgStatus.Signature.State.GOOD, "ABCD", "Dana", "F", true);
        assertEquals(tr("encryption.sig.goodVerified", "Dana"), EncryptionCoordinator.signatureText(good));
        GpgStatus.Signature unverified =
                new GpgStatus.Signature(GpgStatus.Signature.State.GOOD, "ABCD", "Dana", "F", false);
        assertEquals(tr("encryption.sig.goodUnverified", "Dana"), EncryptionCoordinator.signatureText(unverified));
        Map<GpgStatus.Signature.State, String> keys = Map.of(
                GpgStatus.Signature.State.BAD, "encryption.sig.bad",
                GpgStatus.Signature.State.EXPIRED_SIGNATURE, "encryption.sig.expiredSignature",
                GpgStatus.Signature.State.EXPIRED_KEY, "encryption.sig.expiredKey",
                GpgStatus.Signature.State.REVOKED_KEY, "encryption.sig.revokedKey",
                GpgStatus.Signature.State.UNKNOWN_KEY, "encryption.sig.unknownKey");
        for (var e : keys.entrySet()) {
            GpgStatus.Signature s = new GpgStatus.Signature(e.getKey(), "ABCD", " ", "F", false);
            assertEquals(
                    tr(e.getValue(), "0xABCD"), EncryptionCoordinator.signatureText(s), "a blank signer: the key id");
        }
    }

    @Test
    void aDetachedSignatureIsFoundFromEitherSide(@TempDir Path dir) throws Exception {
        Path data = Files.writeString(dir.resolve("notes.txt"), "hello");
        assertNull(EncryptionCoordinator.detachedPair(data, "hello"), "no signature beside it");
        Path sig = Files.writeString(dir.resolve("notes.txt.sig"), "binary");
        assertArrayEquals(new Path[] {sig, data}, EncryptionCoordinator.detachedPair(data, "hello"));
        assertArrayEquals(new Path[] {sig, data}, EncryptionCoordinator.detachedPair(sig, "binary"));

        Path other = Files.writeString(dir.resolve("other.md"), "x");
        Path asc = Files.writeString(dir.resolve("other.md.asc"), "-----BEGIN PGP SIGNATURE-----\n");
        assertArrayEquals(new Path[] {asc, other}, EncryptionCoordinator.detachedPair(other, "x"));
        assertArrayEquals(
                new Path[] {asc, other}, EncryptionCoordinator.detachedPair(asc, "  -----BEGIN PGP SIGNATURE-----\n"));
        assertNull(
                EncryptionCoordinator.detachedPair(asc, "-----BEGIN PGP PUBLIC KEY BLOCK-----\n"),
                "an armored key is not a signature, and nothing is signed by a file named .asc.asc");

        Path lone = Files.writeString(dir.resolve("lone.sig"), "binary");
        assertNull(EncryptionCoordinator.detachedPair(lone, "binary"), "the signed file is gone");
    }
}
