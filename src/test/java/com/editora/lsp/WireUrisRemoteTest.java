package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A server on another machine knows a document by its path there, as a {@code file:} URI. */
class WireUrisRemoteTest {

    private static final WireUris REMOTE = WireUris.remote("ana@build:22");

    @Test
    void aRemoteKeyIsAFileUriOnTheHostAndBack() {
        String key = "sftp://ana@build:22/srv/app/src/main.py";

        String wire = REMOTE.toWire(key);

        assertEquals("file:///srv/app/src/main.py", wire);
        assertEquals(key, REMOTE.toKey(wire));
    }

    @Test
    void spacesPercentAndNonAsciiSurviveTheTrip() {
        String key = "sftp://ana@build:22/srv/my app/100% año/ñ.py";

        String wire = REMOTE.toWire(key);

        assertEquals("file:///srv/my%20app/100%25%20a%C3%B1o/%C3%B1.py", wire);
        assertEquals(key, REMOTE.toKey(wire));
    }

    @Test
    void aFolderKeepsItsTrailingSlash() {
        assertEquals("file:///srv/app/", REMOTE.toWire("sftp://ana@build:22/srv/app/"));
        assertEquals("sftp://ana@build:22/srv/app/", REMOTE.toKey("file:///srv/app/"));
    }

    @Test
    void anythingElsePassesThrough() {
        assertEquals("sftp://ana@other:22/x.py", REMOTE.toWire("sftp://ana@other:22/x.py"), "another host's key");
        assertEquals("jdt://contents/rt.jar/String.class", REMOTE.toKey("jdt://contents/rt.jar/String.class"));
        assertEquals("untitled:Untitled-1", REMOTE.toWire("untitled:Untitled-1"));
        assertEquals(
                "sftp://ana@build:22/usr/lib/python3/os.py",
                REMOTE.toKey("file:///usr/lib/python3/os.py"),
                "a file outside the project is still on the host");
    }
}
