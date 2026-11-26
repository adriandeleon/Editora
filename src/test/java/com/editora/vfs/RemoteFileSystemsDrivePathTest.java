package com.editora.vfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoteFileSystemsDrivePathTest {

    @Test
    void aWindowsHostsDrivePathIsStoredAbsoluteWithoutAskingTheHost() {
        // MINA's server on Windows names the home C:/Users/…: the client's path is then relative, and making it
        // absolute asked the server, which failed once the connection was gone (RemoteFileSystemsHostKeyFxTest).
        assertEquals("/C:/Users/me/repo", RemoteFileSystems.drivePath("C:/Users/me/repo"));
        assertEquals("/D:/x", RemoteFileSystems.drivePath("D:\\x"));
        assertNull(RemoteFileSystems.drivePath("/home/me"));
        assertNull(RemoteFileSystems.drivePath("src/main"));
        assertNull(RemoteFileSystems.drivePath("C:"));
    }
}
