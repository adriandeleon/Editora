package com.editora.github;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code gh} release a version line names ({@code gh version 2.96.0 (2026-07-02)}), and what Editora
 * needs from it. Established from gh's release notes: everything Editora calls exists since gh 2.16
 * ({@code run list --json displayTitle,workflowName}; {@code run rerun --failed} is 2.6, {@code browse
 * --no-browser} 1.13) except {@code pr checks --json}, which arrived in <b>2.50</b> — so 2.50 is the stated
 * minimum, and an older gh loses only the CI-checks roll-up. ({@code auth status --json}, 2.81, is optional:
 * {@link GitHubService} falls back to the exit code.) Pure — unit-tested.
 */
public final class GhVersion {

    private GhVersion() {}

    /** The oldest gh with every feature Editora uses, as shown to the user. */
    public static final String MINIMUM = "2.50";

    private static final int MINIMUM_MAJOR = 2;
    private static final int MINIMUM_MINOR = 50;

    private static final Pattern NUMBER = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /**
     * Whether the gh that printed {@code versionLine} is at least {@code major.minor}. An unreadable line (a
     * wrapper, a development build) counts as new enough: an unknown version must not disable a feature.
     */
    public static boolean atLeast(String versionLine, int major, int minor) {
        if (versionLine == null) {
            return true;
        }
        Matcher m = NUMBER.matcher(versionLine);
        if (!m.find()) {
            return true;
        }
        try {
            int maj = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            return maj > major || (maj == major && min >= minor);
        } catch (NumberFormatException absurd) {
            return true;
        }
    }

    /** Whether this gh has {@code pr checks --json} (2.50+), which the CI-checks roll-up reads. */
    public static boolean supportsChecksJson(String versionLine) {
        return atLeast(versionLine, MINIMUM_MAJOR, MINIMUM_MINOR);
    }

    /** The bare number in {@code versionLine} ({@code 2.96.0}), or the line itself when it has none. */
    public static String number(String versionLine) {
        if (versionLine == null) {
            return "";
        }
        Matcher m = NUMBER.matcher(versionLine);
        return m.find() ? m.group() : versionLine.strip();
    }
}
