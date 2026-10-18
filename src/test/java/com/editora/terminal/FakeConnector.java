package com.editora.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.jediterm.core.util.TermSize;

/**
 * A "shell" made of two pipes, for tests: what the test emits is the shell's output, what the terminal writes
 * is recorded. No process, so it behaves the same on every OS.
 */
public final class FakeConnector implements TerminalConnector {
    private final PipedWriter output = new PipedWriter();
    private final PipedReader reader;
    private final ByteArrayOutputStream input = new ByteArrayOutputStream();
    private final CountDownLatch closed = new CountDownLatch(1);
    /** Counted down by a close or the end of output: what a shell's exit does to a real connector. */
    private final CountDownLatch finished = new CountDownLatch(1);

    public final AtomicReference<TermSize> lastResize = new AtomicReference<>();
    public volatile TermSize openedAt;

    public FakeConnector() throws IOException {
        reader = new PipedReader(output, 64 * 1024);
    }

    public void emit(String text) throws IOException {
        output.write(text);
        output.flush();
    }

    private volatile int exitCode;

    /** Ends the "shell" as a program exiting with {@code code} would. */
    public void exit(int code) throws IOException {
        exitCode = code;
        endOfOutput();
    }

    public void endOfOutput() throws IOException {
        finished.countDown();
        output.close();
    }

    public synchronized String written() {
        return input.toString(StandardCharsets.UTF_8);
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        try {
            return reader.read(buf, offset, length);
        } catch (IOException e) {
            // "Write end dead" once the test closed it: the end of the shell's output.
            return -1;
        }
    }

    @Override
    public synchronized void write(byte[] bytes) {
        input.writeBytes(bytes);
    }

    @Override
    public void write(String string) {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return closed.getCount() > 0;
    }

    @Override
    public void resize(TermSize size) {
        lastResize.set(size);
    }

    @Override
    public int waitFor() throws InterruptedException {
        finished.await();
        return exitCode;
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return "fake";
    }

    @Override
    public void close() {
        closed.countDown();
        finished.countDown();
        try {
            output.close();
        } catch (IOException ignored) {
            // Already closed by the test.
        }
    }

    private volatile boolean lost;

    /** Ends the "shell" as a dropped connection would: its output stops and it reports the connection lost. */
    public void dropConnection() throws IOException {
        lost = true;
        endOfOutput();
    }

    @Override
    public boolean lostConnection() {
        return lost;
    }

    /** Whether the terminal closed this connector (its tab was closed, or its window). */
    public boolean wasClosed() {
        return closed.getCount() == 0;
    }
}
