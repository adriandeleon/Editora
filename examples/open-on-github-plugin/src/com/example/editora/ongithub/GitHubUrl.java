package com.example.editora.ongithub;

/** Pure builder: turn a git remote URL + branch + repo-relative path + line into a web "blob" URL. */
final class GitHubUrl {

    private GitHubUrl() {
    }

    /** Normalizes a git remote to {@code https://host/owner/repo} (handles scp/ssh/https, strips {@code .git}). */
    static String normalizeRemote(String remote) {
        String r = remote == null ? "" : remote.strip();
        if (r.endsWith(".git")) {
            r = r.substring(0, r.length() - 4);
        }
        if (r.startsWith("git@")) {                       // git@github.com:owner/repo
            int colon = r.indexOf(':');
            if (colon > 0) {
                return "https://" + r.substring(4, colon) + "/" + r.substring(colon + 1);
            }
        }
        if (r.startsWith("ssh://")) {                     // ssh://git@github.com/owner/repo
            String rest = r.substring("ssh://".length());
            int at = rest.indexOf('@');
            if (at >= 0) {
                rest = rest.substring(at + 1);
            }
            return "https://" + rest;
        }
        if (r.startsWith("http://")) {
            return "https://" + r.substring("http://".length());
        }
        return r;                                         // already https://host/owner/repo
    }

    /** Builds {@code <base>/blob/<branch>/<relPath>#L<line>} ({@code line<=0} omits the anchor). */
    static String build(String remote, String branch, String relPath, int line) {
        String base = normalizeRemote(remote);
        String rel = relPath.replace('\\', '/');
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        String url = base + "/blob/" + branch + "/" + rel;
        return line > 0 ? url + "#L" + line : url;
    }
}
