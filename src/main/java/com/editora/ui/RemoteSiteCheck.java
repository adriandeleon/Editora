package com.editora.ui;

/**
 * What is wrong with a value typed into the Settings → Remote site form, as a message key, or {@code null} when
 * it is fine. Used to refuse a value before it is saved (the field turns red and says why), instead of saving it
 * and failing later at Connect. Pure.
 */
final class RemoteSiteCheck {

    private RemoteSiteCheck() {}

    /** A host name or address; blank is allowed (a new site has none yet). */
    static String host(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return null;
        }
        if (t.chars().anyMatch(Character::isWhitespace)) {
            return "settings.remote.check.hostSpaces";
        }
        if (t.contains("@")) {
            return "settings.remote.check.hostUser";
        }
        if (t.matches("[^:\\[\\]]+:\\d*")) { // "host:2222" — an IPv6 address has several colons
            return "settings.remote.check.hostPort";
        }
        if (t.contains("/") || t.contains("\\") || t.startsWith("-")) {
            return "settings.remote.check.hostName";
        }
        return null;
    }

    /** A TCP port, 1–65535; blank means the default SFTP port. */
    static String port(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return null;
        }
        if (!t.matches("\\d{1,5}")) {
            return "settings.remote.check.port";
        }
        int port = Integer.parseInt(t);
        return port >= 1 && port <= 65535 ? null : "settings.remote.check.port";
    }

    /** A login name; blank is allowed (the server's default). */
    static String user(String text) {
        String t = text == null ? "" : text.strip();
        return t.chars().anyMatch(c -> Character.isWhitespace(c) || c == '@' || c == ':')
                ? "settings.remote.check.user"
                : null;
    }

    /** {@code path} with a leading {@code ~} (as the field's own hint shows it) replaced by {@code home}. */
    static String expandHome(String path, String home) {
        String t = path == null ? "" : path.strip();
        if (t.equals("~")) {
            return home;
        }
        if (t.startsWith("~/") || t.startsWith("~\\")) {
            return home + t.substring(1);
        }
        return t;
    }
}
