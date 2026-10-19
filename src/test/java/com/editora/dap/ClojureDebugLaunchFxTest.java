package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.lsp.LspManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link DapManager} starts a Clojure debug session, with no Clojure installed: a stand-in {@code clojure} on
 * the PATH records the command line it was started with and exits, so the test sees exactly what Debug would run
 * (the JDWP agent as a {@code -J} argument right after the executable, locals kept, {@code -M -m} in the project
 * folder; the stand-in never connects, so the session is then stopped). A Babashka script and a ClojureScript file
 * are refused before anything starts.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the stand-in is a POSIX shell script
class ClojureDebugLaunchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        com.editora.i18n.Messages.init("en");
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<String> output = new CopyOnWriteArrayList<>();
    private String savedPath;

    @BeforeEach
    void setUp() throws Exception {
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        dap = new DapManager(lsp);
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            dap.setBreakpointsSupplier(List::of);
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
        savedPath = augmentedPath();
    }

    @AfterEach
    void tearDown() throws Exception {
        setAugmentedPath(savedPath);
        onFx(dap::shutdown);
        lsp.shutdownAll();
    }

    private static java.lang.reflect.Field cachedPathField() throws ReflectiveOperationException {
        var f = com.editora.process.ProcessRunner.class.getDeclaredField("cachedPath");
        f.setAccessible(true);
        return f;
    }

    private static String augmentedPath() throws ReflectiveOperationException {
        return (String) cachedPathField().get(null);
    }

    /** The PATH every tool lookup in this JVM sees (the login shell's, cached once); restored after the test. */
    private static void setAugmentedPath(String path) throws ReflectiveOperationException {
        cachedPathField().set(null, path);
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    private void awaitError() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (errors.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "no error reported; output: " + output);
            onFx(() -> {});
            Thread.sleep(20);
        }
    }

    private void debug(Path file) throws Exception {
        onFx(() -> dap.startLaunch(file, "clojure", (options, chosen) -> {}, "", null));
    }

    @Test
    void aNamespaceIsStartedUnderTheClojureCliWithTheAgentAsAJArgument() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path argsFile = dir.resolve("args.txt");
        Path stand = bin.resolve("clojure");
        Files.writeString(
                stand,
                "#!/bin/sh\nprintf '%s\\n' \"$0\" \"$@\" > '" + argsFile + "'\npwd >> '" + argsFile + "'\nexit 3\n");
        Files.setPosixFilePermissions(stand, PosixFilePermissions.fromString("rwxr-xr-x"));
        setAugmentedPath(bin + java.io.File.pathSeparator + System.getenv("PATH"));
        Path project = Files.createDirectories(dir.resolve("proj"));
        Files.writeString(project.resolve("deps.edn"), "{}");
        Path core = Files.createDirectories(project.resolve("src/my_app")).resolve("core.clj");
        Files.writeString(core, "(ns my-app.core)\n(defn -main [& args] (println args))\n");

        debug(core);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(argsFile)) {
            assertTrue(System.nanoTime() < deadline, "the stand-in never ran: " + errors + " " + output);
            Thread.sleep(20);
        }
        List<String> argv = Files.readAllLines(argsFile);
        assertEquals(stand.toString(), argv.get(0));
        assertTrue(
                argv.get(1).startsWith("-J-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:"),
                argv::toString);
        assertEquals(
                List.of(
                        "-J-Dclojure.main.report=stderr",
                        "-J-Dclojure.compiler.disable-locals-clearing=true",
                        "-M",
                        "-m",
                        "my-app.core"),
                argv.subList(2, 7));
        assertEquals(
                project.toRealPath().toString(),
                Path.of(argv.get(7)).toRealPath().toString(),
                "in the project");
        assertTrue(String.join("", output).contains("-M -m my-app.core"), output::toString);
        onFx(dap::stop); // the stand-in never connects: end the session rather than wait out the adapter
    }

    @Test
    void aBabashkaScriptIsRefusedNotStarted() throws Exception {
        Path script = dir.resolve("deploy.bb");
        Files.writeString(script, "(println 1)\n");
        debug(script);
        awaitError();
        assertTrue(errors.get(0).contains("Babashka"), errors::toString);
    }

    @Test
    void clojureScriptAndEdnAreNotProgramsToDebug() throws Exception {
        Path cljs = dir.resolve("app.cljs");
        Files.writeString(cljs, "(ns app)\n");
        debug(cljs);
        awaitError();
        assertTrue(errors.get(0).startsWith("app.cljs is not a program the JVM runs"), errors::toString);
    }
}
