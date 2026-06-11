package com.editora.vfs;

/**
 * A parsed {@code sftp://[user@]host[:port]/path} URI — the storable form of a remote file and the key for
 * a cached connection. Pure (no toolkit, no network), so it is unit-tested.
 */
public record SftpUri(String user, String host, int port, String path) {

    public static final int DEFAULT_PORT = 22;

    /** Parses {@code sftp://[user@]host[:port]/path}; returns {@code null} if it is not a valid sftp URI. */
    public static SftpUri parse(String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.strip();
        if (!s.regionMatches(true, 0, "sftp://", 0, 7)) {
            return null;
        }
        String rest = s.substring(7);
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);

        String user = "";
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            user = authority.substring(0, at);
            authority = authority.substring(at + 1);
        }
        int port = DEFAULT_PORT;
        int colon = authority.indexOf(':');
        String host = authority;
        if (colon >= 0) {
            host = authority.substring(0, colon);
            try {
                port = Integer.parseInt(authority.substring(colon + 1).strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            return null;
        }
        return new SftpUri(user, host, port, path.isEmpty() ? "/" : path);
    }

    /** The {@code sftp://user@host[:port]/path} string (the default port is omitted). */
    public String format() {
        StringBuilder sb = new StringBuilder("sftp://");
        if (user != null && !user.isEmpty()) {
            sb.append(user).append('@');
        }
        sb.append(host);
        if (port != DEFAULT_PORT) {
            sb.append(':').append(port);
        }
        return sb.append(path).toString();
    }

    /** The connection key ({@code user@host:port}) — all paths on one host+user+port share a filesystem. */
    public String authority() {
        return (user == null || user.isEmpty() ? "" : user + "@") + host + ":" + port;
    }

    /** A short {@code host:/path} label for the UI. */
    public String label() {
        return host + ":" + path;
    }
}
