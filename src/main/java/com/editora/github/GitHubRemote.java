package com.editora.github;

import java.util.Locale;

/**
 * Pure classifier for a git remote URL: is it a GitHub host, and what is that host? Used as the fourth
 * self-gating check for the GitHub integration (beyond the {@code githubSupport} setting, {@code gh} being
 * on PATH, and {@code gh auth status} succeeding) so the PR/issue surfaces stay inert on a GitLab / Gitea /
 * Bitbucket repo where every {@code gh} call would just error.
 *
 * <p>This is a cheap pre-filter, not the authority — {@code gh} itself resolves owner/repo from the remote
 * and errors if it can't, so a GitHub Enterprise host with an unusual name still works via the palette
 * commands (which gate only on {@code gh} being usable); the host heuristic just decides whether to *show*
 * the always-on surfaces. Handles {@code https://…}, {@code ssh://git@…}, and scp-style
 * {@code git@host:org/repo.git} forms. Pure — unit-tested.
 */
public final class GitHubRemote {

    private GitHubRemote() {}

    /** Whether {@code remoteUrl} points at a GitHub (or GitHub Enterprise) host. */
    public static boolean isGitHub(String remoteUrl) {
        String host = hostOf(remoteUrl);
        if (host.isEmpty()) {
            return false;
        }
        return host.equals("github.com")
                || host.endsWith(".github.com")
                || host.endsWith(".ghe.com")
                // GitHub Enterprise Server is commonly named github.<company>.com / <anything>github<anything>;
                // this is the catch-all for on-prem installs. A false positive (e.g. "notgithub.example.com")
                // is harmless — gh simply errors and the error surfaces to the user.
                || host.contains("github");
    }

    /**
     * The host of a git remote URL, lowercased, or {@code ""} when it can't be parsed (a local path, a blank
     * string). Strips any {@code user@} credentials and {@code :port}.
     */
    public static String hostOf(String remoteUrl) {
        if (remoteUrl == null) {
            return "";
        }
        String s = remoteUrl.strip();
        if (s.isEmpty()) {
            return "";
        }
        String authority;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            // https://host/…, ssh://git@host:port/…
            String rest = s.substring(scheme + 3);
            int slash = rest.indexOf('/');
            authority = slash >= 0 ? rest.substring(0, slash) : rest;
        } else if (s.contains("@") && s.contains(":")) {
            // scp-style: git@host:org/repo.git
            int at = s.indexOf('@');
            int colon = s.indexOf(':', at);
            authority = s.substring(at + 1, colon);
        } else {
            return ""; // a local path or unrecognized form
        }
        // Drop any user@ and :port left in the authority.
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            authority = authority.substring(0, colon);
        }
        return authority.strip().toLowerCase(Locale.ROOT);
    }
}
