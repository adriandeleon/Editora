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

    private static final int MAX_NOISE = 64 * 1024;

    private final String targetId;
    private final Process client;
    private final long pid;
    private final Signaller signaller;

    private LauncherProcess(String targetId, Process client, long pid, Signaller signaller) {
        this.targetId = targetId;
        this.client = client;
        this.pid = pid;
        this.signaller = signaller;
    }

    /** Waits for the command's pid line, so a command that cannot even begin fails here, saying why. */
    public static LauncherProcess start(String targetId, Process client, Duration timeout, Signaller signaller)
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
            return new LauncherProcess(targetId, client, pid, signaller);
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
