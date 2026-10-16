package com.editora.process;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** A {@link RemoteProcess} with canned output that runs until it is told to exit or is force-signalled. */
final class FakeRemoteProcess extends RemoteProcess {

    final List<Boolean> signals = new CopyOnWriteArrayList<>();
    final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
    private final CountDownLatch exited = new CountDownLatch(1);
    private final InputStream out;
    private final InputStream err;
    private final boolean exitOnTerm;
    private volatile int exit;

    /** Still running: only {@code signalTree(true)} (or {@code signalTree(false)} when {@code exitOnTerm}) ends it. */
    FakeRemoteProcess(boolean exitOnTerm) {
        this("", "", exitOnTerm);
    }

    private FakeRemoteProcess(String out, String err, boolean exitOnTerm) {
        this.out = new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
        this.err = new ByteArrayInputStream(err.getBytes(StandardCharsets.UTF_8));
        this.exitOnTerm = exitOnTerm;
    }

    /** Already finished with this output. */
    static FakeRemoteProcess finished(int exit, String out, String err) {
        FakeRemoteProcess p = new FakeRemoteProcess(out, err, true);
        p.exit(exit);
        return p;
    }

    void exit(int code) {
        exit = code;
        exited.countDown();
    }

    @Override
    public String targetId() {
        return "user@fake:22";
    }

    @Override
    public long remotePid() {
        return 4242;
    }

    @Override
    public void signalTree(boolean force) {
        signals.add(force);
        if (force || exitOnTerm) {
            exit(force ? 137 : 143);
        }
    }

    @Override
    public boolean connectionLost() {
        return false;
    }

    /** What {@link #orphanRecord} answers: null, as for an SSH host, unless a test sets one. */
    volatile List<String> record;

    @Override
    public List<String> orphanRecord() {
        return record;
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    @Override
    public InputStream getInputStream() {
        return out;
    }

    @Override
    public InputStream getErrorStream() {
        return err;
    }

    @Override
    public int waitFor() throws InterruptedException {
        exited.await();
        return exit;
    }

    @Override
    public int exitValue() {
        if (exited.getCount() > 0) {
            throw new IllegalThreadStateException("still running");
        }
        return exit;
    }
}
