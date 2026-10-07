package com.editora.logviewer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The follow poll, with events delivered on the poll thread itself (no FX toolkit) and a short period. */
class LogTailServiceTest {

    private final LogTailService service = new LogTailService(Runnable::run, 15);

    @AfterEach
    void stop() {
        service.shutdown();
    }

    /** Records what a follow delivers, as one event per line: {@code +text}, {@code R text}, {@code missing}, {@code !err}. */
    private static final class Events implements LogTailService.Listener {
        final List<String> seen = new CopyOnWriteArrayList<>();
        volatile LogTailService.FileState last;

        @Override
        public void appended(String text, LogTailService.FileState file) {
            last = file;
            seen.add("+" + text);
        }

        @Override
        public void rotated(String text, LogTailService.FileState file) {
            last = file;
            seen.add("R " + text);
        }

        @Override
        public void missing() {
            seen.add("missing");
        }

        @Override
        public void error(String message) {
            seen.add("!" + message);
        }

        String text() {
            return String.join(
                    "",
                    seen.stream()
                            .filter(e -> e.startsWith("+"))
                            .map(e -> e.substring(1))
                            .toList());
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(10);
        }
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardOpenOption.APPEND);
    }

    @Test
    void deliversWhatIsAppendedAndReportsHowFarItGot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "old\n");
        Events events = new Events();
        LogTailService.Handle handle = service.follow(file, 4, StandardCharsets.UTF_8, events);

        append(file, "one\n");
        await(() -> events.text().equals("one\n"), "the first line");
        assertEquals(8, handle.offset(), "the offset is what reached the listener");
        assertEquals(8, events.last.size(), "and the file's size goes with the text, for the disk snapshot");

        append(file, "two\n");
        await(() -> events.text().equals("one\ntwo\n"), "the second line");
        assertEquals(12, handle.offset());
    }

    @Test
    void nothingIsDeliveredAfterStopAndTheOffsetStaysWhereDeliveryDid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "");
        Events events = new Events();
        LogTailService.Handle handle = service.follow(file, 0, StandardCharsets.UTF_8, events);
        append(file, "one\n");
        await(() -> events.text().equals("one\n"), "the first line");

        handle.stop();
        append(file, "written while paused\n");
        Thread.sleep(120);

        assertEquals("one\n", events.text());
        assertEquals(4, handle.offset(), "a later follow resumes here and reads the paused lines");
        assertTrue(handle.isStopped());
    }

    @Test
    void crlfBecomesLfEvenWhenSplitAcrossTwoReads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "");
        Events events = new Events();
        service.follow(file, 0, StandardCharsets.UTF_8, events);

        append(file, "one\r");
        Thread.sleep(80); // the \r is read alone
        append(file, "\ntwo\r\n");
        await(() -> events.text().endsWith("two\n"), "both lines");

        assertEquals("one\ntwo\n", events.text());
    }

    @Test
    void aFollowOutlivesARotationThatBrieflyRemovesTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.log");
        Files.writeString(file, "old\n");
        Events events = new Events();
        LogTailService.Handle handle = service.follow(file, 4, StandardCharsets.UTF_8, events);

        Files.move(file, dir.resolve("a.log.1"));
        await(() -> events.seen.contains("missing"), "the gap to be noticed");
        Files.writeString(file, "new file, already longer than the old offset\n");
        await(() -> events.seen.stream().anyMatch(e -> e.startsWith("R ")), "the new file");

        assertEquals("R new file, already longer than the old offset\n", events.seen.get(events.seen.size() - 1));
        assertFalse(handle.isStopped(), "still following");
        assertFalse(events.seen.stream().anyMatch(e -> e.startsWith("!")), "a missing file is not an error");

        append(file, "more\n");
        await(() -> events.text().equals("more\n"), "what the new file gets next");
    }

    @Test
    void aRealFailureStopsTheFollowAndSaysWhatItWas(@TempDir Path dir) throws Exception {
        Events events = new Events();
        // A directory can be stat'ed but not read as a file.
        LogTailService.Handle handle = service.follow(dir, 99, StandardCharsets.UTF_8, events);
        await(() -> events.seen.stream().anyMatch(e -> e.startsWith("!")), "the error");

        assertTrue(handle.isStopped());
        assertTrue(events.seen.get(0).matches("!\\w+(Exception|Error).*"), "names the failure: " + events.seen.get(0));
        int count = events.seen.size();
        Thread.sleep(80);
        assertEquals(count, events.seen.size(), "and is not reported again on every poll");
    }
}
