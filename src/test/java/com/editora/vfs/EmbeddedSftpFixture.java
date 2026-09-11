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
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
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

    private volatile Path remoteRoot;

    private EmbeddedSftpFixture(Path base) throws Exception {
        serverRoot = Files.createDirectories(base.resolve("remote-root"));
        SftpSubsystemFactory sftp = new SftpSubsystemFactory();
        sftp.addSftpEventListener(new FaultListener());
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(base.resolve("host-key.ser")));
        server.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        server.setFileSystemFactory(new VirtualFileSystemFactory(serverRoot));
        server.setSubsystemFactories(List.of(sftp));
        server.start();

        connection = new RemoteConnection(
                "127.0.0.1", server.getPort(), "tester", RemoteConnection.AuthMethod.PASSWORD, "", "fixture", "/");
        fileSystems = new RemoteFileSystems((host, port, keyType, fingerprint) -> true, base.resolve("known_hosts"));
        connect();
    }

    public static EmbeddedSftpFixture start(Path base) throws Exception {
        return new EmbeddedSftpFixture(base);
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
    }

    private static boolean staged(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && name.toString().endsWith(".editora-tmp");
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
