package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.config.HttpResponseHistory;
import com.editora.editor.EditorBuffer;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved HTTP history in a real window when its file moves the way the Project tree moves it, and when Editora
 * quits right after a response landed: what is on disk afterwards.
 */
@Tag("fx")
class HttpHistoryFileMoveFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private Path project;
    private Path config;
    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        project = Files.createTempDirectory("http-move");
        config = Files.createTempDirectory("http-move-cfg");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.close();
        }
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String requests() {
        return "### a\nGET " + url("/a") + "\n\n### b\nGET " + url("/b") + "\n\n### c\nGET " + url("/c") + "\n";
    }

    private MainController window(Path... files) throws Exception {
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(files)
                .map(f -> new MainController.OpenTarget(f, 2, 1))
                .toList();
        fx = FxWindowFixture.create(config, false, false, false, targets, true, c -> {});
        MainController c = fx.controller;
        await(() -> c.activeBuffer() != null && c.httpClient.panelForTest(c.activeBuffer()) != null);
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        return c;
    }

    private static void await(java.util.concurrent.Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!FxTestSupport.callOnFx(check)) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(10);
        }
    }

    private static void run(MainController c, int line) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        FxTestSupport.runOnFx(() -> c.httpClient.runRequest(b, line));
        await(() -> !c.httpClient.isRunningForTest(b));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
    }

    /** What the Project tree does on a rename or move: the file moves on disk, then every window follows. */
    private static void treeMove(MainController c, Path from, Path to) throws Exception {
        Files.move(from, to);
        FxTestSupport.runOnFx(() -> c.remapProjectFileLocal(from, to, false));
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
    }

    private List<String> saved(Path file) {
        fx.shared.flushWrites();
        return new HttpResponseHistory(config)
                .load(file).responses.stream()
                        .map(r -> r.url().replace(url(""), ""))
                        .toList();
    }

    @Test
    void aTreeRenameThenClosingTheTabKeepsTheHistoryUnderTheNewName() throws Exception {
        Path file = project.resolve("api.http");
        Files.writeString(file, requests());
        MainController c = window(file);
        run(c, 1);
        run(c, 4);
        Path renamed = project.resolve("renamed.http");
        treeMove(c, file, renamed);
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        FxTestSupport.runOnFx(() -> registry.run("buffer.close"));
        FxTestSupport.drainFx();
        HttpHistorySync.awaitIdleForTest();
        assertEquals(List.of("/b", "/a"), saved(renamed));
        assertFalse(Files.exists(fx.shared.httpHistory().fileFor(file)), "no orphan under the old name");
    }

    @Test
    void aRunBetweenATreeRenameAndATabSwitchShowsEachResponseOnce() throws Exception {
        Path file = project.resolve("api.http");
        Files.writeString(file, requests());
        Path other = project.resolve("notes.txt");
        Files.writeString(other, "x");
        MainController c = window(other, file);
        run(c, 1);
        Path renamed = project.resolve("renamed.http");
        treeMove(c, file, renamed);
        run(c, 4);
        FileWorkflowCoordinator files = FxTestSupport.field(c, "fileWorkflows");
        FxTestSupport.runOnFx(() -> files.openPath(other));
        FxTestSupport.runOnFx(() -> files.openPath(renamed));
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.drainFx();
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        List<String> shown = FxTestSupport.callOnFx(() -> c.httpClient.panelForTest(b).history().stream()
                .map(e -> e.exchange().url().replace(url(""), ""))
                .toList());
        assertEquals(List.of("/b", "/a"), shown);
        assertEquals(List.of("/b", "/a"), saved(renamed));
    }

    @Test
    void aMovedFolderTakesTheHistoryOfAFileThatIsNotOpen() throws Exception {
        Path folder = Files.createDirectories(project.resolve("api"));
        Path file = folder.resolve("users.http");
        Files.writeString(file, requests());
        Path open = project.resolve("open.http");
        Files.writeString(open, requests());
        MainController c = window(file);
        run(c, 1);
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        FileWorkflowCoordinator files = FxTestSupport.field(c, "fileWorkflows");
        FxTestSupport.runOnFx(() -> files.openPath(open));
        FxTestSupport.runOnFx(() -> {
            files.openPath(file);
            registry.run("buffer.close"); // users.http is no longer open
        });
        HttpHistorySync.awaitIdleForTest();
        assertEquals(List.of("/a"), saved(file));
        Path moved = project.resolve("v2");
        treeMove(c, folder, moved);
        assertEquals(List.of("/a"), saved(moved.resolve("users.http")));
        assertFalse(Files.exists(fx.shared.httpHistory().fileFor(file)));
    }

    @Test
    void quittingRightAfterAResponseLandedStillSavesIt() throws Exception {
        Path file = project.resolve("api.http");
        Files.writeString(file, requests());
        MainController c = window(file);
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        // The history thread is busy (another file's history is being read) when the response lands.
        CountDownLatch busy = HttpHistorySync.holdForTest();
        FxTestSupport.runOnFx(() -> c.httpClient.runRequest(b, 1));
        await(() -> !c.httpClient.isRunningForTest(b)
                && !c.httpClient.panelForTest(b).history().isEmpty());
        // Quit: the windows go, then the shared config shuts down while the history thread is still held.
        Thread quit = new Thread(() -> {
            try {
                fx.keepConfigDir = true;
                fx.close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        quit.start();
        quit.join(TimeUnit.SECONDS.toMillis(3)); // an old build is done by now, its writer already stopped
        busy.countDown();
        quit.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        fx = null;
        assertEquals(
                List.of("/a"),
                new HttpResponseHistory(config)
                        .load(file).responses.stream()
                                .map(r -> r.url().replace(url(""), ""))
                                .toList());
    }
}
