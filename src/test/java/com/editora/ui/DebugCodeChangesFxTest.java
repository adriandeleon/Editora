package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hot code replace, Restart Frame and Smart Step Into as the user meets them in a real window: a save of a Java
 * file during a session has jdtls build and java-debug replace the classes, and the Debug console says so; the
 * setting decides whether a save does that; the commands reach the adapter with the right frame and target.
 * jdtls is a fake that answers {@code java/buildWorkspace}; the adapter is {@link FakeDebugAdapter}.
 */
@Tag("fx")
class DebugCodeChangesFxTest {

    private static final String SOURCE =
            "public class App {\n  public static void main(String[] a) {\n    System.out.println(\"v1\");\n  }\n}\n";

    private FxWindowFixture fx;
    private Path dir;
    private Path file;
    private LspManager lspManager;
    private List<FakeLanguageServer> fakes;
    private DapManager dap;
    private DebugCoordinator debug;
    private CommandRegistry registry;
    private ConfigManager config;
    private FakeDebugAdapter adapter;
    private EditorBuffer buffer;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        dir = Files.createTempDirectory("editora-hcr-test");
        Path cfg = Files.createDirectories(dir.resolve("config"));
        file = dir.resolve("proj/src/main/java/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        String anExecutable = ProcessHandle.current().info().command().orElseThrow();
        List<FakeLanguageServer>[] made = new List[1];
        fx = FxWindowFixture.create(cfg, false, false, false, List.of(), controller -> {
            LspManager manager = FxTestSupport.field(controller, "lspManager");
            made[0] = LspTestHooks.useFakeSessionsWithRawRequests(manager, LspTestHooks.caps());
            ConfigManager c = FxTestSupport.field(controller, "config");
            c.getSettings().setLspSupport(true);
            c.getSettings().setJavaLspEnabled(true);
            c.getSettings().setJavaLspCommand(anExecutable);
            c.getSettings().setDebugSupport(true);
            LspCoordinator coordinator = FxTestSupport.field(controller, "lspCoordinator");
            coordinator.applySupport();
        });
        fakes = made[0];
        lspManager = FxTestSupport.field(fx.controller, "lspManager");
        dap = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        registry = FxTestSupport.field(fx.controller, "registry");
        config = FxTestSupport.field(fx.controller, "config");
        adapter = new FakeDebugAdapter(false);
        adapter.restartFrame = true;
        adapter.stepInTargets = true;
        buffer = open(file);
        await("jdtls to manage the file", () -> FxTestSupport.callOnFx(() -> lspManager.isManaged(file)));
        for (FakeLanguageServer fake : fakes) {
            fake.rawHandler = raw -> "java/buildWorkspace".equals(raw.method()) ? 1 : null; // SUCCEED
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dap != null) {
            FxTestSupport.runOnFx(dap::stop);
        }
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    // --- harness --------------------------------------------------------------------------------------

    private static void await(String what, ThrowingCheck condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.holds()) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            FxTestSupport.drainFx();
            Thread.sleep(20);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingCheck {
        boolean holds() throws Exception;
    }

    private EditorBuffer open(Path path) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, path));
        EditorBuffer[] found = new EditorBuffer[1];
        await("the file to open", () -> {
            found[0] = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            return found[0] != null
                    && path.equals(found[0].getPath())
                    && !FxTestSupport.callOnFx(found[0]::getContent).isEmpty();
        });
        return found[0];
    }

    /** The window's manager in a live Java session with {@link #adapter}, as after a launch of {@link #file}. */
    private FakeDebugAdapter.Session connect() throws Exception {
        long epoch = (Long) FxTestSupport.callOnFx(() -> FxTestSupport.call(dap, "beginSession", new Class<?>[] {}));
        DapClient client = new DapClient(
                (DapClient.Host) FxTestSupport.call(dap, "sessionHost", new Class<?>[] {long.class}, epoch));
        client.connect(adapter.port(), "java").get(10, TimeUnit.SECONDS);
        assertTrue((Boolean)
                FxTestSupport.call(dap, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
        FxTestSupport.runOnFx(() -> {
            set(dap, "debugFile", file);
            FxTestSupport.call(dap, "setState", new Class<?>[] {DapManager.State.class}, DapManager.State.RUNNING);
        });
        return adapter.awaitSession();
    }

    private void editAndSave(String from, String to) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = buffer.getArea();
            int at = area.getText().indexOf(from);
            area.replaceText(at, at + from.length(), to);
            registry.run("file.save");
        });
        await("the save", () -> Files.readString(file).contains(to));
    }

    private List<Object> builds() {
        List<Object> out = new ArrayList<>();
        for (FakeLanguageServer fake : fakes) {
            for (FakeLanguageServer.Raw raw : List.copyOf(fake.rawRequests)) {
                if ("java/buildWorkspace".equals(raw.method())) {
                    out.add(raw.params());
                }
            }
        }
        return out;
    }

    private String console() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(debug.panel(), "console");
            return area.getText();
        });
    }

    private List<String> statuses() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            MessageLog log = FxTestSupport.field(fx.controller.statusBar, "messageLog");
            return log.entries().stream().map(MessageLog.Entry::text).toList();
        });
    }

    private void mode(String value) throws Exception {
        FxTestSupport.runOnFx(() -> config.getSettings().setDebugHotCodeReplace(value));
    }

    // --- hot code replace ----------------------------------------------------------------------------

    @Test
    void savingDuringAJavaSessionLoadsTheChangeIntoTheRunningProgramAndSaysSo() throws Exception {
        FakeDebugAdapter.Session session = connect();
        session.redefineAnswer = Map.of("changedClasses", List.of("App"));

        editAndSave("\"v1\"", "\"v2\"");

        session.awaitRequest("redefineClasses");
        assertEquals(List.of(false), builds(), "jdtls built the saved file incrementally first");
        String applied = tr("debug.hcr.applied", "App");
        await("the console line", () -> console().contains(applied));
        assertTrue(
                statuses().contains(tr("debug.hcr.appliedStatus", "App")),
                statuses().toString());
    }

    @Test
    void aReplaceJavaDebugRefusesTellsTheUserToRestart() throws Exception {
        FakeDebugAdapter.Session session = connect();
        session.redefineNotices = List.<String[]>of(new String[] {"ERROR", "Add method not implemented"});
        session.redefineAnswer = Map.of(
                "changedClasses", List.of(), "errorMessage", "Failed to redefine classes: Add method not implemented");

        editAndSave("\"v1\"", "\"v2\"");

        await("the restart advice", () -> console().contains(tr("debug.hcr.restartNeeded")));
        assertTrue(console().contains(tr("debug.hcr.notice", "Add method not implemented")), console());
        assertFalse(console().contains("Failed to redefine"), "the reason is said once, not twice");
        assertTrue(statuses().contains(tr("debug.hcr.failedStatus")));

        // A refusal no event explained gives its own reason.
        session.redefineNotices = List.of();
        session.redefineAnswer = Map.of("changedClasses", List.of(), "errorMessage", "JVM is not suspendable");
        editAndSave("\"v2\"", "\"v3\"");
        await("the reason", () -> console().contains(tr("debug.hcr.failed", "JVM is not suspendable")));
    }

    @Test
    void inManualModeASaveOnlySaysHowAndTheCommandApplies() throws Exception {
        mode("manual");
        FakeDebugAdapter.Session session = connect();
        session.redefineAnswer = Map.of("changedClasses", List.of("App"));

        editAndSave("\"v1\"", "\"v2\"");
        await("the hint", () -> statuses().contains(tr("debug.hcr.manualHint")));
        assertEquals(List.of(), builds(), "nothing was built on the save");
        assertFalse(session.requests.contains("redefineClasses"));

        FxTestSupport.runOnFx(() -> registry.run("debug.applyCodeChanges"));

        session.awaitRequest("redefineClasses");
        assertEquals(List.of(false), builds());
        await("the console line", () -> console().contains(tr("debug.hcr.applied", "App")));
    }

    @Test
    void whenOffASaveLeavesTheProgramAloneAndTheCommandSaysItIsOff() throws Exception {
        mode("off");
        FakeDebugAdapter.Session session = connect();

        editAndSave("\"v1\"", "\"v2\"");
        FxTestSupport.runOnFx(() -> registry.run("debug.applyCodeChanges"));
        FxTestSupport.drainFx();

        assertEquals(List.of(), builds());
        assertFalse(session.requests.contains("redefineClasses"));
        assertTrue(statuses().contains(tr("debug.hcr.off")), statuses().toString());
    }

    @Test
    void withNoSessionTheCommandSaysSoAndASaveBuildsNothing() throws Exception {
        editAndSave("\"v1\"", "\"v2\"");
        FxTestSupport.runOnFx(() -> registry.run("debug.applyCodeChanges"));
        FxTestSupport.drainFx();

        assertEquals(List.of(), builds());
        assertTrue(statuses().contains(tr("debug.hcr.noSession")), statuses().toString());
    }

    // --- restart frame and smart step into -----------------------------------------------------------

    @Test
    void restartFrameRestartsTheSelectedFrame() throws Exception {
        FakeDebugAdapter.Session session = connect();
        session.framePath = file.toString();
        session.stop(7, "breakpoint");
        await("the stop", () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);
        DapModels.StackFrameInfo[] shown = new DapModels.StackFrameInfo[1];
        await("the frame to show", () -> (shown[0] = FxTestSupport.field(debug, "shownFrame")) != null);

        FxTestSupport.runOnFx(() -> registry.run("debug.restartFrame"));

        session.awaitRequest("restartFrame");
        assertEquals(List.of(shown[0].id()), session.restartedFrames);
    }

    @Test
    void theCallStackMenuOffersRestartFrameExceptOnTheBottomFrame() throws Exception {
        FakeDebugAdapter.Session session = connect();
        session.stop(7, "breakpoint");
        await("the stop", () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);
        DapModels.StackFrameInfo top = new DapModels.StackFrameInfo(11, "App.work()", file, 2, 1);
        DapModels.StackFrameInfo bottom = new DapModels.StackFrameInfo(12, "App.main(String[])", file, 1, 1);

        List<javafx.scene.control.MenuItem> onTop =
                FxTestSupport.callOnFx(() -> debug.stackMenuItems(top, List.of(top, bottom)));
        List<javafx.scene.control.MenuItem> onBottom =
                FxTestSupport.callOnFx(() -> debug.stackMenuItems(bottom, List.of(top, bottom)));

        assertEquals(tr("debugpanel.restartFrame"), onTop.get(0).getText());
        assertFalse(onTop.get(0).isDisable());
        assertTrue(onBottom.get(0).isDisable(), "nothing called the bottom frame, so nothing can call it again");
        FxTestSupport.runOnFx(() -> onTop.get(0).fire());
        session.awaitRequest("restartFrame");
        assertEquals(List.of(11), session.restartedFrames);
    }

    @Test
    void smartStepIntoStepsStraightIntoTheOnlyCallOrLetsTheUserPick() throws Exception {
        FakeDebugAdapter.Session session = connect();
        session.stepTargetLabels = List.of("work()");
        session.stop(7, "breakpoint");
        await("the stop", () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);

        FxTestSupport.runOnFx(() -> registry.run("debug.smartStepInto"));

        session.awaitRequest("stepIn");
        assertEquals(100, session.stepIns.get(0).getTargetId(), "one call: no picker, straight in");
        await("running after the step", () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.RUNNING);

        session.stepTargetLabels = List.of("add(twice(1),twice(2))", "twice(1)");
        session.stop(7, "step");
        await("the second stop", () -> FxTestSupport.callOnFx(dap::state) == DapManager.State.SUSPENDED);
        FxTestSupport.runOnFx(() -> registry.run("debug.smartStepInto"));
        QuickOpen<DapModels.StepTarget>[] picker = new QuickOpen[1];
        await(
                "the picker",
                () -> (picker[0] = FxTestSupport.field(debug, "smartStepPicker")) != null
                        && FxTestSupport.callOnFx(picker[0]::isShown));
        java.util.function.Supplier<List<DapModels.StepTarget>> items = FxTestSupport.field(picker[0], "itemsSupplier");
        List<DapModels.StepTarget> rows = FxTestSupport.callOnFx(items::get);
        assertEquals(
                List.of("add(twice(1),twice(2))", "twice(1)"),
                rows.stream().map(DapModels.StepTarget::label).toList());
        java.util.function.Consumer<DapModels.StepTarget> choose = FxTestSupport.field(picker[0], "onChoose");

        FxTestSupport.runOnFx(() -> choose.accept(rows.get(1)));

        session.awaitRequests("stepIn", 2);
        assertEquals(101, session.stepIns.get(1).getTargetId());
    }
}
