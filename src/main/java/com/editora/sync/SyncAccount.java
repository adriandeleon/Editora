package com.editora.sync;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.editora.git.QuietGit;

/**
 * Which GitHub CLI account settings sync signs in as.
 *
 * <p>Someone who works with two accounts ({@code gh auth switch}) has a credential helper that hands git
 * the token of whichever account is active at that moment, so a sync repository of the personal account
 * stops answering as soon as the work account is the active one. Sync therefore finds out once which of
 * gh's accounts can read the repository, remembers its name in the clone's own configuration
 * ({@value #KEY}), and from then on asks gh for that account's token by name. Editora never sees the token:
 * git runs gh as its credential helper.
 *
 * <p>Only an {@code https://} repository on a host gh has an account for is affected. Anything else — an
 * SSH URL, another credential helper, no gh — signs in exactly as the user's git does. Blocking; the caller
 * picks the thread.
 */
public final class SyncAccount {

    /** The clone's configuration key holding the account's login. Gone with the clone on Disconnect. */
    static final String KEY = "editora.syncAccount";

    private SyncAccount() {}

    /** The lower-cased host of an {@code https://} URL; blank for any other kind of remote. Pure. */
    public static String httpsHost(String url) {
        if (url == null || !url.strip().toLowerCase(Locale.ROOT).startsWith("https://")) {
            return "";
        }
        try {
            String host = URI.create(url.strip()).getHost();
            return host != null && host.matches("[A-Za-z0-9.-]+") ? host.toLowerCase(Locale.ROOT) : "";
        } catch (IllegalArgumentException notAUrl) {
            return "";
        }
    }

    /**
     * The {@code -c} pairs that make git sign in to {@code host} as gh's account {@code login}: the helpers
     * the user configured are dropped for this one command and replaced by one that prints
     * {@code gh auth token --user <login>}. Empty when a part cannot be written into a shell command safely.
     * Pure.
     *
     * <p>The helper is a shell snippet (git runs {@code !} helpers through its own {@code sh}, on Windows
     * too) with no double quote in it, which is what a Windows command line would mangle.
     */
    public static List<String> credentialConfig(List<String> ghCommand, String host, String login) {
        if (ghCommand == null
                || ghCommand.isEmpty()
                || host == null
                || !host.matches("[A-Za-z0-9.-]+")
                || !isLogin(login)) {
            return List.of();
        }
        StringBuilder gh = new StringBuilder();
        for (String token : ghCommand) {
            if (token.isEmpty() || token.indexOf('\'') >= 0 || token.indexOf('\n') >= 0) {
                return List.of();
            }
            gh.append('\'').append(token).append("' ");
        }
        String helper = "!f() { case $1 in get) ;; *) exit 0 ;; esac; t=$(" + gh + "auth token --hostname " + host
                + " --user " + login + ") && echo username=" + login + " && echo password=$t; }; f";
        String key = "credential.https://" + host + ".helper=";
        return List.of("-c", key, "-c", key + helper);
    }

    /** Whether {@code login} looks like an account name (and so is safe inside the helper). Pure. */
    static boolean isLogin(String login) {
        return login != null && login.matches("[A-Za-z0-9_][A-Za-z0-9_-]*");
    }

    /** The account remembered for {@code url} in the clone; blank when there is none or it is not usable. */
    public static String stored(QuietGit git, String url) {
        if (!java.nio.file.Files.isDirectory(git.dir().resolve(".git"))) {
            return "";
        }
        var origin = git.run("config", "--local", "--get", "remote.origin.url");
        if (!origin.ok() || url == null || !origin.out().strip().equals(url.strip())) {
            return ""; // remembered for another repository
        }
        var login = git.run("config", "--local", "--get", KEY);
        return login.ok() && isLogin(login.out().strip()) ? login.out().strip() : "";
    }

    /**
     * The account to sign in as, looked for when needed.
     *
     * <p>A remembered account is returned as it is unless {@code verify} asks for a fresh look (the last
     * sync could not reach the repository). Otherwise, when {@code search} allows it, each of gh's accounts
     * on the repository's host is tried — the remembered one first, then the active one — with one
     * {@code git ls-remote} that can use nothing but that account, and the first that is let in is
     * remembered. An account gh no longer has is forgotten. Finding none changes nothing: the repository may
     * simply be out of reach.
     *
     * @param git a runner on the clone
     * @param logins gh's accounts on a host, the active one first
     * @return the login, or blank to sign in the way the user's git does
     */
    public static String choose(
            QuietGit git,
            List<String> ghCommand,
            String url,
            Function<String, List<String>> logins,
            boolean verify,
            boolean search) {
        String host = httpsHost(url);
        if (host.isEmpty() || !java.nio.file.Files.isDirectory(git.dir().resolve(".git"))) {
            return "";
        }
        String stored = stored(git, url);
        if (stored.isEmpty() ? !search : !verify) {
            return stored;
        }
        List<String> known = logins.apply(host);
        if (known.isEmpty()) {
            return stored; // gh did not answer; what is remembered may still be right
        }
        List<String> candidates = new ArrayList<>();
        if (known.contains(stored)) {
            candidates.add(stored);
        }
        for (String login : known) {
            if (isLogin(login) && !candidates.contains(login)) {
                candidates.add(login);
            }
        }
        QuietGit silent = git.silent();
        for (String login : candidates) {
            List<String> config = credentialConfig(ghCommand, host, login);
            if (!config.isEmpty()
                    && silent.with(config).network("ls-remote", "--quiet", url).ok()) {
                if (!login.equals(stored)) {
                    git.run("config", "--local", KEY, login);
                }
                return login;
            }
        }
        if (!stored.isEmpty() && !known.contains(stored)) {
            git.run("config", "--local", "--unset", KEY);
            return "";
        }
        return stored;
    }
}
