package com.editora.process;

import java.util.List;

/**
 * A sink for <em>completed</em> one-shot CLI invocations, so the user can read exactly what Editora ran on
 * their behalf and what it answered — the console half of the native-CLI design that {@code GitService} and
 * {@code GitHubService} are built on.
 *
 * <p>Deliberately about finished commands, not a stream: {@code git}/{@code gh} calls are short request/reply
 * round-trips whose whole output arrives at once (unlike a build, which streams for minutes and needs
 * {@code BuildService.Listener}). One {@link Entry} is therefore one complete transcript entry.
 *
 * <p><b>Threading:</b> implementations are called on whichever worker thread ran the command, so a UI sink
 * must marshal to the FX thread itself.
 *
 * <p><b>What gets logged is the caller's choice, and it matters:</b> a service is expected to report only
 * user-initiated commands. {@code GitService} in particular re-runs {@code status}/{@code diff} on every tab
 * switch, window focus-regain and save — piping those here would bury the commit the user is looking for.
 */
@FunctionalInterface
public interface CommandLog {

    /** Reports one finished command. Never throws back into the caller's service. */
    void record(Entry entry);

    /**
     * One finished invocation: its full argv, exit code, captured streams and wall-clock duration.
     *
     * <p>No working directory: it would be near-constant (a window is one project) and only lengthen every
     * echoed line — the tab is already scoped to the window that ran the command.
     */
    record Entry(List<String> argv, int exitCode, String out, String err, long millis) {}

    /** The no-op sink — the default, so a service with no console attached costs one null-free call. */
    static CommandLog none() {
        return entry -> {};
    }
}
