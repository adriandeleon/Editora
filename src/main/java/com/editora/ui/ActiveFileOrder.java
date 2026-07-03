package com.editora.ui;

import java.nio.file.Path;
import java.util.Comparator;

/**
 * Pure ordering helper: sort the currently-active file to the top of a tool-window list (Problems, TODO, …),
 * mirroring how IDEs surface the file you're looking at first. Matching is by normalized absolute path, so
 * the caller must pass the active path in the same form as the list's paths (e.g. canonical for LSP
 * diagnostics, as-walked for a project scan). Toolkit-free and unit-tested.
 */
public final class ActiveFileOrder {

    private ActiveFileOrder() {}

    /** True when {@code p} refers to the active file (normalized absolute-path equality; false if either is null). */
    public static boolean isActive(Path p, Path active) {
        return p != null && active != null && norm(p).equals(norm(active));
    }

    /** A comparator that orders the active file first, then falls back to {@code tie} for everything else. */
    public static <T> Comparator<T> activeFirst(
            java.util.function.Function<T, Path> pathOf, Path active, Comparator<T> tie) {
        return Comparator.comparingInt((T t) -> isActive(pathOf.apply(t), active) ? 0 : 1)
                .thenComparing(tie);
    }

    private static Path norm(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
