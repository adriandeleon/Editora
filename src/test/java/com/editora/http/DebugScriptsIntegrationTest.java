package com.editora.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Debug Request end to end with the real vscode-js-debug and Node (skipped without them): a script waits on its
 * inspector, Editora's debugger attaches, stops on a breakpoint in an inline {@code > {% %}} block — at its line of the
 * {@code .http} file, the locals readable — and then in a handler file, and the request's result arrives once the
 * scripts are resumed.
 */
@Tag("fx")
class DebugScriptsIntegrationTest {

    private record Stop(String reason, List<DapModels.StackFrameInfo> frames) {}

    private HttpServer server;
    private HttpClientService svc;
    private DapManager manager;

    @BeforeAll
    static void bootFx() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another test booted it
        }
        Platform.setImplicitExit(false);
        Messages.init("en");
    }

    @AfterEach
    void stop() throws Exception {
        if (manager != null) {
            DapManager m = manager;
            CountDownLatch done = new CountDownLatch(1);
            Platform.runLater(() -> {
                m.shutdown();
                done.countDown();
            });
            done.await(10, TimeUnit.SECONDS);
        }
        if (svc != null) {
            svc.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aBreakpointInTheHttpFileAndOneInAHandlerFileBothStop(@TempDir Path tmp) throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", home).isPresent(), "needs vscode-js-debug");
        Path dir = Files.createDirectories(tmp.resolve("api")).toRealPath();
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "{\"id\":7}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();

        Path httpFile = dir.resolve("api.http");
        String text = "### one\nGET http://127.0.0.1:" + server.getAddress().getPort() + "/x\n\n> {%\n"
                + "  const id = response.body.id;\n" // line 4 (0-based)
                + "  const twice = id * 2;\n" // line 5: the breakpoint
                + "  client.test('id', () => client.assert(twice === 14));\n%}\n> ./check.js\n";
        Files.writeString(httpFile, text);
        Path check = dir.resolve("check.js");
        Files.writeString(check, "const seen = response.status;\nclient.log('status', seen);\n"); // breakpoint: line 1

        manager = new DapManager(null);
        BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        manager.setListener(new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {}

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                stops.add(new Stop(reason, frames));
            }

            @Override
            public void onOutput(String text, String category) {}

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });
        manager.setBreakpointsSupplier(() -> List.of(
                new DapModels.FileBreakpoints(httpFile, List.of(new DapModels.LineBreakpoint(5, null, null))),
                new DapModels.FileBreakpoints(check, List.of(new DapModels.LineBreakpoint(1, null, null)))));
        CompletableFuture<Boolean> detected = new CompletableFuture<>();
        Platform.runLater(() -> {
            manager.configure(true, "", false, "", true, "");
            manager.detectJs(detected::complete);
        });
        assumeTrue(detected.get(60, TimeUnit.SECONDS), "needs node on the PATH");

        List<String> labels = new CopyOnWriteArrayList<>();
        NodeResponseScripts.Debugger debugger = (port, label, shim) -> {
            labels.add(label);
            Platform.runLater(() -> manager.startJsAttach(
                    port, label, shim.getParent(), List.of(shim.toString(), "<node_internals>/**")));
        };
        ResponseScripts scripts = new NodeResponseScripts(
                new NodeResponseScripts.Settings(true, "node", Duration.ofSeconds(5), cache, debugger),
                new NodeResponseScripts.Trust() {
                    @Override
                    public SecretGate.Verdict allowRun(java.util.function.BooleanSupplier cancelled) {
                        return SecretGate.Verdict.ALLOWED;
                    }

                    @Override
                    public boolean isTrusted(Path file) {
                        return true;
                    }
                },
                httpFile,
                dir);
        svc = new HttpClientService();
        AtomicReference<List<HttpExchange>> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        svc.runAll(
                HttpFile.parse(text).stream().map(HttpFile::parseRequest).toList(),
                RunEnvironment.of(Map.of()),
                dir,
                new CapturedResponses(),
                SecretGate.UNCHECKED,
                scripts,
                null,
                all -> {
                    got.set(all);
                    done.countDown();
                });

        Stop inline = stops.poll(60, TimeUnit.SECONDS);
        assertNotNull(inline, "the .http breakpoint never hit; errors: " + errors);
        assertEquals(httpFile, inline.frames().get(0).file().toRealPath(), "the frame is the .http file");
        assertEquals(5, inline.frames().get(0).line(), "at the breakpoint's line of the .http file");
        // Longer than the 5 s timeout: a paused script is not stopped.
        Thread.sleep(6000);
        Platform.runLater(manager::resume);

        Stop inFile = stops.poll(60, TimeUnit.SECONDS);
        assertNotNull(inFile, "the handler file's breakpoint never hit; errors: " + errors);
        assertEquals(check.toRealPath(), inFile.frames().get(0).file().toRealPath());
        assertEquals(1, inFile.frames().get(0).line());
        Platform.runLater(manager::resume);

        assertTrue(done.await(60, TimeUnit.SECONDS), "the run finishes once the scripts are resumed");
        HttpTests tests = got.get().get(0).result().tests();
        assertEquals(1, tests.passed(), tests.toString());
        assertEquals(List.of("status 200"), tests.log());
        assertEquals(List.of("Response handler of one", "Response handler of one"), labels);
    }
}
