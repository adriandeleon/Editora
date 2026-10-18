package com.editora.terminal;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.editora.process.TerminalChannel;
import com.jediterm.core.util.TermSize;

/**
 * A shell on another machine, as the emulator sees it: a {@link TerminalChannel} from an execution target (an SSH
 * host now; containers and WSL later), read as UTF-8 — the shell was told {@code TERM=xterm-256color}, and every
 * host this talks to is asked for a UTF-8 locale by its own profile.
 */
public final class ChannelConnector implements TerminalConnector {

    private final TerminalChannel channel;
    private final InputStreamReader reader;
    private final String name;

    public ChannelConnector(TerminalChannel channel, String name) {
        this.channel = channel;
        this.reader = new InputStreamReader(channel.output(), StandardCharsets.UTF_8);
        this.name = name;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        try {
            return reader.read(buf, offset, length);
        } catch (IOException e) {
            if (!channel.isOpen()) {
                return -1; // the channel closed under the read: the end of the shell's output
            }
            throw e;
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        var in = channel.input();
        in.write(bytes);
        in.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return channel.isOpen();
    }

    @Override
    public void resize(TermSize size) {
        channel.resize(size.getColumns(), size.getRows());
    }

    @Override
    public int waitFor() throws InterruptedException {
        return channel.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void close() {
        channel.close();
    }

    /** The shell's pid on its own machine, once known; what a "still running?" check asks about there. */
    @Override
    public long remotePid() {
        return channel.pid();
    }

    @Override
    public boolean lostConnection() {
        return channel.lostConnection();
    }
}
