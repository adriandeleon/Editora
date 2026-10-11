package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch saves the projects index on the config writer instead of {@code fsync}ing on the FX thread; a
 * direct {@link ProjectManager#save()} made before the queued write runs is newer and must win.
 */
class ProjectManagerQueuedSaveTest {

    /** Runs nothing until {@link #runAll}, so a test decides when the writer's thread "gets to it". */
    private static final class HeldExecutor extends AbstractExecutorService {
        private final List<Runnable> tasks = new ArrayList<>();
        private boolean down;

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        synchronized void runAll() {
            List<Runnable> batch = new ArrayList<>(tasks);
            tasks.clear();
            batch.forEach(Runnable::run);
        }

        @Override
        public void shutdown() {
            down = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            down = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return down;
        }

        @Override
        public boolean isTerminated() {
            return down;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    @Test
    void queuedSaveLandsWhenTheWriterRuns(@TempDir Path dir) throws Exception {
        ProjectManager pm = new ProjectManager(dir);
        pm.createOrGet("Alpha", dir.resolve("alpha"));
        HeldExecutor io = new HeldExecutor();
        ConfigWriter writer = new ConfigWriter(io);

        pm.saveQueued(writer);
        assertFalse(Files.exists(dir.resolve("projects.json")), "nothing is written on the calling thread");
        io.runAll();
        assertTrue(Files.exists(dir.resolve("projects.json")));
        assertEquals(1, new ProjectManager(dir).list().size());
    }

    @Test
    void aDirectSaveMadeMeanwhileIsNotOverwrittenByTheOlderQueuedBytes(@TempDir Path dir) throws Exception {
        ProjectManager pm = new ProjectManager(dir);
        pm.createOrGet("Alpha", dir.resolve("alpha"));
        HeldExecutor io = new HeldExecutor();
        ConfigWriter writer = new ConfigWriter(io);

        pm.saveQueued(writer); // one project, serialized now, written later
        pm.createOrGet("Beta", dir.resolve("beta"));
        assertTrue(pm.save()); // two projects, written now
        io.runAll(); // the older snapshot reaches the writer only now

        List<Project> onDisk = new ProjectManager(dir).list();
        assertEquals(2, onDisk.size(), "the queued one-project snapshot must not land over the two-project save");
    }

    @Test
    void theNewestQueuedSaveWins(@TempDir Path dir) throws Exception {
        ProjectManager pm = new ProjectManager(dir);
        HeldExecutor io = new HeldExecutor();
        ConfigWriter writer = new ConfigWriter(io);
        pm.createOrGet("Alpha", dir.resolve("alpha"));
        pm.saveQueued(writer);
        pm.createOrGet("Beta", dir.resolve("beta"));
        pm.saveQueued(writer);
        io.runAll();
        assertEquals(2, new ProjectManager(dir).list().size());
    }
}
