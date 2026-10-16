package com.editora.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.PortForward;
import com.editora.process.PosixShell;
import com.editora.process.RemoteEnv;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

/**
 * The host behind one SFTP connection, as a place to run commands: each one on its own exec channel of the
 * session the filesystem already holds, so there is one login, one host-key check and one connection to lose.
 * A path's name on the host is simply its absolute SFTP path.
 */
final class SshExecTarget implements ExecTarget {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * An OpenSSH server allows ten channels on one connection by default ({@code MaxSessions}); past that it
     * refuses, and the refusal would land on whichever feature asked next — a save as easily as a build.
     * So the connection's channels are budgeted here: one is SFTP's, one is kept for the kill commands that
     * stop other channels' processes, and the rest are split between short commands, which queue, and
     * long-lived ones (servers, runs, builds), which are refused with an explanation instead.
     */
    static final int MAX_ONE_SHOT = 3;

    static final int MAX_LONG_LIVED = 5;
    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LONG_LIVED_WAIT = Duration.ofSeconds(5);

    private final Semaphore oneShots = new Semaphore(MAX_ONE_SHOT, true);
    private final Semaphore longLived = new Semaphore(MAX_LONG_LIVED);

    private final String id;
    private final SftpFileSystem fs;
    private volatile RemoteEnv env;

    /** The least the host's clock can be ahead of this machine's, in millis, once measured. */
    private volatile Long clockLead;

    SshExecTarget(String id, SftpFileSystem fs) {
        this.id = id;
        this.fs = fs;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return !RemoteFileSystems.isDisconnected(fs.getPath("/"));
    }

    @Override
    public Process start(ExecSpec spec) throws IOException {
        ClientSession session = session();
        RemoteEnv known = env();
        String cwd = spec.workingDir() == null ? null : toTargetPath(spec.workingDir());
        String line = PosixShell.commandLine(spec, cwd, known.path(), known.locale(), known.profile());
        Semaphore budget = acquire(spec.longLived());
        try {
            SshExecProcess process = SshExecProcess.start(session, id, line, START_TIMEOUT);
            process.whenClosed(budget::release);
            return process;
        } catch (IOException | RuntimeException e) {
            budget.release();
            if (e instanceof SshChannelOpenException) {
                throw new IOException(
                        id + " refused to run another command at the same time (its MaxSessions limit)", e);
            }
            throw e;
        }
    }

    private Semaphore acquire(boolean isLongLived) throws IOException {
        if (isLongLived) {
            // A program just stopped frees its slot when its channel closes, a moment after the stop returns:
            // a start right behind it waits that moment rather than being refused.
            boolean got;
            try {
                got = longLived.tryAcquire(LONG_LIVED_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting to start a program on " + id, e);
            }
            if (!got) {
                throw new IOException("too many programs are already running on " + id + " (" + MAX_LONG_LIVED
                        + " at once); stop one of them first");
            }
            return longLived;
        }
        try {
            if (!oneShots.tryAcquire(QUEUE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("timed out waiting for " + id + " to finish other commands");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting to run a command on " + id, e);
        }
        return oneShots;
    }

    @Override
    public PortForward forwardLocal(String host, int port) throws IOException {
        ExplicitPortForwardingTracker tracker = session()
                .createLocalPortForwardingTracker(
                        new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress(host, port));
        int localPort = tracker.getBoundAddress().getPort();
        return new PortForward() {
            @Override
            public int localPort() {
                return localPort;
            }

            @Override
            public void close() {
                try {
                    tracker.close();
                } catch (IOException | RuntimeException gone) {
                    // the connection went first, and took the listener with it
                }
            }
        };
    }

    @Override
    public String toTargetPath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    @Override
    public Path toPath(String targetPath) {
        return fs.getPath(targetPath);
    }

    private ClientSession session() throws IOException {
        ClientSession session = fs.isOpen() ? fs.getSession() : null;
        if (session == null || !session.isOpen()) {
            throw new IOException("not connected to " + id);
        }
        return session;
    }

    /**
     * The host's login-shell environment, asked for once. A probe that fails (a startup file that hangs, a
     * shell that is not POSIX) is not remembered, so a later command tries again; meanwhile commands run
     * with whatever environment the server gives them.
     */
    RemoteEnv env() throws IOException {
        RemoteEnv known = env;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (env == null) {
                RemoteEnv probed = RemoteEnv.parse(capture(session(), RemoteEnv.probeCommandLine()));
                if (probed == RemoteEnv.UNKNOWN) {
                    return probed;
                }
                env = probed;
            }
            return env;
        }
    }

    /** {@code ~/.cache/editora} in the account's home, as the login shell reports it. */
    @Override
    public Path cacheDir() throws IOException {
        return homeDir().resolve(".cache").resolve("editora");
    }

    /** The account's home, as the login shell reports it. */
    @Override
    public Path homeDir() throws IOException {
        String home = env().home();
        if (home == null || !home.startsWith("/")) {
            throw new IOException("could not learn the home folder on " + id);
        }
        return fs.getPath(home);
    }

    /**
     * The host's clock is read once per connection with {@code date +%s} (whole seconds, as anywhere). The
     * host's second {@code s} began no later than the moment the answer arrived, so {@code s - arrival} is
     * the least the host can be ahead; converting with it gives the latest local instant a host time can
     * mean, whatever the round trip took.
     */
    @Override
    public long latestLocalTime(long hostMillis) {
        Long lead = clockLead();
        return lead == null ? -1 : hostMillis - lead;
    }

    private Long clockLead() {
        Long known = clockLead;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (clockLead == null) {
                try {
                    Semaphore slot = acquire(false);
                    try {
                        String out = capture(session(), "date +%s").strip();
                        long arrived = System.currentTimeMillis();
                        clockLead = leastLead(out, arrived);
                    } finally {
                        slot.release();
                    }
                } catch (IOException e) {
                    return null; // asked again next time
                }
            }
            return clockLead;
        }
    }

    /** {@code date +%s} output and the local instant it arrived → the least lead, or null when unreadable. */
    static Long leastLead(String dateOutput, long arrivedMillis) {
        try {
            return Long.parseLong(dateOutput.strip()) * 1000 - arrivedMillis;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Runs a raw command line and returns its stdout; empty when it fails or outlives the probe timeout. */
    private static String capture(ClientSession session, String commandLine) {
        try (ChannelExec channel = session.createExecChannel(commandLine)) {
            channel.open().verify(PROBE_TIMEOUT);
            channel.getInvertedIn().close();
            Thread closer = Thread.ofVirtual().name("ssh-probe-timeout").start(() -> {
                try {
                    Thread.sleep(PROBE_TIMEOUT);
                    channel.close(true);
                } catch (InterruptedException done) {
                    // finished in time
                }
            });
            try (InputStream out = channel.getInvertedOut()) {
                byte[] bytes = out.readNBytes(1024 * 1024);
                channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(1));
                return new String(bytes, StandardCharsets.UTF_8);
            } finally {
                closer.interrupt();
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
