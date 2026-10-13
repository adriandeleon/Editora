package com.editora.vfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import com.editora.process.PosixShell;
import com.editora.process.RemoteProcess;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

/**
 * A command running on an SSH host, on its own exec channel of the connection's session. The channel's three
 * streams are the process's; its close is the exit. What a channel cannot do is signal: closing it only ends
 * our end, so the tree is stopped by running {@code kill} on a second, short-lived channel.
 */
final class SshExecProcess extends RemoteProcess {

    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(5);
    /** Bytes a login shell's startup files may print ahead of the pid line before it counts as a failure. */
    private static final int MAX_NOISE = 64 * 1024;

    private final ClientSession session;
    private final ChannelExec channel;
    private final String targetId;
    private final long pid;
    /** Released by the channel's own close notification, so a waiter never polls or misses it. */
    private final CountDownLatch exited = new CountDownLatch(1);

    private SshExecProcess(ClientSession session, ChannelExec channel, String targetId, long pid) {
        this.session = session;
        this.channel = channel;
        this.targetId = targetId;
        this.pid = pid;
        channel.addCloseFutureListener(closed -> exited.countDown());
    }

    /** Runs {@code action} once the channel has closed, at once if it already has. */
    void whenClosed(Runnable action) {
        channel.addCloseFutureListener(closed -> action.run());
    }

    /**
     * Starts {@code commandLine} (built by {@link PosixShell#commandLine}) and waits for its pid line, so a
     * command the host could not even begin fails here, with what the host said, rather than as a process
     * that produced nothing.
     */
    static SshExecProcess start(ClientSession session, String targetId, String commandLine, Duration timeout)
            throws IOException {
        ChannelExec channel = session.createExecChannel(commandLine);
        AtomicBoolean started = new AtomicBoolean();
        try {
            channel.open().verify(timeout);
            // A startup file that never returns would leave the read below waiting forever.
            Thread.ofVirtual().name("ssh-exec-start-timeout").start(() -> {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    return;
                }
                if (!started.get()) {
                    channel.close(true);
                }
            });
            long pid = readPidLine(channel.getInvertedErr());
            started.set(true);
            return new SshExecProcess(session, channel, targetId, pid);
        } catch (IOException | RuntimeException e) {
            started.set(true);
            channel.close(true);
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    /** Consumes stderr up to and including the pid line; everything after it is the command's own. */
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
            throw new IOException(said.isEmpty() ? "the remote host did not start the command" : said);
        }
        try {
            return Long.parseLong(text.substring(PosixShell.PID_MARK.length()).strip());
        } catch (NumberFormatException e) {
            throw new IOException("the remote host did not report a process id");
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
        if (channel.isClosed()) {
            return;
        }
        if (!session.isOpen()) {
            channel.close(true); // nothing can be signalled; the host's watchdog ends a long-lived tree
            return;
        }
        Thread.ofVirtual().name("ssh-exec-kill").start(() -> {
            try (ChannelExec kill = session.createExecChannel(PosixShell.killCommandLine(pid, force))) {
                kill.open().verify(KILL_TIMEOUT);
                kill.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), KILL_TIMEOUT);
            } catch (IOException | RuntimeException e) {
                if (force) {
                    channel.close(true); // could not reach it: at least stop waiting on it
                }
            }
        });
    }

    @Override
    public boolean connectionLost() {
        return channel.isClosed() && channel.getExitStatus() == null && channel.getExitSignal() == null;
    }

    @Override
    public OutputStream getOutputStream() {
        return channel.getInvertedIn();
    }

    @Override
    public InputStream getInputStream() {
        return channel.getInvertedOut();
    }

    @Override
    public InputStream getErrorStream() {
        return channel.getInvertedErr();
    }

    @Override
    public int waitFor() throws InterruptedException {
        exited.await();
        return exitValue();
    }

    @Override
    public int exitValue() {
        if (exited.getCount() > 0) {
            throw new IllegalThreadStateException("process has not exited");
        }
        Integer status = channel.getExitStatus();
        return status == null ? -1 : status;
    }
}
