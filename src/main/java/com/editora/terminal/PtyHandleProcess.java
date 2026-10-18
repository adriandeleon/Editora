package com.editora.terminal;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.pty4j.PtyProcess;

/**
 * A pty4j process as a {@link Process} that answers {@link #toHandle()}.
 *
 * <p>pty4j's {@code UnixPtyProcess} throws from {@code toHandle()} — and so from {@code descendants()}, which
 * goes through it — while Editora's {@code ProcessRegistry} needs both: the handle's start time to recognise
 * the process after a crash, and its descendants to end a shell's whole tree. The pid is real, so the handle
 * is the operating system's own; everything else is the pty4j process's.
 */
final class PtyHandleProcess extends Process {

    private final PtyProcess pty;

    PtyHandleProcess(PtyProcess pty) {
        this.pty = pty;
    }

    @Override
    public ProcessHandle toHandle() {
        return ProcessHandle.of(pty.pid())
                .orElseThrow(() -> new IllegalStateException("process " + pty.pid() + " has already ended"));
    }

    @Override
    public long pid() {
        return pty.pid();
    }

    @Override
    public OutputStream getOutputStream() {
        return pty.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return pty.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return pty.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        return pty.waitFor();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return pty.waitFor(timeout, unit);
    }

    @Override
    public int exitValue() {
        return pty.exitValue();
    }

    @Override
    public boolean isAlive() {
        return pty.isAlive();
    }

    @Override
    public void destroy() {
        pty.destroy();
    }

    @Override
    public Process destroyForcibly() {
        pty.destroyForcibly();
        return this;
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return pty.onExit().thenApply(p -> this);
    }
}
