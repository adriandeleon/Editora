package com.editora.logviewer;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which file names are logs. Pure (java.base only), so it is unit-tested.
 *
 * <p>{@code .log} alone misses most of a log directory: logrotate renames {@code app.log} to
 * {@code app.log.1}, Logback rolls to {@code app.log.2026-10-06} or {@code app.2026-10-06.log}, and the
 * oldest names in {@code /var/log} have no extension at all.
 */
public final class LogFileNames {

    /** {@code app.log.1}, {@code app.log.2026-10-06}, {@code app.log.2026-10-06.3}, {@code app.log.old}. */
    private static final Pattern ROTATED = Pattern.compile(".*\\.log(?:\\.(?:\\d[\\d._-]*|old|bak|prev))+");

    /** {@code access_log}, {@code error_log}, {@code access_log.1} (Apache httpd). */
    private static final Pattern APACHE = Pattern.compile(".*_log(?:\\.\\d[\\d._-]*)?");

    private static final Set<String> KNOWN = Set.of("syslog", "dmesg", "catalina.out", "nohup.out");

    private LogFileNames() {}

    /** Whether {@code fileName} names a log by convention, so it opens in the log viewer without being asked. */
    public static boolean isLog(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (compressed(name)) {
            return isLog(name.substring(0, name.length() - 3)); // app.log.2.gz: logrotate's compressed copy
        }
        return name.endsWith(".log")
                || ROTATED.matcher(name).matches()
                || APACHE.matcher(name).matches()
                || KNOWN.contains(name)
                || KNOWN.contains(stripRotation(name));
    }

    /**
     * Whether a file with this name is worth sniffing: it might be a log, but the name alone does not say so
     * ({@code server.out}, {@code worker.err}, a name with no extension). The caller decides from the content
     * ({@link LogPatterns#looksLikeLog}).
     */
    public static boolean mayBeLog(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        if (compressed(name)) {
            return mayBeLog(name.substring(0, name.length() - 3));
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 || name.endsWith(".out") || name.endsWith(".err");
    }

    /** One gzip-compressed file ({@code x.gz}), which opens decompressed; not an archive ({@code x.tar.gz}). */
    private static boolean compressed(String lowerName) {
        return lowerName.endsWith(".gz") && lowerName.length() > 3 && !lowerName.endsWith(".tar.gz");
    }

    /** {@code syslog.1} → {@code syslog}. */
    private static String stripRotation(String name) {
        int dot = name.indexOf('.');
        return dot > 0 && name.substring(dot + 1).matches("\\d[\\d._-]*") ? name.substring(0, dot) : name;
    }
}
