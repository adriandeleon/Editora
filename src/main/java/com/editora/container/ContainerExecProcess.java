package com.editora.container;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.editora.process.PosixShell;
import com.editora.process.RemoteProcess;

/**
 * A command running in a container: the local {@code exec} client's streams are the program's, its exit is
 * the program's exit. Stopping it runs {@code kill} in the container: ending the client alone would leave the
 * program running there.
 */
final class ContainerExecProcess extends RemoteProcess {

    private static final int MAX_NOISE = 64 * 1024;

    private final ContainerExecTarget target;
    private final Process client;
    private final long pid;

    private ContainerExecProcess(ContainerExecTarget target, Process client, long pid) {
        this.target = target;
        this.client = client;
        this.pid = pid;
    }

    /** Waits for the command's pid line, so a command that cannot even begin fails here, saying why. */
    static ContainerExecProcess start(ContainerExecTarget target, Process client, Duration timeout) throws IOException {
        AtomicBoolean started = new AtomicBoolean();
        Thread.ofVirtual().name("container-exec-start-timeout").start(() -> {
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
            return new ContainerExecProcess(target, client, pid);
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
            throw new IOException(said.isEmpty() ? "the container did not start the command" : said);
        }
        try {
            return Long.parseLong(text.substring(PosixShell.PID_MARK.length()).strip());
        } catch (NumberFormatException e) {
            throw new IOException("the container did not report a process id");
        }
    }

    @Override
    public String targetId() {
        return target.id();
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
        Thread.ofVirtual().name("container-exec-kill").start(() -> {
            if (!target.signal(pid, force) && force) {
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
