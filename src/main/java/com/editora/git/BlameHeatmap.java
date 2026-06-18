package com.editora.git;

/**
 * Pure helper for the IntelliJ-style blame "Annotate" column's age heatmap: maps a commit's age, relative
 * to the file's oldest/newest blamed commits, to an intensity in {@code [0,1]} where {@code 1} = newest
 * (most recently changed) and {@code 0} = oldest. The caller turns the intensity into a background tint
 * (more recent → more saturated), mirroring IntelliJ's annotation gradient. Unit-tested.
 */
public final class BlameHeatmap {

    private BlameHeatmap() {}

    /**
     * Newness of {@code epoch} within the inclusive range {@code [minEpoch, maxEpoch]}.
     *
     * @return {@code 1.0} for the newest commit, {@code 0.0} for the oldest, linearly interpolated in
     *     between; {@code 1.0} when the range is degenerate (a single commit, or all lines same age) so a
     *     one-commit file is shown as "recent"; clamped to {@code [0,1]} for out-of-range inputs.
     */
    public static double intensity(long epoch, long minEpoch, long maxEpoch) {
        if (maxEpoch <= minEpoch) {
            return 1.0; // single commit / no spread — treat as fully recent
        }
        double t = (double) (epoch - minEpoch) / (double) (maxEpoch - minEpoch);
        if (t < 0.0) {
            return 0.0;
        }
        return Math.min(t, 1.0);
    }

    /**
     * Age-heatmap tint as an {@code rgba(...)} string: a warm background whose opacity scales with
     * {@code intensity} ({@code 0}=oldest → {@code 1}=newest, clamped), toned down on dark themes so it
     * doesn't overpower the text.
     */
    public static String heatmapColor(double intensity, boolean dark) {
        double t = Math.max(0.0, Math.min(1.0, intensity));
        double alpha = (dark ? 0.05 : 0.04) + (dark ? 0.25 : 0.30) * t;
        int r = dark ? 255 : 240;
        int g = dark ? 190 : 165;
        int b = dark ? 90 : 70;
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", r, g, b, alpha);
    }
}
