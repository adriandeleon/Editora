package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.RecentFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The recent menu is rendered from a cached answer; the stats that answer comes from run on a worker. */
class RecentFilesCheckTest {

    private final Deque<Runnable> worker = new ArrayDeque<>();
    private final Set<Path> onDisk = new HashSet<>();
    private final AtomicInteger stats = new AtomicInteger();
    private final AtomicInteger changed = new AtomicInteger();
    private final RecentFilesCheck check = new RecentFilesCheck(worker::add, Runnable::run, path -> {
        stats.incrementAndGet();
        return onDisk.contains(path);
    });

    private static RecentFiles recent(Path dir, Path... paths) throws Exception {
        RecentFiles recent = new RecentFiles(Files.createTempDirectory(dir, "cfg"));
        for (int i = paths.length - 1; i >= 0; i--) {
            recent.add(paths[i]);
        }
        return recent;
    }

    @Test
    void renderingAsksTheDiskNothingAndTheWorkerCorrectsIt(@TempDir Path dir) throws Exception {
        Path here = dir.resolve("here.txt");
        Path gone = dir.resolve("gone.txt");
        onDisk.add(here);
        RecentFiles recent = recent(dir, here, gone);

        check.revalidate(recent, changed::incrementAndGet);
        assertEquals(List.of(here, gone), check.showable(recent.getList()), "not checked yet: still offered");
        assertEquals(0, stats.get(), "nothing was asked on the calling (FX) thread");

        worker.poll().run();
        assertEquals(2, stats.get());
        assertEquals(1, changed.get(), "the menu is told once that its answer changed");
        assertEquals(List.of(here), check.showable(recent.getList()));
        assertEquals(2, stats.get(), "and rendering it again still asks nothing");
    }

    @Test
    void anUnchangedAnswerDoesNotRebuildTheMenuAgain(@TempDir Path dir) throws Exception {
        Path here = dir.resolve("here.txt");
        Path gone = dir.resolve("gone.txt");
        onDisk.add(here);
        RecentFiles recent = recent(dir, here, gone);
        check.revalidate(recent, changed::incrementAndGet);
        worker.poll().run();

        check.revalidate(recent, changed::incrementAndGet); // what the rebuild it triggered asks for
        worker.poll().run();
        assertEquals(1, changed.get());

        onDisk.add(gone); // it came back
        check.revalidate(recent, changed::incrementAndGet);
        worker.poll().run();
        assertEquals(2, changed.get());
        assertEquals(List.of(here, gone), check.showable(recent.getList()));
    }

    @Test
    void anAnswerSupersededByANewerRequestIsDropped(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.txt");
        RecentFiles recent = recent(dir, a);
        check.revalidate(recent, changed::incrementAndGet); // a is missing
        Runnable stale = worker.poll();
        onDisk.add(a);
        check.revalidate(recent, changed::incrementAndGet);
        worker.poll().run();
        stale.run();
        assertEquals(0, changed.get());
        assertEquals(List.of(a), check.showable(recent.getList()));
    }
}
