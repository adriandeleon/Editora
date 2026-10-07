package com.editora.github;

/**
 * When the status bar's pull-request checks are asked for again on their own. Only a roll-up with a check
 * still <em>pending</em> is polled — a finished one cannot change without a new push, which arrives as a
 * branch update — and the interval backs off, so a slow pipeline costs a handful of {@code gh} calls rather
 * than one every few seconds. Pure — unit-tested.
 */
public final class ChecksPoll {

    private ChecksPoll() {}

    /** After this many polls of one pull request the roll-up is left as it is (about two hours). */
    public static final int MAX_ATTEMPTS = 28;

    private static final long[] DELAYS_SECONDS = {15, 30, 60, 120, 300};

    /** What to do once a roll-up has been shown. */
    public enum Next {
        /** Nothing is pending (or the cap is reached): do not ask again. */
        STOP,
        /** Ask again after {@link #delaySeconds}. */
        WAIT
    }

    /** Whether to poll again after the {@code attempt}-th answer (0 = the first fetch). */
    public static Next after(ChecksParser.Overall overall, int attempt) {
        return overall == ChecksParser.Overall.PENDING && attempt < MAX_ATTEMPTS ? Next.WAIT : Next.STOP;
    }

    /** The wait before poll number {@code attempt + 1}: 15 s, 30 s, 1 min, 2 min, then every 5 min. */
    public static long delaySeconds(int attempt) {
        return DELAYS_SECONDS[Math.clamp(attempt, 0, DELAYS_SECONDS.length - 1)];
    }

    /**
     * The Actions run a check belongs to, read from its link
     * ({@code https://host/owner/repo/actions/runs/<run>/job/<job>}); {@code -1} for a check that is not a
     * GitHub Actions job (an external status has some other URL), which has no log {@code gh} can fetch.
     */
    public static long runId(String link) {
        if (link == null) {
            return -1;
        }
        String marker = "/actions/runs/";
        int at = link.indexOf(marker);
        if (at < 0) {
            return -1;
        }
        int start = at + marker.length();
        int end = start;
        while (end < link.length() && Character.isDigit(link.charAt(end))) {
            end++;
        }
        if (end == start || end - start > 18) {
            return -1;
        }
        return Long.parseLong(link.substring(start, end));
    }
}
