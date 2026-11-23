package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * The real host behind the host of an SSH remote. A remote such as {@code git@github-work:owner/repo.git} names a
 * {@code Host} alias of {@code ~/.ssh/config} (two accounts on one site), and {@code
 * ssh://git@ssh.github.com:443/owner/repo.git} names GitHub's SSH-over-443 endpoint: neither is the host the web
 * pages, or {@code gh}, know. {@link #resolve} asks {@code ssh -G <alias>} for the host ssh would connect to — the
 * user's own config, read by ssh itself — and falls back to reading {@code ~/.ssh/config} when ssh cannot be run;
 * {@link #canonical} then maps the SSH-only endpoints to their sites. Only SSH remotes are resolved (an HTTPS URL
 * names its host outright), each alias once per session.
 *
 * <p>{@link #resolve} starts a process: call it off the FX thread. The parsing is pure and unit-tested.
 */
public final class SshHosts {

    private SshHosts() {}

    /** How long {@code ssh -G} may take: it reads files, it does not connect. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** A host token ssh would take as a host, never as an option. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    /** Each alias's answer, for the session. */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

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

    /**
     * The host ssh connects to for {@code alias}, canonical ({@link #canonical}); {@code alias} itself (canonical)
     * when it is no alias or nothing could be read. Runs {@code ssh -G} the first time an alias is asked: off the FX
     * thread.
     */
    public static String resolve(String alias) {
        String a = alias == null ? "" : alias.strip().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || !HOST.matcher(a).matches()) {
            return canonical(a);
        }
        return CACHE.computeIfAbsent(a, SshHosts::lookUp);
    }

    /** The resolver to hand {@link GitWebLinks#repo(String, UnaryOperator)}. */
    public static UnaryOperator<String> resolver() {
        return SshHosts::resolve;
    }

    private static String lookUp(String alias) {
        String found = null;
        try {
            ProcessRunner.Result r = ProcessRunner.run(null, TIMEOUT, List.of("ssh", "-G", alias));
            if (r.ok()) {
                found = hostnameOf(r.out());
            }
        } catch (RuntimeException noSsh) {
            // not on PATH, or not launchable: read the config ourselves
        }
        if (found == null) {
            found = fromConfig(readConfig(), alias);
        }
        return canonical(found == null || found.isBlank() ? alias : found);
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

    /**
     * The {@code HostName} that {@code config} (the text of an {@code ~/.ssh/config}) gives {@code alias}: the first
     * one in a {@code Host} block whose patterns match it, as ssh takes the first value it meets; null with none.
     * Patterns with {@code *} and {@code ?} and negated ones ({@code !pattern}) are understood; {@code Match} blocks
     * and {@code Include} are not (ssh itself, when it runs, does both). A {@code %h} in the value is the alias.
     * Pure.
     */
    static @Nullable String fromConfig(String config, String alias) {
        if (config == null || alias == null) {
            return null;
        }
        String a = alias.toLowerCase(Locale.ROOT);
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
            } else if (matching && key.equals("hostname") && !value.isEmpty()) {
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
            String glob = (negated ? p.substring(1) : p).toLowerCase(Locale.ROOT);
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
        return resolveUrl(remoteUrl, SshHosts::resolve);
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
