package com.editora.git;

/**
 * Small pure formatters for displaying Git data — extracted from {@code MainController} so they're testable
 * without the toolkit. Used for status messages, diff titles, and the blame "Annotate" column.
 */
public final class GitFormat {

    private GitFormat() {}

    /** The abbreviated (7-char) commit hash, or {@code ""} for a null hash; shorter hashes pass through. */
    public static String shortHash(String hash) {
        return hash == null ? "" : hash.substring(0, Math.min(7, hash.length()));
    }

    /** The author's first name (the part before the first space) for the compact blame column. */
    public static String shortAuthor(String author) {
        if (author == null) {
            return "";
        }
        String trimmed = author.strip();
        int sp = trimmed.indexOf(' ');
        return sp > 0 ? trimmed.substring(0, sp) : trimmed;
    }

    /**
     * A remote URL fit to put on screen: the credentials a URL may carry in its userinfo part
     * ({@code https://user:token@host/...}, a common way to store a PAT or deploy token) are removed. An
     * {@code http(s)} URL loses the whole userinfo (a bare user there is usually the token itself); other
     * schemes keep the user and lose only the password. An scp-style {@code git@host:path} has no secret.
     */
    public static String displayRemoteUrl(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int authority = scheme + 3;
        int end = url.indexOf('/', authority);
        int at = url.lastIndexOf('@', end < 0 ? url.length() : end);
        if (at < authority) {
            return url;
        }
        String userInfo = url.substring(authority, at);
        int colon = userInfo.indexOf(':');
        boolean http = url.regionMatches(true, 0, "http", 0, 4);
        String kept = http || colon < 0 ? (http ? "" : userInfo + "@") : userInfo.substring(0, colon) + "@";
        return url.substring(0, authority) + kept + url.substring(at + 1);
    }
}
