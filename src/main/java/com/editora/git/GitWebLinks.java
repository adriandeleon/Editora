package com.editora.git;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Web links to a repository's code at a commit — permalinks — from a remote's URL: GitHub (and GitHub Enterprise),
 * GitLab, Bitbucket Cloud and Bitbucket Server / Data Center, Gitea, Forgejo and Codeberg, sourcehut and Azure DevOps,
 * each in its own URL form; a host it does not know is taken to follow GitHub's, as most do. The host of an SSH remote
 * may be an {@code ~/.ssh/config} alias or an SSH-only endpoint ({@code ssh.github.com}): {@link #repo(String,
 * UnaryOperator)} takes the resolver that maps it to the site ({@link SshHosts#resolver()}). Pure for a pure resolver.
 */
public final class GitWebLinks {

    private GitWebLinks() {}

    /** The kinds of host, by how their links are written. */
    public enum Host {
        GITHUB,
        GITLAB,
        /** Bitbucket Cloud, {@code bitbucket.org}. */
        BITBUCKET,
        /** Bitbucket Server / Data Center: {@code /projects/KEY/repos/name/browse/…?at=…}. */
        BITBUCKET_SERVER,
        GITEA,
        SOURCEHUT,
        AZURE
    }

    /** A repository on the web: its kind of host and its address ({@code https://host/owner/repo}). */
    public record Repo(Host host, String base) {}

    private static final Pattern SCP = Pattern.compile("^(?:[\\w.+-]+@)?([^:/\\s]+):(?!//)(.+)$");
    private static final Pattern URL =
            Pattern.compile("^([a-zA-Z][\\w+.-]*)://(?:[^@/]*@)?([^/:]+)(?::(\\d+))?(/.*)?$");

    /**
     * The repository a remote URL names, or null when it is not on a web host (a local path, a {@code file://} URL,
     * a bare word). Handles {@code https://}, {@code http://}, {@code ssh://}, {@code git://} and scp-like {@code
     * git@host:owner/repo.git} forms; user names, ssh ports and {@code .git} are dropped.
     */
    public static @Nullable Repo repo(String remoteUrl) {
        return repo(remoteUrl, SshHosts::canonical);
    }

    /**
     * {@link #repo(String)}, the host of an SSH remote ({@code ssh://} or scp-like) first mapped by {@code sshHost} —
     * an {@code ~/.ssh/config} alias ({@code github-work}) to the host it stands for. An HTTPS URL names its host
     * outright and is never resolved.
     */
    public static @Nullable Repo repo(String remoteUrl, UnaryOperator<String> sshHost) {
        String url = remoteUrl.strip();
        String scheme;
        String host;
        String port = null;
        String path;
        Matcher full = URL.matcher(url);
        Matcher scp = SCP.matcher(url);
        if (full.matches()) {
            scheme = full.group(1).toLowerCase(Locale.ROOT);
            host = full.group(2);
            port = full.group(3);
            path = full.group(4) == null ? "" : full.group(4);
            if (scheme.equals("file")) {
                return null;
            }
        } else if (scp.matches() && !url.startsWith("/") && !url.matches("^[a-zA-Z]:[\\\\/].*")) {
            scheme = "ssh";
            host = scp.group(1);
            path = scp.group(2);
        } else {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (scheme.equals("ssh") || scheme.equals("git+ssh")) {
            String resolved = sshHost.apply(host);
            host = SshHosts.canonical(resolved == null || resolved.isBlank() ? host : resolved);
        }
        path = trimPath(path);
        if (path.isEmpty() || !host.contains(".") && !host.equals("localhost")) {
            return null;
        }
        boolean web = scheme.equals("https") || scheme.equals("http");
        Host kind = kindOf(host);
        if (kind == Host.AZURE) {
            return azure(host, path);
        }
        if (kind == Host.BITBUCKET && !host.equals("bitbucket.org")
                || kind == Host.GITHUB && !host.equals("github.com") && isBitbucketServerPath(web, path, port)) {
            String origin =
                    (scheme.equals("http") ? "http://" : "https://") + host + (web && port != null ? ":" + port : "");
            return bitbucketServer(origin, path, web);
        }
        if (kind == Host.SOURCEHUT && !path.startsWith("~")) {
            return null;
        }
        String origin =
                (scheme.equals("http") ? "http://" : "https://") + host + (web && port != null ? ":" + port : "");
        return new Repo(kind, origin + "/" + path);
    }

    /**
     * Whether a remote on a host whose name does not say Bitbucket is still a Bitbucket Server: its HTTPS clone URL
     * goes through {@code /scm/PROJECT/repo}, and its SSH one is on Bitbucket Server's port, 7999.
     */
    private static boolean isBitbucketServerPath(boolean web, String path, @Nullable String port) {
        String[] parts = path.split("/");
        if (web) {
            int scm = java.util.Arrays.asList(parts).indexOf("scm");
            return scm >= 0 && parts.length == scm + 3;
        }
        return "7999".equals(port) && parts.length == 2;
    }

    /**
     * Bitbucket Server / Data Center: {@code https://host[/context]/scm/proj/repo.git} or {@code
     * ssh://git@host:7999/proj/repo.git} is browsed at {@code https://host[/context]/projects/PROJ/repos/repo}; a
     * personal repository ({@code ~user/repo}) at {@code /users/user/repos/repo}.
     */
    private static @Nullable Repo bitbucketServer(String origin, String path, boolean web) {
        String context = "";
        String rest = path;
        int scm = path.startsWith("scm/") ? 0 : path.indexOf("/scm/") + 1;
        if (web && (scm > 0 || path.startsWith("scm/"))) {
            context = scm == 0 ? "" : "/" + path.substring(0, scm - 1);
            rest = path.substring(scm + 4);
        }
        String[] parts = rest.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        String owner = parts[0].startsWith("~")
                ? "users/" + parts[0].substring(1).toLowerCase(Locale.ROOT)
                : "projects/" + parts[0].toUpperCase(Locale.ROOT);
        return new Repo(Host.BITBUCKET_SERVER, origin + context + "/" + owner + "/repos/" + parts[1]);
    }

    private static String trimPath(String raw) {
        String path = raw;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
    }

    private static Host kindOf(String host) {
        if (host.equals("dev.azure.com") || host.endsWith(".dev.azure.com") || host.endsWith("visualstudio.com")) {
            return Host.AZURE;
        }
        if (host.equals("git.sr.ht")) {
            return Host.SOURCEHUT;
        }
        if (host.contains("gitlab")) {
            return Host.GITLAB;
        }
        if (host.contains("bitbucket")) {
            return Host.BITBUCKET;
        }
        if (host.contains("codeberg") || host.contains("gitea") || host.contains("forgejo")) {
            return Host.GITEA;
        }
        return Host.GITHUB;
    }

    /** Azure DevOps: {@code ssh.dev.azure.com:v3/org/project/repo} or {@code dev.azure.com/org/project/_git/repo}. */
    private static @Nullable Repo azure(String host, String path) {
        String[] parts = path.split("/");
        if (host.startsWith("ssh.") && parts.length == 4 && parts[0].equals("v3")) {
            return new Repo(Host.AZURE, "https://dev.azure.com/" + parts[1] + "/" + parts[2] + "/_git/" + parts[3]);
        }
        if (!path.contains("/_git/")) {
            return null;
        }
        String web = host.startsWith("ssh.") ? host.substring(4) : host;
        return new Repo(Host.AZURE, "https://" + web + "/" + path);
    }

    /**
     * The link to {@code path} (repository-relative, {@code /}-separated) at {@code commit}, its 0-based lines {@code
     * startLine..endLine} marked; a negative {@code startLine} marks none.
     */
    public static String fileUrl(Repo repo, String commit, String path, int startLine, int endLine) {
        String encoded = encode(path);
        boolean lines = startLine >= 0;
        int first = startLine + 1;
        int last = Math.max(startLine, endLine) + 1;
        boolean range = lines && last > first;
        return switch (repo.host()) {
            case GITHUB ->
                repo.base() + "/blob/" + commit + "/" + encoded
                        + (lines ? (rendered(path) ? "?plain=1" : "") + "#L" + first + (range ? "-L" + last : "") : "");
            case GITLAB ->
                repo.base() + "/-/blob/" + commit + "/" + encoded
                        + (lines ? "#L" + first + (range ? "-" + last : "") : "");
            case BITBUCKET ->
                repo.base() + "/src/" + commit + "/" + encoded
                        + (lines ? "#lines-" + first + (range ? ":" + last : "") : "");
            case BITBUCKET_SERVER ->
                repo.base() + "/browse/" + encoded + "?at=" + commit
                        + (lines ? "#" + first + (range ? "-" + last : "") : "");
            case GITEA ->
                repo.base() + "/src/commit/" + commit + "/" + encoded
                        + (lines ? "#L" + first + (range ? "-L" + last : "") : "");
            case SOURCEHUT ->
                repo.base() + "/tree/" + commit + "/item/" + encoded
                        + (lines ? "#L" + first + (range ? "-" + last : "") : "");
            case AZURE ->
                repo.base() + "?path=/" + encoded + "&version=GC" + commit
                        + (lines
                                ? "&line=" + first + "&lineEnd=" + (last + 1)
                                        + "&lineStartColumn=1&lineEndColumn=1&lineStyle=plain"
                                : "");
        };
    }

    /** The link to the folder {@code path} (repository-relative, {@code ""} for the repository) at {@code commit}. */
    public static String folderUrl(Repo repo, String commit, String path) {
        String encoded = encode(path);
        String tail = encoded.isEmpty() ? "" : "/" + encoded;
        return switch (repo.host()) {
            case GITHUB -> repo.base() + "/tree/" + commit + tail;
            case GITLAB -> repo.base() + "/-/tree/" + commit + tail;
            case BITBUCKET -> repo.base() + "/src/" + commit + tail + "/";
            case BITBUCKET_SERVER -> repo.base() + "/browse" + tail + "?at=" + commit;
            case GITEA -> repo.base() + "/src/commit/" + commit + tail;
            case SOURCEHUT -> repo.base() + "/tree/" + commit + (encoded.isEmpty() ? "" : "/item/" + encoded);
            case AZURE -> repo.base() + "?path=/" + encoded + "&version=GC" + commit;
        };
    }

    /**
     * The kinds of file GitHub shows rendered (Markdown, notebooks, images, tables): a line anchor there marks
     * nothing unless the link asks for the source with {@code ?plain=1}.
     */
    private static final Set<String> RENDERED = Set.of(
            "md",
            "markdown",
            "mdown",
            "mkd",
            "mkdn",
            "mdx",
            "rst",
            "adoc",
            "asciidoc",
            "asc",
            "org",
            "textile",
            "rdoc",
            "creole",
            "mediawiki",
            "wiki",
            "pod",
            "ipynb",
            "svg",
            "csv",
            "tsv",
            "geojson",
            "topojson",
            "stl");

    /** Whether GitHub shows {@code path} rendered rather than as source. Pure. */
    static boolean rendered(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && RENDERED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Each part of a path percent-encoded for a URL, the slashes kept. */
    static String encode(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }
}
