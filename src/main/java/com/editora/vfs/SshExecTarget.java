package com.editora.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.PosixShell;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

/**
 * The host behind one SFTP connection, as a place to run commands: each one on its own exec channel of the
 * session the filesystem already holds, so there is one login, one host-key check and one connection to lose.
 * A path's name on the host is simply its absolute SFTP path.
 */
final class SshExecTarget implements ExecTarget {

    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final String id;
    private final SftpFileSystem fs;
    private volatile RemoteEnv env;

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
        String line = PosixShell.commandLine(spec, cwd, known.path(), known.locale());
        return SshExecProcess.start(session, id, line, START_TIMEOUT);
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
