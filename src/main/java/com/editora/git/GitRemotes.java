package com.editora.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for a repository's remotes: the parser for {@code git remote -v} and the split of a
 * remote-tracking branch's short name into its remote and branch. Toolkit-free and unit-tested.
 */
public final class GitRemotes {

    private GitRemotes() {}

    /** One remote: its name and the URLs it fetches from and pushes to (usually the same). */
    public record Remote(String name, String fetchUrl, String pushUrl) {}

    /** A remote-tracking branch split into the remote it lives on and its name there. */
    public record RemoteBranch(String remote, String branch) {}

    /**
     * Parses {@code git remote -v}: {@code <name>\t<url> (fetch)} and {@code <name>\t<url> (push)} lines,
     * in git's order. A remote with no URL of a kind keeps {@code ""} there.
     */
    public static List<Remote> parse(String remoteV) {
        Map<String, String[]> byName = new LinkedHashMap<>();
        if (remoteV != null) {
            for (String raw : remoteV.split("\\R")) {
                int tab = raw.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String name = raw.substring(0, tab);
                String rest = raw.substring(tab + 1).strip();
                String[] urls = byName.computeIfAbsent(name, n -> new String[] {"", ""});
                if (rest.endsWith(" (fetch)")) {
                    urls[0] = rest.substring(0, rest.length() - " (fetch)".length());
                } else if (rest.endsWith(" (push)")) {
                    urls[1] = rest.substring(0, rest.length() - " (push)".length());
                } else if (urls[0].isEmpty()) {
                    urls[0] = rest; // `git remote -v` of a very old git has no kind suffix
                }
            }
        }
        List<Remote> out = new ArrayList<>();
        byName.forEach((name, urls) -> out.add(new Remote(name, urls[0], urls[1].isEmpty() ? urls[0] : urls[1])));
        return out;
    }

    /**
     * Splits {@code origin/feature/x} into {@code origin} and {@code feature/x}. A remote's name may itself
     * contain a slash, so the longest of {@code remoteNames} that prefixes the branch wins; with no known
     * remote matching, the split is at the first slash. {@code null} when there is nothing to split.
     */
    public static RemoteBranch split(String remoteBranch, List<String> remoteNames) {
        if (remoteBranch == null) {
            return null;
        }
        String best = null;
        for (String name : remoteNames == null ? List.<String>of() : remoteNames) {
            if (remoteBranch.startsWith(name + "/")
                    && remoteBranch.length() > name.length() + 1
                    && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        if (best == null) {
            int slash = remoteBranch.indexOf('/');
            if (slash <= 0 || slash == remoteBranch.length() - 1) {
                return null;
            }
            best = remoteBranch.substring(0, slash);
        }
        return new RemoteBranch(best, remoteBranch.substring(best.length() + 1));
    }
}
