package com.editora.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;

/**
 * A command started elsewhere through a local launcher ({@code docker exec}, {@code wsl.exe}): the
 * launcher's streams are the program's, its exit is the program's exit. The command is a
 * {@link PosixShell} script, so its pid line comes first on stderr. Stopping it signals the program's group
 * where it runs ({@link Signaller}): ending the launcher alone would leave the program running there.
 */
public final class LauncherProcess extends RemoteProcess {

    /** Signals the process group {@code pid} leads where the program runs; false when that failed. */
    @FunctionalInterface
    public interface Signaller {
        boolean signal(long pid, boolean force);
    }

    /** {@link RemoteProcess#orphanRecord} for the program with {@code pid}, asked of its target. */
    @FunctionalInterface
    public interface Recorder {
        java.util.List<String> record(long pid) throws IOException;
    }

    private static final int MAX_NOISE = 64 * 1024;

    private final String targetId;
    private final Process client;
    private final long pid;
    private final Signaller signaller;
    private final @Nullable Recorder recorder;

    private LauncherProcess(
            String targetId, Process client, long pid, Signaller signaller, @Nullable Recorder recorder) {
        this.targetId = targetId;
        this.client = client;
        this.pid = pid;
        this.signaller = signaller;
        this.recorder = recorder;
    }

    /** {@link #start(String, Process, Duration, Signaller, Recorder)} with no orphan record. */
    public static LauncherProcess start(String targetId, Process client, Duration timeout, Signaller signaller)
            throws IOException {
        return start(targetId, client, timeout, signaller, null);
    }

    /**
     * Waits for the command's pid line, so a command that cannot even begin fails here, saying why.
     *
     * @param recorder how the next Editora finds the program after a crash ({@link #orphanRecord}), or null
     */
    public static LauncherProcess start(
            String targetId, Process client, Duration timeout, Signaller signaller, @Nullable Recorder recorder)
            throws IOException {
        AtomicBoolean started = new AtomicBoolean();
        Thread.ofVirtual().name("launcher-start-timeout").start(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                return;
            }
            if (!started.get()) {
                client.destroyForcibly();
            }
        });
        try {
            long pid = readPidLine(client.getErrorStream());
            started.set(true);
            return new LauncherProcess(targetId, client, pid, signaller, recorder);
        } catch (IOException | RuntimeException e) {
            started.set(true);
            client.destroyForcibly();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    static long readPidLine(InputStream err) throws IOException {
        ByteArrayOutputStream noise = new ByteArrayOutputStream();
        int mark = PosixShell.PID_MARK.charAt(0);
        int b;
        while ((b = err.read()) != -1 && b != mark) {
            if (noise.size() < MAX_NOISE) {
                noise.write(b);
            }
        }
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (b != -1 && (b = err.read()) != -1 && b != '\n') {
            line.write(b);
        }
        String text = (char) mark + line.toString(StandardCharsets.UTF_8);
        if (b == -1 || !text.startsWith(PosixShell.PID_MARK)) {
            String said = noise.toString(StandardCharsets.UTF_8).strip();
            throw new IOException(said.isEmpty() ? "the command did not start" : said);
        }
        try {
            return Long.parseLong(text.substring(PosixShell.PID_MARK.length()).strip());
        } catch (NumberFormatException e) {
            throw new IOException("the command did not report a process id");
        }
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
        if (!client.isAlive()) {
            return;
        }
        Thread.ofVirtual().name("launcher-kill").start(() -> {
            if (!signaller.signal(pid, force) && force) {
                client.destroyForcibly(); // could not reach it: at least stop waiting on it
            }
        });
    }

    @Override
    public boolean connectionLost() {
        return false;
    }

    /**
     * Ending the launcher here does not end the program there, so a crashed Editora would leave it running:
     * the recorder says how the next one finds it ({@link ProcessRegistry#reapOrphans}).
     */
    @Override
    public java.util.@Nullable List<String> orphanRecord() {
        if (recorder == null || !client.isAlive()) {
            return null;
        }
        try {
            return recorder.record(pid);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void signalTreeNow(boolean force, long millis) {
        if (!client.isAlive()) {
            return;
        }
        Thread signal = Thread.ofVirtual().name("launcher-kill").unstarted(() -> {
            if (!signaller.signal(pid, force) && force) {
                client.destroyForcibly();
            }
        });
        signal.start();
        try {
            signal.join(Duration.ofMillis(Math.max(1, millis)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public OutputStream getOutputStream() {
        return client.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return client.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return client.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return client.waitFor();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return client.waitFor(timeout, unit);
    }

    @Override
    public int exitValue() {
        return client.exitValue();
    }

    @Override
    public boolean isAlive() {
        return client.isAlive();
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return client.onExit().thenApply(p -> this);
    }
}
