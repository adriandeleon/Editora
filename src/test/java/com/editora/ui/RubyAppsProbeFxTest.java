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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Ruby's tests and Rails under the real rdbg, in a window: Debug Test at Caret in an RSpec file runs the
 * example at the caret alone and stops in it; in a plain Minitest file, the test at the caret by its name; in a Rails
 * app's controller test, the test at the caret's line, stopping in the controller; F5 in a Rails controller serves the
 * app and a request stops in its action. The Rails app is a copy of {@code -Drails.app=<a Rails app with its bundle
 * installed>}, else {@code ~/.cache/editora/rails-probe}. Run with {@code ./mvnw test -Dtest=RubyAppsProbeFxTest
 * -Dgroups=probe -Dlsp.probe=true}; needs {@code ruby}, {@code rdbg} and {@code rspec}, and Rails for the Rails cases.
 */
@Tag("probe")
@Tag("fx")
class RubyAppsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final String SPEC = """
            RSpec.describe "calc" do
              it "adds" do
                sum = 2 + 2
                expect(sum).to eq(4)
              end
              it "subtracts" do
                diff = 3 - 1
                expect(diff).to eq(2)
              end
            end
            """;

    private static final String MINITEST = """
            require "minitest/autorun"

            class CalcTest < Minitest::Test
              def test_adds
                sum = 2 + 2
                assert_equal 4, sum
              end

              def test_subtracts
                diff = 3 - 1
                assert_equal 2, diff
              end
            end
            """;

    private static final String CONTROLLER = """
            class HelloController < ApplicationController
              def index
                who = params[:who] || "world"
                render plain: "hello #{who}"
              end
            end
            """;

    private static final String CONTROLLER_TEST = """
            require "test_helper"

            class HelloControllerTest < ActionDispatch::IntegrationTest
              test "should get index" do
                get hello_index_url
                assert_response :success
              end

              test "greets by name" do
                get hello_index_url(who: "ada")
                assert_equal "hello ada", response.body
              end
            end
            """;

    /** {@code render plain: …}, 0-based. */
    private static final int ACTION_LINE = 3;

    private static void assumeRuby(String... tools) {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : tools) {
            assumeTrue(
                    Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of(tool))
                                    .getFirst())
                            .isAbsolute(),
                    "needs " + tool);
        }
    }

    /** A copy of the prepared Rails app, with its controller and test; the test skipped without it. */
    private static Path railsApp(Path dir) throws Exception {
        assumeRuby("ruby", "rdbg", "bundle");
        Path template = Path.of(System.getProperty(
                "rails.app",
                Path.of(System.getProperty("user.home"), ".cache/editora/rails-probe")
                        .toString()));
        assumeTrue(Files.isRegularFile(template.resolve("bin/rails")), "needs a Rails app at " + template);
        Path app = dir.resolve("shop");
        Process cp = new ProcessBuilder("cp", "-a", template.toString(), app.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(cp.waitFor(60, TimeUnit.SECONDS) && cp.exitValue() == 0, "the app copied");
        app = app.toRealPath();
        Files.writeString(app.resolve("app/controllers/hello_controller.rb"), CONTROLLER);
        Files.createDirectories(app.resolve("test/controllers"));
        Files.writeString(app.resolve("test/controllers/hello_controller_test.rb"), CONTROLLER_TEST);
        Path routes = app.resolve("config/routes.rb");
        String r = Files.readString(routes);
        if (!r.contains("hello/index")) {
            Files.writeString(routes, r.replaceFirst("(?m)^end\\s*$", "  get \"hello/index\"\nend\n"));
        }
        return app;
    }

    @Test
    void debugTestAtCaretInAnRspecFileRunsThatExampleAndStopsInIt(@TempDir Path dir) throws Exception {
        assumeRuby("ruby", "rdbg", "rspec");
        Path project =
                Files.createDirectories(dir.resolve("calc/spec")).toRealPath().getParent();
        Path spec = Files.writeString(project.resolve("spec/calc_spec.rb"), SPEC);
        Window w = open(dir, spec);
        try {
            FxTestSupport.runOnFx(() -> {
                w.b.toggleBreakpoint(3); // the first example's expect
                w.b.toggleBreakpoint(7); // the second's
                w.b.getFocusedArea().moveTo(6, 0); // in "subtracts"
            });
            FxTestSupport.runOnFx(() -> w.c.testNavigation.runTestAtCaret(false, true));
            awaitStop(w, 7, "calc_spec.rb");
            FxTestSupport.runOnFx(w.dap::resume);
            await("RSpec's end", 120, () -> !call(w.dap::isActive));
            assertTrue(console(w).contains("1 example, 0 failures"), "subtracts ran alone: " + console(w));
        } finally {
            w.close();
        }
    }

    @Test
    void debugTestAtCaretInAMinitestFileRunsThatTestByName(@TempDir Path dir) throws Exception {
        assumeRuby("ruby", "rdbg");
        Path project =
                Files.createDirectories(dir.resolve("calc/test")).toRealPath().getParent();
        Path test = Files.writeString(project.resolve("test/calc_test.rb"), MINITEST);
        Window w = open(dir, test);
        try {
            FxTestSupport.runOnFx(() -> {
                w.b.toggleBreakpoint(5);
                w.b.toggleBreakpoint(10);
                w.b.getFocusedArea().moveTo(4, 0); // in test_adds
            });
            FxTestSupport.runOnFx(() -> w.c.testNavigation.runTestAtCaret(false, true));
            awaitStop(w, 5, "calc_test.rb");
            FxTestSupport.runOnFx(w.dap::resume);
            await("Minitest's end", 120, () -> !call(w.dap::isActive));
            assertTrue(console(w).contains("1 runs, 1 assertions"), "test_adds ran alone: " + console(w));
        } finally {
            w.close();
        }
    }

    @Test
    void debugTestAtCaretInARailsTestStopsInTheController(@TempDir Path dir) throws Exception {
        Path app = railsApp(dir);
        Path test = app.resolve("test/controllers/hello_controller_test.rb");
        Path controller = app.resolve("app/controllers/hello_controller.rb");
        Window w = open(dir, controller, test);
        try {
            EditorBuffer c = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<DebugCoordinator.Ops>field(w.debug, "ops").bufferForPath(controller));
            FxTestSupport.runOnFx(() -> {
                c.toggleBreakpoint(ACTION_LINE);
                w.b.getFocusedArea().moveTo(9, 0); // in "greets by name"
            });
            FxTestSupport.runOnFx(() -> w.c.testNavigation.runTestAtCaret(false, true));
            awaitStop(w, ACTION_LINE, "hello_controller.rb");
            FxTestSupport.runOnFx(w.dap::resume);
            await("the test's end", 180, () -> !call(w.dap::isActive));
            assertTrue(console(w).contains("1 runs, 1 assertions"), "only that test ran: " + console(w));
        } finally {
            w.close();
        }
    }

    @Test
    void f5InARailsControllerServesTheAppAndARequestStops(@TempDir Path dir) throws Exception {
        Path app = railsApp(dir);
        Path controller = app.resolve("app/controllers/hello_controller.rb");
        Window w = open(dir, controller);
        try {
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(ACTION_LINE));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run("debug.start"));
            String url = awaitServing(w);
            CompletableFuture<String> reply = get(url + "/hello/index?who=probe");
            awaitStop(w, ACTION_LINE, "hello_controller.rb");
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(ACTION_LINE));
            FxTestSupport.runOnFx(w.dap::resume);
            assertEquals("hello probe", reply.get(120, TimeUnit.SECONDS));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run("debug.stop"));
            await("the server ended", 60, () -> !call(w.dap::isActive));
        } finally {
            w.close();
        }
    }

    /** The server's address: the status line names it once the server is started. */
    private static String awaitServing(Window w) throws InterruptedException {
        String[] url = new String[1];
        await("the server's address", 180, () -> {
            String console = console(w);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Listening on (http://127\\.0\\.0\\.1:\\d+)")
                    .matcher(console);
            if (m.find()) {
                url[0] = m.group(1);
                return true;
            }
            return false;
        });
        return url[0];
    }

    private static CompletableFuture<String> get(String url) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return client.sendAsync(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(150))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private record Window(
            FxWindowFixture fx, MainController c, DebugCoordinator debug, DapManager dap, EditorBuffer b) {
        void close() throws Exception {
            FxTestSupport.runOnFx(dap::stop);
            fx.dispose();
        }
    }

    /** A window with {@code files} open, the last in front; Ruby debugging on. */
    private static Window open(Path dir, Path... files) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(files)
                .map(p -> new MainController.OpenTarget(p, 0, 0))
                .toList();
        FxWindowFixture fx = FxWindowFixture.create(configDir, false, false, false, targets, true, c -> {});
        MainController c = fx.controller;
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        await("Ruby debugging available", 60, () -> call(() -> debug.debugEffectiveFor("ruby")));
        Path front = files[files.length - 1];
        FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(front));
        await(
                "the file in front",
                30,
                () -> call(() -> c.activeBuffer() != null
                        && front.equals(c.activeBuffer().getPath())));
        return new Window(fx, c, debug, dap, FxTestSupport.callOnFx(c::activeBuffer));
    }

    private static String console(Window w) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(w.debug.panel(), "console")
                .getText());
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

    private static void awaitStop(Window w, int line, String file) throws InterruptedException {
        try {
            await(
                    "a stop at " + file + ":" + (line + 1),
                    180,
                    () -> call(() -> w.dap.state() == DapManager.State.SUSPENDED
                            && !w.debug.panel().callStack().isEmpty()
                            && w.debug.panel().callStack().get(0).line() == line
                            && w.debug.panel().callStack().get(0).file() != null
                            && w.debug.panel().callStack().get(0).file().endsWith(file)));
        } catch (AssertionError e) {
            String top = call(() -> w.debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : w.debug.panel().callStack().get(0).name() + " at "
                            + w.debug.panel().callStack().get(0).file() + ":"
                            + (w.debug.panel().callStack().get(0).line() + 1));
            throw new AssertionError(e.getMessage() + " (" + call(w.dap::state) + ", " + top + ")\n" + console(w), e);
        }
    }
}
