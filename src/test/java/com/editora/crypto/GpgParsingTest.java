package com.editora.crypto;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsers behind encrypted files, fed with what gpg 2.4.7 really printed ({@code --with-colons}, status
 * lines, {@code --list-packets}); the revoked and unverified keys are those lines with their validity letter
 * changed, which is the only field that differs.
 */
class GpgParsingTest {

    /** {@code gpg --list-keys --with-colons --fixed-list-mode --with-subkey-fingerprints}. */
    static final String PUBLIC = String.join(
            "\n",
            "tru::1:1792339320:1792425720:3:1:5",
            "pub:u:255:22:B2B49ED225B2A685:1792339320:::u:::scESC:::::ed25519:::0:",
            "fpr:::::::::A945A093C019D17EF48E6AFCB2B49ED225B2A685:",
            "uid:u::::1792339320::60D9F080980129787933C7B61ABC756BCA281752::Adrian Test <adrian@example.com>::::::::::0:",
            "sub:u:255:18:0B064607A4EC04D0:1792339320::::::e:::::cv25519::",
            "fpr:::::::::37DBA715DD1BAB55241339180B064607A4EC04D0:",
            "pub:-:255:22:7FB0E2B5956BC805:1792339320:::-:::scESC:::::ed25519:::0:",
            "fpr:::::::::75C437B236E5FFCC96D7E68B7FB0E2B5956BC805:",
            "uid:-::::1792339320::04D9AF5BC5D32E74A120FAA80453591907CDA034::Dana Ruiz (work) <dana@example.com>::::::::::0:",
            "sub:-:255:18:49D108EC3FB069FD:1792339320::::::e:::::cv25519::",
            "fpr:::::::::BFBDAC440950333E196CA7DD49D108EC3FB069FD:",
            "pub:u:255:22:3FFB3CCE44500404:1792339320:::u:::scSC:::::ed25519:::0:",
            "fpr:::::::::40B8A627CE931F1C895DD73B3FFB3CCE44500404:",
            "uid:u::::1792339320::3491D2DBC58C15779AE11CFD6239AEFD8EDF7181::Signer Only <signer@example.com>::::::::::0:",
            "pub:r:255:22:A7778ED43721BF1D:1792339320:::u:::sc:::::ed25519:::0:",
            "fpr:::::::::C0C9DBFD9E764B365568A5B0A7778ED43721BF1D:",
            "uid:r::::1792339320::E917D02DF1B842AEC5B3825B383B15A58620DD7E::Revoked Person <revoked@example.com>::::::::::0:",
            "pub:e:255:22:E345267CA5BE91D5:1577836800:1577923200::u:::sc:::::ed25519:::0:",
            "fpr:::::::::F346B1444037603C774E51B6E345267CA5BE91D5:",
            "uid:e::::1577836800::C3AFE61ED7144F05824659207D2A0E912CA10267::Expired Person <expired@example.com>::::::::::0:",
            "sub:e:255:18:8D88897488E20C2E:1577836800:1577923200:::::e:::::cv25519::",
            "fpr:::::::::A80EFF32E88139C66096F57D8D88897488E20C2E:");

    /** {@code gpg --list-secret-keys --with-colons …} (only Adrian's secret key is here). */
    static final String SECRET = String.join(
            "\n",
            "sec:u:255:22:B2B49ED225B2A685:1792339320:::u:::scESC:::+::ed25519:::0:",
            "fpr:::::::::A945A093C019D17EF48E6AFCB2B49ED225B2A685:",
            "grp:::::::::5AB90CBE387C1B0706F00262EAC9F0EE20E8AE2F:",
            "uid:u::::1792339320::60D9F080980129787933C7B61ABC756BCA281752::Adrian Test <adrian@example.com>::::::::::0:",
            "ssb:u:255:18:0B064607A4EC04D0:1792339320::::::e:::+::cv25519:",
            "fpr:::::::::37DBA715DD1BAB55241339180B064607A4EC04D0:");

    private static GpgKey key(List<GpgKey> keys, String email) {
        return keys.stream().filter(k -> k.email().equals(email)).findFirst().orElseThrow();
    }

    @Test
    void readsEveryPrimaryKeyWithItsSubkeysAndWhetherItIsYours() {
        List<GpgKey> keys = KeyListing.parse(PUBLIC, SECRET);
        assertEquals(5, keys.size());
        GpgKey adrian = key(keys, "adrian@example.com");
        assertEquals("A945A093C019D17EF48E6AFCB2B49ED225B2A685", adrian.fingerprint());
        assertEquals(List.of("0B064607A4EC04D0"), adrian.subkeyIds());
        assertTrue(adrian.secret());
        assertTrue(adrian.verified());
        assertTrue(adrian.canEncrypt());
        assertFalse(key(keys, "dana@example.com").secret());
    }

    @Test
    void namesComeFromTheUserIdWithoutTheComment() {
        GpgKey dana = key(KeyListing.parse(PUBLIC, ""), "dana@example.com");
        assertEquals("Dana Ruiz", dana.name());
        assertFalse(dana.verified(), "unknown validity is not verified");
        assertTrue(dana.canEncrypt(), "an unverified key can still be chosen (with a warning)");
    }

    @Test
    void anEncryptedFileNamesTheEncryptionSubkeyWhichMatchesItsKey() {
        GpgKey dana = key(KeyListing.parse(PUBLIC, ""), "dana@example.com");
        assertTrue(dana.matches("49D108EC3FB069FD"));
        assertTrue(dana.matches("0x49d108ec3fb069fd"));
        assertTrue(dana.matches("75C437B236E5FFCC96D7E68B7FB0E2B5956BC805"));
        assertFalse(dana.matches("0B064607A4EC04D0"));
    }

    @Test
    void unusableKeysSayWhy() {
        List<GpgKey> keys = KeyListing.parse(PUBLIC, "");
        assertEquals(
                GpgKey.Unusable.NO_ENCRYPTION, key(keys, "signer@example.com").unusable());
        assertEquals(GpgKey.Unusable.EXPIRED, key(keys, "expired@example.com").unusable());
        assertEquals(1577923200L, key(keys, "expired@example.com").expires());
        GpgKey revoked = keys.stream()
                .filter(k -> k.keyId().equals("A7778ED43721BF1D"))
                .findFirst()
                .orElseThrow();
        assertEquals(GpgKey.Unusable.REVOKED, revoked.unusable());
        assertTrue(revoked.userIds().isEmpty(), "a revoked user id is not who the key is");
        assertEquals("3721BF1D", revoked.name(), "a key without a user id is named by its id");
    }

    @Test
    void fingerprintsAreGroupedForReadingAloud() {
        GpgKey adrian = key(KeyListing.parse(PUBLIC, SECRET), "adrian@example.com");
        assertEquals("A945 A093 C019 D17E F48E  6AFC B2B4 9ED2 25B2 A685", adrian.groupedFingerprint());
        assertEquals("A945 A093 … B2B4 9ED2 25B2 A685", adrian.shortFingerprint());
    }

    @Test
    void userIdEscapesAreUndone() {
        assertEquals("Café: Ünïcode", KeyListing.unescape("Caf\\xc3\\xa9\\x3a \\xc3\\x9cn\\xc3\\xafcode"));
        assertEquals("plain", KeyListing.unescape("plain"));
    }

    @Test
    void statusLinesAreSeparatedFromHumanMessages() {
        GpgStatus s = GpgStatus.parse(String.join(
                "\n",
                "gpg: encrypted with cv25519 key, ID 49D108EC3FB069FD",
                "[GNUPG:] ENC_TO 49D108EC3FB069FD 18 0",
                "[GNUPG:] ENC_TO 0B064607A4EC04D0 18 0",
                "[GNUPG:] ENC_TO 49D108EC3FB069FD 18 0",
                "[GNUPG:] DECRYPTION_OKAY"));
        assertEquals(List.of("49D108EC3FB069FD", "0B064607A4EC04D0"), s.encryptedTo());
        assertTrue(s.has("DECRYPTION_OKAY"));
        assertEquals("gpg: encrypted with cv25519 key, ID 49D108EC3FB069FD", s.humanText());
    }

    @Test
    void aGoodSignatureNamesItsSignerAndTrust() {
        GpgStatus s = GpgStatus.parse(String.join(
                "\n",
                "[GNUPG:] GOODSIG B2B49ED225B2A685 Adrian%20Test <adrian@example.com>",
                "[GNUPG:] VALIDSIG A945A093C019D17EF48E6AFCB2B49ED225B2A685 2026-10-18 1792339320 0 4 0 22 10 01"
                        + " A945A093C019D17EF48E6AFCB2B49ED225B2A685",
                "[GNUPG:] TRUST_ULTIMATE 0 pgp"));
        GpgStatus.Signature sig = s.signature();
        assertNotNull(sig);
        assertEquals(GpgStatus.Signature.State.GOOD, sig.state());
        assertEquals("Adrian Test <adrian@example.com>", sig.signer());
        assertTrue(sig.verified());
        assertNull(GpgStatus.parse("[GNUPG:] DECRYPTION_OKAY").signature());
    }

    @Test
    void failuresAreClassifiedFromStatusNotFromWords() {
        assertEquals(
                GpgFailure.Kind.MISSING_PUBLIC_KEY,
                GpgFailure.classify(2, "[GNUPG:] INV_RECP 1 0123456789ABCDEF\n[GNUPG:] FAILURE encrypt 9", null, false)
                        .kind());
        assertEquals(
                GpgFailure.Kind.UNUSABLE_RECIPIENT,
                GpgFailure.classify(
                                2,
                                "[GNUPG:] KEYEXPIRED 1577923200\n[GNUPG:] INV_RECP 0 expired@example.com\n"
                                        + "[GNUPG:] FAILURE encrypt 167772218",
                                null,
                                false)
                        .kind());
        GpgFailure noKey = GpgFailure.classify(
                2,
                "[GNUPG:] ENC_TO 49D108EC3FB069FD 18 0\n[GNUPG:] NO_SECKEY 49D108EC3FB069FD\n"
                        + "[GNUPG:] ERROR pkdecrypt_failed 33554449\n[GNUPG:] DECRYPTION_FAILED",
                null,
                false);
        assertEquals(GpgFailure.Kind.NO_SECRET_KEY, noKey.kind());
        assertEquals(List.of("49D108EC3FB069FD"), noKey.keyIds());
        assertEquals(
                GpgFailure.Kind.BAD_PASSPHRASE,
                GpgFailure.classify(
                                2,
                                "[GNUPG:] ERROR symkey_decrypt.maybe_error 11_BAD_PASSPHRASE\n"
                                        + "[GNUPG:] DECRYPTION_FAILED\n[GNUPG:] FAILURE gpg-exit 33554433",
                                null,
                                false)
                        .kind());
        assertEquals(
                GpgFailure.Kind.CANCELLED,
                GpgFailure.classify(2, "[GNUPG:] ERROR pkdecrypt_failed 83886179", null, false)
                        .kind());
        assertEquals(
                GpgFailure.Kind.NO_PINENTRY,
                GpgFailure.classify(2, "gpg: problem with the agent: Inappropriate ioctl for device", null, false)
                        .kind());
        assertEquals(
                GpgFailure.Kind.NOT_INSTALLED,
                GpgFailure.classify(-1, "", "Cannot run program", false).kind());
        assertEquals(
                GpgFailure.Kind.TIMED_OUT,
                GpgFailure.classify(-1, "", null, true).kind());
        assertEquals(
                GpgFailure.Kind.NOT_ENCRYPTED,
                GpgFailure.classify(2, "[GNUPG:] NODATA 1", null, false).kind());
    }

    @Test
    void packetListingsSayWhoAMessageIsForWithoutDecryptingIt() {
        PacketInfo pub = PacketInfo.parse(String.join(
                "\n",
                "# off=0 ctb=84 tag=1 hlen=2 plen=94",
                ":pubkey enc packet: version 3, algo 18, keyid 7C87D6C4C081F182",
                "\tdata: [263 bits]",
                ":pubkey enc packet: version 3, algo 18, keyid FE607EF633265327",
                ":pubkey enc packet: version 3, algo 18, keyid 0000000000000000"));
        assertEquals(List.of("7C87D6C4C081F182", "FE607EF633265327"), pub.namedKeyIds());
        assertFalse(pub.passphraseOnly());
        PacketInfo sym = PacketInfo.parse(
                "# off=0 ctb=8c tag=3 hlen=2 plen=13\n:symkey enc packet: version 4, cipher 9, aead 0, s2k 3, hash 10");
        assertTrue(sym.passphraseOnly());
    }

    @Test
    void encryptedFilesAreKnownByNameAndBytes() {
        assertTrue(EncryptedFiles.isEncryptedFile("notes.md.gpg", new byte[] {(byte) 0x84, 0x5e}));
        assertTrue(EncryptedFiles.isEncryptedFile("notes.md.gpg", new byte[] {(byte) 0xc1, 0x5e}));
        assertTrue(EncryptedFiles.isEncryptedFile("secret.pgp", new byte[] {(byte) 0x8c, 0x0d}));
        assertTrue(EncryptedFiles.isEncryptedFile("mail.asc", "\n-----BEGIN PGP MESSAGE-----\n".getBytes()));
        assertFalse(EncryptedFiles.isEncryptedFile("dana.asc", "-----BEGIN PGP PUBLIC KEY BLOCK-----".getBytes()));
        assertFalse(EncryptedFiles.isEncryptedFile("notes.gpg", "plain text".getBytes()), "a plain .gpg is text");
        assertFalse(EncryptedFiles.isEncryptedFile("notes.md", new byte[] {(byte) 0x84}), "the name must say so");
        assertEquals("notes.md", EncryptedFiles.innerName("notes.md.gpg"));
        assertEquals("notes.md", EncryptedFiles.languageName("notes.md.asc"));
        assertEquals("key.asc", EncryptedFiles.languageName("key.asc"));
        assertTrue(EncryptedFiles.armoredByName("x.asc"));
        assertFalse(EncryptedFiles.armoredByName("x.gpg"));
    }

    @Test
    void anArmoredBlockIsFoundAroundTheCaret() {
        String text = "Hi,\n-----BEGIN PGP MESSAGE-----\n\nabc\n-----END PGP MESSAGE-----\nBye";
        EncryptedFiles.Block b = EncryptedFiles.blockAt(text, text.indexOf("abc"));
        assertNotNull(b);
        assertEquals(text.indexOf("-----BEGIN"), b.start());
        assertEquals(text.indexOf("\nBye"), b.end());
        assertNotNull(EncryptedFiles.blockAt(text, text.indexOf("BEGIN") + 2), "on the BEGIN line");
        assertNull(EncryptedFiles.blockAt(text, text.length()), "after the block");
        assertNull(EncryptedFiles.blockAt(text, 1), "before the block");
    }

    @Test
    void gpgIdFilesListOneIdPerLine() {
        assertEquals(
                List.of("A945A093C019D17EF48E6AFCB2B49ED225B2A685", "dana@example.com"),
                GpgIdFiles.parse(
                        "A945A093C019D17EF48E6AFCB2B49ED225B2A685\n\n# a comment\ndana@example.com  # Dana\n"));
    }

    @Test
    void aPassphraseIsOneLineAheadOfTheData() {
        byte[] stdin = Gpg.withPassphrase("pa ss".toCharArray(), "data".getBytes());
        assertEquals("pa ss\ndata", new String(stdin));
        assertFalse(Gpg.acceptable("two\nlines".toCharArray()));
        assertFalse(Gpg.acceptable(new char[0]));
        assertTrue(Gpg.acceptable("ok".toCharArray()));
    }

    @Test
    void pinentryProgramsThatNeedATerminalAreTold() {
        assertTrue(Pinentry.graphical("pinentry-gnome3"));
        assertTrue(Pinentry.graphical("pinentry-mac"));
        assertFalse(Pinentry.graphical("pinentry-curses"));
        assertFalse(Pinentry.graphical("pinentry-tty"));
        assertEquals(
                "/opt/homebrew/bin/pinentry-mac",
                Pinentry.configuredProgram(
                        "# mine\ndefault-cache-ttl 600\npinentry-program /opt/homebrew/bin/pinentry-mac\n"));
        assertEquals(
                "C:/Program Files/p.exe", Pinentry.configuredProgram("pinentry-program \"C:/Program Files/p.exe\""));
        assertEquals("", Pinentry.configuredProgram("pinentry-timeout 30\n# pinentry-program /x\n"));
    }
}
