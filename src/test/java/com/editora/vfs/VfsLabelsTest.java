package com.editora.vfs;

import java.nio.file.Path;

import com.editora.vfs.RemoteConnection.AuthMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How paths and connections are named for storage and for the UI when parts of them are missing. */
class VfsLabelsTest {

    @Test
    void aConnectionWithoutAPortUsesTheSftpDefaultAndItsIdOmitsAMissingUser() {
        RemoteConnection anonymous = new RemoteConnection("build-host", 0, null, AuthMethod.PASSWORD, null, null, null);
        assertEquals(SftpUri.DEFAULT_PORT, anonymous.port());
        assertEquals("build-host:" + SftpUri.DEFAULT_PORT, anonymous.id());
        assertEquals("build-host", anonymous.displayLabel());

        RemoteConnection blankUser =
                new RemoteConnection("build-host", -5, "", AuthMethod.DEFAULT_KEYS, null, "  ", null);
        assertEquals("build-host:" + SftpUri.DEFAULT_PORT, blankUser.id());
        assertEquals("build-host", blankUser.displayLabel(), "a blank label is no label");

        RemoteConnection named =
                new RemoteConnection("build-host", 2222, "deploy", AuthMethod.KEY, "/k", "Staging", "/srv");
        assertEquals("deploy@build-host:2222", named.id());
        assertEquals("Staging", named.displayLabel(), "the user's own label wins");
        assertEquals(
                "deploy@build-host",
                new RemoteConnection("build-host", 2222, "deploy", AuthMethod.KEY, "/k", null, null).displayLabel());
    }

    @Test
    void aLocalPathIsStoredAndLabelledAsItsPlainString() {
        Path local = Path.of("notes", "today.md").toAbsolutePath();

        assertEquals(local.toString(), Vfs.toStorableString(local));
        assertEquals(local.toString(), Vfs.displayLabel(local));
        assertEquals(local, Vfs.parseStorable(local.toString()));
        assertNull(Vfs.authorityOf(local));
        assertTrue(Vfs.isLocal(local));
    }

    @Test
    void nothingIsStoredOrShownForNoPath() {
        assertEquals("", Vfs.toStorableString(null));
        assertEquals("", Vfs.displayLabel(null));
        assertNull(Vfs.parseStorable(null));
        assertNull(Vfs.parseStorable("   "));
    }

    @Test
    void onlyANonFileSchemeMakesAStoredStringRemote() {
        assertTrue(Vfs.isRemoteUri("sftp://deploy@build-host:22/srv/app.conf"));
        assertFalse(Vfs.isRemoteUri("file:///home/me/notes.txt"));
        assertFalse(Vfs.isRemoteUri("FILE://server/share/notes.txt"), "whatever the case of the scheme");
        assertFalse(Vfs.isRemoteUri("/home/me/notes.txt"));
        assertFalse(Vfs.isRemoteUri("://no-scheme"));
        assertFalse(Vfs.isRemoteUri(null));
    }

    @Test
    void aRemotePathWhoseHostIsNotConnectedResolvesToNothingRatherThanToALocalFile() {
        assertNull(Vfs.parseStorable("sftp://nobody@not-connected.invalid:22/etc/hosts"));
    }
}
