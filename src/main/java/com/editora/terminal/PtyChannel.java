package com.editora.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

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
 */
public final class PtyChannel implements TerminalChannel {

    private final PtyProcess process;
    private final InputStream output;
    private final LongConsumer hangUp;
    private volatile long pid = -1;

    private PtyChannel(PtyProcess process, LongConsumer hangUp) {
        this.process = process;
        this.hangUp = hangUp;
        this.output = new PidMarkStream(process.getInputStream(), found -> pid = found);
    }

    /**
     * Starts {@code argv} on a {@code columns} x {@code rows} pseudo-terminal with {@code env} (the launcher's
     * environment here — the shell's own is the target's).
     *
     * @param hangUp sends SIGHUP to the shell's pid on the target, when it is known; run off the caller's thread
     */
    public static PtyChannel start(
            List<String> argv, Map<String, String> env, int columns, int rows, LongConsumer hangUp) throws IOException {
        PtyProcess process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(System.getProperty("user.home"))
                .setInitialColumns(Math.max(1, columns))
                .setInitialRows(Math.max(1, rows))
                .setConsole(false)
                .setUseWinConPty(true)
                .setWindowsAnsiColorEnabled(true)
                .start();
        return new PtyChannel(process, hangUp);
    }

    /** The shell's pid on its target, once its start script has said it; -1 before that. */
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
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        long known = pid;
        Thread.ofVirtual().name("terminal-hangup").start(() -> {
            try {
                if (known > 0 && process.isAlive()) {
                    hangUp.accept(known);
                }
            } finally {
                end();
            }
        });
    }
}
