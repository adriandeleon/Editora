package com.editora.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import com.editora.process.LauncherProcess;
import com.editora.process.ProcessRegistry;
import com.editora.process.RemoteProcess;
import com.editora.process.TerminalChannel;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

/**
 * A shell somewhere else reached through a launcher run here on a pseudo-terminal: {@code docker exec -it} (or
 * podman's) into a container, {@code wsl.exe} into a WSL distribution. The launcher carries the size on to the
 * shell (the engine's client forwards SIGWINCH; ConPTY resizes the console wsl.exe is attached to).
 *
 * <p>The start script prints the shell's pid first ({@link PidMarkStream}). Closing hangs that pid up through the
 * target before ending the launcher, because a container keeps a program whose {@code exec} client died.
 *
 * <p>Once the pid is known the shell is {@linkplain ProcessRegistry#track tracked} as a {@link RemoteProcess}, as
 * Run's programs there are: the shutdown hook then waits (bounded) for the hang-up instead of letting the JVM exit
 * with it still on its way, and a crash leaves an orphan record the next start reaps.
 */
public final class PtyChannel implements TerminalChannel {

    private final PtyProcess process;
    private final InputStream output;
    private final LongConsumer hangUp;
    private final Shell shell;
    private volatile long pid = -1;
    /** The close in progress: the hang-up, then the end of the launcher. Null until closed. */
    private volatile Thread closing;

    private PtyChannel(PtyProcess process, LongConsumer hangUp, String targetId, LauncherProcess.Recorder recorder) {
        this.process = process;
        this.hangUp = hangUp;
        this.shell = new Shell(targetId == null ? "local" : targetId, recorder);
        this.output = new PidMarkStream(process.getInputStream(), found -> {
            pid = found;
            if (targetId != null && closing == null) {
                ProcessRegistry.track(shell);
            }
        });
    }

    /**
     * Starts {@code argv} on a {@code columns} x {@code rows} pseudo-terminal with {@code env} (the launcher's
     * environment here — the shell's own is the target's).
     *
     * @param hangUp sends SIGHUP to the shell's pid on the target, when it is known; run off the caller's thread
     */
    public static PtyChannel start(
            List<String> argv, Map<String, String> env, int columns, int rows, LongConsumer hangUp) throws IOException {
        return start(argv, env, columns, rows, hangUp, null, null);
    }

    /**
     * As above, tracking the shell on {@code targetId} once its pid is known; {@code recorder} says how a later
     * Editora finds it after a crash (null for no record).
     */
    public static PtyChannel start(
            List<String> argv,
            Map<String, String> env,
            int columns,
            int rows,
            LongConsumer hangUp,
            String targetId,
            LauncherProcess.Recorder recorder)
            throws IOException {
        PtyProcess process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.home"))
                .setInitialColumns(Math.max(1, columns))
                .setInitialRows(Math.max(1, rows))
                .setConsole(false)
                .setUseWinConPty(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
        return new PtyChannel(process, hangUp, targetId, recorder);
    }

    /** The shell as the process registry sees it. */
    RemoteProcess shell() {
        return shell;
    }

    /** The shell's pid on its target, once its start script has said it; -1 before that. */
    @Override
    public long pid() {
        return pid;
    }

    @Override
    public InputStream output() {
        return output;
    }

    @Override
    public OutputStream input() {
        return process.getOutputStream();
    }

    @Override
    public void resize(int columns, int rows) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(Math.max(1, columns), Math.max(1, rows)));
        }
    }

    @Override
    public boolean isOpen() {
        return process.isAlive();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    /** A launcher here has no connection to lose; when its target goes, the shell ends and so does the tab. */
    @Override
    public boolean lostConnection() {
        return false;
    }

    /**
     * Ends the launcher. SIGTERM alone is not enough when it is a shell (interactive shells ignore it), so the
     * pseudo-terminal is closed too, which hangs up its other side; a launcher still there after that is killed.
     */
    private void end() {
        process.destroy();
        try {
            process.getOutputStream().close();
            process.getInputStream().close();
        } catch (IOException | RuntimeException alreadyClosed) {
            // nothing left to hang up
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        startClosing();
    }

    /** Starts the close once — hang up, then end the launcher — and returns the thread doing it. */
    private synchronized Thread startClosing() {
        if (closing == null) {
            long known = pid;
            closing = Thread.ofVirtual().name("terminal-hangup").start(() -> {
                try {
                    if (known > 0 && process.isAlive()) {
                        hangUp.accept(known);
                    }
                } finally {
                    end();
                }
            });
        }
        return closing;
    }

    /**
     * The shell for {@link ProcessRegistry}: stopping it is closing the channel, and it is alive while the launcher
     * is, which outlives a hang-up in progress — so the shutdown hook's wait covers the hang-up too.
     */
    private final class Shell extends RemoteProcess {

        private final String targetId;
        private final LauncherProcess.Recorder recorder;

        Shell(String targetId, LauncherProcess.Recorder recorder) {
            this.targetId = targetId;
            this.recorder = recorder;
        }

        @Override
        public String targetId() {
            return targetId;
        }

        @Override
        public long remotePid() {
            return pid;
        }

        @Override
        public void signalTree(boolean force) {
            startClosing();
            if (force) {
                Thread.ofVirtual().name("terminal-hangup-force").start(() -> signalTreeNow(true, 5_000));
            }
        }

        /** Waits for the hang-up (at most {@code millis}); forced, a launcher still there is then killed. */
        @Override
        public void signalTreeNow(boolean force, long millis) {
            Thread closer = startClosing();
            try {
                closer.join(java.time.Duration.ofMillis(Math.max(1, millis)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (force && process.isAlive()) {
                process.destroyForcibly();
            }
        }

        @Override
        public boolean connectionLost() {
            return false;
        }

        @Override
        public List<String> orphanRecord() {
            long known = pid;
            if (recorder == null || known <= 0 || !process.isAlive()) {
                return null;
            }
            try {
                return recorder.record(known);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return process.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return process.waitFor(timeout, unit);
        }

        @Override
        public int exitValue() {
            return process.exitValue();
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return process.onExit().thenApply(p -> this);
        }
    }
}
