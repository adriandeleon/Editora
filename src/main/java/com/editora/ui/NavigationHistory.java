package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A browser-style back/forward jump list of editor {@link Location}s (file + line + column). Recording a
 * new location truncates any forward history and appends it (deduping a consecutive same-file+line entry);
 * {@link #back()}/{@link #forward()} move a cursor through the list and return the target to navigate to.
 *
 * <p>Pure and toolkit-free (just {@code java.nio.file.Path}) so it is unit-tested directly; the controller
 * feeds it origin/destination locations at the navigation choke points.
 */
public final class NavigationHistory {

    /** A navigable spot: a file-backed buffer path + a 0-based line and column. */
    public record Location(Path path, int line, int column) {}

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
    public Location back() {
        if (index <= 0) {
            return null;
        }
        index--;
        return entries.get(index);
    }

    /** Steps forward and returns the next location, or {@code null} when already at the newest. */
    public Location forward() {
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

    // --- test visibility ---
    int index() {
        return index;
    }

    int size() {
        return entries.size();
    }

    private static boolean sameLine(Location a, Location b) {
        return a.path().equals(b.path()) && a.line() == b.line();
    }
}
