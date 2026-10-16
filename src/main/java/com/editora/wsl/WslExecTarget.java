package com.editora.wsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.LauncherProcess;
import com.editora.process.PortForward;
import com.editora.process.PortRelays;
import com.editora.process.PosixShell;
import com.editora.process.RelayPortForward;
import com.editora.process.RemoteEnv;

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

    /** {@code wsl.exe -d <distro> --exec <inner…>}: no shell of WSL's own in between. */
    List<String> argv(List<String> inner) {
        List<String> argv = new ArrayList<>(launcher);
        argv.addAll(List.of("-d", distro, "--exec"));
        argv.addAll(inner);
        return List.copyOf(argv);
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
        return LauncherProcess.start(id(), client, START_TIMEOUT, this::signal);
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
