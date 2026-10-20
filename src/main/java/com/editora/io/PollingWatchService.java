package com.editora.io;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * A {@link WatchService} that finds changes by listing the registered folders every few seconds — what Editora
 * falls back to when the operating system will not give it a watcher of its own ({@link WatchServices#open}).
 * On Linux that happens when the user's inotify instances are used up ({@code fs.inotify.max_user_instances},
 * 128 by default, shared by every program the user runs): the JDK's watcher then fails with "User limit of
 * inotify instances reached", and the project tree used to stop following the disk without a word.
 *
 * <p>Named like the JDK's own polling watcher, so code that waits longer for a polled event recognises it. A
 * folder is registered with {@link #register}, not {@code Path.register} (that only accepts the provider's own
 * service); {@link WatchServices#register} picks the right one. One daemon thread per service.
 */
public final class PollingWatchService implements WatchService {

    /** How often the folders are listed. */
    public static final long INTERVAL_MS = 2000;

    /**
     * A folder entry as last seen: what a change to it would alter. A save that renames a new file over the old
     * one within the same millisecond and size is caught by the file key (the inode on Linux and macOS) or, where
     * there is none (Windows), by the creation time, kept to the filesystem's full precision (100 ns on NTFS): a
     * Windows rename that replaces its target keeps the new file's own creation time.
     */
    private record Stamp(long modified, long size, boolean dir, Object fileKey, long created) {}

    private final Map<Path, Key> keys = new ConcurrentHashMap<>();
    private final LinkedBlockingDeque<Key> signalled = new LinkedBlockingDeque<>();
    private final Key closedMarker = new Key(Path.of(""), Set.of());
    private final long intervalMs;
    private volatile boolean closed;

    public PollingWatchService() {
        this(INTERVAL_MS);
    }

    /** With a chosen interval (tests poll faster). */
    PollingWatchService(long intervalMs) {
        this.intervalMs = intervalMs;
        Thread t = new Thread(this::run, "polling-watch");
        t.setDaemon(true);
        t.start();
    }

    /** Starts watching {@code dir} for the given kinds; a folder registered twice keeps one key. */
    public WatchKey register(Path dir, WatchEvent.Kind<?>... kinds) throws IOException {
        if (closed) {
            throw new ClosedWatchServiceException();
        }
        if (!Files.isDirectory(dir)) {
            throw new java.nio.file.NotDirectoryException(dir.toString());
        }
        Key key = keys.computeIfAbsent(dir, d -> new Key(d, Set.of(kinds)));
        key.last = snapshot(dir);
        return key;
    }

    private void run() {
        while (!closed) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            for (Key key : keys.values()) {
                if (closed) {
                    return;
                }
                if (key.valid) {
                    scan(key);
                }
            }
        }
    }

    /** Lists the key's folder and queues one event per entry that appeared, vanished or changed. */
    private void scan(Key key) {
        Map<String, Stamp> now = snapshot(key.dir);
        if (now == null) {
            return; // unreadable this time: compare again next round
        }
        Map<String, Stamp> before = key.last;
        if (before == null) {
            key.last = now; // the folder was unreadable when registered: this is its first listing
            return;
        }
        List<WatchEvent<?>> events = new ArrayList<>();
        now.forEach((name, stamp) -> {
            Stamp old = before.get(name);
            if (old == null) {
                events.add(new Event(StandardWatchEventKinds.ENTRY_CREATE, Path.of(name)));
            } else if (!old.equals(stamp)) {
                events.add(new Event(StandardWatchEventKinds.ENTRY_MODIFY, Path.of(name)));
            }
        });
        before.keySet().stream()
                .filter(name -> !now.containsKey(name))
                .forEach(name -> events.add(new Event(StandardWatchEventKinds.ENTRY_DELETE, Path.of(name))));
        key.last = now;
        events.removeIf(e -> !key.kinds.contains(e.kind()));
        if (!events.isEmpty()) {
            key.signal(events);
        }
    }

    /** The folder's entries by name, or null when it cannot be listed (a vanished folder lists as empty). */
    private static @Nullable Map<String, Stamp> snapshot(Path dir) {
        Map<String, Stamp> out = new HashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    out.put(
                            p.getFileName().toString(),
                            new Stamp(
                                    a.lastModifiedTime().toMillis(),
                                    a.size(),
                                    a.isDirectory(),
                                    a.fileKey(),
                                    a.creationTime().to(TimeUnit.NANOSECONDS)));
                } catch (IOException gone) {
                    // removed between the listing and the read: the next round reports it
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return out;
    }

    @Override
    public WatchKey poll() {
        return checked(signalled.poll());
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
        return checked(signalled.poll(timeout, unit));
    }

    @Override
    public WatchKey take() throws InterruptedException {
        return checked(signalled.take());
    }

    private WatchKey checked(Key key) {
        if (closed || key == closedMarker) {
            throw new ClosedWatchServiceException();
        }
        return key;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        keys.values().forEach(k -> k.valid = false);
        keys.clear();
        signalled.offer(closedMarker); // unblocks a take()
    }

    private record Event(WatchEvent.Kind<Path> kind, Path context) implements WatchEvent<Path> {
        @Override
        public int count() {
            return 1;
        }
    }

    private final class Key implements WatchKey {
        final Path dir;
        final Set<WatchEvent.Kind<?>> kinds;
        volatile @Nullable Map<String, Stamp> last = Map.of();
        volatile boolean valid = true;
        private final List<WatchEvent<?>> pending = new ArrayList<>();
        private boolean queued;

        Key(Path dir, Set<WatchEvent.Kind<?>> kinds) {
            this.dir = dir;
            this.kinds = kinds;
        }

        synchronized void signal(List<WatchEvent<?>> events) {
            pending.addAll(events);
            if (!queued) {
                queued = true;
                signalled.offer(this);
            }
        }

        @Override
        public boolean isValid() {
            return valid && !closed;
        }

        @Override
        public synchronized List<WatchEvent<?>> pollEvents() {
            List<WatchEvent<?>> out = List.copyOf(pending);
            pending.clear();
            return out;
        }

        @Override
        public synchronized boolean reset() {
            if (!isValid()) {
                return false;
            }
            if (pending.isEmpty()) {
                queued = false;
            } else {
                signalled.offer(this);
            }
            return true;
        }

        @Override
        public void cancel() {
            valid = false;
            keys.remove(dir, this);
        }

        @Override
        public Watchable watchable() {
            return dir;
        }
    }
}
