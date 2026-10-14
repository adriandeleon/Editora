package com.editora.config;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the trusted-workspace-root store. */
class TrustStoreTest {

    @Test
    void nothingIsTrustedByDefault(@TempDir Path dir) {
        TrustStore store = new TrustStore();
        assertFalse(store.isTrusted(dir), "a fresh store must trust nothing");
        assertFalse(store.isTrusted(null));
        assertTrue(store.trustedRoots().isEmpty());
    }

    @Test
    void trustsAnExplicitlyTrustedRoot(@TempDir Path dir) {
        TrustStore store = new TrustStore();
        store.trust(dir);
        assertTrue(store.isTrusted(dir));
        assertEquals(1, store.trustedRoots().size());
    }

    @Test
    void trustIsInheritedBySubfolders(@TempDir Path dir) throws Exception {
        Path module = java.nio.file.Files.createDirectories(dir.resolve("server/build"));
        TrustStore store = new TrustStore();
        store.trust(dir);
        assertTrue(store.isTrusted(module), "a trusted root must cover its nested build roots");
    }

    @Test
    void trustDoesNotLeakToTheParent(@TempDir Path dir) throws Exception {
        Path child = java.nio.file.Files.createDirectories(dir.resolve("child"));
        TrustStore store = new TrustStore();
        store.trust(child);
        assertFalse(store.isTrusted(dir), "trusting a subfolder must not trust its parent");
    }

    @Test
    void trustDoesNotLeakToASiblingSharingAStringPrefix(@TempDir Path dir) throws Exception {
        // The trap: "app2".startsWith("app") is true as a *string* but false as a path.
        Path app = java.nio.file.Files.createDirectories(dir.resolve("app"));
        Path app2 = java.nio.file.Files.createDirectories(dir.resolve("app2"));
        TrustStore store = new TrustStore();
        store.trust(app);
        assertTrue(store.isTrusted(app));
        assertFalse(store.isTrusted(app2), "a sibling sharing a name prefix must not inherit trust");
    }

    @Test
    void revokeDropsTheEntry(@TempDir Path dir) {
        TrustStore store = new TrustStore();
        store.trust(dir);
        store.revoke(dir);
        assertFalse(store.isTrusted(dir));
        assertTrue(store.trustedRoots().isEmpty());
    }

    @Test
    void revokeAllClearsEverything(@TempDir Path dir) throws Exception {
        Path a = java.nio.file.Files.createDirectories(dir.resolve("a"));
        Path b = java.nio.file.Files.createDirectories(dir.resolve("b"));
        TrustStore store = new TrustStore();
        store.trust(a);
        store.trust(b);
        store.revokeAll();
        assertFalse(store.isTrusted(a));
        assertFalse(store.isTrusted(b));
    }

    @Test
    void anEntryExplicitlySetFalseIsNotTrusted(@TempDir Path dir) {
        TrustStore store = new TrustStore();
        store.roots.put(PathKeys.canonical(dir).toString(), false);
        assertFalse(store.isTrusted(dir));
        assertTrue(store.trustedRoots().isEmpty());
    }

    @Test
    void aSymlinkedRootResolvesToTheSameEntry(@TempDir Path dir) throws Exception {
        Path real = java.nio.file.Files.createDirectories(dir.resolve("real"));
        Path link = dir.resolve("link");
        try {
            java.nio.file.Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no symlink support (e.g. Windows without privilege) — nothing to assert
        }
        TrustStore store = new TrustStore();
        store.trust(real);
        assertTrue(store.isTrusted(link), "the same folder reached via a symlink is the same trust entry");
        assertEquals(1, store.trustedRoots().size());
    }

    @Test
    void anUnparseableStoredKeyIsIgnoredRatherThanThrowing(@TempDir Path dir) {
        TrustStore store = new TrustStore();
        store.roots.put("\0not-a-path", true); // a NUL is invalid on every supported filesystem
        store.trust(dir);
        assertTrue(store.isTrusted(dir), "a bad key must not stop a good one from matching");
    }

    @org.junit.jupiter.api.Test
    void trustingAFolderOnAHostSaysNothingAboutTheSameFolderHere(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        TrustStore store = new TrustStore();
        // The same path, here and on the host. ("/srv/app" here is a drive-relative path on Windows; it does not
        // have to exist, and which folder it names makes no difference to what is trusted.)
        Path local = Path.of("/srv/app");
        try (FakeRemoteHost host = new FakeRemoteHost(dir, "ana@build:22")) {
            Path remote = host.path("/srv/app");

            store.trust(remote);

            org.junit.jupiter.api.Assertions.assertTrue(store.isTrusted(remote));
            org.junit.jupiter.api.Assertions.assertTrue(store.isTrusted(remote.resolve("server")), "inherited");
            org.junit.jupiter.api.Assertions.assertFalse(store.isTrusted(local), "the local folder was never trusted");
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of("sftp://ana@build:22/srv/app"), store.trustedRoots(), "stored with its host");

            store.revoke(remote);
            store.trust(local);
            org.junit.jupiter.api.Assertions.assertTrue(store.isTrusted(local));
            org.junit.jupiter.api.Assertions.assertFalse(store.isTrusted(remote), "nor the other way round");
        }
    }

    @org.junit.jupiter.api.Test
    void aTrustedRemoteFolderIsNotTrustedOnAnotherHostAndIsHarmlessWhileDisconnected(
            @org.junit.jupiter.api.io.TempDir Path dir) throws java.io.IOException {
        TrustStore store = new TrustStore();
        try (FakeRemoteHost one = new FakeRemoteHost(dir, "ana@one:22");
                FakeRemoteHost two = new FakeRemoteHost(dir, "ana@two:22")) {
            store.trust(one.path("/srv/app"));

            org.junit.jupiter.api.Assertions.assertFalse(store.isTrusted(two.path("/srv/app")));
        }
        // Both hosts are gone: the entry resolves to nothing and matches nothing, without throwing.
        org.junit.jupiter.api.Assertions.assertFalse(store.isTrusted(Path.of("/srv/app")));
    }
}
