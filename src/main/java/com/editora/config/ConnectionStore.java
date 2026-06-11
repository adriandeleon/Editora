package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.editora.vfs.RemoteConnection;

/**
 * The persisted list of saved SFTP connections (in {@code connections.json}). Only non-secret metadata is
 * stored — host/port/user/auth-method/key-path/last-path — never a password or passphrase. Schema-versioned
 * via {@link com.editora.config.migration.ConfigSchema#CONNECTIONS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionStore {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public List<RemoteConnection> connections = new ArrayList<>();

    /** Adds or replaces the connection with the same {@link RemoteConnection#id()} (most-recent first). */
    public void put(RemoteConnection conn) {
        connections.removeIf(c -> c.id().equals(conn.id()));
        connections.add(0, conn);
    }

    public void remove(String id) {
        connections.removeIf(c -> c.id().equals(id));
    }
}
