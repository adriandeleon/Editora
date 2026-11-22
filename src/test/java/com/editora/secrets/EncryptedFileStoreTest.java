package com.editora.secrets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The encrypted file the secrets go to when no keychain answers (SET-13 exception, decided by adl). */
class EncryptedFileStoreTest {

    @TempDir
    Path cfg;

    @Test
    void aSecretReadsBackFromAnotherInstanceAndIsNotInTheFileAsText() throws Exception {
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        assertTrue(store.available());
        assertEquals(Optional.empty(), store.read("ai-api-key.anthropic"), "no file yet: no secret");
        store.write("ai-api-key.anthropic", "sk-ant-FAKE-ünïcode=\n");
        store.write("ai-api-key.openai", "or-FAKE");

        EncryptedFileStore again = new EncryptedFileStore(cfg);
        assertEquals(
                "sk-ant-FAKE-ünïcode=\n", again.read("ai-api-key.anthropic").orElseThrow());
        assertEquals(
                Map.of("ai-api-key.anthropic", "sk-ant-FAKE-ünïcode=\n", "ai-api-key.openai", "or-FAKE"),
                again.readAll());
        String raw = new String(Files.readAllBytes(store.dataFile()), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("or-FAKE"), "encrypted");
        assertFalse(raw.contains(java.util.HexFormat.of().formatHex("or-FAKE".getBytes(StandardCharsets.UTF_8))));
        assertEquals(cfg.resolve("keys"), store.dir(), "beside Torrenta's keys/<name>.key, outside settings");

        again.delete("ai-api-key.openai");
        assertEquals(Optional.empty(), new EncryptedFileStore(cfg).read("ai-api-key.openai"));
        assertEquals(
                "sk-ant-FAKE-ünïcode=\n",
                new EncryptedFileStore(cfg).read("ai-api-key.anthropic").orElseThrow());
    }

    @Test
    void eachWriteUsesAFreshNonce() throws Exception {
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        store.write("a", "same");
        byte[] first = Files.readAllBytes(store.dataFile());
        store.write("a", "same");
        assertFalse(java.util.Arrays.equals(first, Files.readAllBytes(store.dataFile())));
    }

    @Test
    void theFolderAndBothFilesAreTheOwnersAlone() throws Exception {
        assumeTrue(cfg.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX modes here");
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        store.write("ai-api-key.anthropic", "sk-ant-FAKE");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.dir())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.dataFile())));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.keyFile())));

        Files.setPosixFilePermissions(store.dir(), PosixFilePermissions.fromString("rwxr-xr-x"));
        store.write("ai-api-key.openai", "or-FAKE");
        assertEquals(
                "rwx------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(store.dir())),
                "a folder opened up is closed again on the next write");
    }

    @Test
    void anotherKeyIsRefusedAndTheFileIsMovedAsideBeforeANewOneStarts() throws Exception {
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        store.write("ai-api-key.anthropic", "sk-ant-FAKE");
        byte[] data = Files.readAllBytes(store.dataFile());
        String oldKey = Files.readString(store.keyFile());
        byte[] other = new byte[32];
        other[0] = 1;
        Files.writeString(store.keyFile(), EncryptedFileStore.encodeKey(other));

        EncryptedFileStore reopened = new EncryptedFileStore(cfg);
        SecretStoreException e = assertThrows(SecretStoreException.class, () -> reopened.read("ai-api-key.anthropic"));
        assertTrue(e.getMessage().contains("does not decrypt"), e.getMessage());
        assertThrows(SecretStoreException.class, () -> reopened.delete("ai-api-key.anthropic"));
        assertArrayEquals(data, Files.readAllBytes(store.dataFile()), "a delete never writes over it");
        assertNull(reopened.setAside());

        reopened.write("ai-api-key.openai", "or-FAKE");
        Path kept = store.dir().resolve("secrets.enc.unreadable");
        assertEquals(kept, reopened.setAside());
        assertArrayEquals(data, Files.readAllBytes(kept), "the old file is kept, not overwritten");
        assertTrue(Files.exists(store.dir().resolve("secrets.key.unreadable")), "with the key that went with it");
        assertEquals(Map.of("ai-api-key.openai", "or-FAKE"), reopened.readAll());
        assertFalse(oldKey.isEmpty());
    }

    @Test
    void aDamagedFileOrAMissingKeyIsReportedNotACrash() throws Exception {
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        store.write("a", "secret");
        byte[] data = Files.readAllBytes(store.dataFile());
        data[data.length - 3] ^= 0x40;
        Files.write(store.dataFile(), data);
        assertThrows(SecretStoreException.class, () -> new EncryptedFileStore(cfg).read("a"));

        Files.write(store.dataFile(), "not even close".getBytes(StandardCharsets.UTF_8));
        SecretStoreException format = assertThrows(SecretStoreException.class, store::readAll);
        assertTrue(format.getMessage().contains("not an Editora secrets file"), format.getMessage());

        store.write("b", "fresh"); // moves the junk aside
        Files.delete(store.keyFile());
        SecretStoreException missing = assertThrows(SecretStoreException.class, () -> store.read("b"));
        assertTrue(missing.getMessage().contains("missing"), missing.getMessage());

        Files.writeString(store.keyFile(), "format=1\nkey=zz\n");
        assertThrows(SecretStoreException.class, () -> store.read("b"), "a key file that is not hex");
    }

    @Test
    void theKeyFileFormatRoundTrips() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        String text = EncryptedFileStore.encodeKey(key);
        assertTrue(text.startsWith("# Editora"), "a warning for whoever opens it");
        assertArrayEquals(key, EncryptedFileStore.decodeKey(text));
        assertThrows(IllegalArgumentException.class, () -> EncryptedFileStore.decodeKey("format=2\nkey=00\n"));
        assertThrows(IllegalArgumentException.class, () -> EncryptedFileStore.decodeKey("format=1\nkey=0011\n"));
    }

    @Test
    void somethingThatIsNotAFolderWhereTheFolderGoesIsNotAvailableAndAWriteFails() throws Exception {
        Files.writeString(cfg.resolve("keys"), "x");
        EncryptedFileStore store = new EncryptedFileStore(cfg);
        assertFalse(store.available());
        assertThrows(SecretStoreException.class, () -> store.write("a", "b"));
    }
}
