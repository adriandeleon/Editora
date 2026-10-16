package com.editora.ui;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.wsl.WslExecTarget;
import com.editora.wsl.WslTargets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outside changes to a folder inside a <b>real</b> WSL distribution, opened from Windows as
 * {@code \\wsl.localhost\…}: Windows's own watcher on the share (reported, not asserted), and Editora's watch
 * inside the distribution ({@link RemoteWatcher}), with inotifywait and with the polling fallback. Opt-in, on
 * Windows with WSL: {@code -Dwsl.probe=<distribution>}; inotifywait needs {@code inotify-tools} there.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "wsl.probe", matches = ".+")
class WslWatchProbeTest {

    private String distro;
    private Path folder;
    private final WslTargets targets = new WslTargets(List.of("wsl.exe"));
    private RemoteWatcher watcher;
    private final List<ProjectPanel.FsChange> seen = new CopyOnWriteArrayList<>();

    private ProcessRunner.Result wsl(String script) {
        List<String> argv = new ArrayList<>(List.of("wsl.exe", "-d", distro, "--exec"));
        argv.addAll(WslExecTarget.windowsSafe(List.of("sh", "-c", script)));
        return ProcessRunner.run(null, Duration.ofSeconds(120), argv);
    }

    @BeforeEach
    void setUp() {
        distro = System.getProperty("wsl.probe");
        ProcessRunner.Result made = wsl("mkdir -p \"$HOME/editora watch\" && cd \"$HOME/editora watch\" && pwd");
        assertTrue(made.ok(), made.message());
        String inside = made.out().strip();
        for (String host : List.of("\\\\wsl.localhost\\", "\\\\wsl$\\")) {
            Path candidate = Path.of(host + distro + inside.replace('/', '\\'));
            if (Files.isDirectory(candidate)) {
                folder = candidate;
                break;
            }
        }
        assertNotNull(folder, "Windows reaches the folder: " + inside);
        ExecTargets.setRemoteEnabled(() -> true);
        ExecTargets.registerMapped(targets);
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.stop();
        }
        RemoteWatcher.pollForTest = false;
        ExecTargets.unregisterMapped(targets);
        ExecTargets.setRemoteEnabled(null);
        wsl("rm -rf \"$HOME/editora watch\"");
    }

    private boolean saw(Path path, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (seen.stream().anyMatch(c -> c.path().equals(path))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void windowsOwnWatcherOnTheShare() throws Exception {
        assertFalse(ExecTargets.forPath(folder).localWatchSeesChanges(), "so Editora watches inside instead");
        int inside;
        int fromWindows;
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            WatchKey key = folder.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            assertTrue(wsl("cd \"$HOME/editora watch\" && echo in > inside.txt").ok());
            inside = drain(service, 8000);
            Files.writeString(folder.resolve("windows.txt"), "out");
            fromWindows = drain(service, 8000);
            key.cancel();
        } catch (java.io.IOException e) {
            System.out.println("WSL watch probe: Windows cannot watch the share at all: " + e);
            return;
        }
        System.out.println("WSL watch probe: Windows's watcher on " + folder + " saw " + inside
                + " event(s) for a change made inside, " + fromWindows + " for one made from Windows");
    }

    private static int drain(WatchService service, long millis) throws InterruptedException {
        int events = 0;
        WatchKey key = service.poll(millis, TimeUnit.MILLISECONDS);
        while (key != null) {
            events += key.pollEvents().size();
            key.reset();
            key = service.poll(500, TimeUnit.MILLISECONDS);
        }
        return events;
    }

    private void watchInside(boolean poll) throws Exception {
        RemoteWatcher.pollForTest = poll;
        watcher = RemoteWatcher.start(folder, seen::addAll);
        assertNotNull(watcher, "the watch started in the distribution");
        Thread.sleep(2500); // inotifywait sets up its watches; a poll takes its first stamp

        assertTrue(wsl("cd \"$HOME/editora watch\" && mkdir -p src && echo in > src/Inside.java")
                .ok());
        assertTrue(saw(folder.resolve("src").resolve("Inside.java"), 20_000), "a change made inside: " + seen);

        Files.writeString(folder.resolve("Windows.java"), "out");
        boolean fromWindows = saw(folder.resolve("Windows.java"), 20_000);
        System.out.println("WSL watch probe (" + (poll ? "poll" : "inotifywait if present") + "): a file written"
                + " from Windows through the share was " + (fromWindows ? "" : "NOT ") + "reported");
        assertTrue(fromWindows, "a file written through the share is a file of the distribution: " + seen);
    }

    @Test
    void editoraWatchesInsideTheDistribution() throws Exception {
        System.out.println("WSL watch probe: inotifywait "
                + (wsl("command -v inotifywait").ok() ? "is" : "is not") + " in " + distro);
        watchInside(false);
    }

    @Test
    void withoutInotifywaitItPolls() throws Exception {
        watchInside(true);
    }
}
