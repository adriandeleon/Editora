package com.editora.github;

import java.util.Collection;
import java.util.Locale;

/**
 * Pure classifier for a git remote URL: is it a GitHub host, and what is that host? Used as the fourth
 * self-gating check for the GitHub integration (beyond the {@code githubSupport} setting, {@code gh} being
 * on PATH, and {@code gh} being signed in) so the PR/issue surfaces stay inert on a GitLab / Gitea /
 * Bitbucket repo where every {@code gh} call would just error.
 *
 * <p>The authority is {@code gh} itself: a remote is a GitHub remote when its host is one {@code gh} has an
 * account on ({@code gh auth status --json hosts}) — which covers a GitHub Enterprise Server whose name says
 * nothing about GitHub. Only when those hosts are not known (an older gh, an inconclusive probe) does the
 * host-name heuristic decide. This only gates the always-on surfaces; the palette commands run regardless and
 * surface gh's own error. Handles {@code https://…}, {@code ssh://git@…}, and scp-style
 * {@code [user@]host:org/repo.git} forms. Pure — unit-tested.
 */
public final class GitHubRemote {

    private GitHubRemote() {}

    /** Whether {@code remoteUrl} looks like a GitHub (or GitHub Enterprise) host, by its name alone. */
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
     * Whether {@code remoteUrl} is on a host {@code gh} can talk to: one of {@code ghHosts} (the hosts gh has
     * an account on) when those are known, else by the {@link #isGitHub(String) name heuristic}.
     */
    public static boolean isGitHub(String remoteUrl, Collection<String> ghHosts) {
        if (ghHosts == null || ghHosts.isEmpty()) {
            return isGitHub(remoteUrl);
        }
        String host = hostOf(remoteUrl);
        if (host.isEmpty()) {
            return false;
        }
        for (String known : ghHosts) {
            if (known != null && host.equals(known.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether <em>any</em> of a repository's remotes is a GitHub remote ({@link #isGitHub(String, Collection)})
     * — {@code gh} works from a fork whose {@code origin} is elsewhere as long as one remote is on GitHub.
     */
    public static boolean anyGitHub(Collection<String> remoteUrls, Collection<String> ghHosts) {
        if (remoteUrls == null) {
            return false;
        }
        for (String url : remoteUrls) {
            if (isGitHub(url, ghHosts)) {
                return true;
            }
        }
        return false;
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
        } else {
            // scp-style: [user@]host:org/repo.git. As git reads it: a colon with no slash before it. A
            // one-letter "host" is a Windows drive (C:\repo, C:/repo), and a leading . or / a local path.
            int colon = s.indexOf(':');
            int slash = s.indexOf('/');
            int backslash = s.indexOf('\\');
            if (colon < 0
                    || (slash >= 0 && slash < colon)
                    || (backslash >= 0 && backslash < colon)
                    || s.charAt(0) == '.') {
                return ""; // a local path or unrecognized form
            }
            authority = s.substring(0, colon);
            if (authority.substring(authority.lastIndexOf('@') + 1).length() < 2) {
                return "";
            }
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
