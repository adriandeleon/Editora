package com.editora.vfs;

/**
 * The (non-secret) description of an SFTP connection: where to connect and how to authenticate. The secret
 * itself (a password or a key passphrase) is never stored here — it is passed transiently to
 * {@link RemoteFileSystems#connect}. Persisted (in R2) as the {@code connections.json} entry.
 */
public record RemoteConnection(
        String host, int port, String user, AuthMethod auth, String keyPath, String label, String lastPath) {

    public enum AuthMethod {
        /** Try the user's default key files in {@code ~/.ssh} (id_ed25519 / id_rsa / …). */
        DEFAULT_KEYS,
        /** A specific private-key file ({@link #keyPath}), passphrase prompted if needed. */
        KEY,
        /** A password, prompted per session. */
        PASSWORD
    }

    public RemoteConnection {
        if (port <= 0) {
            port = SftpUri.DEFAULT_PORT;
        }
    }

    /** The connection key — equal to {@link SftpUri#authority()} for paths on this host. */
    public String id() {
        return (user == null || user.isEmpty() ? "" : user + "@") + host + ":" + port;
    }

    /** A short label for the UI (the user-set label, else {@code user@host}). */
    public String displayLabel() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return (user == null || user.isEmpty() ? "" : user + "@") + host;
    }
}
