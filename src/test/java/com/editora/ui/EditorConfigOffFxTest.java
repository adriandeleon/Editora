package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editorconfig.EditorConfig;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .editorconfig} rules on the JavaFX thread come from what a look off it found (PERF-01, Pauta 6.0.1):
 * the FX thread never stats, reads or parses a config, a file never looked at answers from the folders' configs
 * in memory (or as having no rules), and a changed config reaches the listeners when the look behind lands.
 */
@Tag("fx")
class EditorConfigOffFxTest {

    private final List<String> heardOnFx = Collections.synchronizedList(new ArrayList<>());
    private final List<Path> told = Collections.synchronizedList(new ArrayList<>());
    private final Consumer<Path> listener = told::add;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private void listen() {
        EditorConfig.clearCache();
        EditorConfig.addListener(listener);
        BlockingSites.listenForTest((site, thread) -> {
            if (site.startsWith("EditorConfig.") && Platform.isFxApplicationThread()) {
                heardOnFx.add(site);
            }
        });
    }

    @AfterEach
    void stop() {
        BlockingSites.listenForTest(null);
        EditorConfig.clearCache();
    }

    private void waitUntil(java.util.concurrent.Callable<Boolean> condition, String what) throws Exception {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.call()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out: " + what);
            }
            Thread.sleep(20);
            FxTestSupport.drainFx();
        }
    }

    @Test
    void aFileLookedAtOffTheThreadIsAnsweredFromMemoryOnIt(@TempDir Path dir) throws Exception {
        listen();
        Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nend_of_line = crlf\n");
        Path file = Files.writeString(dir.resolve("a.txt"), "x\n");
        EditorConfig.warm(file); // as a file's load does, on its own thread

        Map<String, String> onFx = FxTestSupport.callOnFx(() -> EditorConfig.rawFor(file));
        assertEquals("crlf", onFx.get("end_of_line"));
        assertEquals(List.of(), heardOnFx, "no stat, read or parse on the FX thread");
    }

    @Test
    void aFileNeverLookedAtIsAnsweredFromItsFoldersAndToldWhenItsLookLands(@TempDir Path dir) throws Exception {
        listen();
        Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*.md]\nindent_size = 3\n");
        Path sibling = Files.writeString(dir.resolve("a.md"), "x\n");
        Path fresh = Files.writeString(dir.resolve("b.md"), "y\n");
        EditorConfig.warm(sibling); // the folder and its config are known now

        // b.md was never looked at: its rules come from the folder's config already in memory.
        assertEquals(
                "3", FxTestSupport.callOnFx(() -> EditorConfig.rawFor(fresh)).get("indent_size"));

        Path elsewhere = Files.createDirectories(dir.resolve("deeper/still"));
        Path cold = Files.writeString(elsewhere.resolve("c.md"), "z\n");
        // Folders nobody looked at: no rules for now (never a read on FX), the look behind tells the listener.
        assertEquals(Map.of(), FxTestSupport.callOnFx(() -> EditorConfig.rawFor(cold)));
        waitUntil(() -> told.contains(cold.toAbsolutePath().normalize()), "the look behind lands");
        assertEquals(
                "3", FxTestSupport.callOnFx(() -> EditorConfig.rawFor(cold)).get("indent_size"));
        assertEquals(List.of(), heardOnFx);
    }

    @Test
    void aConfigChangedOnDiskReachesTheFileWhenItIsLookedAtAgain(@TempDir Path dir) throws Exception {
        listen();
        Path config = Files.writeString(dir.resolve(".editorconfig"), "root = true\n");
        Path file = Files.writeString(dir.resolve("f.txt"), "x\n");
        EditorConfig.warm(file);
        assertEquals(Map.of(), FxTestSupport.callOnFx(() -> EditorConfig.rawFor(file)));

        Files.writeString(config, "root = true\n[*]\nend_of_line = crlf\n");
        Files.setLastModifiedTime(
                config, FileTime.fromMillis(Files.getLastModifiedTime(config).toMillis() + 5_000));
        FxTestSupport.runOnFx(() -> EditorConfig.refresh(file)); // a save asks for a fresh look
        waitUntil(() -> told.contains(file.toAbsolutePath().normalize()), "the change is told");
        assertEquals(
                "crlf", FxTestSupport.callOnFx(() -> EditorConfig.rawFor(file)).get("end_of_line"));
        assertTrue(heardOnFx.isEmpty(), String.valueOf(heardOnFx));
    }
}
