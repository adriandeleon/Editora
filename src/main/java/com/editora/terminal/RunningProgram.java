package com.editora.terminal;

import java.util.List;
import java.util.Optional;

/**
 * What a shell is running, if anything.
 *
 * <p>A tab always has a process — the shell — so "is something running" cannot mean "is the tab
 * alive". What matters before closing is whether the shell has <b>children</b>: an idle prompt has
 * none, and one running {@code vim}, an ssh session or a build has one. Measured against a real
 * shell, an idle prompt reports zero even with a prompt framework installed, which is what makes
 * this usable as a gate — a check that fired at every prompt would be worse than no check.
 *
 * <p>Background jobs count, deliberately. {@code sleep 60 &} is a child, and closing the tab kills
 * it just as surely as it kills a foreground program, so it is worth being asked about.
 */
public final class RunningProgram {

    private RunningProgram() {}

    /** How many names to name before the message stops listing and starts counting. */
    public static final int MAX_NAMED = 3;

    /** The programs a shell is running, innermost name first, or empty for an idle prompt. */
    public static List<String> in(long pid) {
        // Children by parent, not ProcessHandle.children(): that walks the whole process table, and this is asked per
        // terminal, every watcher tick. See ChildProcesses.
        return ChildProcesses.of(pid).stream()
                .map(child -> ProcessHandle.of(child)
                        .flatMap(handle -> handle.info().command())
                        .map(RunningProgram::displayName)
                        .orElse(""))
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
    }

    /**
     * The name to show for a command path.
     *
     * <p>{@code /usr/bin/sleep} is "sleep": the path is how the OS found it and says nothing the
     * reader of a dialog needs. Windows keeps its extension, because there {@code .exe} is part of
     * how a program is named rather than noise.
     */
    public static String displayName(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return "";
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /** Whether closing would end something. */
    public static Optional<String> first(long pid) {
        List<String> names = in(pid);
        return names.isEmpty() ? Optional.empty() : Optional.of(names.get(0));
    }
}
