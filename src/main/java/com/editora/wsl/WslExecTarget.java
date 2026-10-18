package com.editora.wsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import com.editora.process.TerminalChannel;
import com.editora.process.TerminalSpec;
import com.editora.terminal.PtyChannel;

/**
 * A WSL distribution, for its folders as Windows reaches them ({@code \\wsl.localhost\<distro>\…}): their
 * tools run inside the distribution with {@code wsl.exe -d <distro> --exec}, with its login environment, as
 * on an SSH host — the same script ({@link PosixShell#argv}): the pid line, the folder, the environment, then
 * the program. Stop signals the program's group with a second {@code wsl.exe}; a port is reached through a
 * relay run the same way ({@link RelayPortForward}).
 */
public final class WslExecTarget implements ExecTarget {

    static final Duration START_TIMEOUT = Duration.ofSeconds(60); // a distribution may have to boot first

    private final List<String> launcher;
    private final String distro;
    private final String sharePrefix;
    private volatile RemoteEnv env;
    private volatile String relayTool;

    /**
     * @param launcher the {@code wsl.exe} command
     * @param sharePrefix how Windows reaches the distribution's root ({@code \\wsl.localhost\Ubuntu})
     */
    public WslExecTarget(List<String> launcher, String distro, String sharePrefix) {
        this.launcher = List.copyOf(launcher);
        this.distro = distro;
        this.sharePrefix = sharePrefix;
    }

    public String distro() {
        return distro;
    }

    @Override
    public String id() {
        return "wsl:" + distro;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return true; // wsl.exe starts the distribution when asked
    }

    /**
     * Windows's watcher on {@code \\wsl.localhost} (the 9P share) is not told what changes inside the
     * distribution, so a WSL folder is watched there, by inotify or a poll ({@code ui/RemoteWatcher}).
     */
    @Override
    public boolean localWatchSeesChanges() {
        return false;
    }

    /** {@code wsl.exe -d <distro> --exec <inner…>}: no shell of WSL's own in between. */
    List<String> argv(List<String> inner) {
        List<String> argv = new ArrayList<>(launcher);
        argv.addAll(List.of("-d", distro, "--exec"));
        argv.addAll(windowsSafe(inner));
        return List.copyOf(argv);
    }

    /**
     * Decodes its one argument ({@link #encode}) and runs it. No {@code "} and no {@code \}: on Windows an argv
     * is joined into one command line that {@code wsl.exe} splits again, and Java does not escape an embedded
     * {@code "}, so the launcher script would arrive in pieces. Globbing and splitting are off while the
     * decoded text is handed to {@code eval}; the {@code exec}'d shell starts with neither setting.
     */
    static final String DECODE = "set -f; IFS=; eval $(printf %b $1)";

    /**
     * {@code inner} as an argv of the distribution's shell that survives Windows' command-line joining: what
     * follows {@code wsl.exe -d <distro> --exec} for any command Editora runs there.
     */
    public static List<String> windowsSafe(List<String> inner) {
        StringBuilder command = new StringBuilder("exec");
        for (String word : inner) {
            command.append(' ').append(PosixShell.quote(word));
        }
        return List.of("/bin/sh", "-c", DECODE, "editora", encode(command.toString()));
    }

    /**
     * {@code text} for {@code printf %b}: letters, digits and {@code _./=,:+@} as they are, every other UTF-8 byte
     * as {@code \0ooo}. The result has no space, quote or glob character, and never ends with {@code \}.
     */
    static String encode(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2);
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "_./=,:+@".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append("\\0")
                        .append((char) ('0' + (c >> 6)))
                        .append((char) ('0' + ((c >> 3) & 7)))
                        .append((char) ('0' + (c & 7)));
            }
        }
        return out.toString();
    }

    private static ProcessBuilder client(List<String> argv) {
        ProcessBuilder pb = new ProcessBuilder(com.editora.process.ProcessRunner.resolveExecutable(argv));
        com.editora.process.ProcessRunner.applyUserEnv(
                pb); // the launcher's; the distribution's own comes from the script
        return pb;
    }

    @Override
    public Process start(ExecSpec spec) throws IOException {
        String cwd = spec.workingDir() == null ? null : toTargetPath(spec.workingDir());
        RemoteEnv known = env();
        Process client = client(argv(PosixShell.argv(spec, cwd, known.path(), known.locale(), known.profile())))
                .start();
        return LauncherProcess.start(id(), client, START_TIMEOUT, this::signal, this::orphanRecord);
    }

    /**
     * A login shell on a pseudo-terminal in the distribution, for the integrated terminal: {@code wsl.exe} run here
     * on ConPTY. WSL 1 ends the shell with {@code wsl.exe}; closing also hangs it up in the distribution
     * ({@link #hangUp}), for WSL 2, where that is not certain.
     */
    @Override
    public TerminalChannel startTerminal(TerminalSpec spec) throws IOException {
        String cwd = spec.workingDir() == null ? null : toTargetPath(spec.workingDir());
        List<String> argv = argv(List.of("/bin/sh", "-c", PosixShell.terminalScript(cwd, true, spec)));
        return PtyChannel.start(
                com.editora.process.ProcessRunner.resolveExecutable(argv),
                com.editora.process.ProcessRunner.applyUserEnv(new java.util.HashMap<>(System.getenv()), Map.of()),
                spec.columns(),
                spec.rows(),
                this::hangUp);
    }

    /** SIGHUP to the shell {@code pid} in the distribution. */
    void hangUp(long pid) {
        try {
            Process kill = client(argv(List.of("kill", "-s", "HUP", "--", Long.toString(pid))))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            kill.getOutputStream().close();
            if (!kill.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                kill.destroyForcibly();
            }
        } catch (IOException e) {
            // The distribution went with it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@link #orphanRecord}'s kind, and the reaper {@code App} registers for it. */
    public static final String ORPHAN_KIND = "wsl";

    /**
     * How the next Editora finds the program with {@code pid} again: the distribution, the pid and its start
     * time there ({@link PosixShell#startTimeArgv}), then the launcher's command.
     */
    private List<String> orphanRecord(long pid) throws IOException {
        String start = capture(PosixShell.startTimeArgv(pid)).strip();
        if (!start.matches("\\d+")) {
            throw new IOException("no start time for " + pid + " in " + distro);
        }
        List<String> record = new ArrayList<>(List.of(ORPHAN_KIND, distro, Long.toString(pid), start));
        record.addAll(launcher);
        return record;
    }

    /**
     * Stops a program a crashed Editora left in a distribution, from its {@link #orphanRecord} (kind removed),
     * only while the pid is still the process that started then. False when it was not there any more.
     */
    public static boolean reapOrphan(List<String> fields) throws IOException {
        if (fields.size() < 4) {
            return false;
        }
        WslExecTarget target = new WslExecTarget(fields.subList(3, fields.size()), fields.get(0), "");
        Process p = client(target.argv(PosixShell.reapArgv(Long.parseLong(fields.get(1)), fields.get(2))))
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

    /** The distribution's login environment, asked once. */
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
        Process p = client(argv(inner))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        try (InputStream out = p.getInputStream()) {
            byte[] bytes = out.readAllBytes();
            if (!p.waitFor(START_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the WSL distribution " + distro + " did not answer");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        }
    }

    boolean signal(long pid, boolean force) {
        try {
            Process kill = client(argv(PosixShell.killArgv(pid, force)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            kill.getOutputStream().close();
            return kill.waitFor(10, TimeUnit.SECONDS) && kill.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public PortForward forwardLocal(String host, int port) throws IOException {
        String tool = relayTool;
        if (tool == null) {
            tool = capture(List.of("/bin/sh", "-c", PortRelays.DETECT)).strip();
            relayTool = tool;
        }
        List<String> relay = PortRelays.argv(tool, host, port);
        if (relay == null) {
            throw new IOException(
                    "the WSL distribution " + distro + " has no nc, bash or python3 to reach its port " + port);
        }
        return RelayPortForward.open(() -> client(argv(relay))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start());
    }

    /** The distribution's name for {@code path}: inside it, or a drive under {@code /mnt}; else as it is. */
    @Override
    public String toTargetPath(Path path) {
        String windows = path.toString();
        String linux = WslPaths.toLinux(windows, distro);
        return linux != null ? linux : windows;
    }

    /** Windows's name for the distribution's {@code targetPath}. */
    @Override
    public Path toPath(String targetPath) {
        String windows = WslPaths.toWindows(targetPath, sharePrefix);
        return Path.of(windows != null ? windows : targetPath);
    }

    /** {@code ~/.cache/editora} in the distribution, as Windows reaches it. */
    @Override
    public Path cacheDir() throws IOException {
        return homeDir().resolve(".cache").resolve("editora");
    }

    @Override
    public Path homeDir() throws IOException {
        String home = env().home();
        if (home == null || !home.startsWith("/")) {
            throw new IOException("could not learn the home folder in " + distro);
        }
        return toPath(home);
    }
}
