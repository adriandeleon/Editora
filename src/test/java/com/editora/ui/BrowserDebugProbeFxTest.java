package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import com.editora.web.Browsers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the installed vscode-js-debug and a real Chrome (or another Chromium browser), headless, in a
 * real window. F5 on an HTML file serves its site and opens it in the browser: a breakpoint in the page's inline script
 * stops with its locals, then one in a script file it loads; Stop closes the browser. A launch.json {@code chrome}
 * configuration opens a {@code file:} URL and stops at a breakpoint in its script. Run it with {@code ./mvnw test
 * -Dtest=BrowserDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true -Dnode.probe=true}.
 *
 * <p>The browser is started once before the tests ({@link #firstStart}): its first start on a machine, with nothing
 * of it in memory, can take longer than a debugged browser is given to be ready, and how long that takes is the
 * machine's, not what is probed here. On CI it sometimes passed js-debug's own 10 seconds, js-debug dropped the
 * launch without a word, and the test that ran first failed.
 */
@Tag("probe")
@Tag("fx")
class BrowserDebugProbeFxTest {

    private static final String PAGE = """
            <!doctype html>
            <html><body>
            <script>
              window.greet = function (name) {
                const message = 'hi ' + name;
                return message;
              };
            </script>
            <script src="app.js"></script>
            </body></html>
            """;

    /** {@code const message = …}, 0-based. */
    private static final int PAGE_LINE = 4;

    private static final String SCRIPT = """
            let count = 0;
            function tick(n) {
              const doubled = n * 2;
              document.title = window.greet('tick ' + doubled);
            }
            setInterval(() => tick(++count), 300);
            """;

    /** {@code const doubled = n * 2;}, 0-based. */
    private static final int SCRIPT_LINE = 2;

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @BeforeAll
    static void boot(@TempDir Path profile) throws Exception {
        FxTestSupport.bootToolkit();
        String id = found();
        if (optedIn() && id != null && Browsers.executable(id) != null) {
            firstStart(Browsers.executable(id), profile);
        }
    }

    private static boolean optedIn() {
        return Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe");
    }

    /** The Chromium browser the tests debug in, or null when there is none. */
    private static String found() {
        return com.editora.dap.BrowserLaunch.pick(
                Browsers.detect().stream().map(Browsers.Browser::id).toList(), null, null);
    }

    /**
     * Starts the browser, headless, on an empty page and waits for it to exit, so that the starts under the debugger
     * are not the machine's first. Says how long it took: the number to look at when a launch is given up on.
     */
    private static void firstStart(String executable, Path profile) throws InterruptedException {
        long start = System.nanoTime();
        String outcome;
        try {
            Process browser = new ProcessBuilder(
                            executable,
                            "--headless=new",
                            "--no-sandbox",
                            "--no-first-run",
                            "--user-data-dir=" + profile,
                            "--dump-dom",
                            "about:blank")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (browser.waitFor(FIRST_START_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                outcome = "exit code " + browser.exitValue();
            } else {
                browser.destroyForcibly();
                outcome = "still running, ended";
            }
        } catch (java.io.IOException e) {
            outcome = "not started: " + e.getMessage(); // the tests' own launches say what is wrong with it
        }
        System.out.printf(
                "BrowserDebugProbeFxTest: the browser's first start took %.1f s (%s)%n",
                (System.nanoTime() - start) / 1e9, outcome);
    }

    /** How long the browser's first start may take before it is ended and the tests go on anyway. */
    private static final long FIRST_START_SECONDS = 120;

    /** A Chromium browser found here, or the test is skipped. */
    private static String browser() {
        String id = found();
        assumeTrue(id != null && Browsers.executable(id) != null, "needs Chrome, Edge or another Chromium browser");
        return id;
    }

    private static FxWindowFixture window(Path dir, String browser, Path... open) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setDebugBrowser(browser);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(open)
                .map(p -> new MainController.OpenTarget(p, 0, 0))
                .toList();
        return FxWindowFixture.create(configDir, false, false, false, targets, true, c -> {});
    }

    @Test
    void f5OnAnHtmlFileStopsInItsInlineScriptAndInAScriptItLoads(@TempDir Path dir) throws Exception {
        assumeTrue(optedIn(), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        String browser = browser();
        Path site = Files.createDirectory(dir.resolve("site")).toRealPath();
        Path script = Files.writeString(site.resolve("app.js"), SCRIPT);
        Path page = Files.writeString(site.resolve("index.html"), PAGE);

        FxWindowFixture fx = window(dir, browser, script, page);
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            debug.browserArgs = List.of("--headless=new", "--no-sandbox");
            EditorBuffer html = FxTestSupport.callOnFx(c::activeBuffer);
            assertTrue(call(html::isHtml));
            assertTrue(call(() -> debug.isDebuggableBuffer(html)), "an HTML file takes breakpoints");
            FxTestSupport.runOnFx(() -> html.toggleBreakpoint(PAGE_LINE));

            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            awaitStop(c, dap, debug, PAGE_LINE, "index.html");
            awaitRow(debug, "name", null);
            assertEquals(Browsers.knownName(browser) + ": index.html", call(() ->
                    (String) FxTestSupport.field(debug.panel(), "sessionFile")));

            // Now the script file's breakpoint only: the next tick stops there.
            EditorBuffer js = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<DebugCoordinator.Ops>field(debug, "ops").bufferForPath(script));
            FxTestSupport.runOnFx(() -> {
                html.toggleBreakpoint(PAGE_LINE);
                js.toggleBreakpoint(SCRIPT_LINE);
            });
            resume(dap, debug);
            awaitStop(c, dap, debug, SCRIPT_LINE, "app.js");
            awaitRow(debug, "n", null);

            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aLaunchJsonChromeConfigurationOpensAFileUrl(@TempDir Path dir) throws Exception {
        assumeTrue(optedIn(), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        String browser = browser();
        Path root = Files.createDirectory(dir.resolve("web")).toRealPath();
        Path script = Files.writeString(root.resolve("app.js"), SCRIPT);
        Files.writeString(root.resolve("index.html"), PAGE);
        Files.createDirectories(root.resolve(".vscode"));
        String type = browser.equals("edge") ? "msedge" : "chrome";
        Path launchJson = Files.writeString(root.resolve(".vscode/launch.json"), """
                {
                  "version": "0.2.0",
                  "configurations": [
                    {
                      "name": "Page in the browser",
                      "type": "%s",
                      "request": "launch",
                      "file": "${workspaceFolder}/index.html",
                      "runtimeExecutable": "%s",
                      "runtimeArgs": ["--headless=new", "--no-sandbox"]
                    }
                  ]
                }
                """.formatted(
                        type, Browsers.executable(browser).replace("\\", "\\\\")));

        FxWindowFixture fx = window(dir, browser, script);
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            EditorBuffer js = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> js.toggleBreakpoint(SCRIPT_LINE));
            LaunchJson.File parsed = LaunchJson.parse(Files.readString(launchJson));
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(
                    launchJson, root, parsed, parsed.configurations().getFirst(), js));
            awaitStop(c, dap, debug, SCRIPT_LINE, "app.js");
            awaitRow(debug, "n", null);
            assertEquals("Page in the browser", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    /** A stop at {@code line} of {@code file} (the top frame's). */
    private static void awaitStop(MainController c, DapManager dap, DebugCoordinator debug, int line, String file)
            throws InterruptedException {
        String what = "a stop at " + file + ":" + (line + 1);
        boolean[] live = {false};
        try {
            await(what, 90, () -> {
                // A session that has ended stops nowhere: say so now rather than wait the rest out.
                DapManager.State state = call(dap::state);
                if (live[0] && state == DapManager.State.INACTIVE) {
                    throw new AssertionError("the session ended before " + what);
                }
                live[0] |= state != DapManager.State.INACTIVE;
                return call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().get(0).line() == line
                        && debug.panel().callStack().get(0).file() != null
                        && debug.panel().callStack().get(0).file().endsWith(file));
            });
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().get(0).name() + " at "
                            + debug.panel().callStack().get(0).file() + ":"
                            + (debug.panel().callStack().get(0).line() + 1));
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            throw new AssertionError(
                    e.getMessage() + " (" + call(dap::state) + ", " + top + ")\nstatus messages (newest first): "
                            + call(() -> status(c)) + "\noverlay: " + call(() -> overlay(c)) + "\n" + console,
                    e);
        }
    }

    /** Every message the status line showed, newest first (a launch's error can be overwritten). FX thread. */
    private static String status(MainController c) {
        MessageLog log = FxTestSupport.field(FxTestSupport.field(c, "statusBar"), "messageLog");
        return String.join(
                " | ", log.entries().stream().map(MessageLog.Entry::text).toList());
    }

    /** The texts of what the overlay shows (a question the launch waits on), or "none". FX thread. */
    private static String overlay(MainController c) {
        if (!OverlayDriver.showing(c)) {
            return "none";
        }
        List<String> texts = new java.util.ArrayList<>();
        OverlayDriver.card(c).lookupAll(".label").forEach(n -> {
            if (n instanceof javafx.scene.control.Labeled l
                    && l.getText() != null
                    && !l.getText().isBlank()) {
                texts.add(l.getText());
            }
        });
        return String.join(" | ", texts);
    }

    /** Resumes, and forgets the tree shown so far: a row is looked for only in the next stop's tree. */
    private static void resume(DapManager dap, DebugCoordinator debug) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            staleRoot = tree.getRoot();
            dap.resume();
        });
    }

    private static TreeItem<DebugPanel.VarRow> staleRoot;

    private static TreeItem<DebugPanel.VarRow> row(DebugPanel panel, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
        if (tree.getRoot() == null || tree.getRoot() == staleRoot) {
            return null;
        }
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** Waits for local {@code name} in the Variables tree, with {@code value} (null: any). */
    private static void awaitRow(DebugCoordinator debug, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                30,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> row = row(debug.panel(), name);
                    return row != null
                            && (value == null || value.equals(row.getValue().value()));
                }));
    }
}
