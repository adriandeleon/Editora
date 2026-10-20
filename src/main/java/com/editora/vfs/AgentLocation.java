package com.editora.vfs;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Where the SSH agent is, as {@code ssh} finds it: the config's {@code IdentityAgent} for the host when it names
 * one ({@code none} turns the agent off; {@code SSH_AUTH_SOCK} or {@code $VAR} reads that variable; a path may
 * start with {@code ~} and use {@code %d} and {@code %u}), else {@code $SSH_AUTH_SOCK}, else — on Windows — the
 * OpenSSH agent's named pipe. A Unix-domain socket, or on Windows a named pipe ({@code \\.\pipe\…}). Pure.
 */
record AgentLocation(Kind kind, String where) {

    enum Kind {
        UNIX_SOCKET,
        NAMED_PIPE
    }

    /** The Windows OpenSSH agent service's pipe, which 1Password and others also serve. */
    static final String WINDOWS_PIPE = "\\\\.\\pipe\\openssh-ssh-agent";

    private static final Pattern VARIABLE = Pattern.compile("^\\$\\{?([A-Za-z_][A-Za-z0-9_]*)}?$");

    /**
     * The agent for a login, or null when there is none to use.
     *
     * @param identityAgent the host's {@code IdentityAgent} from {@code ~/.ssh/config}; empty or null when unset
     */
    static @Nullable AgentLocation find(
            String identityAgent, Map<String, String> env, boolean windows, Path home, String user) {
        String named = identityAgent == null ? "" : identityAgent.strip();
        if (named.length() >= 2 && named.startsWith("\"") && named.endsWith("\"")) {
            named = named.substring(1, named.length() - 1);
        }
        if (named.equalsIgnoreCase("none")) {
            return null;
        }
        String where;
        if (named.isEmpty() || named.equals("SSH_AUTH_SOCK")) {
            where = env.get("SSH_AUTH_SOCK");
            if ((where == null || where.isBlank()) && windows) {
                where = WINDOWS_PIPE;
            }
        } else {
            Matcher variable = VARIABLE.matcher(named);
            where = variable.matches() ? env.get(variable.group(1)) : expand(named, home, user);
        }
        if (where == null || where.isBlank()) {
            return null;
        }
        String normalized = where.replace('/', '\\').toLowerCase(Locale.ROOT);
        boolean pipe = windows && normalized.startsWith("\\\\.\\pipe\\");
        return new AgentLocation(pipe ? Kind.NAMED_PIPE : Kind.UNIX_SOCKET, where);
    }

    /** {@code ~} and the {@code %d} (home) and {@code %u} (user) tokens {@code ssh} allows in IdentityAgent. */
    private static String expand(String path, Path home, String user) {
        String p = path;
        if (p.equals("~") || p.startsWith("~/")) {
            p = home + p.substring(1);
        }
        return p.replace("%d", home.toString())
                .replace("%u", user == null ? "" : user)
                .replace("%%", "%");
    }
}
