package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LaunchJson;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * launch.json and the user's own adapters through the window, against stand-in adapters (scripts starting
 * {@code RelayAdapterMain}, relaying to a {@link FakeDebugAdapter}): the picker, a {@code pickString} input, a
 * {@code preLaunchTask}, a CodeLLDB configuration, an adapter of {@code debug-adapters.json} by launch.json and by F5,
 * and what is said for a configuration that cannot start. {@code LaunchJsonProbeFxTest} drives the real debuggers.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LaunchJsonWindowFxTest {

    private static final String LAUNCH = """
            {
              "configurations": [
                { "name": "Fake", "type": "fake-dap", "request": "launch", "program": "${file}",
                  "level": "${input:level}", "preLaunchTask": "prep" },
                { "name": "Native", "type": "lldb", "request": "launch", "program": "${workspaceFolder}/app" },
                { "name": "Chrome", "type": "chrome", "request": "launch" },
                { "name": "No task", "type": "fake-dap", "request": "launch", "preLaunchTask": "missing" },
                { "name": "Cargo task", "type": "fake-dap", "request": "launch", "preLaunchTask": "cargo-build" },
                { "name": "Bad variable", "type": "fake-dap", "request": "launch", "program": "${command:pickProcess}" },
                { "name": "Java", "type": "java", "request": "launch" },
              ],
              "inputs": [ { "id": "level", "type": "pickString", "options": ["low", "high"] } ],
            }
            """;

    private static final String TASKS = """
            { "tasks": [
              { "label": "prep", "type": "shell", "command": "echo prepared > prepared.txt" },
              { "label": "cargo-build", "type": "cargo", "command": "build" }
            ] }
            """;

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path launchJson;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("proj")).toRealPath();
        Files.createDirectories(root.resolve(".vscode"));
        launchJson = Files.writeString(root.resolve(".vscode/launch.json"), LAUNCH);
        Files.writeString(root.resolve(".vscode/tasks.json"), TASKS);
        Files.writeString(root.resolve("main.lua"), "print('hi')\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String relay = "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                + "' com.editora.dap.RelayAdapterMain ";
        Path codelldb = fx.configDir.resolve("codelldb");
        Files.writeString(codelldb, relay + "listen \"$2\" " + adapter.port() + "\n");
        Path fake = fx.configDir.resolve("fake-dap");
        Files.writeString(fake, relay + "stdio " + adapter.port() + "\n");
        assertTrue(codelldb.toFile().setExecutable(true) && fake.toFile().setExecutable(true));
        Files.writeString(
                fx.configDir.resolve("debug-adapters.json"),
                "{\"adapters\": [{\"type\": \"fake-dap\", \"name\": \"Fake\", \"command\": [\"" + fake
                        + "\"], \"languages\": [\"lua\"], \"launch\": {\"program\": \"${file}\", \"mode\": \"f5\"}}]}");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRustDebugPath(codelldb.toString());
            debug.applySupport();
        });
        await(
                "the fake adapter found",
                () -> call(() -> manager.isExternalLocated(com.editora.dap.DebugLanguages.byId("fake-dap"))));
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(root.resolve("main.lua")));
        await(
                "main.lua in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && b.getPath() != null && Files.isSameFile(b.getPath(), root.resolve("main.lua"));
                }));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
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
        long end = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private javafx.scene.Node picker() {
        javafx.scene.Scene scene = call(() ->
                FxTestSupport.<javafx.stage.Stage>field(fx.controller, "stage").getScene());
        return call(() -> scene.getRoot().lookup(".command-palette"));
    }

    private List<String> pickerRows() {
        return call(() -> ((javafx.scene.control.ListView<?>) picker().lookup(".list-view"))
                .getItems().stream().map(String::valueOf).toList());
    }

    private void choose(String row) throws Exception {
        await(
                "a picker with " + row,
                () -> picker() != null && pickerRows().stream().anyMatch(r -> r.contains(row)));
        FxTestSupport.runOnFx(() -> {
            javafx.scene.Node card = picker();
            var list = (javafx.scene.control.ListView<?>) card.lookup(".list-view");
            for (int i = 0; i < list.getItems().size(); i++) {
                if (String.valueOf(list.getItems().get(i)).contains(row)) {
                    list.getSelectionModel().select(i);
                    break;
                }
            }
            card.lookup(".text-field")
                    .fireEvent(new javafx.scene.input.KeyEvent(
                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                            "",
                            "",
                            javafx.scene.input.KeyCode.ENTER,
                            false,
                            false,
                            false,
                            false));
        });
        FxTestSupport.drainFx();
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private FakeDebugAdapter.Session next(String request) throws Exception {
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            String console = call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
            throw new AssertionError(e.getMessage() + "; status: " + status() + "; console: " + console, e);
        }
        session.awaitRequest(request);
        return session;
    }

    @Test
    void thePickerStartsAConfigurationAfterItsInputAndTaskAndOffersItFirstNextTime() throws Exception {
        run("debug.startConfiguration");
        choose("Fake");
        choose("high");
        FakeDebugAdapter.Session session = next("launch");
        assertEquals(root.resolve("main.lua").toString(), session.launchArgs.get("program"), "${file}");
        assertEquals("high", session.launchArgs.get("level"), "the input picked");
        assertEquals("fake-dap", session.launchArgs.get("type"));
        assertTrue(Files.isRegularFile(root.resolve("prepared.txt")), "the preLaunchTask ran first");
        assertEquals("Fake", call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile")));
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));

        run("debug.startConfiguration");
        await("the picker", () -> picker() != null);
        assertTrue(pickerRows().getFirst().contains("Fake"), "the last one first: " + pickerRows());
        choose("Native");
        FakeDebugAdapter.Session lldb = next("launch");
        assertEquals("lldb", lldb.launchArgs.get("type"));
        assertEquals(root.resolve("app").toString(), lldb.launchArgs.get("program"));
    }

    @Test
    void f5OnAFileOfAnAdaptersLanguageSendsItsLaunchTemplate() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = next("launch");
        assertEquals("f5", session.launchArgs.get("mode"));
        assertEquals(root.resolve("main.lua").toString(), session.launchArgs.get("program"));
        assertEquals("fake-dap", session.launchArgs.get("type"));
    }

    @Test
    void aConfigurationThatCannotStartSaysWhy() throws Exception {
        LaunchJson.File parsed = LaunchJson.parse(LAUNCH);
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        String[][] cases = {
            {"Chrome", tr("status.debug.launchUnsupported", "Chrome", "type \"chrome\"")},
            {"No task", tr("status.debug.taskMissing", "missing")},
            {"Cargo task", tr("status.debug.taskUnsupported", "cargo-build")},
            {
                "Bad variable",
                tr("status.debug.launchVariable", "${command:pickProcess} is a variable Editora does not know")
            },
            {"Java", tr("status.debug.launchUnsupported", "Java", "a Java configuration without a mainClass")},
        };
        for (String[] c : cases) {
            LaunchJson.Configuration config = parsed.configurations().stream()
                    .filter(x -> x.name().equals(c[0]))
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> debug.runLaunchConfiguration(launchJson, root, parsed, config, b));
            await(c[0], () -> c[1].equals(status()));
            assertTrue(!call(manager::isActive), c[0] + ": nothing started");
        }
    }

    @Test
    void aMissingEmptyOrBrokenLaunchJsonIsSaid() throws Exception {
        Path saved = Files.readString(launchJson).isEmpty() ? null : launchJson;
        String text = Files.readString(launchJson);
        try {
            Files.writeString(launchJson, "{ broken");
            run("debug.startConfiguration");
            assertTrue(status().startsWith(
                            tr("status.debug.launchJsonInvalid", launchJson, "").substring(0, 10)));
            Files.writeString(launchJson, "{\"configurations\": []}");
            run("debug.startConfiguration");
            assertEquals(tr("status.debug.noConfigurations", launchJson), status());
            Files.delete(launchJson);
            run("debug.startConfiguration");
            assertEquals(tr("status.debug.noLaunchJson"), status());
        } finally {
            Files.writeString(launchJson, text);
        }
        assertTrue(saved != null);
    }

    @Test
    void editDebugAdaptersOpensTheUsersFileWithAnExampleTheFirstTime() throws Exception {
        Path file = fx.configDir.resolve("debug-adapters.json");
        String mine = Files.readString(file);
        Files.delete(file);
        try {
            run("debug.editCustomAdapters");
            await(
                    "the file opened",
                    () -> call(() -> {
                        EditorBuffer b = fx.controller.activeBuffer();
                        return b != null && b.getPath() != null && b.getPath().endsWith("debug-adapters.json");
                    }));
            assertTrue(Files.readString(file).contains("\"adapters\""), "the example");
        } finally {
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(root.resolve("main.lua")));
            Files.writeString(file, mine);
        }
    }
}
