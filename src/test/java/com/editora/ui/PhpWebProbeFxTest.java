package com.editora.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of PHP web apps and tests against the real PHP, Xdebug and vscode-php-debug, in a window: F5 on a
 * document root's {@code index.php} serves it on PHP's built-in server, and a request stops at a breakpoint; F5 on a
 * class file of a Laravel-shaped app serves the app through its router, and a request stops in the class; Debug Test
 * at Caret runs PHPUnit on the test at the caret alone; Debug: Listen for Xdebug stops in a script started outside
 * Editora. Run with {@code ./mvnw test -Dtest=PhpWebProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code php}
 * with Xdebug, {@code node}, vscode-php-debug, and {@code phpunit} on the PATH for the test case.
 */
@Tag("probe")
@Tag("fx")
class PhpWebProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final String INDEX = """
            <?php
            $who = $_GET['who'] ?? 'world';
            $greeting = "hello $who";
            echo $greeting, "\\n";
            """;

    @Test
    void f5OnAnIndexServesItAndARequestStops(@TempDir Path dir) throws Exception {
        String configured = PhpDebugProbeFxTest.assumePhpXdebugAndAdapter();
        Path site = Files.createDirectories(dir.resolve("site/public")).toRealPath();
        Path index = Files.writeString(site.resolve("index.php"), INDEX);
        Window w = open(dir, configured, index);
        try {
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(2)); // $greeting = …
            run(w, "debug.start");
            String url = awaitServing(w);
            CompletableFuture<String> reply = get(url + "/?who=probe");
            awaitStop(w, 2);
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(2));
            FxTestSupport.runOnFx(w.dap::resume);
            assertEquals(
                    "hello probe",
                    reply.get(60, java.util.concurrent.TimeUnit.SECONDS).strip());
            // A second request: served by the same server, a thread of the same session.
            assertEquals(
                    "hello again",
                    get(url + "/?who=again")
                            .get(60, java.util.concurrent.TimeUnit.SECONDS)
                            .strip());
        } finally {
            w.close();
        }
    }

    @Test
    void f5OnAClassOfALaravelShapedAppServesTheAppThroughItsRouter(@TempDir Path dir) throws Exception {
        String configured = PhpDebugProbeFxTest.assumePhpXdebugAndAdapter();
        Path app = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(app.resolve("artisan"), "#!/usr/bin/env php\n<?php\necho \"artisan\\n\";\n");
        Files.createDirectories(app.resolve("public"));
        Files.createDirectories(app.resolve("app"));
        Files.writeString(
                app.resolve("public/index.php"),
                "<?php\nrequire __DIR__ . '/../app/Greeter.php';\necho (new App\\Greeter())->greet($_GET['who'] ??"
                        + " 'world'), \"\\n\";\n");
        // An app's own router, as Laravel 10 and older have: what artisan serve runs php -S with.
        Files.writeString(app.resolve("server.php"), "<?php\nrequire __DIR__ . '/public/index.php';\n");
        Path greeter = Files.writeString(app.resolve("app/Greeter.php"), """
                <?php
                namespace App;

                final class Greeter
                {
                    public function greet(string $who): string
                    {
                        $line = "hi $who";
                        return $line;
                    }
                }
                """);
        Window w = open(dir, configured, greeter);
        try {
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(8)); // return $line;
            run(w, "debug.start");
            String url = awaitServing(w);
            CompletableFuture<String> reply = get(url + "/?who=shop");
            awaitStop(w, 8);
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(8));
            FxTestSupport.runOnFx(w.dap::resume);
            assertEquals(
                    "hi shop",
                    reply.get(60, java.util.concurrent.TimeUnit.SECONDS).strip());
        } finally {
            w.close();
        }
    }

    @Test
    void debugTestAtCaretRunsThatTestAlone(@TempDir Path dir) throws Exception {
        String configured = PhpDebugProbeFxTest.assumePhpXdebugAndAdapter();
        assumeTrue(
                Path.of(ProcessRunner.resolveExecutable(List.of("phpunit")).getFirst())
                        .isAbsolute(),
                "needs phpunit");
        Path project = Files.createDirectories(dir.resolve("calc")).toRealPath();
        Files.writeString(project.resolve("phpunit.xml"), "<phpunit/>\n");
        Files.createDirectories(project.resolve("tests"));
        Path test = Files.writeString(project.resolve("tests/CalcTest.php"), """
                <?php
                use PHPUnit\\Framework\\TestCase;

                final class CalcTest extends TestCase
                {
                    public function testAdds(): void
                    {
                        $sum = 2 + 2;
                        $this->assertSame(4, $sum);
                    }

                    public function testAddsMore(): void
                    {
                        $sum = 2 + 3;
                        $this->assertSame(5, $sum);
                    }
                }
                """);
        Window w = open(dir, configured, test);
        try {
            FxTestSupport.runOnFx(() -> {
                w.b.toggleBreakpoint(8); // testAdds: assertSame
                w.b.toggleBreakpoint(14); // testAddsMore: assertSame
                w.b.getFocusedArea().moveTo(7, 0); // in testAdds
            });
            FxTestSupport.runOnFx(() -> w.c.testNavigation.runTestAtCaret(false, true));
            awaitStop(w, 8);
            FxTestSupport.runOnFx(w.dap::resume);
            await("PHPUnit's end", 120, () -> !call(w.dap::isActive));
            String console = call(
                    () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(w.debug.panel(), "console")).getText());
            assertTrue(
                    console.contains("OK (1 test, 1 assertion)"),
                    "testAdds ran alone, never stopping in the other: " + console);
        } finally {
            w.close();
        }
    }

    @Test
    void listenForXdebugStopsInAScriptStartedOutside(@TempDir Path dir) throws Exception {
        String configured = PhpDebugProbeFxTest.assumePhpXdebugAndAdapter();
        assumeTrue(PhpDebugLaunch.portFree(com.editora.dap.ScriptLaunch.XDEBUG_PORT), "needs Xdebug's port free");
        Path script = Files.writeString(
                Files.createDirectories(dir.resolve("cli")).toRealPath().resolve("job.php"), """
                        <?php
                        $count = 3;
                        $done = $count * 2;
                        echo "done $done\\n";
                        """);
        Window w = open(dir, configured, script);
        Process php = null;
        try {
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(2));
            run(w, "debug.listenXdebug");
            await("listening", 60, () -> call(w.dap::isActive) && !PhpDebugLaunch.portFree(9003));
            php = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of(
                            "php",
                            "-dxdebug.mode=debug",
                            "-dxdebug.start_with_request=yes",
                            "-dxdebug.client_port=9003",
                            script.toString())))
                    .redirectErrorStream(true)
                    .start();
            awaitStop(w, 2);
            FxTestSupport.runOnFx(w.dap::resume);
            Process started = php;
            await("the script's end", 60, () -> !started.isAlive());
            assertTrue(new String(started.getInputStream().readAllBytes()).contains("done 6"));
            assertTrue(call(w.dap::isActive), "still listening for the next one");
            FxTestSupport.runOnFx(w.dap::stop);
            await("stopped", 30, () -> !call(w.dap::isActive));
        } finally {
            if (php != null) {
                php.destroyForcibly();
            }
            w.close();
        }
    }

    @Test
    void runFileRunsAScriptAndATestFilesTests(@TempDir Path dir) throws Exception {
        String configured = PhpDebugProbeFxTest.assumePhpXdebugAndAdapter();
        Path project = Files.createDirectories(dir.resolve("tool")).toRealPath();
        Path script = Files.writeString(project.resolve("job.php"), "<?php\n$n = 6 * 7;\necho \"answer $n\\n\";\n");
        Window w = open(dir, configured, script);
        try {
            await("a ▶ for the script", 30, () -> call(w.b::isRunnable));
            run(w, "file.run");
            await("the script's output", 60, () -> runOutput(w).contains("answer 42"));
            boolean phpunit = Path.of(
                            ProcessRunner.resolveExecutable(List.of("phpunit")).getFirst())
                    .isAbsolute();
            if (phpunit) {
                Files.writeString(project.resolve("phpunit.xml"), "<phpunit/>\n");
                Path test = Files.writeString(
                        project.resolve("JobTest.php"),
                        "<?php\nfinal class JobTest extends \\PHPUnit\\Framework\\TestCase\n{\n"
                                + "    public function testAnswer(): void { $this->assertSame(42, 6 * 7); }\n}\n");
                FxTestSupport.runOnFx(() -> w.c.fileWorkflows.openPath(test));
                await(
                        "the test file in front",
                        30,
                        () -> call(() -> w.c.activeBuffer() != null
                                && test.equals(w.c.activeBuffer().getPath())
                                && w.c.activeBuffer().isRunnable()));
                await("the run done", 60, () -> !call(() -> w.c.runCoordinator.isRunning()));
                run(w, "file.run");
                await("PHPUnit's report", 120, () -> runOutput(w).contains("OK (1 test, 1 assertion)"));
            }
        } finally {
            w.close();
        }
    }

    private static String runOutput(Window w) {
        return call(() ->
                ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(w.c.runCoordinator.panel(), "output")).getText());
    }

    private record Window(
            FxWindowFixture fx, MainController c, DebugCoordinator debug, DapManager dap, EditorBuffer b) {
        void close() throws Exception {
            FxTestSupport.runOnFx(dap::stop);
            fx.dispose();
        }
    }

    private static Window open(Path dir, String configured, Path file) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setPhpDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        MainController c = fx.controller;
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        await("PHP debugging available", 60, () -> call(() -> debug.debugEffectiveFor("php")));
        EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
        return new Window(fx, c, debug, dap, b);
    }

    private static void run(Window w, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run(command));
    }

    /** The address the session's server says it serves, once it listens. */
    private static String awaitServing(Window w) throws InterruptedException {
        String[] url = new String[1];
        await("the server's address", 60, () -> {
            String console = call(
                    () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(w.debug.panel(), "console")).getText());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((http://127\\.0\\.0\\.1:\\d+)\\) started")
                    .matcher(console);
            if (m.find()) {
                url[0] = m.group(1);
                return true;
            }
            return false;
        });
        return url[0];
    }

    /** A GET of {@code url} on a client of its own (a breakpoint holds the reply until the session goes on). */
    private static CompletableFuture<String> get(String url) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return client.sendAsync(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(120))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
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
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }

    private static void awaitStop(Window w, int line) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (line + 1),
                    120,
                    () -> call(() -> w.dap.state() == DapManager.State.SUSPENDED
                            && !w.debug.panel().callStack().isEmpty()
                            && w.debug.panel().callStack().get(0).line() == line));
        } catch (AssertionError e) {
            String top = call(() -> w.debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : w.debug.panel().callStack().get(0).name() + " at "
                            + (w.debug.panel().callStack().get(0).line() + 1));
            String console = call(
                    () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(w.debug.panel(), "console")).getText());
            throw new AssertionError(
                    e.getMessage() + " (" + call(w.dap::state) + ", " + top + ")\nconsole:\n" + console, e);
        }
    }
}
