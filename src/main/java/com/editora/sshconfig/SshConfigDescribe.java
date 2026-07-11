package com.editora.sshconfig;

import java.util.Locale;

/**
 * Plain-English glosses for SSH client config blocks: a one-line connection summary per {@code Host} block
 * and per-option glosses. Pure, java.base-only, unit-tested. An unrecognized option returns {@code null}
 * (the preview shows the raw {@code Keyword value}).
 */
public final class SshConfigDescribe {

    private SshConfigDescribe() {}

    /** A one-line summary of a block's intent. */
    public static String summary(SshConfig.Block b) {
        if (b.type().equals("global")) {
            return "Applies to all hosts";
        }
        if (b.type().equals("Match")) {
            return "For connections matching " + b.argument();
        }
        String hostName = b.first("HostName");
        String port = b.first("Port");
        String user = b.first("User");
        String identity = b.first("IdentityFile");
        String proxyJump = b.first("ProxyJump");
        StringBuilder sb = new StringBuilder("Connects to ");
        sb.append(hostName != null ? hostName : b.argument());
        if (port != null) {
            sb.append(" on port ").append(port);
        }
        if (user != null) {
            sb.append(" as ").append(user);
        }
        if (identity != null) {
            sb.append(", key ").append(identity);
        }
        if (proxyJump != null) {
            sb.append(", via jump host ").append(proxyJump);
        }
        return sb.toString();
    }

    /** A plain-English gloss for an option, or {@code null} if the keyword isn't recognized. */
    public static String gloss(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "hostname" -> "actual hostname: " + value;
            case "port" -> "port " + value;
            case "user" -> "log in as " + value;
            case "identityfile" -> "private key: " + value;
            case "identitiesonly" -> onOff(value, "only use the configured key(s)", "may try other keys");
            case "proxyjump" -> "connect via jump host: " + value;
            case "proxycommand" -> "connect via command: " + value;
            case "forwardagent" -> onOff(value, "SSH agent forwarding on", "agent forwarding off");
            case "forwardx11" -> onOff(value, "X11 forwarding on", "X11 forwarding off");
            case "localforward" -> "local port forward: " + value;
            case "remoteforward" -> "remote port forward: " + value;
            case "dynamicforward" -> "SOCKS proxy on: " + value;
            case "compression" -> onOff(value, "compression on", "compression off");
            case "serveraliveinterval" -> "send a keepalive every " + value + "s";
            case "serveralivecountmax" -> "disconnect after " + value + " missed keepalives";
            case "stricthostkeychecking" -> "strict host-key checking: " + value;
            case "userknownhostsfile" -> "known-hosts file: " + value;
            case "pubkeyauthentication" -> onOff(value, "public-key auth on", "public-key auth off");
            case "passwordauthentication" -> onOff(value, "password auth on", "password auth off");
            case "preferredauthentications" -> "authentication order: " + value;
            case "controlmaster" -> "connection sharing: " + value;
            case "controlpath" -> "control socket: " + value;
            case "controlpersist" -> "keep the master connection: " + value;
            case "connecttimeout" -> value + "s connect timeout";
            case "addkeystoagent" -> "add keys to the agent: " + value;
            case "loglevel" -> "log verbosity: " + value;
            case "requesttty" -> "TTY allocation: " + value;
            case "sendenv" -> "send environment variables: " + value;
            case "setenv" -> "set environment variables: " + value;
            case "ciphers" -> "ciphers: " + value;
            case "macs" -> "MAC algorithms: " + value;
            case "kexalgorithms" -> "key-exchange algorithms: " + value;
            default -> null;
        };
    }

    private static String onOff(String value, String onText, String offText) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        boolean on = v.equals("yes") || v.equals("true") || v.equals("on") || v.equals("1");
        return on ? onText : offText;
    }
}
