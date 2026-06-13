package com.editora.config;

import com.editora.vfs.RemoteConnection;
import com.editora.vfs.RemoteConnection.AuthMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the saved-connections store (put de-dupes by id + moves to front; remove). */
class ConnectionStoreTest {

    private static RemoteConnection conn(String host, String user) {
        return new RemoteConnection(host, 22, user, AuthMethod.DEFAULT_KEYS, null, null, null);
    }

    @Test
    void putAddsMostRecentFirstAndDeDupesById() {
        ConnectionStore s = new ConnectionStore();
        s.put(conn("a.com", "ada"));
        s.put(conn("b.com", "bo"));
        assertEquals("bo@b.com:22", s.connections.get(0).id());
        // re-saving an existing connection moves it to the front, no duplicate
        s.put(conn("a.com", "ada"));
        assertEquals(2, s.connections.size());
        assertEquals("ada@a.com:22", s.connections.get(0).id());
    }

    @Test
    void removeDropsById() {
        ConnectionStore s = new ConnectionStore();
        s.put(conn("a.com", "ada"));
        s.remove("ada@a.com:22");
        assertTrue(s.connections.isEmpty());
    }
}
