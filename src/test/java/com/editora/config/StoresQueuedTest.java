package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stores written on the config writer's thread (PERF-01, Pauta 6.0.1): a change made where the FX thread makes
 * it is on disk once the writes are waited for, a later save of a file wins over one still queued, the quit's
 * save writes what was changed just before it, a failure is reported once and written later, another process's
 * entries are kept, and what comes back to the FX thread is applied only while it is still the newest.
 */
class StoresQueuedTest {

    /** A writer held by a latch in a task queued first: what a caller does while its write has not run yet. */
    private static CountDownLatch hold(SharedConfig shared) {
        CountDownLatch gate = new CountDownLatch(1);
        shared.writer().run(() -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return gate;
    }

    @Test
    void aSaveMadeWhileTheWriterIsBusyIsCopiedNotSerializedLiveAndTheNewerOneWins(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        CountDownLatch gate = hold(config.shared());
        config.getBookmarks()
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(new Bookmark(1, "first", "l"));
        Thread first = Thread.ofVirtual().start(config::saveBookmarks); // queued behind the held task
        Thread.sleep(50);
        // The live store goes on changing while that save waits: its copy was taken when it was made.
        config.getBookmarks().get("/x/a.txt").add(new Bookmark(2, "second", "l"));
        config.getBookmarks().get("/x/a.txt").remove(0);
        Thread saver = Thread.ofVirtual().start(config::saveBookmarks); // the newer save of the same file
        Thread.sleep(50);
        gate.countDown();
        saver.join();
        first.join();
        assertTrue(config.shared().awaitWrites());
        String written = Files.readString(config.getBookmarksFile());
        assertTrue(written.contains("second"), written);
        assertFalse(written.contains("first"), "an older state is never written over a newer one: " + written);
        config.shared().shutdown();
    }

    @Test
    void theQuitWritesWhatChangedJustBeforeIt(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        CountDownLatch gate = hold(config.shared());
        config.getSettings().setTabSize(11);
        config.saveAsync(); // queued behind the held task, as the FX thread queues it
        config.getNotes()
                .computeIfAbsent("/x/n.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "LAST-NOTE", List.of()));
        Thread.ofVirtual().start(config::saveNotes);
        Thread.sleep(50);
        gate.countDown();
        assertTrue(config.shared().shutdown(), "the quit's wait makes them durable");
        assertTrue(Files.readString(config.getSettingsFile()).contains("\"tabSize\" : 11"));
        assertTrue(Files.readString(config.getNotesFile()).contains("LAST-NOTE"));
    }

    @Test
    void aFailedWriteIsReportedOnceAndWrittenByTheNextFlush(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        List<String> reported = new CopyOnWriteArrayList<>();
        config.shared()
                .setOnWriteError((file, e) -> reported.add(file.getFileName().toString()));
        Path macros = config.shared().getMacrosFile();
        Files.deleteIfExists(macros);
        Files.createDirectories(macros); // nothing can be written in its place
        config.getMacroStore().put(new com.editora.macro.Macro("m1", "Kept", List.of()));
        config.saveMacros();
        assertEquals(List.of("macros.json"), reported);
        assertTrue(config.shared().hasUnsavedStores());
        Files.delete(macros);
        assertTrue(config.shared().flushWrites(), "the durable flush writes it again");
        assertTrue(Files.readString(macros).contains("Kept"));
        assertFalse(config.shared().hasUnsavedStores());
        config.shared().shutdown();
    }

    @Test
    void anotherProcessesBookmarksAreKeptByAQueuedWrite(@TempDir Path dir) throws Exception {
        ConfigManager one = new ConfigManager(dir);
        one.load();
        ConfigManager two = new ConfigManager(dir);
        two.load();
        one.getBookmarks().computeIfAbsent("/x/one.txt", k -> new ArrayList<>()).add(new Bookmark(1, "ONE", "l"));
        one.saveBookmarks();
        two.getBookmarks().computeIfAbsent("/x/two.txt", k -> new ArrayList<>()).add(new Bookmark(2, "TWO", "l"));
        two.saveBookmarks();
        String written = Files.readString(one.getBookmarksFile());
        assertTrue(written.contains("ONE") && written.contains("TWO"), written);
        one.shared().shutdown();
        two.shared().shutdown();
    }

    @Test
    void aDeletedProjectsSessionIsNotBroughtBackByAWriteStillQueued(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        Project project = config.projects().createOrGet("gone", Files.createDirectories(dir.resolve("gone")));
        ConfigManager window =
                new ConfigManager(config.shared(), config.projects().stateFile(project));
        CountDownLatch gate = hold(config.shared());
        window.saveAsync(); // the project window's session, queued
        config.projects().delete(project.id());
        gate.countDown();
        assertTrue(config.shared().awaitWrites());
        assertFalse(Files.exists(config.projects().stateFile(project)));
        config.shared().shutdown();
    }

    @Test
    void wordsAnotherProcessAddedComeBackToTheSharedSetUnlessItChangedSince(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        List<Runnable> toFx = new CopyOnWriteArrayList<>();
        config.shared().setFxThreadForTest(toFx::add); // held: the test says when each result lands
        int[] told = {0};
        config.shared().setOnUserDictionaryChanged(() -> told[0]++);
        Files.writeString(config.getUserDictionaryFile(), "fromelsewhere\n"); // another process's word

        config.addUserWord("mine"); // written (the word appended); the merge comes back to the FX thread
        assertEquals(1, toFx.size());
        config.addUserWord("newer"); // a change made before that result landed: a write of its own
        assertEquals(2, toFx.size());

        toFx.get(0).run(); // made from the set before "newer": dropped
        assertEquals(java.util.Set.of("mine", "newer"), config.getUserDictionary());
        assertEquals(0, told[0]);
        toFx.get(1).run(); // the newest: applied, in place, and the windows are told
        assertEquals(java.util.Set.of("fromelsewhere", "mine", "newer"), config.getUserDictionary());
        assertEquals(1, told[0]);
        assertEquals(List.of("fromelsewhere", "mine", "newer"), Files.readAllLines(config.getUserDictionaryFile()));
        config.shared().shutdown();
    }

    @Test
    void aSettingsSaveMadeWhileSettingsJsonIsBeingReReadWaitsForItsValues(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setTabSize(4);
        config.save();
        Files.writeString(
                config.getSettingsFile(),
                Files.readString(config.getSettingsFile()).replace("\"tabSize\" : 4", "\"tabSize\" : 8"));
        List<Runnable> toFx = new CopyOnWriteArrayList<>();
        config.shared().setFxThreadForTest(toFx::add);
        List<Object> done = new ArrayList<>();
        config.shared().reloadSettings(done::add); // read on the writer; applied when toFx runs
        assertEquals(1, toFx.size());
        config.saveAsync(); // a save of the old values while the re-read is under way: held
        assertTrue(config.shared().awaitWrites());
        assertTrue(Files.readString(config.getSettingsFile()).contains("\"tabSize\" : 8"), "the hand edit stays");
        toFx.remove(0).run();
        assertEquals(8, config.getSettings().getTabSize(), "the file's values are in");
        assertEquals(1, done.size());
        assertTrue(config.shared().awaitWrites());
        assertTrue(Files.readString(config.getSettingsFile()).contains("\"tabSize\" : 8"));
        config.shared().shutdown();
    }

    @Test
    void afterWritesSeesTheSaveQueuedBeforeIt(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.shared().setFxThreadForTest(Runnable::run);
        config.getSettings().setTabSize(13);
        config.saveAsync();
        List<String> seen = new ArrayList<>();
        config.shared().afterWrites(() -> Files.readString(config.getSettingsFile()), (text, e) -> seen.add(text));
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).contains("\"tabSize\" : 13"));
        List<Exception> failures = new ArrayList<>();
        config.shared()
                .afterWrites(
                        () -> {
                            throw new java.io.IOException("refused");
                        },
                        (text, e) -> failures.add(e));
        assertEquals("refused", failures.get(0).getMessage());
        config.shared().shutdown();
    }

    @Test
    void theAgentHistoryIsReadOnceAndCallersWaitingForItRunInOrder(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        List<Runnable> toFx = new CopyOnWriteArrayList<>();
        shared.setFxThreadForTest(toFx::add);
        List<String> order = new ArrayList<>();
        shared.agentSessions(h -> order.add("first"));
        shared.agentSessions(h -> order.add("second"));
        shared.readAgentSessionsAhead().get(10, TimeUnit.SECONDS);
        for (int i = 0; i < 50 && toFx.isEmpty(); i++) {
            Thread.sleep(10);
        }
        toFx.forEach(Runnable::run);
        assertEquals(List.of("first", "second"), order);
        shared.agentSessions(h -> order.add("third")); // read: at once
        assertEquals(List.of("first", "second", "third"), order);
        shared.shutdown();
    }

    @Test
    void writerTasksRunInOrderAndAfterTheQuitOnTheCallersThread(@TempDir Path dir) throws Exception {
        ConfigWriter writer = new ConfigWriter();
        List<Integer> order = new CopyOnWriteArrayList<>();
        writer.enqueue(dir.resolve("a.json"), "{}".getBytes());
        writer.run(() -> order.add(1));
        writer.run(() -> order.add(2));
        writer.run(() -> {
            throw new IllegalStateException("a failing task is logged, the writer goes on");
        });
        writer.run(() -> order.add(3));
        assertTrue(writer.flush());
        assertEquals(List.of(1, 2, 3), order);
        assertTrue(Files.exists(dir.resolve("a.json")));
        assertTrue(writer.shutdown());
        writer.run(() -> order.add(4)); // the writer is gone: here, as the only writer left
        assertEquals(List.of(1, 2, 3, 4), order);
        assertTrue(writer.awaitQueued());
    }
}
