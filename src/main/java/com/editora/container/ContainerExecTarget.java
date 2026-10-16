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
import com.editora.process.PortForward;
import com.editora.process.PosixShell;
import com.editora.process.RemoteEnv;

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
    private volatile RemoteEnv env;

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
        return ContainerExecProcess.start(this, client, START_TIMEOUT);
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
    private volatile String relayTool;

    /**
     * A loopback port here joined to {@code host:port} as the container sees it, through a relay run with
     * {@code exec} for each connection ({@link ContainerPortForward}).
     */
    @Override
    public PortForward forwardLocal(String host, int port) throws IOException {
        if (!running) {
            throw new IOException("the container " + info.name() + " is not running");
        }
        List<String> relay = relayArgv(relayTool(), host, port);
        if (relay == null) {
            throw new IOException(
                    "the container " + info.name() + " has no nc, bash or python3 to reach its port " + port);
        }
        return ContainerPortForward.open(this, relay);
    }

    private String relayTool() throws IOException {
        String known = relayTool;
        if (known == null) {
            known = capture(List.of(
                            "/bin/sh",
                            "-c",
                            "for t in nc bash python3; do command -v $t >/dev/null 2>&1 && { echo $t; exit; }; done"))
                    .strip();
            relayTool = known;
        }
        return known;
    }

    /** The inner command that joins its stdin and stdout to {@code host:port} with {@code tool}; null for none. Pure. */
    static List<String> relayArgv(String tool, String host, int port) {
        String p = String.valueOf(port);
        return switch (tool == null ? "" : tool) {
            case "nc" -> List.of("nc", host, p);
            // The writer becomes the shell; the reader keeps stdout, so the port closing ends this side too.
            case "bash" ->
                List.of("bash", "-c", "exec 3<>\"/dev/tcp/$0/$1\" || exit 1; cat <&3 & exec cat >&3", host, p);
            case "python3" ->
                List.of(
                        "python3",
                        "-c",
                        "import socket,sys,threading\n"
                                + "s=socket.create_connection((sys.argv[1],int(sys.argv[2])))\n"
                                + "def up():\n"
                                + "  while True:\n"
                                + "    b=sys.stdin.buffer.read1(65536)\n"
                                + "    if not b: s.shutdown(socket.SHUT_WR); return\n"
                                + "    s.sendall(b)\n"
                                + "threading.Thread(target=up,daemon=True).start()\n"
                                + "while True:\n"
                                + "  b=s.recv(65536)\n"
                                + "  if not b: break\n"
                                + "  sys.stdout.buffer.write(b); sys.stdout.buffer.flush()\n",
                        host,
                        p);
            default -> null;
        };
    }

    /** Starts one relay connection: {@code exec} of {@code relay} in the container. */
    Process relayProcess(List<String> relay) throws IOException {
        return client(engine.execArgv(info.id(), info.user(), relay))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** The container's name for {@code path}: through its mounts, else the path as it is (not in the container). */
    @Override
    public String toTargetPath(Path path) {
        String inside = info.mounts().toContainer(path);
        return inside != null ? inside : path.toAbsolutePath().normalize().toString();
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

    /** Whether {@code local} is in one of the container's mounts. No I/O. */
    public boolean covers(Path local) {
        return info.mounts().covers(local);
    }

    @Override
    public String toString() {
        return id() + " " + Map.of("mounts", info.mounts().mounts());
    }
}
