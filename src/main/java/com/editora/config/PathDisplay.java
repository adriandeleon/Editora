package com.editora.config;

import java.nio.file.Path;

/**
 * How a path is written when it is shown to a person: with the home directory collapsed to {@code ~}.
 *
 * <p>One implementation, because there were three — two identical private copies in {@code ui} and a
 * better one in {@code doctor} — and the surfaces that used none of them (the status bar's "Opened…"
 * messages) printed a raw absolute path beside a title bar and a breadcrumb that both collapsed it.
 *
 * <p>Pure: the home directory is a parameter on the primary method, so the rules are testable without
 * depending on the machine the tests run on.
 */
public final class PathDisplay {

    private PathDisplay() {}

    /**
     * {@code text} with a leading {@code home} replaced by {@code ~}.
     *
     * <p>Matches on a whole path segment, never a string prefix: {@code /home/adl2} is not inside
     * {@code /home/adl}, and collapsing it would name a directory the user does not have. A trailing
     * separator on {@code home} is tolerated because {@code user.home} is allowed to carry one, and a
     * {@code home} of {@code /} is ignored — every absolute path would otherwise become {@code ~}.
     */
    public static String collapseHome(String text, String home) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String h = stripTrailingSeparator(home);
        if (h.isEmpty() || h.equals("/")) {
            return text;
        }
        if (text.equals(h)) {
            return "~";
        }
        if (text.startsWith(h) && text.length() > h.length()) {
            char next = text.charAt(h.length());
            if (next == '/' || next == '\\') {
                return "~" + text.substring(h.length());
            }
        }
        return text;
    }

    /** {@link #collapseHome(String, String)} against the running user's home directory. */
    public static String collapseHome(String text) {
        return collapseHome(text, System.getProperty("user.home", ""));
    }

    /**
     * A path as shown to a person. Null-safe, and inert for a remote (SFTP) path, whose string cannot
     * begin with the local home directory.
     */
    public static String of(Path path) {
        return path == null ? "" : collapseHome(path.toString());
    }

    private static String stripTrailingSeparator(String path) {
        if (path == null) {
            return "";
        }
        String p = path;
        while (p.length() > 1 && (p.endsWith("/") || p.endsWith("\\"))) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
