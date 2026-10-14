package com.editora.vfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystem;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-owned SFTP server with a chroot and deterministic faults at safe-replacement stages. */
public final class EmbeddedSftpFixture implements AutoCloseable {

    public enum Fault {
        NONE,
        CREATE,
        WRITE,
        MOVE
    }

    public record StageBlock(CountDownLatch staged, CountDownLatch release) {}

    private final Path serverRoot;
    private final SshServer server;
    private final RemoteFileSystems fileSystems;
    private final RemoteConnection connection;
    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final AtomicReference<StageBlock> stageBlock = new AtomicReference<>();
    private final AtomicBoolean stageHeld = new AtomicBoolean();

    private final AtomicBoolean reportLinkCounts = new AtomicBoolean();

    private volatile Path remoteRoot;

    /**
     * Every process the shell mode started. A real SSH server leaves them running when it stops, and so does
     * this one while it is up; {@link #close} ends them, so no test leaves programs behind for the next.
     */
    private static final List<Process> STARTED = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Every command line the server was asked to run (real-shell mode), in order. */
    private final List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();

    private EmbeddedSftpFixture(Path base, boolean realShell) throws Exception {
        serverRoot = Files.createDirectories(base.resolve("remote-root")).toRealPath();
        SftpSubsystemFactory sftp = new LinkCountingSubsystemFactory();
        sftp.addSftpEventListener(new FaultListener());
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(base.resolve("host-key.ser")));
        server.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        if (realShell) {
            // No chroot, so a path means the same file to SFTP and to the commands the server runs.
            server.setFileSystemFactory(org.apache.sshd.common.file.nativefs.NativeFileSystemFactory.INSTANCE);
            server.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);
            server.setCommandFactory((channel, command) -> {
                commands.add(command);
                return new ShellCommand(command);
            });
        } else {
            server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        }
        server.setSubsystemFactories(List.of(sftp));
        server.start();

        connection = new RemoteConnection(
                "127.0.0.1",
                server.getPort(),
                "tester",
                RemoteConnection.AuthMethod.PASSWORD,
                "",
                "fixture",
                realShell ? serverRoot.toString() : "/");
        fileSystems = new RemoteFileSystems((host, port, keyType, fingerprint) -> true, base.resolve("known_hosts"));
        connect();
    }

    public static EmbeddedSftpFixture start(Path base) throws Exception {
        return new EmbeddedSftpFixture(base, false);
    }

    /**
     * A server that also <b>runs commands</b>, with a real {@code /bin/sh}, the way an SSH server hands a
     * command line to the account's shell. Its files are not chrooted: the remote root is {@code base}'s own
     * directory, so what a command sees is what SFTP sees. POSIX only.
     */
    public static EmbeddedSftpFixture startWithShell(Path base) throws Exception {
        return new EmbeddedSftpFixture(base, true);
    }

    /**
     * Another window's connection engine for this server: its own SSH client, a throwaway {@code known_hosts}
     * and a host-key prompt that says yes — never the user's own file, never a dialog. The caller shuts it down.
     */
    public RemoteFileSystems newEngine(Path base) {
        return new RemoteFileSystems((host, port, keyType, fingerprint) -> true, base.resolve("known_hosts-2"));
    }

    public List<String> commands() {
        return commands;
    }

    public RemoteFileSystems fileSystems() {
        return fileSystems;
    }

    public RemoteConnection connection() {
        return connection;
    }

    public Path serverPath(String relative) {
        return serverRoot.resolve(relative);
    }

    public Path remotePath(String relative) {
        return remoteRoot.resolve(relative);
    }

    public void fault(Fault next) {
        fault.set(next == null ? Fault.NONE : next);
    }

    public StageBlock holdAfterNextStagedWrite() {
        StageBlock block = new StageBlock(new CountDownLatch(1), new CountDownLatch(1));
        stageHeld.set(false);
        stageBlock.set(block);
        return block;
    }

    /**
     * Makes the server speak SFTP protocol {@code version} and reconnects. The embedded server and client
     * agree on version 6 by default; OpenSSH — what a real connection almost always reaches — speaks 3, whose
     * attributes and directory listings differ (numeric owners, {@code ls -l} long names).
     */
    public Path speakProtocolVersion(int version) throws Exception {
        org.apache.sshd.sftp.SftpModuleProperties.SFTP_VERSION.set(server, version);
        disconnect();
        return reconnect();
    }

    /**
     * Makes the server send {@code SSH_FILEXFER_ATTR_LINK_COUNT} in its protocol-6 attributes, as the
     * protocol allows and other servers do. MINA's own server never sends it.
     */
    public void reportLinkCounts() {
        reportLinkCounts.set(true);
    }

    public void disconnect() {
        fileSystems.disconnect(connection.id());
    }

    public Path reconnect() throws Exception {
        connect();
        return remoteRoot;
    }

    private void connect() throws Exception {
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        fileSystems.connect(connection, "password".toCharArray(), connected -> {
            result.set(connected);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "embedded SFTP connection should complete");
        RemoteFileSystems.Result connected = result.get();
        assertNotNull(connected);
        assertTrue(connected.ok(), connected.error());
        remoteRoot = connected.root();
    }

    @Override
    public void close() throws Exception {
        StageBlock block = stageBlock.getAndSet(null);
        if (block != null) {
            block.release().countDown();
        }
        fileSystems.shutdown();
        server.stop(true);
        for (Process p : STARTED) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        STARTED.clear();
    }

    /**
     * One command line run by a real {@code /bin/sh}. On Linux it is started through {@code setsid}, so it
     * leads its own session and process group as it does under OpenSSH. Like OpenSSH, closing the channel
     * signals nobody.
     */
    private static final class ShellCommand implements Command {
        private final String commandLine;
        private java.io.InputStream in;
        private java.io.OutputStream out;
        private java.io.OutputStream err;
        private org.apache.sshd.server.ExitCallback exit;
        private volatile Process process;

        ShellCommand(String commandLine) {
            this.commandLine = commandLine;
        }

        @Override
        public void setInputStream(java.io.InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(java.io.OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(java.io.OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(org.apache.sshd.server.ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, org.apache.sshd.server.Environment env) throws IOException {
            boolean linux = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("linux");
            List<String> argv =
                    linux ? List.of("setsid", "/bin/sh", "-c", commandLine) : List.of("/bin/sh", "-c", commandLine);
            Process started = new ProcessBuilder(argv).start();
            process = started;
            STARTED.add(started);
            Thread stdout = pump(started.getInputStream(), out, false);
            Thread stderr = pump(started.getErrorStream(), err, false);
            pump(in, started.getOutputStream(), true);
            Thread.ofVirtual().start(() -> {
                try {
                    int code = started.waitFor();
                    stdout.join(2000);
                    stderr.join(2000);
                    exit.onExit(code);
                } catch (InterruptedException e) {
                    exit.onExit(-1);
                }
            });
        }

        private static Thread pump(java.io.InputStream from, java.io.OutputStream to, boolean closeTarget) {
            return Thread.ofVirtual().start(() -> {
                byte[] buffer = new byte[8192];
                try {
                    int n;
                    while ((n = from.read(buffer)) != -1) {
                        to.write(buffer, 0, n);
                        to.flush();
                    }
                } catch (IOException gone) {
                    // one side closed: stop pumping
                } finally {
                    if (closeTarget) {
                        try {
                            to.close();
                        } catch (IOException ignored) {
                            // already closed
                        }
                    }
                }
            });
        }

        @Override
        public void destroy(ChannelSession channel) {
            // Deliberately nothing: an SSH server does not kill the command when its channel closes.
        }
    }

    private static boolean staged(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && name.toString().endsWith(".editora-tmp");
    }

    /** The stock subsystem, plus the protocol-6 link-count field when {@link #reportLinkCounts()} asked. */
    private final class LinkCountingSubsystemFactory extends SftpSubsystemFactory {

        @Override
        public Command createSubsystem(ChannelSession channel) throws IOException {
            SftpSubsystem subsystem = new SftpSubsystem(channel, this) {
                @Override
                protected void writeAttrs(Buffer buffer, Map<String, ?> attributes) {
                    int start = buffer.wpos();
                    super.writeAttrs(buffer, attributes);
                    int flags = (int) BufferUtils.getUInt(buffer.array(), start, Integer.BYTES);
                    if (reportLinkCounts.get()
                            && getVersion() >= SftpConstants.SFTP_V6
                            && (flags & SftpConstants.SSH_FILEXFER_ATTR_EXTENDED) == 0
                            && attributes.get("nlink") instanceof Number links) {
                        // link-count follows every field the stock writer emits (it has no extended ones here).
                        BufferUtils.putUInt(
                                (flags | SftpConstants.SSH_FILEXFER_ATTR_LINK_COUNT) & 0xFFFFFFFFL,
                                buffer.array(),
                                start,
                                Integer.BYTES);
                        buffer.putInt(links.intValue());
                    }
                }
            };
            getRegisteredListeners().forEach(subsystem::addSftpEventListener);
            return subsystem;
        }
    }

    private final class FaultListener implements SftpEventListener {

        @Override
        public void opening(ServerSession session, String remoteHandle, org.apache.sshd.sftp.server.Handle localHandle)
                throws IOException {
            if (fault.get() == Fault.CREATE
                    && localHandle instanceof FileHandle fileHandle
                    && staged(fileHandle.getFile())) {
                throw new IOException("injected remote create failure");
            }
        }

        @Override
        public void creating(ServerSession session, Path path, Map<String, ?> attrs) throws IOException {
            if (fault.get() == Fault.CREATE && staged(path)) {
                throw new IOException("injected remote create failure");
            }
        }

        @Override
        public void writing(
                ServerSession session,
                String remoteHandle,
                FileHandle localHandle,
                long offset,
                byte[] data,
                int off,
                int len)
                throws IOException {
            if (fault.get() == Fault.WRITE && staged(localHandle.getFile())) {
                throw new IOException("injected remote write failure");
            }
        }

        @Override
        public void written(
                ServerSession session,
                String remoteHandle,
                FileHandle localHandle,
                long offset,
                byte[] data,
                int off,
                int len,
                Throwable thrown)
                throws IOException {
            StageBlock block = stageBlock.get();
            if (thrown != null
                    || block == null
                    || !staged(localHandle.getFile())
                    || !stageHeld.compareAndSet(false, true)) {
                return;
            }
            block.staged().countDown();
            try {
                if (!block.release().await(10, TimeUnit.SECONDS)) {
                    throw new IOException("timed out holding staged remote write");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("staged remote write interrupted", e);
            } finally {
                stageBlock.compareAndSet(block, null);
            }
        }

        @Override
        public void moving(
                ServerSession session, Path source, Path target, java.util.Collection<java.nio.file.CopyOption> options)
                throws IOException {
            if (fault.get() == Fault.MOVE && staged(source)) {
                throw new IOException("injected remote move failure");
            }
        }
    }
}
