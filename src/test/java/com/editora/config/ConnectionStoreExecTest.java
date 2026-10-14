package com.editora.config;

import java.util.List;

import com.editora.vfs.RemoteConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the user allowed and configured for running commands on a host lives beside the saved connections. */
class ConnectionStoreExecTest {

    private static RemoteConnection conn(String host) {
        return new RemoteConnection(host, 22, "ana", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", null, "/srv");
    }

    @Test
    void nothingIsAllowedUntilTheUserSaysSo() {
        ConnectionStore store = new ConnectionStore();
        store.put(conn("build"));

        assertFalse(store.execAllowed("ana@build:22"));
        assertFalse(store.execAllowed(null));
        store.allowExec("ana@build:22");
        assertTrue(store.execAllowed("ana@build:22"));
        assertFalse(store.execAllowed("ana@other:22"), "one host's yes is not another's");
    }

    @Test
    void usingAConnectionAgainKeepsWhatWasAllowedOnIt() {
        ConnectionStore store = new ConnectionStore();
        store.put(conn("build"));
        store.allowExec("ana@build:22");

        store.put(conn("build")); // the connect form rebuilds the connection every time

        assertTrue(store.execAllowed("ana@build:22"));
    }

    @Test
    void forgettingAHostForgetsWhatWasAllowedOnIt() {
        ConnectionStore store = new ConnectionStore();
        store.put(conn("build"));
        store.put(conn("other"));
        store.allowExec("ana@build:22");
        store.allowExec("ana@other:22");

        store.remove("ana@build:22");
        assertFalse(store.execAllowed("ana@build:22"));

        store.connections = new java.util.ArrayList<>(List.of()); // the Settings editor removed the rest
        store.pruneExec();
        assertTrue(store.exec.isEmpty());
    }

    @Test
    void aToolsCommandIsPerHostAndBlankMeansThePath() {
        ConnectionStore store = new ConnectionStore();
        ConnectionStore.HostExec host = new ConnectionStore.HostExec();
        host.tools.put("git", "/opt/git/bin/git");
        host.tools.put("rg", "  ");
        store.exec.put("ana@build:22", host);

        assertEquals("/opt/git/bin/git", store.tool("ana@build:22", "git"));
        assertNull(store.tool("ana@build:22", "rg"));
        assertNull(store.tool("ana@build:22", "mvn"));
        assertNull(store.tool("ana@other:22", "git"));
    }

    @Test
    void aFileFromBeforeHasNothingAllowedAndRoundTripsWithIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String v1 = "{\"schemaVersion\":1,\"connections\":[{\"host\":\"build\",\"port\":22,\"user\":\"ana\","
                + "\"auth\":\"DEFAULT_KEYS\",\"keyPath\":\"\",\"label\":null,\"lastPath\":\"/srv\"}]}";

        ConnectionStore old = mapper.readValue(v1, ConnectionStore.class);
        assertEquals(1, old.connections.size());
        assertFalse(old.execAllowed("ana@build:22"));

        old.allowExec("ana@build:22");
        old.exec.get("ana@build:22").tools.put("git", "/usr/local/bin/git");
        ConnectionStore back = mapper.readValue(mapper.writeValueAsString(old), ConnectionStore.class);
        assertTrue(back.execAllowed("ana@build:22"));
        assertEquals("/usr/local/bin/git", back.tool("ana@build:22", "git"));
    }

    @Test
    void theStepFromVersionOneIsRegisteredAndAddsNothing() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var v1 = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree("{\"schemaVersion\":1,\"connections\":[]}");

        var up = com.editora.config.migration.ConfigMigrations.upgrade(
                com.editora.config.migration.ConfigSchema.CONNECTIONS, v1, mapper);

        assertEquals(ConnectionStore.SCHEMA_VERSION, up.get("schemaVersion").asInt());
        assertFalse(up.has("exec"), "left to the default: nothing allowed anywhere");
    }
}
