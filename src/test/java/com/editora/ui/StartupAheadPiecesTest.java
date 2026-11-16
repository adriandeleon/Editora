package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.UiZoom;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01, start-up and a window's set-up: the pieces of the work moved off the FX thread, each on its own. What
 * the launch reads ahead is found where the window looks; what a window learns of the disk later is learnt off
 * the FX thread. ({@link StartupOffFxThreadFxTest} runs them together through a real launch.)
 */
class StartupAheadPiecesTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    private void listen() {
        Thread self = Thread.currentThread();
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == self) {
                heard.add(site);
            }
        });
    }

    @AfterEach
    void stop() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void theDiffOperandsAreJudgedOffTheFxThread(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "a");
        Path folder = Files.createDirectories(dir.resolve("b"));
        assertNull(WindowSessionCoordinator.diffPathProblem(file, file));
        assertNull(WindowSessionCoordinator.diffPathProblem(folder, folder));
        assertNotNull(WindowSessionCoordinator.diffPathProblem(file, folder), "a file against a folder");
        assertNotNull(WindowSessionCoordinator.diffPathProblem(file, dir.resolve("missing")), "unreadable");
        assertNotNull(WindowSessionCoordinator.diffPathProblem(null, file), "no operand");
    }

    @Test
    void theMainClassSourceIsTheFirstCandidateThatIsAFile(@TempDir Path dir) throws Exception {
        Path second = Files.writeString(dir.resolve("B.java"), "class B {}");
        assertEquals(second, WindowSessionCoordinator.firstRegularFile(List.of(dir.resolve("A.java"), second)));
        assertNull(WindowSessionCoordinator.firstRegularFile(List.of(dir.resolve("A.java"))));
    }

    @Test
    void aWindowsSessionIsReadAheadWithItsProjectSettings(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("project"));
        ConfigManager bootstrap = new ConfigManager(dir);
        bootstrap.load();
        try {
            Path stateFile = dir.resolve("windows/w.json");
            ConfigManager read = WindowManager.readWindowConfig(bootstrap.shared(), stateFile, root);
            assertEquals(stateFile, read.getWorkspaceStateFile());
            assertNotNull(read.takeStartupProjectSettings(root), "its project's settings came with it");
            assertNotNull(read.takeStartupSessionScan(), "and the scan of its open files");
            ConfigManager noProject = WindowManager.readWindowConfig(bootstrap.shared(), stateFile, null);
            assertNull(noProject.takeStartupProjectSettings(root));
        } finally {
            bootstrap.shared().shutdown();
        }
    }

    @Test
    void theServerCopiesTheLaunchLookedUpAreAnsweredFromMemory(@TempDir Path config) throws Exception {
        Settings settings = new Settings();
        LspServerCommands.prepareOwnCopies(settings, config);
        listen();
        LspServerCommands.launchCommandsForAllServers(settings, config);
        assertEquals(List.of(), heard, "every server's command was looked up ahead");

        LspServerCommands.forgetOwnCopies(); // an install: what is on disk changed
        LspServerCommands.launchCommandsForAllServers(settings, config);
        assertFalse(heard.isEmpty(), "forgotten: looked up again");

        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        LspServerCommands.refreshOwnCopies(never::countDown);
        assertFalse(never.await(300, java.util.concurrent.TimeUnit.MILLISECONDS), "nothing changed on disk");
    }

    @Test
    void theJavaDebugPluginIsLookedUpOnceUntilForgotten(@TempDir Path home) throws Exception {
        com.editora.dap.DebugAdapterLocator.prepareLocate("", home);
        listen();
        assertTrue(com.editora.dap.DebugAdapterLocator.locateKept("", home).isEmpty());
        assertEquals(List.of(), heard);
        com.editora.dap.DebugAdapterLocator.forgetLocated();
        assertTrue(com.editora.dap.DebugAdapterLocator.locateKept(null, home).isEmpty());
        assertEquals(List.of("java-debug-locate"), heard);
    }

    @Test
    void zoomedSheetsMadeAheadAreNotMadeAgain() {
        String sheet = WindowManager.class
                .getResource("/com/editora/styles/syntax.css")
                .toExternalForm();
        listen();
        UiZoom.prepare(List.of(sheet), Settings.DEFAULT_UI_ZOOM);
        assertEquals(List.of(), heard, "nothing to make at 100%");
        UiZoom.prepare(java.util.Arrays.asList(sheet, null), 170);
        int made = heard.size();
        assertTrue(made <= 1, "made once at most (an earlier test may have made it): " + heard);
        UiZoom.prepare(List.of(sheet), 170);
        assertEquals(made, heard.size(), "kept");
        assertNotEquals("", UiZoom.base(sheet));
    }

    @Test
    void theWelcomePagesRootsAreCheckedOnAWorker(@TempDir Path dir) throws Exception {
        Deque<Runnable> worker = new ArrayDeque<>();
        AtomicInteger changed = new AtomicInteger();
        Path kept = Files.createDirectories(dir.resolve("kept"));
        Path gone = dir.resolve("gone");
        RecentFilesCheck roots = new RecentFilesCheck(worker::add, Runnable::run, Files::isDirectory);
        roots.revalidate(java.util.Arrays.asList(kept, gone, null), changed::incrementAndGet);
        assertFalse(roots.isMissing(gone), "not checked yet: shown");
        worker.remove().run();
        assertTrue(roots.isMissing(gone));
        assertFalse(roots.isMissing(kept));
        assertEquals(1, changed.get());
        roots.revalidate(List.of(), changed::incrementAndGet);
        assertFalse(roots.isMissing(gone), "nothing local to check: nothing missing");
    }

    @Test
    void theFirstWindowsWorkIsDoneAheadAndAFailingPieceStopsNothing(@TempDir Path dir) throws Exception {
        ConfigManager bootstrap = new ConfigManager(dir);
        bootstrap.load();
        try {
            bootstrap.shared().getSettings().setMmdcPath("/opt/My Tools/mmdc"); // a configured value, not the default
            bootstrap.shared().getSettings().setTheme("No Such Theme");
            listen();
            FirstWindowPrep.prepare(bootstrap.shared(), List.of());
            assertTrue(heard.contains("tool-path-is-file"), "the configured tool path was looked at: " + heard);
            assertTrue(heard.contains("UserThemes.load"));
            assertTrue(Files.isDirectory(dir.resolve(LspCoordinator.LOOSE_ROOT)), "the loose-files root is made");
        } finally {
            bootstrap.shared().shutdown();
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> FirstWindowPrep.step("a failing piece", () -> {
            throw new IllegalStateException("broken");
        }));
    }

    @Test
    void blankCommandsAndJdksAreAskedAsEmpty(@TempDir Path config) {
        assertEquals(
                LspServerCommands.withOwnCopy("xml", "", config, ""),
                LspServerCommands.withOwnCopy("xml", null, config, null),
                "null is the same question as blank");
        com.editora.dap.DebugAdapterLocator.prepareLocate(null, config);
        listen();
        com.editora.dap.DebugAdapterLocator.locateKept("", config);
        assertEquals(List.of(), heard, "a null path was prepared as the blank one");
    }

    @Test
    void noRecentFilesListIsNothingToCheck() {
        Deque<Runnable> worker = new ArrayDeque<>();
        RecentFilesCheck check = new RecentFilesCheck(worker::add, Runnable::run, p -> false);
        check.revalidate((com.editora.config.RecentFiles) null, () -> {});
        assertTrue(worker.isEmpty(), "nothing to ask the disk");
    }
}
