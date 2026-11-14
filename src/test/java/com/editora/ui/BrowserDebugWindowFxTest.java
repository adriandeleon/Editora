package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.BrowserLaunch;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Debugging a web page through the window, against a {@link FakeDebugAdapter} reached as vscode-js-debug is (a
 * stand-in {@code node} announcing the fake's port), with the browsers found stood in for. Debug in Browser asks for
 * a URL, sends js-debug a browser {@code launch} — the browser's type, program and Editora's own profile — names the
 * session after the browser and the page, remembers the URL, and offers it next time; a chosen browser that is gone
 * falls back to one found, and none found is said. F5 on an HTML file serves its site and maps the served folder back
 * to the files; its breakpoints are sent. Attach to Browser attaches to a debugging port and Stop leaves the browser
 * open; a launch.json {@code chrome} configuration goes to js-debug as a browser launch; a launch js-debug drops
 * without answering it is said on the status line. {@code BrowserDebugProbeFxTest} drives the real js-debug and Chrome.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class BrowserDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path site;
    private Path script;
    private Path page;
    private volatile List<String> found = List.of("chrome");

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in node is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        site = Files.createDirectories(fx.configDir.resolve("site")).toRealPath();
        script = Files.writeString(site.resolve("app.js"), "function tick(n) {\n  return n * 2;\n}\n");
        page = Files.writeString(
                site.resolve("index.html"),
                "<!doctype html>\n<html><body>\n<script>\n  const x = 1;\n</script>\n</body></html>\n");
        Path node = fx.configDir.resolve("node");
        Files.writeString(
                node,
                "#!/bin/sh\ncase \"$1\" in --version) echo v22.11.0; exit 0;; esac\n"
                        + "echo \"Debug server listening at 127.0.0.1:" + adapter.port() + "\"\nexec sleep 300\n");
        assertTrue(node.toFile().setExecutable(true));
        Path server = Files.writeString(fx.configDir.resolve("dapDebugServer.js"), "// stand-in\n");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setJsDebugEnabled(true);
            fx.shared.getSettings().setJsDebugPath(server.toString());
            fx.shared.getSettings().setNodePath(node.toString());
            debug.applySupport();
        });
        await("JavaScript debugging available", () -> call(() -> debug.debugEffectiveFor("javascript")));
        debug.browserIds = () -> found;
        debug.browserExecutable = id -> "/opt/" + id + "/" + id;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    @AfterEach
    void end() throws Exception {
        if (OverlayDriver.showing(fx.controller)) {
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(fx.controller));
        }
        if (call(manager::isActive)) {
            FxTestSupport.runOnFx(manager::stop);
            await("ended", () -> !call(manager::isActive));
        }
        found = List.of("chrome");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugBrowser("");
            fx.controller.config.getWorkspaceState().setDebugBrowserUrls(List.of());
        });
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private void open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && file.equals(fx.controller.activeBuffer().getPath())));
    }

    private void awaitPrompt() throws InterruptedException {
        await("a prompt", () -> call(() -> OverlayDriver.showing(fx.controller)));
    }

    /** The session js-debug was sent {@code request} ({@code launch} or {@code attach}) in. */
    private FakeDebugAdapter.Session session(String request) throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest(request);
        return session;
    }

    private String sessionName() {
        return call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile"));
    }

    @Test
    void aUrlTypedInOpensInTheBrowserFoundAndIsOfferedNextTime() throws Exception {
        open(script);
        run("debug.browser");
        awaitPrompt();
        assertEquals("http://localhost:3000", call(() -> OverlayDriver.promptText(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "localhost:5173/app"));
        FakeDebugAdapter.Session session = session("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals(BrowserLaunch.CHROME, launch.get("type"), launch.toString());
        assertEquals("http://localhost:5173/app", launch.get("url"));
        assertEquals("/opt/chrome/chrome", launch.get("runtimeExecutable"));
        assertEquals(fx.configDir.resolve("browser-debug").resolve("chrome").toString(), launch.get("userDataDir"));
        assertFalse(launch.containsKey("pathMapping"), "a URL of the user's maps through webRoot");
        assertEquals("Google Chrome: localhost:5173/app", sessionName());
        assertEquals(
                List.of("http://localhost:5173/app"),
                call(() -> fx.controller.config.getWorkspaceState().getDebugBrowserUrls()));

        // Stop closes the browser it opened.
        FxTestSupport.runOnFx(manager::stop);
        assertTrue(session.disconnected.await(20, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, session.terminateDebuggee);
        await("ended", () -> !call(manager::isActive));

        // Next time the URL is offered first, then "Another page…".
        run("debug.browser");
        await("the picker", () -> call(() -> OverlayDriver.showing(fx.controller)));
        List<String> rows = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(List.of("http://localhost:5173/app", ""), rows);
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, "http://localhost:5173/app"));
        assertEquals("http://localhost:5173/app", session("launch").launchArgs.get("url"));
    }

    @Test
    void aBrowserJsDebugGivesUpOnIsSaidInsteadOfEndingWithNothing() throws Exception {
        open(script);
        adapter.launchGate = new java.util.concurrent.CompletableFuture<>(); // js-debug still waits for the browser
        try {
            run("debug.browser");
            awaitPrompt();
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "http://localhost:5173"));
            FakeDebugAdapter.Session session = session("launch");
            assertEquals(
                    BrowserLaunch.LAUNCH_TIMEOUT_MILLIS,
                    ((Number) session.launchArgs.get("timeout")).longValue(),
                    "the browser gets longer to start than js-debug's own 10 s");
            await("running", () -> call(manager::isActive));
            // js-debug 1.117, when the browser has exited or is not ready in time: the connection closes, with no
            // answer to the launch, no error and no event.
            session.dropConnection();
            await("ended", () -> !call(manager::isActive));
            String said = status();
            assertTrue(
                    java.util.stream.LongStream.rangeClosed(0, 20)
                            .anyMatch(seconds -> tr("status.debug.error", tr("debug.error.browserNotReady", seconds))
                                    .equals(said)),
                    said);
        } finally {
            adapter.launchGate = null;
        }
    }

    @Test
    void aChosenBrowserThatIsGoneFallsBackAndNoneFoundIsSaid() throws Exception {
        open(script);
        found = List.of("firefox", "edge");
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setDebugBrowser("brave"));
        run("debug.browser");
        awaitPrompt();
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "http://127.0.0.1:8080"));
        Map<String, Object> launch = session("launch").launchArgs;
        assertEquals(BrowserLaunch.EDGE, launch.get("type"), launch.toString());
        assertEquals("/opt/edge/edge", launch.get("runtimeExecutable"));
        assertEquals(tr("status.debug.browserFallback", "Brave", "Microsoft Edge"), status());
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));

        found = List.of("firefox", "safari");
        run("debug.browser");
        await("the picker", () -> call(() -> OverlayDriver.showing(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, ""));
        awaitPrompt();
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "http://127.0.0.1:8080"));
        await("said", () -> tr("status.debug.noBrowser").equals(status()));
        assertFalse(call(manager::isActive));

        run("debug.browser");
        await("the picker", () -> call(() -> OverlayDriver.showing(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, ""));
        awaitPrompt();
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "ftp://files"));
        assertEquals(tr("status.debug.badUrl", "ftp://files"), status());
    }

    @Test
    void f5OnAnHtmlFileServesItsSiteAndSendsItsBreakpoints() throws Exception {
        open(page);
        EditorBuffer html = call(fx.controller::activeBuffer);
        assertTrue(call(() -> debug.isDebuggableBuffer(html)), "an HTML file takes breakpoints");
        FxTestSupport.runOnFx(() -> html.toggleBreakpoint(3));
        run("debug.start");
        FakeDebugAdapter.Session session = session("launch");
        Map<String, Object> launch = session.launchArgs;
        String url = String.valueOf(launch.get("url"));
        assertTrue(url.startsWith("http://127.0.0.1:") && url.endsWith("/index.html"), url);
        String token = url.substring(url.indexOf('/', "http://127.0.0.1:".length()), url.lastIndexOf('/') + 1);
        assertEquals(Map.of(token, site + "/"), launch.get("pathMapping"));
        assertEquals(site.toString(), launch.get("webRoot"));
        assertEquals("Google Chrome: index.html", sessionName());
        assertTrue(
                call(() -> fx.controller.config.getWorkspaceState().getDebugBrowserUrls())
                        .isEmpty(),
                "a served page is not remembered as a URL: its token changes");
        await(
                "the page's breakpoint sent",
                () -> session.breakpoints.stream()
                        .anyMatch(b -> b.getSource().getPath() != null
                                && b.getSource().getPath().endsWith("index.html")
                                && b.getBreakpoints().length == 1
                                && b.getBreakpoints()[0].getLine() == 4));

        // The picker offers this page too.
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));
        run("debug.browser");
        await("the picker", () -> call(() -> OverlayDriver.showing(fx.controller)));
        List<String> rows = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(2, rows.size(), rows.toString());
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, rows.getFirst()));
        assertTrue(String.valueOf(session("launch").launchArgs.get("url")).endsWith("/index.html"));
    }

    @Test
    void attachToBrowserLeavesItOpenOnStop() throws Exception {
        open(page);
        run("debug.attach"); // on an HTML file: a browser, not Node.js
        awaitPrompt();
        assertEquals("localhost:9222", call(() -> OverlayDriver.promptText(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "localhost:9333"));
        FakeDebugAdapter.Session session = session("attach");
        assertEquals(BrowserLaunch.CHROME, session.launchArgs.get("type"));
        assertEquals("localhost", session.launchArgs.get("address"));
        assertEquals(9333, ((Number) session.launchArgs.get("port")).intValue());
        assertEquals(tr("debug.browser.attachSession", "localhost:9333"), sessionName());
        FxTestSupport.runOnFx(manager::stop);
        assertTrue(session.disconnected.await(20, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, session.terminateDebuggee);
    }

    @Test
    void aLaunchJsonChromeConfigurationIsABrowserLaunch() throws Exception {
        open(script);
        Path launchJson = Files.createDirectories(site.resolve(".vscode")).resolve("launch.json");
        Files.writeString(launchJson, """
                {"configurations": [
                  {"name": "Edge page", "type": "msedge", "request": "launch", "url": "http://localhost:4200",
                   "presentation": {"hidden": false}, "webRoot": "${workspaceFolder}/src"}
                ]}
                """);
        LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
        EditorBuffer js = call(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                launchJson, site, parsed, parsed.configurations().getFirst(), js));
        Map<String, Object> launch = session("launch").launchArgs;
        assertEquals(BrowserLaunch.EDGE, launch.get("type"), launch.toString());
        assertEquals("http://localhost:4200", launch.get("url"));
        assertEquals(site + "/src", launch.get("webRoot"), "what the configuration says wins");
        assertEquals(fx.configDir.resolve("browser-debug").resolve("edge").toString(), launch.get("userDataDir"));
        assertFalse(launch.containsKey("presentation"), "VS Code's own fields are left out");
        assertEquals("Edge page", sessionName());
        Files.delete(launchJson);
    }
}
