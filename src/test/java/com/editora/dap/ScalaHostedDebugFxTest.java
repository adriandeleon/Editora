package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Scala debug session through the adapter its language server hosts ({@link DapServerRegistry.Kind
 * #LANGUAGE_SERVER}): {@link DapManager} against a Metals that answers {@code debug-adapter-start} with a socket,
 * and a debug adapter on that socket that records the {@code launch} it receives. What Metals is asked (the file,
 * {@code runType}, arguments, JVM options, environment) and what the adapter then gets are the contract; the
 * real Metals is exercised by the opt-in {@code ScalaMetalsProbeFxTest}.
 */
@Tag("fx")
class ScalaHostedDebugFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final List<ExecuteCommandParams> commands = new CopyOnWriteArrayList<>();
    private volatile Function<ExecuteCommandParams, Object> reply;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeDebugAdapter(false);
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(lsp);
        lsp.configure(true, Map.of("scala", "metals"));
        dap = new DapManager(lsp);
        reply = p -> Map.of("name", "hello", "uri", "tcp://127.0.0.1:" + adapter.port());
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

                @Override
                public void onOutput(String text, String category) {
                    output.add(text);
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread ran the action");
    }

    /** A Scala file of an sbt build, open on the fake Metals. */
    private Path sbtFile() throws Exception {
        Path root = dir.resolve("app");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.sbt"), "ThisBuild / scalaVersion := \"3.3.7\"\n");
        Path file = root.resolve("src/main/scala/demo/Main.scala");
        Files.createDirectories(file.getParent());
        String source = "package demo\n\n@main def hello(): Unit =\n  println(1)\n";
        Files.writeString(file, source);
        lsp.openDocument(file, root, "scala", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                commands.add(params);
                return "debug-adapter-start".equals(params.getCommand()) ? reply.apply(params) : null;
            };
        }
        return file;
    }

    private void awaitError() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (errors.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "no error was reported");
            Thread.sleep(20);
        }
    }

    @Test
    void metalsIsAskedForTheFilesAdapterAndTheSessionLaunchesOnItsSocket() throws Exception {
        Path file = sbtFile();
        dap.setBreakpointsSupplier(() -> List.of(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(3, null, null, null)))));
        onFx(() -> {
            dap.setProgramArgs(List.of("a", "b c"));
            dap.setVmArgs(List.of("-Xmx1g"));
            dap.setEnv(Map.of("MODE", "dev"));
            dap.startLaunch(file, "scala", null);
        });
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError("no session; errors=" + errors + " commands=" + commands, e);
        }
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        assertEquals("hello", session.launchArgs.get("name"), "the session is named as Metals names it");
        assertEquals(false, session.launchArgs.get("noDebug"));

        ExecuteCommandParams start = commands.stream()
                .filter(c -> c.getCommand().equals("debug-adapter-start"))
                .findFirst()
                .orElseThrow();
        Gson gson = new Gson();
        JsonObject params = gson.toJsonTree(start.getArguments().get(0)).getAsJsonObject();
        assertEquals(file.toUri().toString(), params.get("path").getAsString());
        assertEquals("run", params.get("runType").getAsString());
        assertEquals("[\"a\",\"b c\"]", params.get("args").toString());
        assertEquals("[\"-Xmx1g\"]", params.get("jvmOptions").toString());
        assertEquals("dev", params.getAsJsonObject("env").get("MODE").getAsString());
        assertTrue(
                String.join("", output).contains(tr("debug.hosted.starting")),
                "the console says what it waits for: " + output);
    }

    @Test
    void withoutMetalsRunningForTheFileDebugSaysSo() throws Exception {
        Path root = dir.resolve("app");
        Files.createDirectories(root);
        Files.writeString(root.resolve("build.sbt"), "");
        Path file = root.resolve("Main.scala");
        Files.writeString(file, "@main def hello() = ()\n"); // not opened: no language server serves it
        onFx(() -> dap.startLaunch(file, "scala", null));
        awaitError();
        assertEquals(tr("debug.error.hostedNoServer", "Main.scala"), errors.get(0));
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void aReplyWithoutASocketEndsTheSession() throws Exception {
        Path file = sbtFile();
        reply = p -> Map.of("name", "hello");
        onFx(() -> dap.startLaunch(file, "scala", null));
        awaitError();
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void aFailedStartIsReported() throws Exception {
        Path file = sbtFile();
        fakes.forEach(f -> f.failEverything = true); // Metals answers with an error ("No main could be found")
        onFx(() -> dap.startLaunch(file, "scala", null));
        awaitError();
        assertEquals(0, adapter.sessionCount());
    }

    /** A script outside any build falls back to Editora's own adapter over Scala CLI, which must be installed. */
    @Test
    void aScriptWithoutScalaCliSaysItIsNotInstalled() throws Exception {
        assumeTrue(com.editora.run.JvmTools.locate("scala-cli") == null, "Scala CLI is installed here");
        Path script = dir.resolve("hello.sc");
        Files.writeString(script, "println(1)\n");
        onFx(() -> dap.startLaunch(script, "scala", null));
        awaitError();
        assertEquals(tr("status.run.toolMissing.scala-cli"), errors.get(0));
        assertEquals(List.of(), commands, "Metals is not asked for a script");
    }

    /**
     * A script falls back to Editora's own adapter, which starts Scala CLI with the debugger's agent as a
     * {@code --java-opt} before the file and the program's arguments after {@code --}. A stand-in
     * {@code scala-cli} records the command it was started with (and exits without connecting).
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(
            value = org.junit.jupiter.api.condition.OS.WINDOWS,
            disabledReason = "the stand-in scala-cli is a POSIX shell script")
    void aScriptRunsUnderScalaCliWithTheAgentAsAJavaOption() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path argvFile = dir.resolve("argv.txt");
        Path cli = bin.resolve("scala-cli");
        Files.writeString(cli, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + argvFile + "'\n");
        cli.toFile().setExecutable(true);
        dap.setJvmToolLocatorForTest(tool -> "scala-cli".equals(tool) ? cli.toString() : null);
        Path script = dir.resolve("scripts/hello.sc");
        Files.createDirectories(script.getParent());
        Files.writeString(script, "println(1)\n");
        onFx(() -> {
            dap.setProgramArgs(List.of("-v", "x"));
            dap.setVmArgs(List.of("-Xmx1g"));
            dap.startLaunch(script, "scala", null);
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(argvFile) || Files.size(argvFile) == 0) {
            assertTrue(System.nanoTime() < deadline, "the stand-in scala-cli never ran; errors=" + errors);
            Thread.sleep(20);
        }
        Thread.sleep(100); // printf has finished writing the lines
        List<String> argv = Files.readAllLines(argvFile);
        assertEquals("run", argv.get(0));
        assertEquals("--java-opt", argv.get(1));
        assertTrue(argv.get(2).startsWith("-agentlib:jdwp=transport=dt_socket,server=n"), argv.toString());
        assertEquals(List.of("--java-opt", "-Xmx1g", script.toString(), "--", "-v", "x"), argv.subList(3, argv.size()));
        assertTrue(
                output.stream().anyMatch(o -> o.contains(script.toString())),
                "the console shows the command line: " + output);
        assertEquals(List.of(), commands, "Metals is not asked for a script");
    }

    @Test
    void onlyAFileOfABuildIsDebuggedByTheServer() throws Exception {
        assertEquals(false, DapManager.serverDebugs(dir.resolve("scripts/hello.sc"), "scala"), "no build above it");
        Files.writeString(dir.resolve("build.mill"), "");
        assertTrue(DapManager.serverDebugs(dir.resolve("app/src/Main.scala"), "scala"));
        assertTrue(DapManager.serverDebugs(dir.resolve("Any.java"), "java"), "only Scala has a fallback");
    }
}
