package com.editora.git;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.process.ProcessRunner;

/**
 * Runs git in a repository Editora itself owns (the settings-sync clone), on Editora's own initiative.
 * Blocking; the caller picks the thread.
 *
 * <p>Every command is a background command in the sense of {@link GitSafety}: hooks, fsmonitor and the pager
 * are off, nothing is logged to a window's Git console, and no end-of-line conversion or commit signing is
 * applied (a signing key's pinentry would be a prompt). A {@linkplain #QuietGit(Path, boolean)
 * non-interactive} instance also never asks for anything — no terminal, no askpass program, no
 * credential-manager window, ssh in batch mode ({@link GitSafety#autoFetchEnv}) — so a remote that needs a
 * sign-in fails with git's message instead. An interactive one keeps the user's askpass and credential
 * helper, for the one command the user is waiting on.
 */
public final class QuietGit {

    /** Ceiling for a command that talks to the remote. */
    public static final Duration NETWORK = Duration.ofMinutes(2);

    /** Ceiling for a local command. */
    public static final Duration LOCAL = Duration.ofSeconds(30);

    private static final List<String> OWN_CONFIG = List.of(
            "-c", "core.autocrlf=false",
            "-c", "core.safecrlf=false",
            // A link in the repository is checked out as a small text file, never followed to a local file.
            "-c", "core.symlinks=false",
            "-c", "commit.gpgsign=false",
            "-c", "tag.gpgsign=false",
            "-c", "advice.detachedHead=false");

    private final Path dir;
    private final boolean interactive;
    private Map<String, String> env;

    public QuietGit(Path dir, boolean interactive) {
        this.dir = dir;
        this.interactive = interactive;
    }

    public Path dir() {
        return dir;
    }

    /** Whether the configured git command starts at all. */
    public static boolean available() {
        List<String> argv = new ArrayList<>(GitService.command());
        argv.add("--version");
        return ProcessRunner.run(null, Duration.ofSeconds(10), argv, GitSafety.BACKGROUND_ENV)
                .ok();
    }

    /** A local command. */
    public ProcessRunner.Result run(String... args) {
        return run(LOCAL, args);
    }

    /** A command that talks to the remote. */
    public ProcessRunner.Result network(String... args) {
        return run(NETWORK, args);
    }

    private ProcessRunner.Result run(Duration timeout, String... args) {
        return ProcessRunner.run(dir, timeout, argv(interactive, args), env());
    }

    /** The whole command line; pure, for tests. */
    static List<String> argv(boolean interactive, String... args) {
        List<String> argv = new ArrayList<>(GitService.command());
        argv.addAll(GitSafety.BACKGROUND_CONFIG);
        argv.addAll(OWN_CONFIG);
        if (!interactive) {
            argv.addAll(GitSafety.AUTO_FETCH_CONFIG);
        }
        argv.addAll(List.of(args));
        return argv;
    }

    private synchronized Map<String, String> env() {
        if (env == null) {
            env = buildEnv();
        }
        return env;
    }

    private Map<String, String> buildEnv() {
        if (interactive) {
            return GitSafety.userEnv(System.getenv());
        }
        // The user's own ssh command is left alone; see GitSafety.autoFetchEnv.
        List<String> probe = new ArrayList<>(GitService.command());
        probe.addAll(List.of("config", "--get", "core.sshCommand"));
        boolean ownSsh = ProcessRunner.run(dir, Duration.ofSeconds(10), probe, GitSafety.BACKGROUND_ENV)
                .ok();
        return GitSafety.autoFetchEnv(System.getenv(), ownSsh);
    }
}
