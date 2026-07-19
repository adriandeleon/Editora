package com.editora.vfs;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure VFS helpers (local branches; remote reconstruction tested via registered providers). */
class VfsTest {

    /** A test remote engine that resolves exactly the URIs it's told to (and owns no paths). */
    private record StubProvider(java.util.function.Function<String, Path> resolver) implements Vfs.RemoteProvider {
        @Override
        public Path resolve(String uri) {
            return resolver.apply(uri);
        }

        @Override
        public String storable(Path path) {
            return null;
        }
    }

    private final java.util.List<Vfs.RemoteProvider> registered = new java.util.ArrayList<>();

    private void register(java.util.function.Function<String, Path> resolver) {
        Vfs.RemoteProvider p = new StubProvider(resolver);
        Vfs.registerRemoteProvider(p);
        registered.add(p);
    }

    @AfterEach
    void clearProviders() {
        registered.forEach(Vfs::unregisterRemoteProvider);
        registered.clear();
    }

    @Test
    void localPathIsLocalAndNullIsLocal() {
        assertTrue(Vfs.isLocal(Path.of("/tmp/x.txt")));
        assertTrue(Vfs.isLocal(null)); // an untitled buffer
        assertFalse(Vfs.isRemote(Path.of("/tmp/x.txt")));
    }

    @Test
    void localPathRoundTripsAsAPlainString() {
        Path p = Path.of("/Users/ada/foo.java");
        assertEquals("/Users/ada/foo.java", Vfs.toStorableString(p));
        assertEquals(p, Vfs.parseStorable(Vfs.toStorableString(p)));
    }

    @Test
    void remoteUriDeserializesToNullWithoutAResolver() {
        assertNull(Vfs.parseStorable("sftp://ada@host:22/srv/app.js"));
    }

    @Test
    void remoteUriUsesTheRegisteredProvider() {
        Path stub = Path.of("/marker");
        register(s -> "sftp://ada@host/srv/app.js".equals(s) ? stub : null);
        assertEquals(stub, Vfs.parseStorable("sftp://ada@host/srv/app.js"));
    }

    @Test
    void multipleWindowsResolveTheirOwnHostsAndSurviveEachOtherClosing() {
        // #436: two windows connect to different hosts; each provider owns only its authority.
        Path pa = Path.of("/on-a");
        Path pb = Path.of("/on-b");
        Vfs.RemoteProvider a = new StubProvider(s -> s.contains("@hostA") ? pa : null);
        Vfs.RemoteProvider b = new StubProvider(s -> s.contains("@hostB") ? pb : null);
        Vfs.registerRemoteProvider(a);
        Vfs.registerRemoteProvider(b);
        registered.add(a);
        registered.add(b);

        assertEquals(pa, Vfs.parseStorable("sftp://ada@hostA/x"), "window A's host resolves");
        assertEquals(pb, Vfs.parseStorable("sftp://ada@hostB/x"), "window B's host resolves");

        // Window B closes → A's paths must still resolve (the single-slot bug stranded them here).
        Vfs.unregisterRemoteProvider(b);
        registered.remove(b);
        assertEquals(pa, Vfs.parseStorable("sftp://ada@hostA/x"), "A still resolves after B closed");
        assertNull(Vfs.parseStorable("sftp://ada@hostB/x"), "B's host no longer resolves (its window is gone)");
    }

    @Test
    void isRemoteUriRecognizesSchemes() {
        assertTrue(Vfs.isRemoteUri("sftp://h/x"));
        assertFalse(Vfs.isRemoteUri("file:/Users/ada/x"));
        assertFalse(Vfs.isRemoteUri("/Users/ada/x")); // a plain local path
        assertFalse(Vfs.isRemoteUri("C:\\Users\\ada\\x")); // a Windows local path
    }

    @Test
    void emptyAndBlankParseToNull() {
        assertNull(Vfs.parseStorable(""));
        assertNull(Vfs.parseStorable("   "));
        assertNull(Vfs.parseStorable(null));
    }
}
