package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R3 / R12: overwrite captures run in turn, pass over what is not a regular file, and stop at a failure. */
@Tag("fx")
class HistoryOverwriteCapturesFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void capturesRegularFilesInTurnAndPassesOverTheRest(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path gone = dir.resolve("gone.txt");
        Path folder = Files.createDirectory(dir.resolve("folder"));
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        List<Path> asked = new ArrayList<>();

        Optional<Path> failed = run(List.of(a, gone, folder, b), (file, done) -> {
            asked.add(file);
            done.accept(new HistoryCoordinator.OverwriteCapture(true, true, null));
        });

        assertEquals(Optional.empty(), failed);
        assertEquals(List.of(a, b), asked);
    }

    @Test
    void stopsAtTheFirstFileWhoseCopyCouldNotBeMade(@TempDir Path dir) throws Exception {
        Path a = Files.writeString(dir.resolve("a.txt"), "a");
        Path b = Files.writeString(dir.resolve("b.txt"), "b");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        List<Path> asked = new ArrayList<>();

        Optional<Path> failed = run(List.of(a, b, c), (file, done) -> {
            asked.add(file);
            done.accept(new HistoryCoordinator.OverwriteCapture(!file.equals(b), false, null));
        });

        assertEquals(Optional.of(b), failed);
        assertEquals(List.of(a, b), asked, "nothing after the failure is tried");
    }

    private static Optional<Path> run(List<Path> files, HistoryOverwriteCaptures.Capturer capturer) throws Exception {
        CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> HistoryOverwriteCaptures.captureAll(files, capturer, result::complete));
        return result.get(10, TimeUnit.SECONDS);
    }
}
