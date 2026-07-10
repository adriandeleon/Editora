package com.editora.fstab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a parsed {@link FstabEntry} into plain-English text for the preview: the device spec, a one-line
 * mount summary, the decoded mount options, and the fsck/dump note. Pure, java.base-only, unit-tested.
 */
public final class FstabDescribe {

    private FstabDescribe() {}

    /** Pseudo/virtual filesystem specs whose device column is a keyword, not a real device. */
    private static final List<String> PSEUDO =
            List.of("proc", "sysfs", "tmpfs", "devpts", "none", "debugfs", "cgroup", "cgroup2", "mqueue", "hugetlbfs");

    /** A readable description of the device column (UUID/LABEL/path/network share/pseudo). */
    public static String device(String spec) {
        if (spec == null || spec.isEmpty()) {
            return "an unspecified device";
        }
        String upper = spec.toUpperCase(Locale.ROOT);
        if (upper.startsWith("UUID=")) {
            return "the filesystem with UUID " + spec.substring(5);
        }
        if (upper.startsWith("PARTUUID=")) {
            return "the partition with PARTUUID " + spec.substring(9);
        }
        if (upper.startsWith("LABEL=")) {
            return "the filesystem labeled \"" + spec.substring(6) + "\"";
        }
        if (upper.startsWith("PARTLABEL=")) {
            return "the partition labeled \"" + spec.substring(10) + "\"";
        }
        if (spec.startsWith("//")) {
            return "the SMB/CIFS share " + spec;
        }
        // host:/export — an NFS export (a colon before a slash, not a Windows-style path).
        int colon = spec.indexOf(':');
        if (colon > 0 && spec.indexOf('/') > colon) {
            return "the NFS export " + spec;
        }
        if (PSEUDO.contains(spec.toLowerCase(Locale.ROOT))) {
            return spec.equalsIgnoreCase("none") ? "no backing device" : "the " + spec + " virtual filesystem";
        }
        if (spec.startsWith("/dev/")) {
            return "device " + spec;
        }
        return spec;
    }

    /** A one-line summary: "Mount &lt;device&gt; at &lt;point&gt; as &lt;type&gt;", or swap phrasing. */
    public static String summary(FstabEntry e) {
        if (e.isSwap()) {
            return "Swap space on " + device(e.spec());
        }
        String type = fsType(e.fsType());
        return "Mount " + device(e.spec()) + " at " + e.mountPoint() + " " + type;
    }

    private static String fsType(String t) {
        if (t == null || t.isEmpty() || t.equalsIgnoreCase("auto")) {
            return "(auto-detected type)";
        }
        return "as " + t;
    }

    /** The decoded mount options, in order; unknown tokens pass through verbatim. */
    public static List<String> options(FstabEntry e) {
        List<String> out = new ArrayList<>();
        for (String opt : e.options()) {
            out.add(describeOption(opt));
        }
        return out;
    }

    private static String describeOption(String opt) {
        int eq = opt.indexOf('=');
        if (eq > 0) {
            return describeKeyValue(opt.substring(0, eq).toLowerCase(Locale.ROOT), opt.substring(eq + 1), opt);
        }
        return switch (opt.toLowerCase(Locale.ROOT)) {
            case "defaults" -> "default options (read-write, auto-mount, executables allowed, setuid honored)";
            case "rw" -> "read-write";
            case "ro" -> "read-only";
            case "auto" -> "mounted automatically at boot";
            case "noauto" -> "not mounted at boot (mounted on demand)";
            case "exec" -> "executables allowed";
            case "noexec" -> "executables blocked";
            case "suid" -> "setuid/setgid bits honored";
            case "nosuid" -> "setuid/setgid bits ignored";
            case "dev" -> "device files interpreted";
            case "nodev" -> "device files not interpreted";
            case "user" -> "any user may mount";
            case "users" -> "any user may mount and unmount";
            case "nouser" -> "only root may mount";
            case "owner" -> "only the device owner may mount";
            case "group" -> "a user in the device's group may mount";
            case "atime" -> "access times updated";
            case "noatime" -> "access times not updated";
            case "nodiratime" -> "directory access times not updated";
            case "relatime" -> "access times updated only relative to modify time";
            case "strictatime" -> "access times always updated";
            case "sync" -> "I/O done synchronously";
            case "async" -> "I/O done asynchronously";
            case "dirsync" -> "directory changes done synchronously";
            case "nofail" -> "boot continues even if the device is missing";
            case "_netdev" -> "waits for the network before mounting";
            case "discard" -> "TRIM/discard enabled (for SSDs)";
            case "nodiscard" -> "TRIM/discard disabled";
            case "bind" -> "bind mount (mirrors another directory)";
            case "rbind" -> "recursive bind mount";
            case "remount" -> "remount an already-mounted filesystem";
            case "x-systemd.automount" -> "mounted on first access (systemd automount)";
            default -> opt;
        };
    }

    private static String describeKeyValue(String key, String value, String raw) {
        return switch (key) {
            case "uid" -> "owned by user id " + value;
            case "gid" -> "owned by group id " + value;
            case "umask" -> "permission mask " + value;
            case "fmask" -> "file permission mask " + value;
            case "dmask" -> "directory permission mask " + value;
            case "mode" -> "mount permissions " + value;
            case "size" -> "size limit " + value;
            case "commit" -> "data flushed to disk every " + value + " seconds";
            case "iocharset", "codepage" -> "character set " + value;
            case "compress", "compress-force" -> "compression: " + value;
            case "subvol" -> "btrfs subvolume " + value;
            case "data" -> "ext journaling mode: " + value;
            case "errors" -> "on errors: " + value.replace('-', ' ');
            case "x-systemd.device-timeout" -> "device timeout " + value;
            default -> raw;
        };
    }

    /** A compact fsck + dump note, e.g. "fsck: after root · not backed up". */
    public static String checkLine(FstabEntry e) {
        String fsck =
                switch (e.pass()) {
                    case 0 -> "never fsck-checked";
                    case 1 -> "fsck-checked first (root)";
                    case 2 -> "fsck-checked after root";
                    default -> "fsck pass " + e.pass();
                };
        String dump = e.dump() == 1 ? "included in dump backups" : "not backed up by dump";
        return fsck + " · " + dump;
    }
}
