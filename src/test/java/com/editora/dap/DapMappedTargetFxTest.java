package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.process.ExecTargets;
import com.editora.process.MappedTestTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debugging a file of a folder whose tools run elsewhere (a container, WSL): the adapter starts on that
 * target, is told the program, its folder and the breakpoints by the target's names, and the frames it
 * reports come back as this machine's files. A breakpoint in a file outside the folder is not sent there.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the stand-in target is a symlink
class DapMappedTargetFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private Path folder;
    private Path alias;
    private Path log;
    private MappedTestTarget target;
    private DapManager dap;
    private final List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    @BeforeEach
    void setUp() throws Exception {
        Path base = dir.toRealPath();
        folder = Files.createDirectories(base.resolve("my app"));
        alias = base.resolve("mounted");
        Files.writeString(folder.resolve("main.py"), "a = 1\nb = 2\nprint(a + b)\n");
        Path adapterDir = Files.createDirectories(base.resolve("site/debugpy/adapter"));
        Files.writeString(base.resolve("site/debugpy/__init__.py"), "");
        Files.writeString(adapterDir.resolve("__init__.py"), "");
        Files.writeString(adapterDir.resolve("__main__.py"), RemoteDebugFxTest.ADAPTER);
        log = base.resolve("dap.log");
        ExecTargets.setRemoteEnabled(() -> true);
        String site = base.resolve("site").toString();
        ExecTargets.setRemoteTools((id, tool) ->
                "dap.python".equals(tool) ? "env FAKE_DAP_LOG=" + log + " PYTHONPATH=" + site + " python3" : null);
        target = MappedTestTarget.register(folder, alias);
        dap = new DapManager(null);
        onFx(() -> {
            dap.configure(true, "", true, "/opt/local-only/python3", true, "/opt/local-only/js-debug");
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {}

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
        target.unregister();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private JsonObject logged(String command) throws Exception {
        for (String line : Files.readAllLines(log)) {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (command.equals(json.get("command").getAsString())) {
                return json.getAsJsonObject("arguments");
            }
        }
        throw new AssertionError("the adapter was never sent " + command);
    }

    @Test
    void aMappedFoldersFileIsDebuggedOnItsTargetByItsNamesThere() throws Exception {
        Path file = folder.resolve("main.py");
        Path outside = dir.toRealPath().resolve("elsewhere.py");
        dap.setBreakpointsSupplier(() -> List.of(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(1, null, null))),
                new DapModels.FileBreakpoints(outside, List.of(new DapModels.LineBreakpoint(0, null, null)))));

        onFx(() -> dap.startLaunch(file, "python", null));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (stops.isEmpty()) {
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(System.nanoTime() < deadline, "never stopped; started " + target.started());
            Thread.sleep(50);
        }
        assertEquals(file, stops.get(0).get(0).file(), "the frame comes back as this machine's file");
        String there = alias.resolve("main.py").toString();
        assertEquals(there, logged("launch").get("program").getAsString());
        assertEquals(alias.toString(), logged("launch").get("cwd").getAsString());
        assertEquals(
                there,
                logged("setBreakpoints").getAsJsonObject("source").get("path").getAsString());
        assertFalse(Files.readString(log).contains("elsewhere.py"), "a breakpoint outside the folder was sent");
        assertTrue(
                target.started().stream().anyMatch(argv -> argv.contains("debugpy.adapter")),
                "debugpy started on the target: " + target.started());
        assertEquals(List.of(), target.leaks());
    }
}
