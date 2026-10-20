package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A browser-style back/forward jump list of editor {@link Location}s (file + line + column). Recording a
 * new location truncates any forward history and appends it (deduping a consecutive same-file+line entry);
 * {@link #back()}/{@link #forward()} move a cursor through the list and return the target to navigate to.
 *
 * <p>Pure and toolkit-free (just {@code java.nio.file.Path}) so it is unit-tested directly; the controller
 * feeds it origin/destination locations at the navigation choke points.
 */
public final class NavigationHistory {

    /**
     * A navigable spot: a file-backed buffer path, a 0-based line and column, and the trimmed text of that
     * line.
     *
     * <p>The snippet is what makes a list of these readable — {@code Foo.java:214} says nothing about why
     * you were there, while the line itself usually says it at a glance, and that is the whole value of a
     * recent-<em>locations</em> list over a recent-<em>files</em> one. It is captured when the location is
     * recorded rather than read back when the list is shown, because by then the file may be closed,
     * edited, or gone.
     */
    public record Location(@Nullable Path path, int line, int column, String snippet) {

        public Location {
            snippet = snippet == null ? "" : snippet;
        }

        /** A location with no captured line text, for a caller with no buffer to read it from. */
        public Location(@Nullable Path path, int line, int column) {
            this(path, line, column, "");
        }
    }

    /** Cap on retained entries; oldest are dropped past this. */
    static final int MAX = 100;

    private final List<Location> entries = new ArrayList<>();
    private int index = -1; // points at the current location, or -1 when empty

    /** Records {@code loc} as the new current location (truncating forward history); dedups same file+line. */
    public void record(Location loc) {
        if (loc == null) {
            return;
        }
        if (index >= 0 && sameLine(entries.get(index), loc)) {
            return; // already here (ignore column drift) — don't spam the trail
        }
        while (entries.size() > index + 1) {
            entries.remove(entries.size() - 1); // drop the forward stack — a new jump forks history
        }
        entries.add(loc);
        index = entries.size() - 1;
        if (entries.size() > MAX) {
            entries.remove(0);
            index--;
        }
    }

    /** Steps back and returns the previous location, or {@code null} when already at the oldest. */
    public @Nullable Location back() {
        if (index <= 0) {
            return null;
        }
        index--;
        return entries.get(index);
    }

    /** Steps forward and returns the next location, or {@code null} when already at the newest. */
    public @Nullable Location forward() {
        if (index < 0 || index >= entries.size() - 1) {
            return null;
        }
        index++;
        return entries.get(index);
    }

    public boolean canBack() {
        return index > 0;
    }

    public boolean canForward() {
        return index >= 0 && index < entries.size() - 1;
    }

    /**
     * The visited trail, newest first, with each file+line appearing once — the backing list for a
     * "recent locations" picker.
     *
     * <p>This is the same trail {@link #back()} walks, not a separate log, so what the picker offers and
     * what Back reaches can never diverge. A jump that forked the history has already truncated what it
     * replaced, which is the honest thing to show: those locations are no longer reachable by going back.
     *
     * <p>Deduping keeps the <em>newest</em> visit of a line and drops older ones, so repeatedly passing
     * through a spot does not push everything else off the list.
     */
    public List<Location> recent() {
        List<Location> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Location loc = entries.get(i);
            if (seen.add(loc.path() + "\0" + loc.line())) {
                out.add(loc);
            }
        }
        return out;
    }

    // --- test visibility ---
    int index() {
        return index;
    }

    int size() {
        return entries.size();
    }

    private static boolean sameLine(Location a, Location b) {
        return java.util.Objects.equals(a.path(), b.path()) && a.line() == b.line();
    }
}
