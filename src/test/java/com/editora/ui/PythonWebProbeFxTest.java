package com.editora.ui;

import java.net.ServerSocket;
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
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Debug File on a web app against debugpy, in a window: Django's {@code manage.py}, a Flask app's
 * file and a FastAPI app's file each start their development server with its reloader on; a request to a view stops
 * on its breakpoint; the view's file is then edited on the disk, the server reloads in a new child process, and the
 * next request stops there again. Needs a venv with Django, Flask, FastAPI and uvicorn: {@code -Dweb.venv=…}, by
 * default {@code ~/.cache/editora/web-venv}. Run with {@code ./mvnw test -Dtest=PythonWebProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class PythonWebProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private static final Path VENV = Path.of(System.getProperty(
            "web.venv", HOME.resolve(".cache/editora/web-venv").toString()));

    @Test
    void django(@TempDir Path dir) throws Exception {
        Path root = project(dir);
        Files.createDirectories(root.resolve("mysite"));
        Files.writeString(root.resolve("mysite/__init__.py"), "");
        Files.writeString(root.resolve("mysite/settings.py"), """
                SECRET_KEY = "probe"
                DEBUG = True
                ALLOWED_HOSTS = ["*"]
                ROOT_URLCONF = "mysite.urls"
                INSTALLED_APPS = []
                DATABASES = {}
                """);
        Files.writeString(root.resolve("mysite/urls.py"), """
                from django.urls import path
                from mysite import views

                urlpatterns = [path("hello/", views.hello)]
                """);
        Path views = Files.writeString(root.resolve("mysite/views.py"), """
                from django.http import HttpResponse


                def hello(request):
                    name = "django"
                    return HttpResponse("hi " + name)
                """);
        Path manage = Files.writeString(root.resolve("manage.py"), """
                import os
                import sys

                if __name__ == "__main__":
                    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "mysite.settings")
                    from django.core.management import execute_from_command_line
                    execute_from_command_line(sys.argv)
                """);
        // Its port given, as `runserver PORT`: arguments of a manage.py run as they are (still followed: it serves).
        int port = freePort();
        probe(root, manage, "runserver " + port, views, 5, port, "/hello/");
    }

    @Test
    void flask(@TempDir Path dir) throws Exception {
        Path root = project(dir);
        Path app = Files.writeString(root.resolve("app.py"), """
                from flask import Flask

                app = Flask(__name__)


                @app.route("/hello")
                def hello():
                    name = "flask"
                    return "hi " + name
                """);
        int port = freePort();
        probe(root, app, "--port " + port, app, 9, port, "/hello");
    }

    @Test
    void fastApi(@TempDir Path dir) throws Exception {
        Path root = project(dir);
        Path pkg = Files.createDirectories(root.resolve("shop"));
        Files.writeString(pkg.resolve("__init__.py"), "");
        Path main = Files.writeString(pkg.resolve("main.py"), """
                from fastapi import FastAPI

                api = FastAPI()


                @api.get("/hello")
                def hello():
                    name = "fastapi"
                    return {"hi": name}
                """);
        int port = freePort();
        probe(root, main, "--port " + port, main, 9, port, "/hello");
    }

    @Test
    void aFastApiRunConfiguration(@TempDir Path dir) throws Exception {
        // What Add proposes for the app's file: python -m uvicorn, its app named from the project's folder.
        Path root = project(dir);
        Path main = Files.writeString(root.resolve("main.py"), """
                from fastapi import FastAPI

                api = FastAPI()


                @api.get("/hello")
                def hello():
                    name = "fastapi"
                    return {"hi": name}
                """);
        int port = freePort();
        var saved = new com.editora.config.RunConfiguration(
                "API",
                "python-module",
                "uvicorn",
                "",
                "",
                "main:api --reload --port " + port,
                "",
                root.toString(),
                "",
                "",
                "");
        probe(root, main, "", saved, main, 9, port, "/hello");
    }

    private static Path project(Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        assumeTrue(Files.isExecutable(VENV.resolve("bin/python")), "needs a venv with the frameworks: " + VENV);
        Path root = Files.createDirectory(dir.resolve("web")).toRealPath();
        Files.createSymbolicLink(root.resolve(".venv"), VENV);
        return root;
    }

    /**
     * Debug File on {@code launched} (its program arguments {@code args}) in a window on {@code root}; a breakpoint on
     * {@code line} of {@code view}; a request to {@code path} on {@code port} stops there, before and after a reload.
     */
    private static void probe(Path root, Path launched, String args, Path view, int line, int port, String path)
            throws Exception {
        probe(root, launched, args, null, view, line, port, path);
    }

    /** As above; with {@code saved}, Debug of that run configuration instead of Debug File. */
    private static void probe(
            Path root,
            Path launched,
            String args,
            com.editora.config.@Nullable RunConfiguration saved,
            Path view,
            int line,
            int port,
            String path)
            throws Exception {
        Path configDir = Files.createDirectory(root.getParent().resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(view, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            await("python", 120, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer viewBuffer = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> viewBuffer.toggleBreakpoint(line - 1));
            if (saved != null) {
                FxTestSupport.runOnFx(() -> debug.debugConfig(saved));
            } else {
                FxTestSupport.runOnFx(() -> {
                    c.config.getWorkspaceState().getProgramArgs().put(launched.toString(), args);
                    c.fileWorkflows.openPath(launched);
                });
                await(
                        "the launched file in front",
                        30,
                        () -> call(() -> launched.equals(c.activeBuffer().getPath())));
                FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            }

            stopsInTheView(debug, view, line, port, path, "first request");
            // The reloader starts a new server process when a file of the app changes; it is followed too. Edited
            // again until it does: a reloader that had not yet noted the file's time takes the edit for its start.
            java.util.Set<String> before = call(() -> labels(debug));
            Await.until(
                    () -> {
                        try {
                            Files.writeString(view, Files.readString(view) + "\n# edited\n");
                            return Await.holdsWithin(
                                    () -> call(() -> !before.containsAll(labels(debug))), Duration.ofSeconds(3));
                        } catch (java.io.IOException | InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    "a new server process after the edit",
                    Duration.ofSeconds(60));
            stopsInTheView(debug, view, line, port, path, "after the reload");

            FxTestSupport.runOnFx(() -> debug.sessions().all().forEach(s -> s.dap.stop()));
            await(
                    "stopped",
                    60,
                    () -> call(() -> debug.sessions().all().stream().noneMatch(s -> s.dap.isActive())));
        } finally {
            fx.dispose();
        }
    }

    /** A request to the server stops on the view's breakpoint; resumed, the request is answered. */
    private static void stopsInTheView(DebugCoordinator debug, Path view, int line, int port, String path, String when)
            throws Exception {
        HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .version(HttpClient.Version.HTTP_1_1) // no h2c upgrade asked of a development server
                .timeout(Duration.ofSeconds(150))
                .build();
        // The server takes a moment to listen (and to come back after a reload): asked again until it answers.
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> {
            long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (true) {
                try {
                    try {
                        // A client of its own each time: no connection kept from a server process that has gone.
                        HttpClient http = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(2))
                                .build();
                        return http.send(get, HttpResponse.BodyHandlers.ofString())
                                .body();
                    } catch (java.io.IOException e) {
                        if (System.nanoTime() > until) {
                            throw new IllegalStateException(e);
                        }
                    }
                    Await.holdsWithin(() -> false, Duration.ofMillis(300)); // a pause before asking again
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });
        if (!Await.holdsWithin(() -> call(() -> suspended(debug) != null), Duration.ofSeconds(120))) {
            throw new AssertionError(when + ": no stop in the view; answer " + answer + "; sessions:\n"
                    + call(() -> debug.sessions().all().stream()
                            .map(x -> x.sessionLabel + " " + x.dap.state() + " parent=" + (x.parent != null)
                                    + " sent=" + x.sentBreakpoints.keySet() + " status=" + x.breakpointStatus
                                    + "\n--- console:\n" + tail(x))
                            .reduce("", (a, b) -> a + b + "\n")));
        }
        DebugSession stopped = call(() -> suspended(debug));
        await(
                when + ": the stopped session's frames shown",
                30,
                () -> call(() -> debug.sessions().focused() == stopped
                        && !debug.panel().callStack().isEmpty()));
        DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
        assertEquals(view, top.file(), when + ": " + top);
        assertEquals(line - 1, top.line(), when + " (0-based)");
        FxTestSupport.runOnFx(() -> stopped.dap.resume());
        String body = answer.get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(body.contains("hi"), when + ": the view answered: " + body);
    }

    /** The names of the window's debug sessions ("Subprocess 1234" for each server process). */
    private static java.util.Set<String> labels(DebugCoordinator debug) {
        return debug.sessions().all().stream().map(x -> x.sessionLabel).collect(java.util.stream.Collectors.toSet());
    }

    private static String tail(DebugSession session) {
        if (session.panel == null) {
            return "(no panel)";
        }
        String text;
        try {
            text = FxTestSupport.<org.fxmisc.richtext.CodeArea>field(session.panel, "console")
                    .getText();
        } catch (Exception e) {
            return e.toString();
        }
        return text.length() > 1500 ? text.substring(text.length() - 1500) : text;
    }

    private static @Nullable DebugSession suspended(DebugCoordinator debug) {
        return debug.sessions().all().stream()
                .filter(s -> s.dap.state() == DapManager.State.SUSPENDED)
                .findFirst()
                .orElse(null);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
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
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }
}
