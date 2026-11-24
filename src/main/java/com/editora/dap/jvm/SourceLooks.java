package com.editora.dap.jvm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Whether a candidate source file is on the disk, for {@link JvmDebugAdapter}'s frames. A stop resolves each frame's
 * class against every source root ({@link JvmSourceMap#resolve}); most frames are runtime or library code that is
 * under no root, so without this every step stats {@code roots × frames} paths on the adapter's only JDI thread. A
 * look stands for {@link #FRESH_NANOS}, like {@code FrameFiles}: a step after a step looks again at nothing, and a
 * file created or removed meanwhile is seen within seconds (perf review 2026-11-23, D5).
 */
final class SourceLooks {

    /** How long a look stands. */
    static final long FRESH_NANOS = 5_000_000_000L;

    /** At most this many looks are kept; the whole map is dropped past it (a session rarely sees so many paths). */
    static final int MAX = 4096;

    private record Look(boolean is, long at) {}

    private final Map<Path, Look> looks = new ConcurrentHashMap<>();
    private final Predicate<Path> exists;
    private final LongSupplier clock;

    SourceLooks() {
        this(Files::isRegularFile, System::nanoTime);
    }

    SourceLooks(Predicate<Path> exists, LongSupplier clock) {
        this.exists = exists;
        this.clock = clock;
    }

    /** Whether {@code file} is a regular file, from a look in the last {@link #FRESH_NANOS} when there is one. */
    boolean exists(Path file) {
        long now = clock.getAsLong();
        Look known = looks.get(file);
        if (known != null && now - known.at() <= FRESH_NANOS) {
            return known.is();
        }
        boolean is = exists.test(file);
        if (looks.size() >= MAX) {
            looks.clear();
        }
        looks.put(file, new Look(is, now));
        return is;
    }

    /** Forgets every look (the source roots changed). */
    void clear() {
        looks.clear();
    }
}
