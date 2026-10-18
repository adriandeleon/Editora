package com.editora.terminal;

import java.io.IOException;

import com.jediterm.core.util.TermSize;

/**
 * A connector whose output goes through an {@link OscTap}: what the emulator reads has the shell-integration
 * sequences taken out, and they reach the listener in stream order — each just before the text after it is read.
 */
final class TappedConnector implements TerminalConnector {

    private final TerminalConnector inner;
    private final OscTap tap = new OscTap();
    private final OscTap.Listener listener;
    private final char[] buffer = new char[8192];

    TappedConnector(TerminalConnector inner, OscTap.Listener listener) {
        this.inner = inner;
        this.listener = listener;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        while (true) {
            while (tap.hasNext()) {
                int n = tap.next(buf, offset, length, listener);
                if (n > 0) {
                    return n;
                }
            }
            int read = inner.read(buffer, 0, buffer.length);
            if (read < 0) {
                return -1;
            }
            tap.feed(buffer, 0, read);
        }
    }

    @Override
    public boolean ready() throws IOException {
        return tap.hasNext() || inner.ready();
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        inner.write(bytes);
    }

    @Override
    public void write(String string) throws IOException {
        inner.write(string);
    }

    @Override
    public boolean isConnected() {
        return inner.isConnected();
    }

    @Override
    public void resize(TermSize size) {
        inner.resize(size);
    }

    @Override
    public int waitFor() throws InterruptedException {
        return inner.waitFor();
    }

    @Override
    public String getName() {
        return inner.getName();
    }

    @Override
    public void close() {
        inner.close();
    }

    @Override
    public long pid() {
        return inner.pid();
    }

    @Override
    public long remotePid() {
        return inner.remotePid();
    }

    @Override
    public boolean lostConnection() {
        return inner.lostConnection();
    }
}
