package com.editora.crypto;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real gpg, in a throwaway home: what encrypted files rely on — encrypt for people and read back who they
 * are without decrypting, passphrases by loopback, the failures the UI turns into sentences, signatures, import.
 * Linux CI has gpg (an unexpected skip fails the build there); elsewhere the test skips without it.
 */
class GpgIntegrationTest {

    private static GpgTestHome home;
    private static String adrian;
    private static String dana;

    @BeforeAll
    static void keyring() throws Exception {
        assumeTrue(GpgTestHome.available(), "gpg is not installed");
        home = GpgTestHome.create();
        adrian = home.newKey("Adrian Test <adrian@example.com>");
        dana = home.newKey("Dana Ruiz <dana@example.com>");
        home.dropSecretKey(dana); // Dana's public key only, as for a colleague
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (home != null) {
            home.close();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theKeyringSaysWhichKeysAreYours() {
        List<GpgKey> keys = home.gpg().keys();
        assertEquals(2, keys.size());
        assertTrue(keys.stream()
                .filter(k -> k.fingerprint().equals(adrian))
                .findFirst()
                .orElseThrow()
                .secret());
        assertFalse(keys.stream()
                .filter(k -> k.fingerprint().equals(dana))
                .findFirst()
                .orElseThrow()
                .secret());
        assertNotNull(home.gpg().version());
    }

    @Test
    void aFileEncryptedForTwoPeopleSaysWhoTheyAreAndDecryptsForTheOneHere() {
        Gpg gpg = home.gpg();
        Gpg.Outcome enc = gpg.encrypt(utf8("# Notes\nsecret\n"), List.of(adrian, dana), false);
        assertTrue(enc.ok(), () -> String.valueOf(enc.failure()));
        assertEquals(EncryptedFiles.Kind.BINARY_MESSAGE, EncryptedFiles.sniff(enc.out()));
        PacketInfo info = gpg.inspect(enc.out());
        assertEquals(2, info.namedKeyIds().size());
        List<GpgKey> keys = gpg.keys();
        for (String id : info.namedKeyIds()) {
            assertTrue(keys.stream().anyMatch(k -> k.matches(id)), "subkey " + id + " maps to a person");
        }
        Gpg.Outcome dec = gpg.decrypt(enc.out(), null, null);
        assertTrue(dec.ok(), () -> String.valueOf(dec.failure()));
        assertEquals("# Notes\nsecret\n", dec.text());
    }

    @Test
    void armorIsText() {
        Gpg.Outcome enc = home.gpg().encrypt(utf8("x"), List.of(adrian), true);
        assertTrue(enc.ok());
        assertEquals(EncryptedFiles.Kind.ARMORED_MESSAGE, EncryptedFiles.sniff(enc.out()));
    }

    @Test
    void aPassphraseGoesByLoopbackAndAWrongOneIsSaidSo() {
        Gpg gpg = home.gpg();
        Gpg.Outcome enc = gpg.encryptSymmetric(utf8("pass only"), "correct horse".toCharArray(), true);
        assertTrue(enc.ok(), () -> String.valueOf(enc.failure()));
        assertTrue(gpg.inspect(enc.out()).passphraseOnly());
        Gpg.Outcome wrong = gpg.decrypt(enc.out(), "nope".toCharArray(), null);
        assertEquals(GpgFailure.Kind.BAD_PASSPHRASE, wrong.failure().kind());
        Gpg.Outcome right = gpg.decrypt(enc.out(), "correct horse".toCharArray(), null);
        assertTrue(right.ok(), () -> String.valueOf(right.failure()));
        assertEquals("pass only", right.text());
    }

    @Test
    void aMessageForSomeoneElseHasNoKeyHere() {
        Gpg gpg = home.gpg();
        Gpg.Outcome enc = gpg.encrypt(utf8("for Dana"), List.of(dana), false);
        assertTrue(enc.ok());
        Gpg.Outcome dec = gpg.decrypt(enc.out(), null, null);
        assertEquals(GpgFailure.Kind.NO_SECRET_KEY, dec.failure().kind());
    }

    @Test
    void anUnknownRecipientIsAMissingPublicKey() {
        Gpg.Outcome enc = home.gpg().encrypt(utf8("x"), List.of("0123456789ABCDEF0123456789ABCDEF01234567"), false);
        assertEquals(GpgFailure.Kind.MISSING_PUBLIC_KEY, enc.failure().kind());
    }

    @Test
    void notAMessageIsSaidSo() {
        Gpg.Outcome dec = home.gpg().decrypt(utf8("just text"), null, null);
        assertFalse(dec.ok());
        assertEquals(GpgFailure.Kind.NOT_ENCRYPTED, dec.failure().kind());
    }

    @Test
    void clearSignedAndDetachedSignaturesVerify() throws Exception {
        Gpg gpg = home.gpg();
        Gpg.Outcome signed = gpg.clearSign(utf8("Signed words\n"), adrian, null);
        assertTrue(signed.ok(), () -> String.valueOf(signed.failure()));
        assertTrue(EncryptedFiles.isClearSigned(signed.text()));
        GpgStatus.Signature good = gpg.verify(signed.out()).status().signature();
        assertEquals(GpgStatus.Signature.State.GOOD, good.state());
        assertTrue(good.signer().contains("Adrian Test"));
        GpgStatus.Signature bad = gpg.verify(utf8(signed.text().replace("Signed words", "Changed words")))
                .status()
                .signature();
        assertEquals(GpgStatus.Signature.State.BAD, bad.state());

        byte[] data = utf8("file bytes\n");
        Gpg.Outcome sig = gpg.detachSign(data, null, null, true);
        assertTrue(sig.ok());
        assertEquals(
                GpgStatus.Signature.State.GOOD,
                gpg.verifyDetached(sig.out(), data).status().signature().state());
        assertEquals(
                GpgStatus.Signature.State.BAD,
                gpg.verifyDetached(sig.out(), utf8("other bytes\n"))
                        .status()
                        .signature()
                        .state());
    }

    @Test
    void aPublicKeyImportsIntoAnotherKeyring() throws Exception {
        try (GpgTestHome other = GpgTestHome.create()) {
            Gpg.Outcome imported = other.gpg().importKeys(home.exportKey(adrian));
            GpgStatus.Line res = imported.status().first("IMPORT_RES");
            assertNotNull(res, imported::text);
            assertEquals("1", res.arg(2));
            assertEquals(1, other.gpg().keys().size());
        }
    }
}
