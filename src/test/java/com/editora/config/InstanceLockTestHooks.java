package com.editora.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Test-only stand-in for <em>another Editora process</em> on a config dir: takes the same byte-range locks a
 * real second process would, from a channel of its own. The JVM's file-lock table is per process, so a
 * {@link SharedConfig} in the same JVM is refused the range exactly as it would be by the operating system.
 * Test sources only — no production class references it.
 */
public final class InstanceLockTestHooks {

    private InstanceLockTestHooks() {}

    /** A held lock; closing it is the other process exiting. */
    public static final class Held implements AutoCloseable {
        private final FileChannel channel;

        @SuppressWarnings("unused") // kept reachable for as long as the channel is open
        private final FileLock lock;

        private Held(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    /** Another process that got to {@code configDir} first: it holds the primary lock. */
    public static Held otherProcessIsPrimary(Path configDir) throws IOException {
        return hold(configDir, 0, false);
    }

    /** Another, later process on {@code configDir}: it holds a secondary's shared presence lock. */
    public static Held otherProcessIsSecondary(Path configDir) throws IOException {
        return hold(configDir, 1, true);
    }

    private static Held hold(Path configDir, long position, boolean shared) throws IOException {
        Files.createDirectories(configDir);
        FileChannel channel = FileChannel.open(
                configDir.resolve(InstanceLock.FILE_NAME),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock(position, 1, shared);
        if (lock == null) {
            channel.close();
            throw new IOException("the instance lock is already held by a real process");
        }
        return new Held(channel, lock);
    }
}
