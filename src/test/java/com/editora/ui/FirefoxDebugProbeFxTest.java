package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugLanguages;
import com.editora.dap.FirefoxLaunch;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import com.editora.web.Browsers;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of debugging in Firefox through the real vscode-firefox-debug, in a window, Firefox headless: with
 * Firefox the Debugging setting's browser, F5 on an HTML file serves it and stops in a script it loads; a launch.json
 * {@code firefox} configuration opens a page and stops; Attach to Browser joins a Firefox started with its debugger
 * server. Run with {@code ./mvnw test -Dtest=FirefoxDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs Firefox,
 * Node.js and vscode-firefox-debug (Editora's install, or {@code -Dfirefoxdebug.path=…}).
 */
@Tag("probe")
@Tag("fx")
class FirefoxDebugProbeFxTest {

    private static final String PAGE = """
            <!doctype html>
            <html><body><h1>shelf</h1><script src="app.js"></script></body></html>
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

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** Firefox's program; the test skipped without it, the adapter or the opt-in. */
    private static String firefox() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String exe = Browsers.executable(FirefoxLaunch.TYPE);
        assumeTrue(exe != null, "needs Firefox");
        assumeTrue(
                com.editora.dap.DapManager.locateExternal(
                                DebugLanguages.FIREFOX, System.getProperty("firefoxdebug.path", ""))
                        != null,
                "needs vscode-firefox-debug");
        warmUp(exe);
        return exe;
    }

    private static boolean warmedUp;

    /**
     * Starts Firefox once, before the first test's debug launch, and waits for it to load a {@code file:} page and quit:
     * Firefox's first start on a fresh CI runner (nothing of it in the page cache yet) took 10 s to accept the adapter's
     * connection where a later one takes 1-2 s, and in that cold launch the page's tab was never reported to the adapter
     * — only the extension pages and about:newtab — so its breakpoint never bound and the first test (always the
     * launch.json one) timed out. The probe tests debugging, not how fast a cold Firefox starts.
     */
    private static synchronized void warmUp(String exe) {
        if (warmedUp) {
            return;
        }
        warmedUp = true;
        try {
            Path dir = Files.createTempDirectory("firefox-warm-up");
            Path profile = Files.createDirectory(dir.resolve("profile"));
            Path page = Files.writeString(dir.resolve("warm.html"), "<!doctype html><p>warm</p>");
            Process firefox = new ProcessBuilder(
                            exe,
                            "-headless",
                            "-no-remote",
                            "-profile",
                            profile.toString(),
                            "-screenshot",
                            dir.resolve("warm.png").toString(),
                            page.toUri().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                if (!firefox.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("FirefoxDebugProbeFxTest: the warm-up Firefox did not quit in 90 s");
                }
            } finally {
                firefox.descendants().forEach(ProcessHandle::destroyForcibly);
                firefox.destroyForcibly();
                firefox.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                try (var paths = Files.walk(dir)) {
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
        } catch (java.io.IOException e) {
            System.err.println("FirefoxDebugProbeFxTest: no warm-up Firefox: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static FxWindowFixture window(Path dir, Path... open) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setDebugBrowser(FirefoxLaunch.TYPE);
        seed.getSettings().setFirefoxDebugPath(System.getProperty("firefoxdebug.path", ""));
        assertTrue(seed.saveSettings());
        seed.shutdown();
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(open)
                .map(p -> new MainController.OpenTarget(p, 0, 0))
                .toList();
        return FxWindowFixture.create(configDir, false, false, false, targets, true, c -> {});
    }

    private record Site(Path root, Path script, Path page) {}

    private static Site site(Path dir) throws Exception {
        Path root = Files.createDirectory(dir.resolve("site")).toRealPath();
        return new Site(
                root,
                Files.writeString(root.resolve("app.js"), SCRIPT),
                Files.writeString(root.resolve("index.html"), PAGE));
    }

    @Test
    void f5OnAnHtmlFileServesItInFirefoxAndStopsInItsScript(@TempDir Path dir) throws Exception {
        firefox();
        Site site = site(dir);
        FxWindowFixture fx = window(dir, site.script(), site.page());
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            await("Firefox's debugger found", 60, () -> call(() -> dap.isExternalLocated(DebugLanguages.FIREFOX)));
            debug.browser.firefoxArgs = List.of("-headless");
            Path log = dir.resolve("adapter.log");
            debug.browser.firefoxOptions =
                    Map.of("log", Map.of("fileName", log.toString(), "fileLevel", Map.of("default", "Debug")));
            EditorBuffer html = FxTestSupport.callOnFx(c::activeBuffer);
            EditorBuffer js = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<DebugCoordinator.Ops>field(debug, "ops").bufferForPath(site.script()));
            FxTestSupport.runOnFx(() -> js.toggleBreakpoint(SCRIPT_LINE));
            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(site.page()));
            await("the page in front", 30, () -> call(() -> c.activeBuffer() == html));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(c, dap, debug, SCRIPT_LINE, "app.js", log);
            assertEquals("Firefox: index.html", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aLaunchJsonFirefoxConfigurationOpensAPage(@TempDir Path dir) throws Exception {
        String exe = firefox();
        Site site = site(dir);
        Files.createDirectories(site.root().resolve(".vscode"));
        Path launchJson = Files.writeString(site.root().resolve(".vscode/launch.json"), """
                {
                  "version": "0.2.0",
                  "configurations": [
                    {
                      "name": "Shelf in Firefox",
                      "type": "firefox",
                      "request": "launch",
                      "file": "${workspaceFolder}/index.html",
                      "firefoxExecutable": "%s",
                      "firefoxArgs": ["-headless"],
                      "log": {"fileName": "%s", "fileLevel": {"default": "Debug"}}
                    }
                  ]
                }
                """.formatted(
                exe.replace("\\", "\\\\"), dir.resolve("adapter.log").toString().replace("\\", "\\\\")));
        FxWindowFixture fx = window(dir, site.script());
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("Firefox's debugger found", 60, () -> call(() -> dap.isExternalLocated(DebugLanguages.FIREFOX)));
            EditorBuffer js = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> js.toggleBreakpoint(SCRIPT_LINE));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> debug.launchJson.runLaunchConfiguration(
                    launchJson, site.root(), parsed, parsed.configurations().getFirst(), js));
            awaitStop(c, dap, debug, SCRIPT_LINE, "app.js", dir.resolve("adapter.log"));
            assertEquals("Shelf in Firefox", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void attachToBrowserJoinsAFirefoxStartedWithItsDebuggerServer(@TempDir Path dir) throws Exception {
        String exe = firefox();
        Site site = site(dir);
        int port = freePort();
        Path profile = Files.createDirectory(dir.resolve("profile"));
        // Remote debugging allowed without the prompt, as a user sets it up (about:config) to attach.
        Files.writeString(profile.resolve("user.js"), """
                user_pref("devtools.debugger.remote-enabled", true);
                user_pref("devtools.chrome.enabled", true);
                user_pref("devtools.debugger.prompt-connection", false);
                """);
        Process firefox = new ProcessBuilder(
                        exe,
                        "-headless",
                        "-no-remote",
                        "-profile",
                        profile.toString(),
                        "-start-debugger-server",
                        String.valueOf(port),
                        site.page().toUri().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        FxWindowFixture fx = window(dir, site.script());
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            await("Firefox's debugger found", 60, () -> call(() -> dap.isExternalLocated(DebugLanguages.FIREFOX)));
            await("Firefox's debugger server", 60, () -> !portFree(port));
            EditorBuffer js = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> js.toggleBreakpoint(SCRIPT_LINE));
            FxTestSupport.runOnFx(() -> ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry"))
                    .run("debug.attachBrowser"));
            await("the prompt", 30, () -> call(() -> OverlayDriver.showing(c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(c, "127.0.0.1:" + port));
            awaitStop(c, dap, debug, SCRIPT_LINE, "app.js", null);
            FxTestSupport.runOnFx(dap::stop);
            await("detached", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
            firefox.destroyForcibly();
            firefox.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static int freePort() throws java.io.IOException {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean portFree(int port) {
        try (var socket = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            return true;
        } catch (java.io.IOException e) {
            return false;
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }

    /**
     * A stop at {@code line} of {@code file} (the top frame's); on none, what was going on: the status line's messages,
     * the Firefox and Electron processes running, and the end of the adapter's own log ({@code log}, when it keeps one).
     */
    private static void awaitStop(
            MainController c, DapManager dap, DebugCoordinator debug, int line, String file, @Nullable Path log)
            throws InterruptedException {
        try {
            await(
                    "a stop at " + file + ":" + (line + 1),
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == line
                            && debug.panel().callStack().get(0).file() != null
                            && debug.panel().callStack().get(0).file().endsWith(file)));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + debug.panel().callStack().get(0).file() + ":"
                            + (debug.panel().callStack().get(0).line() + 1));
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            String status = call(() -> {
                MessageLog messages = FxTestSupport.field(FxTestSupport.field(c, "statusBar"), "messageLog");
                return String.join(
                        " | ",
                        messages.entries().stream().map(MessageLog.Entry::text).toList());
            });
            String browsers = String.join(
                    "\n",
                    ProcessHandle.allProcesses()
                            .map(p -> p.info().commandLine().orElse(""))
                            .filter(cmd -> cmd.contains("firefox") || cmd.contains("electron"))
                            .map(cmd -> cmd.length() > 300 ? cmd.substring(0, 300) : cmd)
                            .toList());
            String adapter = "";
            if (log != null && Files.isRegularFile(log)) {
                try {
                    List<String> lines = Files.readAllLines(log);
                    adapter = String.join("\n", lines.subList(Math.max(0, lines.size() - 150), lines.size()));
                } catch (java.io.IOException io) {
                    adapter = "(adapter log unreadable: " + io + ")";
                }
            }
            throw new AssertionError(
                    e.getMessage() + " (" + call(dap::state) + ", " + top + ")\nconsole:\n" + console + "\nstatus: "
                            + status + "\nprocesses:\n" + browsers + "\nadapter log:\n" + adapter,
                    e);
        }
    }
}
