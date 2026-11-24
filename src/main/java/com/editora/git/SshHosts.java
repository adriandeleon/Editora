package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * The real host behind the host of an SSH remote. A remote such as {@code git@github-work:owner/repo.git} names a
 * {@code Host} alias of {@code ~/.ssh/config} (two accounts on one site), and {@code
 * ssh://git@ssh.github.com:443/owner/repo.git} names GitHub's SSH-over-443 endpoint: neither is the host the web
 * pages, or {@code gh}, know. {@link #resolve} asks {@code ssh -G <alias>} for the host (and port) ssh would connect
 * to — the user's own config, read by ssh itself, on the machine the repository is on (a remote project's {@code ssh},
 * through its exec target) — and, for a local repository, falls back to reading {@code ~/.ssh/config} when ssh cannot
 * be run; {@link #canonical} then maps the SSH-only endpoints to their sites. Only SSH remotes are resolved (an HTTPS
 * URL names its host outright).
 *
 * <p>The alias is passed to ssh as it is written: ssh matches {@code Host} patterns case-sensitively. An answer of
 * {@code ssh -G} is kept until {@link #invalidate} ({@code github.refresh}) or until {@code ~/.ssh/config} changes; an
 * answer that is only a fallback (ssh failed or timed out) is kept for {@link #FALLBACK_TTL}, so a slow ssh is asked
 * again rather than believed for the session.
 *
 * <p>{@link #resolve} starts a process: call it off the FX thread. The parsing is pure and unit-tested.
 */
public final class SshHosts {

    private SshHosts() {}

    /** How long {@code ssh -G} may take: it reads files, it does not connect. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** How long an answer that is only a fallback (ssh failed, timed out or is missing) is kept. */
    static final Duration FALLBACK_TTL = Duration.ofSeconds(60);

    /** A host token ssh would take as a host, never as an option. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    /** The host (canonical, lower-cased) and port ({@code null} when not known) ssh connects to for an alias. */
    public record Resolved(String host, @Nullable String port) {

        /** A host with no port known. */
        public static Resolved of(String host) {
            return new Resolved(host, null);
        }
    }

    /** One answer, and until when it holds ({@link Long#MAX_VALUE}: until invalidated). */
    private record Entry(Resolved answer, long expiresAtNanos) {}

    /** Each (machine, alias)'s answer. Keyed by the alias as written: ssh's Host matching is case-sensitive. */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /** The modification time of {@code ~/.ssh/config} the cache was filled under ({@code -2}: not looked at yet). */
    private static volatile long configStamp = -2;

    /** The SSH-only endpoints of the big sites, and the site each serves. */
    private static final Map<String, String> ENDPOINTS = Map.of(
            "ssh.github.com", "github.com",
            "altssh.gitlab.com", "gitlab.com",
            "altssh.bitbucket.org", "bitbucket.org");

    /** {@code host} with an SSH-only endpoint ({@code ssh.github.com}) mapped to its site; lower-cased. Pure. */
    public static String canonical(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        return ENDPOINTS.getOrDefault(h, h);
    }

    /** Forgets every answer ({@code github.refresh}): the next lookup asks ssh again. */
    public static void invalidate() {
        CACHE.clear();
    }

    /** {@link #resolve(Path, String)} for a repository on this machine. */
    public static String resolve(String alias) {
        return resolve(null, alias);
    }

    /**
     * The host ssh connects to for {@code alias}, canonical ({@link #canonical}), on the machine {@code dir} is on;
     * {@code alias} itself (canonical) when it is no alias or nothing could be read. Runs {@code ssh -G}: off the FX
     * thread.
     */
    public static String resolve(@Nullable Path dir, String alias) {
        return lookup(dir, alias).host();
    }

    /** {@link #resolve(Path, String)} with the port ssh would use ({@code null} when not known). Off the FX thread. */
    public static Resolved lookup(@Nullable Path dir, String alias) {
        String a = alias == null ? "" : alias.strip();
        if (a.isEmpty() || !HOST.matcher(a).matches()) {
            return Resolved.of(canonical(a));
        }
        boolean local = ExecTargets.runsHere(dir);
        if (local) {
            long stamp = configModified();
            if (stamp != configStamp) {
                configStamp = stamp;
                CACHE.clear(); // ~/.ssh/config changed (or was first looked at): every answer may be stale
            }
        }
        String key = (local ? "" : ExecTargets.forPath(dir).id()) + "\n" + a;
        Entry cached = CACHE.get(key);
        long now = System.nanoTime();
        if (cached != null && (cached.expiresAtNanos() == Long.MAX_VALUE || now - cached.expiresAtNanos() < 0)) {
            return cached.answer();
        }
        // Not computeIfAbsent: ssh may take seconds, and the map's lock must not be held meanwhile. Two threads
        // asking at once both run ssh; the answers are the same.
        Lookup found = lookUp(local ? null : dir, a, local);
        CACHE.put(
                key,
                new Entry(
                        found.answer(), found.fromSsh() ? Long.MAX_VALUE : System.nanoTime() + FALLBACK_TTL.toNanos()));
        return found.answer();
    }

    /** The resolver to hand {@link GitWebLinks#repo(String, Function)}, for a repository on this machine. */
    public static Function<String, Resolved> resolver() {
        return resolver(null);
    }

    /** The resolver to hand {@link GitWebLinks#repo(String, Function)}, for the repository at {@code dir}. */
    public static Function<String, Resolved> resolver(@Nullable Path dir) {
        return alias -> lookup(dir, alias);
    }

    private record Lookup(Resolved answer, boolean fromSsh) {}

    private static Lookup lookUp(@Nullable Path dir, String alias, boolean local) {
        String found = null;
        String port = null;
        boolean fromSsh = false;
        try {
            ProcessRunner.Result r = ProcessRunner.run(dir, TIMEOUT, List.of("ssh", "-G", alias));
            if (r.ok()) {
                found = hostnameOf(r.out());
                port = portOf(r.out());
                fromSsh = found != null;
            }
        } catch (RuntimeException noSsh) {
            // not on PATH, or not launchable: read the config ourselves (on this machine only)
        }
        if (found == null && local) {
            // A remote project's own ~/.ssh/config is not this machine's: a wrong answer is worse than none there.
            String config = readConfig();
            found = fromConfig(config, alias);
            port = fromConfig(config, alias, "port");
        }
        if (found == null || found.isBlank()) {
            return new Lookup(Resolved.of(canonical(alias)), fromSsh);
        }
        return new Lookup(new Resolved(canonical(found), port), fromSsh);
    }

    private static long configModified() {
        try {
            Path config = Path.of(System.getProperty("user.home"), ".ssh", "config");
            return Files.isRegularFile(config)
                    ? Files.getLastModifiedTime(config).toMillis()
                    : -1;
        } catch (Exception unreadable) {
            return -1;
        }
    }

    private static String readConfig() {
        try {
            Path config = Path.of(System.getProperty("user.home"), ".ssh", "config");
            return Files.isRegularFile(config) ? Files.readString(config) : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** The {@code hostname} line of {@code ssh -G}'s output, or null when it has none. Pure. */
    static @Nullable String hostnameOf(String sshGOutput) {
        if (sshGOutput == null) {
            return null;
        }
        for (String line : sshGOutput.split("\\R")) {
            String l = line.strip();
            int space = l.indexOf(' ');
            if (space > 0 && l.substring(0, space).equalsIgnoreCase("hostname")) {
                String value = l.substring(space + 1).strip();
                return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /** The {@code port} line of {@code ssh -G}'s output, or null when it has none. Pure. */
    static @Nullable String portOf(String sshGOutput) {
        if (sshGOutput == null) {
            return null;
        }
        for (String line : sshGOutput.split("\\R")) {
            String l = line.strip();
            int space = l.indexOf(' ');
            if (space > 0 && l.substring(0, space).equalsIgnoreCase("port")) {
                String value = l.substring(space + 1).strip();
                return value.matches("\\d+") ? value : null;
            }
        }
        return null;
    }

    /**
     * The {@code HostName} that {@code config} (the text of an {@code ~/.ssh/config}) gives {@code alias}: the first
     * one in a {@code Host} block whose patterns match it, as ssh takes the first value it meets; null with none.
     * Patterns with {@code *} and {@code ?} and negated ones ({@code !pattern}) are understood; {@code Match} blocks
     * and {@code Include} are not (ssh itself, when it runs, does both). A {@code %h} in the value is the alias.
     * Pure.
     */
    static @Nullable String fromConfig(String config, String alias) {
        return fromConfig(config, alias, "hostname");
    }

    /**
     * The first value of {@code keyword} (lower-case: {@code hostname}, {@code port}) that {@code config} gives
     * {@code alias}, as {@link #fromConfig(String, String)} reads it; lower-cased; null with none. Pure.
     */
    static @Nullable String fromConfig(String config, String alias, String keyword) {
        if (config == null || alias == null) {
            return null;
        }
        String a = alias; // as written: ssh matches Host patterns case-sensitively
        boolean matching = true; // lines before the first Host apply to every host
        for (String raw : config.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] kv = line.split("[\\s=]+", 2);
            String key = kv[0].toLowerCase(Locale.ROOT);
            String value = kv.length > 1 ? kv[1].strip() : "";
            if (key.equals("host")) {
                matching = matches(value, a);
            } else if (key.equals("match")) {
                matching = false;
            } else if (matching && key.equals(keyword) && !value.isEmpty()) {
                return unquote(value).replace("%h", a).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean matches(String patterns, String alias) {
        boolean any = false;
        for (String p : unquote(patterns).split("\\s+")) {
            if (p.isEmpty()) {
                continue;
            }
            boolean negated = p.startsWith("!");
            String glob = negated ? p.substring(1) : p;
            if (globMatches(glob, alias)) {
                if (negated) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    private static boolean globMatches(String glob, String text) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return text.matches(regex.toString());
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /**
     * {@code remoteUrl} with its host replaced by the host ssh really connects to, when it is an SSH remote (an
     * {@code ssh://} URL or the scp-like {@code [user@]host:path}); any other URL is returned as it is. Starts
     * {@code ssh} the first time a host is seen: off the FX thread.
     */
    public static String resolveUrl(String remoteUrl) {
        return resolveUrl(null, remoteUrl);
    }

    /** {@link #resolveUrl(String)} for a repository at {@code dir}: ssh is asked on the machine it is on. */
    public static String resolveUrl(@Nullable Path dir, String remoteUrl) {
        return resolveUrl(remoteUrl, alias -> resolve(dir, alias));
    }

    /** {@link #resolveUrl(String)} with {@code resolver} in place of {@code ssh -G}. Pure for a pure resolver. */
    static String resolveUrl(String remoteUrl, UnaryOperator<String> resolver) {
        if (remoteUrl == null) {
            return "";
        }
        String url = remoteUrl.strip();
        java.util.regex.Matcher ssh = SSH_URL.matcher(url);
        if (ssh.matches()) {
            String resolved = resolver.apply(ssh.group(2));
            return ssh.group(1) + resolved + ssh.group(3);
        }
        java.util.regex.Matcher scp = SCP_URL.matcher(url);
        if (scp.matches() && !url.matches("^[a-zA-Z]:[\\\\/].*")) {
            String resolved = resolver.apply(scp.group(2));
            return scp.group(1) + resolved + scp.group(3);
        }
        return url;
    }

    /** {@code ssh://[user@]host[:port]/path} (also {@code git+ssh://}), split around the host. */
    private static final Pattern SSH_URL =
            Pattern.compile("^((?:git\\+)?ssh://(?:[^@/]*@)?)([^/:@]+)((?::\\d+)?(?:/.*)?)$", Pattern.CASE_INSENSITIVE);

    /** The scp-like {@code [user@]host:path}, split around the host. */
    private static final Pattern SCP_URL = Pattern.compile("^((?:[\\w.+-]+@)?)([^:/\\s@]+)(:(?!//).+)$");
}
