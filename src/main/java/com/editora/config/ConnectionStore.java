package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.vfs.RemoteConnection;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * The persisted list of saved SFTP connections (in {@code connections.json}). Only non-secret metadata is
 * stored — host/port/user/auth-method/key-path/last-path — never a password or passphrase. Schema-versioned
 * via {@link com.editora.config.migration.ConfigSchema#CONNECTIONS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionStore {

    public static final int SCHEMA_VERSION = 2;

    public int schemaVersion = SCHEMA_VERSION;
    public List<RemoteConnection> connections = new ArrayList<>();

    /**
     * What the user has decided about <em>running commands</em> on a host, by connection id
     * ({@code user@host:port}). Kept beside the connections rather than in them: a connection is rebuilt from
     * the connect form every time it is used, and must not lose this on the way. Removing a saved connection
     * keeps it too, so a site added back is not asked about again and keeps its tool commands.
     */
    public Map<String, HostExec> exec = new LinkedHashMap<>();

    /** One host's execution settings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HostExec {
        /** The user agreed, once, that Editora may run the tools it drives on this host. */
        public boolean allowed;
        /** Tool id ({@code git}, {@code rg}, …) → the command for it on this host, when its PATH is not enough. */
        public Map<String, String> tools = new LinkedHashMap<>();
    }

    public boolean execAllowed(String id) {
        HostExec host = id == null ? null : exec.get(id);
        return host != null && host.allowed;
    }

    public void allowExec(String id) {
        exec.computeIfAbsent(id, k -> new HostExec()).allowed = true;
    }

    /** Grants or withdraws the agreement to run commands on host {@code id}. */
    public void setExecAllowed(String id, boolean allowed) {
        if (id == null) {
            return;
        }
        exec.computeIfAbsent(id, k -> new HostExec()).allowed = allowed;
        dropIfEmpty(id);
    }

    /** Host {@code id}'s tool commands (tool id → command), a copy. */
    public Map<String, String> tools(String id) {
        HostExec host = id == null ? null : exec.get(id);
        return host == null || host.tools == null ? Map.of() : Map.copyOf(host.tools);
    }

    /** Replaces host {@code id}'s tool commands; a blank command means "the host's PATH" and is not kept. */
    public void setTools(String id, Map<String, String> tools) {
        if (id == null) {
            return;
        }
        Map<String, String> kept = new LinkedHashMap<>();
        if (tools != null) {
            tools.forEach((tool, command) -> {
                if (tool != null && !tool.isBlank() && command != null && !command.isBlank()) {
                    kept.put(tool.strip(), command.strip());
                }
            });
        }
        exec.computeIfAbsent(id, k -> new HostExec()).tools = kept;
        dropIfEmpty(id);
    }

    /** Moves what was decided for host {@code from} to {@code to} (the connection's host, user or port was edited). */
    public void renameExec(String from, String to) {
        if (from == null || to == null || from.equals(to) || !exec.containsKey(from)) {
            return;
        }
        exec.put(to, exec.remove(from));
    }

    private void dropIfEmpty(String id) {
        HostExec host = exec.get(id);
        if (host != null && !host.allowed && (host.tools == null || host.tools.isEmpty())) {
            exec.remove(id);
        }
    }

    /** The command configured for {@code tool} on host {@code id}, or null to use the host's PATH. */
    public @Nullable String tool(String id, String tool) {
        HostExec host = id == null ? null : exec.get(id);
        String command = host == null || host.tools == null ? null : host.tools.get(tool);
        return command == null || command.isBlank() ? null : command;
    }

    /** Adds or replaces the connection with the same {@link RemoteConnection#id()} (most-recent first). */
    public void put(RemoteConnection conn) {
        connections.removeIf(c -> c.id().equals(conn.id()));
        connections.add(0, conn);
    }

    /** Removes the saved connection; what was decided about running commands on its host stays ({@link #exec}). */
    public void remove(String id) {
        connections.removeIf(c -> c.id().equals(id));
    }
}
