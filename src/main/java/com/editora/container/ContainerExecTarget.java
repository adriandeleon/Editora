package com.editora.container;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.LauncherProcess;
import com.editora.process.PortForward;
import com.editora.process.PortRelays;
import com.editora.process.PosixShell;
import com.editora.process.RelayPortForward;
import com.editora.process.RemoteEnv;
import com.editora.process.ShellTargetCache;
import com.editora.process.TargetCache;
import com.editora.process.TerminalChannel;
import com.editora.process.TerminalSpec;
import com.editora.terminal.PtyChannel;
import org.jspecify.annotations.Nullable;

/**
 * A running container whose bind-mounted folders of this machine have their tools run inside it, with
 * {@code docker exec} (or {@code podman exec}). The files stay this machine's; the commands, their PATH,
 * environment and user are the container's.
 *
 * <p>A command is the same script as on an SSH host ({@link PosixShell#argv}): it prints its pid, sets its
 * folder and environment, and becomes the program. {@code exec} starts it as the leader of its own process
 * group, so Stop signals that group with a second {@code exec}. Unlike an SSH session, killing the local
 * {@code exec} client does not end the program in the container, so Editora stops what it started itself.
 */
public final class ContainerExecTarget implements ExecTarget {

    /** How long a command may take to print its pid line (the container's startup files run first). */
    static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    private final ContainerEngine engine;
    private final ContainerInfo info;
    private volatile boolean running;
    private volatile @Nullable RemoteEnv env;

    public ContainerExecTarget(ContainerEngine engine, ContainerInfo info) {
        this.engine = engine;
        this.info = info;
        this.running = info.running();
    }

    public ContainerInfo info() {
        return info;
    }

    /** What the engine last said about whether the container runs. */
    void setRunning(boolean value) {
        running = value;
    }

    @Override
    public String id() {
        return "container:" + info.name();
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return running;
    }

    @Override
    public Process start(ExecSpec spec) throws IOException {
        if (!running) {
            throw new IOException("the container " + info.name() + " is not running");
        }
        String cwd = spec.workingDir() == null ? null : toTargetPath(spec.workingDir());
        RemoteEnv known = env();
        List<String> argv = engine.execArgv(
                info.id(), info.user(), PosixShell.argv(spec, cwd, known.path(), known.locale(), known.profile()));
        Process client = client(argv).start();
        return LauncherProcess.start(id(), client, START_TIMEOUT, this::signal, this::orphanRecord);
    }

    /**
     * A login shell on a pseudo-terminal in the container, for the integrated terminal: {@code exec -it} run here on
     * a pseudo-terminal of its own. Closing it hangs the shell up in the container ({@link #hangUp}), because the
     * engine keeps a program whose {@code exec} client went.
     */
    @Override
    public TerminalChannel startTerminal(TerminalSpec spec) throws IOException {
        if (!running) {
            throw new IOException("the container " + info.name() + " is not running");
        }
        String cwd = spec.workingDir() == null ? null : toTargetPath(spec.workingDir());
        List<String> argv = engine.terminalArgv(
                info.id(), info.user(), List.of("/bin/sh", "-c", PosixShell.terminalScript(cwd, true, spec)));
        return PtyChannel.start(
                com.editora.process.ProcessRunner.resolveExecutable(argv),
                com.editora.process.ProcessRunner.applyUserEnv(new java.util.HashMap<>(System.getenv()), Map.of()),
                spec.columns(),
                spec.rows(),
                this::hangUp,
                id(),
                this::orphanRecord);
    }

    /** SIGHUP to the shell {@code pid} in the container — what a terminal window closing sends its shell. */
    void hangUp(long pid) {
        try {
            Process kill = client(engine.execArgv(info.id(), info.user(), hangUpArgv(pid)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            kill.getOutputStream().close();
            if (!kill.waitFor(5, TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (IOException e) {
            // The container went with it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code kill -s HUP -- <pid>}: an interactive shell passes the hang-up on to its jobs and exits. */
    static List<String> hangUpArgv(long pid) {
        return List.of("kill", "-s", "HUP", "--", Long.toString(pid));
    }

    /** {@link #orphanRecord}'s kind, and the reaper {@code App} registers for it. */
    public static final String ORPHAN_KIND = "container";

    /**
     * How the next Editora finds the program with {@code pid} again: the container, the user it runs as, the
     * pid and its start time there ({@link PosixShell#startTimeArgv}), then the engine's command.
     */
    private List<String> orphanRecord(long pid) throws IOException {
        String start = capture(PosixShell.startTimeArgv(pid)).strip();
        if (!start.matches("\\d+")) {
            throw new IOException("no start time for " + pid + " in " + info.name());
        }
        List<String> record = new java.util.ArrayList<>(
                List.of(ORPHAN_KIND, info.id(), info.user() == null ? "" : info.user(), Long.toString(pid), start));
        record.addAll(engine.command());
        return record;
    }

    /**
     * Stops a program a crashed Editora left in a container, from its {@link #orphanRecord} (kind removed): in
     * that container, as that user, and only while the pid is still the process that started then. False when
     * it was not there any more, or the container is gone (and the program with it).
     */
    public static boolean reapOrphan(List<String> fields) throws IOException {
        if (fields.size() < 5) {
            return false;
        }
        ContainerEngine engine = new ContainerEngine(fields.subList(4, fields.size()));
        List<String> argv = engine.execArgv(
                fields.get(0), fields.get(1), PosixShell.reapArgv(Long.parseLong(fields.get(2)), fields.get(3)));
        Process p = client(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        try {
            if (!p.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return false;
        }
    }

    /**
     * The engine's own client, run here. Its environment is the user's (the augmented PATH, so a
     * desktop-launched Editora finds {@code docker}): it never reaches the container, whose commands get the
     * container's environment from the script.
     */
    private static ProcessBuilder client(List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(com.editora.process.ProcessRunner.resolveExecutable(argv));
        com.editora.process.ProcessRunner.applyUserEnv(pb);
        return pb;
    }

    /** The container's login environment (PATH, locale, what the profile exports), asked once. */
    RemoteEnv env() throws IOException {
        RemoteEnv known = env;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (env == null) {
                RemoteEnv probed = RemoteEnv.parse(capture(List.of("/bin/sh", "-c", RemoteEnv.probeCommandLine())));
                if (probed == RemoteEnv.UNKNOWN) {
                    return probed;
                }
                env = probed;
            }
            return env;
        }
    }

    private String capture(List<String> inner) throws IOException {
        Process p = client(engine.execArgv(info.id(), info.user(), inner))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        try (InputStream out = p.getInputStream()) {
            byte[] bytes = out.readAllBytes();
            if (!p.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the container " + info.name() + " did not answer");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        }
    }

    /** Signals the process group {@code pid} leads in the container: TERM, or KILL with {@code force}. */
    boolean signal(long pid, boolean force) {
        try {
            Process kill = client(engine.execArgv(info.id(), info.user(), PosixShell.killArgv(pid, force)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            kill.getOutputStream().close();
            return kill.waitFor(5, TimeUnit.SECONDS) && kill.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The relay the container has, asked once: {@code nc}, {@code bash} or {@code python3}; "" for none. */
    private volatile @Nullable String relayTool;

    /**
     * A loopback port here joined to {@code host:port} as the container sees it, through a relay run with
     * {@code exec} for each connection ({@link RelayPortForward}).
     */
    @Override
    public PortForward forwardLocal(String host, int port) throws IOException {
        if (!running) {
            throw new IOException("the container " + info.name() + " is not running");
        }
        String tool = relayTool;
        if (tool == null) {
            tool = capture(List.of("/bin/sh", "-c", PortRelays.DETECT)).strip();
            relayTool = tool;
        }
        List<String> relay = PortRelays.argv(tool, host, port);
        if (relay == null) {
            throw new IOException(
                    "the container " + info.name() + " has no nc, bash or python3 to reach its port " + port);
        }
        return RelayPortForward.open(() -> relayProcess(relay));
    }

    /** Starts one relay connection: {@code exec} of {@code relay} in the container. */
    Process relayProcess(List<String> relay) throws IOException {
        return client(engine.execArgv(info.id(), info.user(), relay))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** The container's name for {@code path}: through its mounts, else the path as it is (not in the container). */
    @Override
    public String toTargetPath(@Nullable Path given) {
        Path path = java.util.Objects.requireNonNull(given, "path");
        String inside = info.mounts().toContainer(path);
        if (inside != null) {
            return inside;
        }
        if (path.getRoot() != null && !path.isAbsolute()) {
            // A container's own file (toPath) on Windows, rooted but driveless: its name, not a drive's.
            return path.normalize().toString().replace('\\', '/');
        }
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * This machine's file for a path the container names: through its mounts. A path no mount covers is a
     * file of the container only; it is returned as it is, and resolves to no target of this container.
     */
    @Override
    public Path toPath(String targetPath) {
        Path local = info.mounts().toLocal(targetPath);
        return local != null ? local : Path.of(targetPath);
    }

    /**
     * {@code ~/.cache/editora} in the container's home, as its login shell reports it. This machine cannot see
     * it (a container's home is in no mount, as a rule), so its files are written by commands in the container
     * ({@link ShellTargetCache}). It lasts as long as the container does.
     */
    @Override
    public TargetCache cache() throws IOException {
        String home = env().home();
        if (home == null || !home.startsWith("/")) {
            throw new IOException("could not learn the home folder in " + info.name());
        }
        return new ShellTargetCache(this, (home.equals("/") ? "" : home) + "/.cache/editora");
    }

    /** Whether {@code local} is in one of the container's mounts. No I/O. */
    public boolean covers(Path local) {
        return info.mounts().covers(local);
    }

    @Override
    public String toString() {
        return id() + " " + Map.of("mounts", info.mounts().mounts());
    }
}
