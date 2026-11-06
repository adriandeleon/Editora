package com.editora.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

/**
 * Where the Project Map's loads get their directory listings (moved out of {@code ProjectMapModel.Listings} in the
 * Map's refactor stage 2, review 3 D; the behaviour is that class's). Three things keep a load from costing a full re-list
 * of every open folder per click:
 *
 * <ul>
 *   <li>A per-directory cache, used only for the folders the Project watcher follows ({@link
 *       #setWatched}): a watcher event for a folder ({@link #invalidate}) or an in-app change ({@link
 *       #invalidateAll}) drops its entry, and a folder that stops being watched is dropped too. A focus
 *       regain only asks each cached folder again whether its modification time moved ({@link
 *       #revalidateAll}), which is one attribute read instead of a listing. A remote root has no local
 *       watcher, so it keeps listing afresh.
 *   <li>A timeout per listing: the listing runs on a worker, and a folder that does not answer in time
 *       is reported as not responding instead of holding up the loader (one hung listing used to block
 *       every later load of the window). When it does answer, {@link #setOnRecovered} hears so.
 *   <li>One listing per folder: a folder whose listing is still blocked is reported as not responding at
 *       once, through any number of invalidations, and never gets a second listing (and a second blocked
 *       thread) of its own. A load that comes after an invalidation joins a listing begun before it and
 *       lists again once that one has finished, so it never takes the earlier answer for the new one.
 * </ul>
 *
 * Thread-safe: the loader lists, the FX thread invalidates and sets the watched set. Wall-clock time (when a
 * listing was taken, how old a remote one is) comes from the clock the service is given, so tests can move it.
 */
final class ListingService implements AutoCloseable {

    /**
     * One child as listed. Its kind (folder or not) is known when it is listed and orders it; the rest of its
     * attributes are read once, either then or (in a large folder of files, see {@link #listDirectory}) the
     * first time its row is loaded, and reused after that.
     */
    static final class Item {
        /** What one attribute read found. */
        private record Attributes(boolean directory, long size, long modifiedMillis, boolean symbolicLink) {
            static final Attributes GONE = new Attributes(false, -1, -1, false);
        }

        private final Path path;
        private final boolean sortsAsDirectory;
        private volatile @Nullable Attributes attributes;

        Item(Path path, boolean directory, long size, long modifiedMillis, boolean symbolicLink) {
            this.path = path;
            this.sortsAsDirectory = directory;
            this.attributes = new Attributes(directory, size, modifiedMillis, symbolicLink);
        }

        private Item(Path path) {
            this.path = path;
            this.sortsAsDirectory = false;
        }

        /** A child of a folder that holds no subfolders, whose attributes are read when its row is loaded. */
        static Item unread(Path path) {
            return new Item(path);
        }

        Path path() {
            return path;
        }

        String name() {
            Path fileName = path.getFileName();
            return fileName == null ? path.toString() : fileName.toString();
        }

        /** Whether it sorts with the folders: what its listing knew, so the order never changes under it. */
        boolean sortsAsDirectory() {
            return sortsAsDirectory;
        }

        boolean directory() {
            return attributes().directory();
        }

        long size() {
            return attributes().size();
        }

        long modifiedMillis() {
            return attributes().modifiedMillis();
        }

        boolean symbolicLink() {
            return attributes().symbolicLink();
        }

        /** Whether the attributes were read (listed with them, or read since); not, for a name-only child. */
        boolean attributesRead() {
            return attributes != null;
        }

        private Attributes attributes() {
            Attributes known = attributes;
            if (known == null) {
                Item read = stat(path);
                Attributes fresh = read == null ? null : read.attributes;
                known = fresh == null ? Attributes.GONE : fresh;
                attributes = known; // read twice at worst, by two loads at once: the same answer
            }
            return known;
        }
    }

    /** The order of one folder's listed children (see {@link ProjectPathOrder#forFolder}). */
    static java.util.Comparator<Item> itemOrder(List<Item> items) {
        return ProjectPathOrder.forFolder(items, Item::sortsAsDirectory, Item::name);
    }

    /** How long one directory listing may take before its folder is drawn as "Not responding". */
    static final long LISTING_TIMEOUT_MILLIS = 10_000;

    /** At most this many folders are listed at once; one that hangs holds one of them until it answers. */
    static final int MAX_LISTING_THREADS = 4;

    /**
     * One directory's listing as {@link #list} answers it: the sorted children, or {@code null} when
     * the folder cannot be read; {@code timedOut} when the listing did not finish in time; {@code cancelled}
     * when the load was superseded while it waited.
     */
    record Listed(@Nullable List<Item> items, boolean timedOut, boolean cancelled, boolean cached) {
        static final Listed CANCELLED = new Listed(null, false, true, false);
        static final Listed TIMED_OUT = new Listed(null, true, false, false);

        Listed(@Nullable List<Item> items, boolean timedOut, boolean cancelled) {
            this(items, timedOut, cancelled, false);
        }
    }

    /** No cache, no worker: lists on the calling thread (pure loads and tests). */
    static final ListingService DIRECT = new ListingService(0, null);

    /**
     * A cached listing whose folder changed in the same clock tick as it was read could keep its old
     * modification time; one read this soon after the folder last changed is listed again on a regain.
     */
    private static final long SETTLED_MILLIS = 2_000;

    private record Key(Path directory, boolean showHidden) {}

    /**
     * A watched folder's listing, the folder's modification time read just before it was listed, when it
     * was listed, and the regain ({@link #revalidateAll}) it was last checked against.
     */
    private record Cached(List<Item> items, long directoryModified, long listedAtMillis, long checked) {
        /** Whether the folder's modification time alone can tell that nothing came or went since. */
        boolean settled() {
            return directoryModified >= 0 && listedAtMillis - directoryModified >= SETTLED_MILLIS;
        }
    }

    /** What a listing thread found: fresh children, or the cached ones again when nothing moved. */
    private record Outcome(
            @Nullable List<Item> items, long directoryModified, long listedAtMillis, boolean confirmed) {}

    /**
     * A listing on a worker, when it began, and the folder's epoch and version then. {@code hungSince} is
     * the folder's "hung since" record: set once some load gave up waiting for it, and kept (through any
     * invalidation) until the listing finishes, since nothing else may list the folder before that.
     */
    private static final class InFlight {
        final java.util.concurrent.CompletableFuture<Outcome> future = new java.util.concurrent.CompletableFuture<>();
        final long startedNanos = System.nanoTime();
        final long epoch;
        final long version;
        final @Nullable Cached known;
        volatile long hungSinceNanos;

        InFlight(long epoch, long version, @Nullable Cached known) {
            this.epoch = epoch;
            this.version = version;
            this.known = known;
        }

        boolean hung() {
            return hungSinceNanos != 0;
        }
    }

    private final long timeoutMillis;
    private final java.util.function.@Nullable Consumer<Path> beforeListForTest;
    private final Map<Key, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * When a remote folder's cached listing was made. No watcher on this machine follows a remote folder, so
     * its listing is reused for {@link #REMOTE_TTL_MILLIS} only, and dropped with every invalidation (an
     * in-app change, a focus regain): one SFTP round trip per open folder per click was the alternative
     * (Project Map review 3 A4).
     */
    private final Map<Key, Long> remoteListedAt = new java.util.concurrent.ConcurrentHashMap<>();
    /** How long a remote folder's listing is reused. */
    static final long REMOTE_TTL_MILLIS = 30_000;
    /** Wall-clock milliseconds: when a listing was taken, and how old a remote one is. */
    private final LongSupplier clockMillis;

    private final Map<Key, InFlight> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Path, Long> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong revalidations = new java.util.concurrent.atomic.AtomicLong();
    private volatile Set<Path> watched = Set.of();
    private volatile Runnable onRecovered = () -> {};
    private java.util.concurrent.@Nullable ThreadPoolExecutor workers;
    private volatile boolean closed;
    /** How many directories were actually listed (not answered from the cache); for tests. */
    final java.util.concurrent.atomic.AtomicInteger listedForTest = new java.util.concurrent.atomic.AtomicInteger();
    /** How many loads waited a listing's whole timeout out (not those told at once it hangs); for tests. */
    final java.util.concurrent.atomic.AtomicInteger waitedOutForTest = new java.util.concurrent.atomic.AtomicInteger();
    /** How many cached listings a regain kept after one attribute read; for tests. */
    final java.util.concurrent.atomic.AtomicInteger confirmedForTest = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * @param timeoutMillis how long one listing may take; {@code <= 0} lists on the calling thread
     * @param beforeListForTest runs on the listing thread before each listing (a test stalls it there)
     */
    ListingService(long timeoutMillis, java.util.function.@Nullable Consumer<Path> beforeListForTest) {
        this(timeoutMillis, beforeListForTest, System::currentTimeMillis);
    }

    /** As above, reading wall-clock time from {@code clockMillis} (a test's clock). */
    ListingService(
            long timeoutMillis,
            java.util.function.@Nullable Consumer<Path> beforeListForTest,
            LongSupplier clockMillis) {
        this.timeoutMillis = timeoutMillis;
        this.beforeListForTest = beforeListForTest;
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
    }

    /** The folders the Project watcher follows now; only these are cached, the rest are dropped. */
    void setWatched(Set<Path> directories) {
        Set<Path> next = new HashSet<>();
        if (directories != null) {
            for (Path directory : directories) {
                Path normalized = ProjectMapModel.normalize(directory);
                if (normalized != null) {
                    next.add(normalized);
                }
            }
        }
        watched = Set.copyOf(next);
        cache.keySet().removeIf(key -> !next.contains(key.directory()) && !remoteCached(key.directory()));
    }

    /** Whether {@code directory} is on a remote file system, whose listings are kept for a while. */
    private static boolean remoteCached(Path directory) {
        return !com.editora.vfs.Vfs.isLocal(directory);
    }

    /** Whether a remote folder's cached listing is young enough to reuse. */
    private boolean remoteFresh(Key key) {
        Long at = remoteListedAt.get(key);
        return at != null && clockMillis.getAsLong() - at < REMOTE_TTL_MILLIS;
    }

    /** Runs (on a worker thread) when a listing that had been reported as not responding finishes. */
    void setOnRecovered(Runnable action) {
        onRecovered = action == null ? () -> {} : action;
    }

    /** Something in these folders came or went, or changed: their next load lists them again. */
    void invalidate(java.util.Collection<Path> directories) {
        for (Path directory : directories) {
            Path normalized = ProjectMapModel.normalize(directory);
            if (normalized == null) {
                continue;
            }
            versions.merge(normalized, 1L, Long::sum);
            cache.remove(new Key(normalized, true));
            cache.remove(new Key(normalized, false));
        }
    }

    /** Anything may have changed (an in-app file operation, a new root). */
    void invalidateAll() {
        epoch.incrementAndGet();
        cache.clear();
        remoteListedAt.clear();
    }

    /**
     * Another program may have changed the watched folders while Editora was in the background (a focus
     * regain): each cached folder's next load reads its modification time, off the FX thread, and lists it
     * again only when that moved. The watcher reports what changes while Editora runs; this covers an
     * event it may have missed.
     */
    void revalidateAll() {
        revalidations.incrementAndGet();
    }

    boolean cached(Path directory, boolean showHidden) {
        Path normalized = ProjectMapModel.normalize(directory);
        return normalized != null && cache.containsKey(new Key(normalized, showHidden));
    }

    /** How many children of a cached listing have not had their attributes read yet; for tests. */
    int unreadForTest(Path directory, boolean showHidden) {
        Path normalized = ProjectMapModel.normalize(directory);
        Cached known = normalized == null ? null : cache.get(new Key(normalized, showHidden));
        if (known == null) {
            return -1;
        }
        int unread = 0;
        for (Item item : known.items()) {
            if (item.attributes == null) {
                unread++;
            }
        }
        return unread;
    }

    /** How many listings are running now (a hung one runs until its folder answers); for tests. */
    int runningForTest() {
        java.util.concurrent.ThreadPoolExecutor pool;
        synchronized (this) {
            pool = workers;
        }
        return pool == null ? 0 : pool.getActiveCount();
    }

    Listed list(Path directory, boolean showHidden, BooleanSupplier cancelled) {
        Key key = new Key(directory, showHidden);
        boolean remote = remoteCached(directory) && !watched.contains(directory);
        boolean cacheable = !closed && (watched.contains(directory) || remote);
        long revalidationBefore = revalidations.get();
        Cached known = cacheable ? cache.get(key) : null;
        // A remote folder has no watcher here: its listing is reused for a while only, and a regain drops it.
        if (known != null && known.checked() == revalidationBefore && (!remote || remoteFresh(key))) {
            return new Listed(known.items(), false, false, true);
        }
        long epochBefore = epoch.get();
        long versionBefore = versions.getOrDefault(directory, 0L);
        Outcome outcome;
        if (timeoutMillis <= 0) {
            outcome = listOrConfirm(key, known);
        } else {
            Awaited awaited = await(key, known, cancelled);
            if (awaited.outcome() == null) {
                return awaited.listed();
            }
            outcome = awaited.outcome();
        }
        // Cached only when nothing invalidated the folder while it was being listed, and it was watched
        // from before the listing began: an event in between is then never lost.
        List<Item> items = outcome.items();
        if (cacheable
                && items != null
                && epoch.get() == epochBefore
                && versions.getOrDefault(directory, 0L) == versionBefore
                && (watched.contains(directory) || remote)) {
            cache.put(
                    key, new Cached(items, outcome.directoryModified(), outcome.listedAtMillis(), revalidationBefore));
            if (remote) {
                remoteListedAt.put(key, clockMillis.getAsLong());
            }
        }
        return new Listed(items, false, false, outcome.confirmed());
    }

    /** A wait's answer: the listing's outcome, or (with no outcome) why there is none. */
    private record Awaited(@Nullable Outcome outcome, Listed listed) {
        static final Awaited CANCELLED = new Awaited(null, Listed.CANCELLED);
        static final Awaited TIMED_OUT = new Awaited(null, Listed.TIMED_OUT);
    }

    private Awaited await(Key key, @Nullable Cached known, BooleanSupplier cancelled) {
        while (true) {
            long epochNow = epoch.get();
            long versionNow = versions.getOrDefault(key.directory(), 0L);
            InFlight[] started = new InFlight[1];
            // At most one listing per folder: a running one is joined, whenever it began.
            InFlight running =
                    inFlight.computeIfAbsent(key, unused -> started[0] = new InFlight(epochNow, versionNow, known));
            if (started[0] != null) {
                try {
                    start(key, started[0]); // registered first, so finishing always removes it again
                } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                    inFlight.remove(key, started[0]);
                    return Awaited.CANCELLED;
                }
            }
            if (running.hung()) {
                return Awaited.TIMED_OUT; // still blocked: said at once, and no second listing is started
            }
            long deadline = running.startedNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            Outcome outcome = null;
            while (outcome == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (running.hungSinceNanos == 0) {
                        running.hungSinceNanos = Math.max(1, System.nanoTime());
                    }
                    waitedOutForTest.incrementAndGet();
                    return Awaited.TIMED_OUT;
                }
                try {
                    outcome = running.future.get(
                            Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)),
                            java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException stillListing) {
                    if (cancelled.getAsBoolean()) {
                        return Awaited.CANCELLED; // the listing goes on; the next load picks its answer up
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Awaited.CANCELLED;
                } catch (java.util.concurrent.ExecutionException | RuntimeException failed) {
                    outcome = new Outcome(null, -1, clockMillis.getAsLong(), false);
                }
            }
            if (running.epoch == epochNow && running.version == versionNow) {
                return new Awaited(outcome, Listed.CANCELLED); // the second half is unused with an outcome
            }
            // It began before the folder was invalidated and may have missed the change: once it has
            // finished (and left the in-flight map), the folder is listed again.
            if (cancelled.getAsBoolean()) {
                return Awaited.CANCELLED;
            }
        }
    }

    private void start(Key key, InFlight running) {
        java.util.concurrent.CompletableFuture<Outcome> future = running.future;
        workers().execute(() -> {
            Outcome outcome = new Outcome(null, -1, clockMillis.getAsLong(), false);
            try {
                outcome = listOrConfirm(key, running.known);
            } catch (RuntimeException failed) {
                ProjectMapLog.unexpected("listing a folder", failed);
            } finally {
                // Dropped before the waiters wake, so a load right after this one never joins a finished listing.
                inFlight.remove(key, running);
                future.complete(outcome);
                if (running.hung() && !closed) {
                    onRecovered.run();
                }
            }
        });
    }

    private synchronized java.util.concurrent.ExecutorService workers() {
        if (closed) {
            throw new java.util.concurrent.RejectedExecutionException("closed");
        }
        if (workers == null) {
            java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                    MAX_LISTING_THREADS,
                    MAX_LISTING_THREADS,
                    30,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "project-map-listing");
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            workers = pool;
        }
        return workers;
    }

    /**
     * Lists the folder, or, when a cached listing is being checked after a regain and the folder's
     * modification time has not moved since it was taken, answers that listing again without listing.
     */
    private Outcome listOrConfirm(Key key, @Nullable Cached known) {
        Path directory = key.directory();
        long modified = directoryModified(directory);
        if (known != null && known.settled() && modified == known.directoryModified()) {
            confirmedForTest.incrementAndGet();
            return new Outcome(known.items(), known.directoryModified(), known.listedAtMillis(), true);
        }
        long listedAt = clockMillis.getAsLong();
        return new Outcome(listSorted(directory, key.showHidden()), modified, listedAt, false);
    }

    private static long directoryModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS)
                    .toMillis();
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
    }

    private @Nullable List<Item> listSorted(Path directory, boolean showHidden) {
        if (beforeListForTest != null) {
            beforeListForTest.accept(directory);
        }
        listedForTest.incrementAndGet();
        List<Item> items = listDirectory(directory, showHidden);
        if (items == null) {
            return null;
        }
        items.sort(itemOrder(items));
        return List.copyOf(items);
    }

    @Override
    public synchronized void close() {
        closed = true;
        cache.clear();
        if (workers != null) {
            workers.shutdownNow(); // a hung listing's thread is a daemon; it is not waited for
        }
    }

    /**
     * One attribute read per path; a symbolic link costs a second one to learn whether it leads to a folder.
     * {@code null} when the path cannot be read at all.
     */
    static @Nullable Item stat(Path path) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            boolean link = attributes.isSymbolicLink();
            return new Item(
                    path,
                    link ? Files.isDirectory(path) : attributes.isDirectory(),
                    attributes.isRegularFile() ? attributes.size() : -1,
                    attributes.lastModifiedTime().toMillis(),
                    link);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The children of {@code directory}, or {@code null} when it cannot be listed. Each child's attributes
     * are read once (two reads for a symbolic link) and serve both the ordering and the row. In a folder
     * longer than {@link ProjectMapModel#DIRECTORY_CHUNK} that its link count says holds no subfolder ({@link
     * #holdsNoSubfolders}), the children are all files, so their order needs only their names: each one's
     * attributes are read when its row is first loaded, and the rows past the limit (most of a 1,500-file
     * corpus folder) are never read at all. (A symbolic link to a folder there sorts among the files.)
     */
    private static @Nullable List<Item> listDirectory(Path directory, boolean showHidden) {
        List<Path> children = new ArrayList<>();
        boolean noSubfolders;
        try {
            noSubfolders = holdsNoSubfolders(directory); // read before listing: a folder made meanwhile shows
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    Path fileName = child.getFileName();
                    if (fileName != null && ProjectWatchChanges.isInternalName(fileName.toString())) {
                        continue; // a save's temporary sibling: gone in a moment, and its removal raises no event
                    }
                    if (fileName != null
                            && (showHidden
                                    ? fileName.toString().equals(".git")
                                    : fileName.toString().startsWith("."))) {
                        continue; // the repository's own .git is never project content (as in ProjectWalk)
                    }
                    children.add(child);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        boolean lazy = noSubfolders && children.size() > ProjectMapModel.DIRECTORY_CHUNK;
        List<Item> result = new ArrayList<>(children.size());
        for (Path child : children) {
            if (lazy) {
                result.add(Item.unread(child));
                continue;
            }
            Item item = stat(child);
            result.add(item == null ? new Item(child, false, -1, -1, false) : item); // vanished mid-listing
        }
        return result;
    }

    /** The file systems whose folder link count is 2 plus the number of subfolders. */
    private static final Set<String> LINK_COUNTING_STORES = Set.of("ext2", "ext3", "ext4", "xfs", "tmpfs", "f2fs");

    /** By device: whether its file system counts subfolders in the link count (asked once per device). */
    private static final Map<Object, Boolean> LINK_COUNTING_DEVICES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether {@code directory} certainly holds no subfolder: on a local Linux file system that counts a
     * folder's subfolders in its link count, a link count of 2 (itself and its {@code .}) says so without
     * reading any child. Anywhere else (another system, btrfs, a network or remote mount) the answer is no,
     * and every child is read as before.
     */
    private static boolean holdsNoSubfolders(Path directory) throws IOException {
        if (directory.getFileSystem() != java.nio.file.FileSystems.getDefault() || !LINUX) {
            return false;
        }
        Map<String, Object> unix;
        try {
            unix = Files.readAttributes(directory, "unix:nlink,dev", LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            return false;
        }
        if (!(unix.get("nlink") instanceof Integer links) || links != 2) {
            return false;
        }
        Object device = unix.get("dev");
        if (device == null) {
            return false;
        }
        Boolean counts = LINK_COUNTING_DEVICES.get(device);
        if (counts == null) {
            counts = LINK_COUNTING_STORES.contains(Files.getFileStore(directory).type());
            LINK_COUNTING_DEVICES.put(device, counts);
        }
        return counts;
    }

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
}
