package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Dart debugging through the window, against a {@link FakeDebugAdapter} reached as {@code dart debug_adapter} and
 * {@code flutter debug_adapter} are (stand-ins over their standard streams): F5 on a Dart program launches it in its
 * package without the SDK's frames, the adapter's own pause as the isolate starts ({@code entry}, which it resumes by
 * itself) is not shown, a breakpoint's stop is; a Flutter app's file goes to Flutter's adapter on the device Settings
 * names. {@code DartDebugProbeFxTest} drives the real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class DartDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path cli;
    private Path app;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        cli = Files.createDirectories(fx.configDir.resolve("cli")).toRealPath();
        Files.writeString(cli.resolve("pubspec.yaml"), "name: cli\nenvironment:\n  sdk: ^3.0.0\n");
        Files.createDirectories(cli.resolve("bin"));
        Files.writeString(
                cli.resolve("bin/main.dart"),
                "void main(List<String> args) {\n  var total = 0;\n  for (var i = 0; i < 3; i++) {\n"
                        + "    total += i;\n  }\n  print(total);\n}\n");
        app = Files.createDirectories(fx.configDir.resolve("app")).toRealPath();
        Files.writeString(app.resolve("pubspec.yaml"), "name: app\ndependencies:\n  flutter:\n    sdk: flutter\n");
        Files.createDirectories(app.resolve("lib"));
        Files.writeString(app.resolve("lib/main.dart"), "void main() {\n  print('app');\n}\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String relay = "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                + "' com.editora.dap.RelayAdapterMain stdio " + adapter.port() + "\n";
        Path dart = Files.writeString(fx.configDir.resolve("dart"), relay);
        Path flutter = Files.writeString(fx.configDir.resolve("flutter"), relay);
        assertTrue(dart.toFile().setExecutable(true) && flutter.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setDartDebugPath(dart.toString());
            fx.shared.getSettings().setFlutterDebugPath(flutter.toString());
            fx.shared.getSettings().setFlutterDevice("chrome");
            fx.shared.getTrustStore().trust(cli); // the trust question (pub runs build hooks), answered
            fx.shared.getTrustStore().trust(app);
            debug.applySupport();
        });
        await("Dart debugging available", () -> call(() -> debug.debugEffectiveFor("dart")));
        await("flutter found", () -> call(() -> manager.isExternalLocated(com.editora.dap.DebugLanguages.FLUTTER)));
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
        Await.until(condition, what, java.time.Duration.ofSeconds(20));
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && file.equals(b.getPath());
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private static StackFrame frame(String name, Path file, int line) {
        StackFrame f = new StackFrame();
        f.setId(1);
        f.setName(name);
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(file.toString());
        f.setSource(source);
        return f;
    }

    @Test
    void f5LaunchesTheProgramInItsPackageAndShowsOnlyTheBreakpointsStop() throws Exception {
        Path main = cli.resolve("bin/main.dart");
        EditorBuffer b = open(main);
        assertEquals("dart", call(b::getLanguage));
        assertTrue(call(b::isBreakpointsEnabled), "a Dart file has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(3));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals("dart", launch.get("type"));
        assertEquals(main.toString(), launch.get("program"));
        assertEquals(cli.toString(), launch.get("cwd"));
        assertEquals(false, launch.get("debugSdkLibraries"));
        assertEquals(false, launch.get("debugExternalPackageLibraries"));
        assertFalse(launch.containsKey("toolArgs"));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);

        // The isolate's pause as it starts, in the SDK: the adapter resumes it itself (continued). Not shown.
        Path sdk = fx.configDir.resolve("isolate_patch.dart");
        session.frames = List.of(frame("_RawReceivePort._handleMessage", sdk, 183));
        session.stop(session.mainThread, "entry");
        await("the entry stop's frames asked", () -> session.requests.contains("stackTrace"));
        session.awaitDelivered();
        FxTestSupport.drainFx();
        assertEquals(DapManager.State.RUNNING, call(manager::state), "the entry stop is not shown");
        session.continued(session.mainThread, false);

        // The breakpoint's stop is shown, the only one.
        session.frames = List.of(frame("main", main, 3));
        session.stop(session.mainThread, "breakpoint");
        await(
                "shown",
                () -> call(() -> manager.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == 3));
        assertEquals(1, call(() -> debug.panel().history().size()), "one stop shown: the breakpoint's");
        assertFalse(session.requests.contains("continue"), "the entry stop was the adapter's to resume");
    }

    @Test
    void aFlutterAppsFileDebugsOnFluttersAdapterOnTheDevice() throws Exception {
        Path main = app.resolve("lib/main.dart");
        open(main);
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals(main.toString(), launch.get("program"));
        assertEquals(app.toString(), launch.get("cwd"));
        assertEquals(List.of("-d", "chrome"), launch.get("toolArgs"));
        assertEquals("dart", call(manager::sessionLanguage), "a Flutter app's session is Dart's");
    }

    @Test
    void aLooseFilesConfigurationRunsInItsFolderWithItsEnvironment() throws Exception {
        Path loose = Files.createDirectories(fx.configDir.resolve("loose")).toRealPath();
        Path file = Files.writeString(loose.resolve("tool.dart"), "void main() {\n  print('tool');\n}\n");
        Path work = Files.createDirectories(fx.configDir.resolve("work")).toRealPath();
        DartDebugLaunch dart = FxTestSupport.field(debug, "dart");
        FxTestSupport.runOnFx(() -> dart.debug(file, List.of("a"), Map.of("MODE", "test"), work, true));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals(file.toString(), launch.get("program"));
        assertEquals(work.toString(), launch.get("cwd"), "the configuration's folder");
        assertEquals(Map.of("MODE", "test"), launch.get("env"));
        assertEquals(List.of("a"), launch.get("args"));
    }

    @Test
    void debugTestAtCaretRunsTheTestThereOnTheTestMode() throws Exception {
        Path tests = Files.createDirectories(cli.resolve("test"));
        Path file = Files.writeString(
                tests.resolve("calc_test.dart"),
                "import 'package:test/test.dart';\n\nvoid main() {\n  group('calc', () {\n    test('adds', () {\n"
                        + "      expect(1 + 2, 3);\n    });\n  });\n}\n");
        EditorBuffer b = open(file);
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(5, 0));
        run("test.debugAtCaret");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals(file.toString(), launch.get("program"));
        assertEquals(cli.toString(), launch.get("cwd"));
        assertEquals(List.of("--name", "^calc adds$"), launch.get("toolArgs"));
        assertEquals("dart", call(manager::sessionLanguage));

        // Not a test file: Debug Test at Caret leaves it to the others (here, nothing to debug at the caret).
        EditorBuffer program = open(cli.resolve("bin/main.dart"));
        assertFalse(call(() -> debug.debugDartTestAtCaret(program)));
    }
}
