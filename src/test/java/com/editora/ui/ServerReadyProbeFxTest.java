package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import com.editora.web.Browsers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of launch.json's {@code serverReadyAction} against the installed vscode-js-debug, Node and a real
 * Chrome (or another Chromium browser), headless: a Node server debugged from launch.json prints VS Code's
 * "listening on port N"; {@code debugWithChrome} opens that address in the browser under the debugger, in a session
 * beside the server's, and a breakpoint in the page's script stops there; stopping the server ends the browser's
 * session ({@code killOnServerStop}) and runs the {@code postDebugTask}. Run it with {@code ./mvnw test
 * -Dtest=ServerReadyProbeFxTest -Dgroups=probe -Dlsp.probe=true -Dnode.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ServerReadyProbeFxTest {

    private static final String SERVER = """
            const http = require('http');
            const fs = require('fs');
            const path = require('path');
            const server = http.createServer((req, res) => {
              const file = req.url === '/app.js' ? 'app.js' : 'index.html';
              res.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : 'text/html');
              res.end(fs.readFileSync(path.join(__dirname, file)));
            });
            server.listen(0, '127.0.0.1', () => console.log('listening on port ' + server.address().port));
            """;

    private static final String PAGE = """
            <!doctype html>
            <html><body><script src="app.js"></script></body></html>
            """;

    private static final String SCRIPT = """
            let count = 0;
            function tick(n) {
              const doubled = n * 2;
              document.title = 'tick ' + doubled;
            }
            setInterval(() => tick(++count), 300);
            """;

    /** {@code const doubled = n * 2;}, 0-based. */
    private static final int SCRIPT_LINE = 2;

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @BeforeAll
    static void boot(@TempDir Path profile) throws Exception {
        FxTestSupport.bootToolkit();
        // As BrowserDebugProbeFxTest: the browser's first start on a fresh runner can outlast what js-debug waits for a
        // debugged browser, and js-debug then drops the launch without a word. CI failed so when this probe ran first.
        String id = com.editora.dap.BrowserLaunch.pick(
                Browsers.detect().stream().map(Browsers.Browser::id).toList(), null, null);
        if (optedIn() && id != null && Browsers.executable(id) != null) {
            BrowserDebugProbeFxTest.firstStart(Browsers.executable(id), profile);
        }
    }

    private static boolean optedIn() {
        return Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe");
    }

    private static String browser() {
        String id = com.editora.dap.BrowserLaunch.pick(
                Browsers.detect().stream().map(Browsers.Browser::id).toList(), null, null);
        assumeTrue(id != null && Browsers.executable(id) != null, "needs Chrome, Edge or another Chromium browser");
        return id;
    }

    @Test
    void theServersPortOpensThePageInChromeUnderTheDebugger(@TempDir Path dir) throws Exception {
        assumeTrue(optedIn(), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        String browser = browser();
        Path root = Files.createDirectory(dir.resolve("web")).toRealPath();
        Files.writeString(root.resolve("server.js"), SERVER);
        Files.writeString(root.resolve("index.html"), PAGE);
        Path script = Files.writeString(root.resolve("app.js"), SCRIPT);
        Files.createDirectories(root.resolve(".vscode"));
        String action = browser.equals("edge") ? "debugWithEdge" : "debugWithChrome";
        Path launchJson = Files.writeString(root.resolve(".vscode/launch.json"), """
                {
                  "version": "0.2.0",
                  "configurations": [
                    {
                      "name": "Server",
                      "type": "node",
                      "request": "launch",
                      "program": "${workspaceFolder}/server.js",
                      "postDebugTask": "cleanup",
                      "serverReadyAction": { "action": "%s", "killOnServerStop": true }
                    }
                  ]
                }
                """.formatted(action));
        Files.writeString(root.resolve(".vscode/tasks.json"), """
                { "tasks": [ { "label": "cleanup", "type": "process", "command": "node",
                               "args": ["-e", "require('fs').writeFileSync('cleaned', '')"] } ] }
                """);

        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setDebugBrowser(browser);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(script, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            fx.shared.getTrustStore().trust(root); // its postDebugTask runs only in a trusted folder
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            debug.browser.browserArgs = List.of("--headless=new", "--no-sandbox");
            EditorBuffer js = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> js.toggleBreakpoint(SCRIPT_LINE));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                    launchJson, root, parsed, parsed.configurations().getFirst(), js));
            await(
                    "the server running",
                    60,
                    () -> call(() -> debug.sessions().size() >= 1
                            && debug.sessions().all().getFirst().dap.state() == DapManager.State.RUNNING));
            DebugSession server = call(() -> debug.sessions().all().getFirst());

            await("the browser's session", 90, () -> call(() -> debug.sessions().size()) == 2);
            DebugSession page = call(() -> debug.sessions().all().getLast());
            assertNotSame(server, page);
            await(
                    "a stop in the page's script",
                    90,
                    () -> call(() -> page.dap.state() == DapManager.State.SUSPENDED
                            && !page.panel.callStack().isEmpty()
                            && page.panel.callStack().getFirst().line() == SCRIPT_LINE),
                    () -> console(server) + "\n---\n" + console(page));
            assertTrue(console(server).contains("http://localhost:"), console(server));
            assertEquals(browser.equals("edge") ? "Edge" : "Chrome", call(() -> page.sessionLabel));

            FxTestSupport.runOnFx(() -> debug.inSession(server, debug::stop));
            await("the server ended", 30, () -> !call(server.dap::isActive));
            await("killOnServerStop ended the browser's session", 30, () -> !call(page.dap::isActive));
            await("the postDebugTask ran", 30, () -> Files.exists(root.resolve("cleaned")));
        } finally {
            fx.dispose();
        }
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

    private static String console(DebugSession session) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(session.panel, "console")
                .getText());
    }

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }

    private static void await(
            String what, int seconds, BooleanSupplier condition, java.util.function.Supplier<String> why)
            throws InterruptedException {
        try {
            await(what, seconds, condition);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + why.get(), e);
        }
    }
}
