package com.editora.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure VFS helpers (local branches; remote reconstruction tested via an injected resolver). */
class VfsTest {

    @AfterEach
    void clearResolver() {
        Vfs.setRemoteResolver(null);
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
    void remoteUriUsesTheInjectedResolver() {
        Path stub = Path.of("/marker");
        Vfs.setRemoteResolver(s -> "sftp://ada@host/srv/app.js".equals(s) ? stub : null);
        assertEquals(stub, Vfs.parseStorable("sftp://ada@host/srv/app.js"));
    }

    @Test
    void isRemoteUriRecognizesSchemes() {
        assertTrue(Vfs.isRemoteUri("sftp://h/x"));
        assertFalse(Vfs.isRemoteUri("file:/Users/ada/x"));
        assertFalse(Vfs.isRemoteUri("/Users/ada/x"));     // a plain local path
        assertFalse(Vfs.isRemoteUri("C:\\Users\\ada\\x")); // a Windows local path
    }

    @Test
    void emptyAndBlankParseToNull() {
        assertNull(Vfs.parseStorable(""));
        assertNull(Vfs.parseStorable("   "));
        assertNull(Vfs.parseStorable(null));
    }
}
