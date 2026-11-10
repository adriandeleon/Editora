package com.editora.git;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Web links to a repository's code at a commit — permalinks — from a remote's URL: GitHub (and GitHub Enterprise),
 * GitLab, Bitbucket, Gitea, Forgejo and Codeberg, sourcehut and Azure DevOps, each in its own URL form; a host it does
 * not know is taken to follow GitHub's, as most do. Pure.
 */
public final class GitWebLinks {

    private GitWebLinks() {}

    /** The kinds of host, by how their links are written. */
    public enum Host {
        GITHUB,
        GITLAB,
        BITBUCKET,
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
        path = trimPath(path);
        if (path.isEmpty() || !host.contains(".") && !host.equals("localhost")) {
            return null;
        }
        Host kind = kindOf(host);
        if (kind == Host.AZURE) {
            return azure(host, path);
        }
        if (kind == Host.SOURCEHUT && !path.startsWith("~")) {
            return null;
        }
        boolean web = scheme.equals("https") || scheme.equals("http");
        String origin =
                (scheme.equals("http") ? "http://" : "https://") + host + (web && port != null ? ":" + port : "");
        return new Repo(kind, origin + "/" + path);
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
                        + (lines ? "#L" + first + (range ? "-L" + last : "") : "");
            case GITLAB ->
                repo.base() + "/-/blob/" + commit + "/" + encoded
                        + (lines ? "#L" + first + (range ? "-" + last : "") : "");
            case BITBUCKET ->
                repo.base() + "/src/" + commit + "/" + encoded
                        + (lines ? "#lines-" + first + (range ? ":" + last : "") : "");
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
            case GITEA -> repo.base() + "/src/commit/" + commit + tail;
            case SOURCEHUT -> repo.base() + "/tree/" + commit + (encoded.isEmpty() ? "" : "/item/" + encoded);
            case AZURE -> repo.base() + "?path=/" + encoded + "&version=GC" + commit;
        };
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
