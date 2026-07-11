package com.editora.systemd;

import java.util.Locale;

/**
 * Plain-English glosses for the common systemd directives (used by the unit preview), plus the unit-kind
 * inference. Time-span-valued keys route through {@link TimeSpan} and {@code OnCalendar} through
 * {@link SystemdCalendar}. An unrecognized key returns {@code null} (the preview then shows the raw
 * {@code Key=value} without a gloss). Pure, java.base-only, unit-tested.
 */
public final class SystemdDescribe {

    private SystemdDescribe() {}

    /** The unit kind inferred from its type-specific section (timer/service/socket/…), else "unit". */
    public static String kind(SystemdUnit u) {
        for (String k : new String[] {"Timer", "Service", "Socket", "Mount", "Automount", "Path", "Swap"}) {
            if (u.hasSection(k)) {
                return k.toLowerCase(Locale.ROOT);
            }
        }
        return "unit";
    }

    /** A plain-English gloss for a directive, or {@code null} if the key isn't recognized. */
    public static String gloss(String key, String value) {
        String k = key.toLowerCase(Locale.ROOT);
        // Time-span-valued monotonic-timer + service keys.
        switch (k) {
            case "onbootsec":
                return TimeSpan.describe(value) + " after boot";
            case "onstartupsec":
                return TimeSpan.describe(value) + " after systemd started";
            case "onactivesec":
                return TimeSpan.describe(value) + " after the timer is activated";
            case "onunitactivesec":
                return TimeSpan.describe(value) + " after the unit was last activated";
            case "onunitinactivesec":
                return TimeSpan.describe(value) + " after the unit was last deactivated";
            case "randomizeddelaysec":
                return "randomized delay of up to " + TimeSpan.describe(value);
            case "accuracysec":
                return "accuracy window of " + TimeSpan.describe(value);
            case "restartsec":
                return "wait " + TimeSpan.describe(value) + " between restarts";
            case "timeoutstartsec":
                return "start timeout " + TimeSpan.describe(value);
            case "timeoutstopsec":
                return "stop timeout " + TimeSpan.describe(value);
            case "timeoutsec":
                return "start/stop timeout " + TimeSpan.describe(value);
            case "oncalendar":
                SystemdCalendar.Parsed p = SystemdCalendar.parse(value);
                return p.ok() ? p.calendar().describe() : "invalid schedule: " + p.error();
            default:
                break;
        }
        return switch (k) {
            // [Unit]
            case "description" -> value;
            case "documentation" -> "documentation: " + value;
            case "after" -> "starts after: " + value;
            case "before" -> "starts before: " + value;
            case "requires" -> "requires (hard dependency): " + value;
            case "wants" -> "wants (soft dependency): " + value;
            case "requisite" -> "requires already-started: " + value;
            case "bindsto" -> "bound to: " + value;
            case "partof" -> "part of: " + value;
            case "conflicts" -> "conflicts with: " + value;
            case "condition", "conditionpathexists" -> "condition: " + value;
            // [Timer]
            case "unit" -> "triggers: " + value;
            case "persistent" ->
                isTrue(value) ? "runs immediately if the last trigger was missed" : "does not catch up missed triggers";
            case "wakesystem" -> isTrue(value) ? "wakes the system from suspend" : null;
            // [Service]
            case "execstart" -> "runs: " + value;
            case "execstartpre" -> "before start, runs: " + value;
            case "execstartpost" -> "after start, runs: " + value;
            case "execstop" -> "on stop, runs: " + value;
            case "execstoppost" -> "after stop, runs: " + value;
            case "execreload" -> "on reload, runs: " + value;
            case "type" -> "service type: " + value;
            case "user" -> "as user " + value;
            case "group" -> "as group " + value;
            case "workingdirectory" -> "working directory: " + value;
            case "restart" -> "restart policy: " + value;
            case "environment" -> "environment: " + value;
            case "environmentfile" -> "environment from file: " + value;
            case "remainafterexit" -> isTrue(value) ? "stays active after the process exits" : null;
            case "standardoutput" -> "stdout → " + value;
            case "standarderror" -> "stderr → " + value;
            case "pidfile" -> "PID file: " + value;
            // [Install]
            case "wantedby" -> "enabled for: " + value;
            case "requiredby" -> "required by: " + value;
            case "alias" -> "alias: " + value;
            case "also" -> "also enable: " + value;
            // [Socket]
            case "listenstream" -> "listens (TCP/stream) on " + value;
            case "listendatagram" -> "listens (UDP/datagram) on " + value;
            case "listenfifo" -> "listens on FIFO " + value;
            case "accept" ->
                isTrue(value) ? "spawns a service instance per connection" : "one service handles all connections";
            case "socketmode" -> "socket permissions " + value;
            // [Mount]
            case "what" -> "source: " + value;
            case "where" -> "mount point: " + value;
            case "options" -> "options: " + value;
            // [Path]
            case "pathexists" -> "activates when path exists: " + value;
            case "pathchanged" -> "activates when path changes: " + value;
            case "pathmodified" -> "activates when path is modified: " + value;
            case "directorynotempty" -> "activates when directory is non-empty: " + value;
            default -> null;
        };
    }

    private static boolean isTrue(String v) {
        String t = v.strip().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("yes") || t.equals("on") || t.equals("1");
    }
}
