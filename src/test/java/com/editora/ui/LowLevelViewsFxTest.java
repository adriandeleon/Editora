package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.SteppingGranularity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Disassembly and Memory views through the window, against an adapter that disassembles and reads memory as
 * CodeLLDB and Delve do ({@link FakeDebugAdapter#lowLevel}), reached as C/C++'s CodeLLDB is: the instructions around a
 * stop, steps by instruction while the view has the keyboard or from the commands, View Memory on a variable, paging,
 * and what each view says when there is nothing to show. {@code DisassemblyMemoryProbeFxTest} drives the real CodeLLDB.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class LowLevelViewsFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        assumeTrue(
                Path.of(com.editora.process.ProcessRunner.resolveExecutable(List.of("make"))
                                .getFirst())
                        .isAbsolute(),
                "needs make");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        adapter.lowLevel = true;
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        Files.writeString(root.resolve("shelf.c"), "int main(void) {\n    int x = 1;\n    return x;\n}\n");
        Files.writeString(root.resolve("Makefile"), "shelf: shelf.c\n\tprintf '\\177ELF' > shelf && chmod +x shelf\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path codelldb = fx.configDir.resolve("codelldb");
        Files.writeString(
                codelldb,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain listen \"$2\" " + adapter.port() + "\n");
        assertTrue(codelldb.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRustDebugPath(codelldb.toString());
            fx.shared.getTrustStore().trust(root);
            debug.applySupport();
        });
        await("C/C++ debugging available", () -> call(() -> debug.debugEffectiveFor("cpp")));
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

    /** The step just sent has been acknowledged (the program runs); the adapter then stops it again. */
    private void stopAgain(FakeDebugAdapter.Session session) throws Exception {
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "step");
        await(
                "stopped again",
                () -> call(manager::state) == DapManager.State.SUSPENDED
                        && !call(() -> debug.panel().callStack().isEmpty()));
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String placeholder(javafx.scene.control.ListView<?> list) {
        return call(() -> ((javafx.scene.control.Label) list.getPlaceholder()).getText());
    }

    @Test
    void theViewsShowAStopsInstructionsAndMemoryAndStepByInstruction() throws Exception {
        Path file = root.resolve("shelf.c");
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                "shelf.c in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && b.getPath() != null && Files.isSameFile(b.getPath(), file);
                }));
        DebugDisassemblyView disassembly = call(() -> debug.panel().disassemblyView());
        DebugMemoryView memory = call(() -> debug.panel().memoryView());
        run("debug.showDisassembly");
        assertEquals(DebugPanel.AREA_DISASSEMBLY, call(() -> debug.panel().shownArea()));
        assertEquals(tr("debugpanel.disassembly.idle"), placeholder(disassembly.list()), "nothing while idle");

        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = file.toString();
        session.frameLine = 2;
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "breakpoint");
        await(
                "the instructions",
                () -> call(() -> disassembly.list().getItems().size()) == DebugCoordinator.DISASSEMBLY_COUNT);
        assertEquals(
                DebugCoordinator.DISASSEMBLY_BEFORE,
                call(() -> disassembly.list().getSelectionModel().getSelectedIndex()),
                "the instruction pointer's row");
        assertEquals(
                FakeDebugAdapter.LOW_LEVEL_IP,
                session.disassembles.getLast().getMemoryReference(),
                "from the frame's instruction pointer");
        assertEquals(
                -DebugCoordinator.DISASSEMBLY_BEFORE,
                session.disassembles.getLast().getInstructionOffset());

        // F10 with the keyboard in the view: one instruction; elsewhere: one line.
        FxTestSupport.runOnFx(() -> disassembly.list().requestFocus());
        await("the view has the keyboard", () -> call(disassembly::hasFocus));
        run("debug.stepOver");
        await("a next", () -> session.nexts.size() == 1);
        assertEquals(SteppingGranularity.INSTRUCTION, session.nexts.getLast().getGranularity());
        stopAgain(session);
        FxTestSupport.runOnFx(() -> debug.panel().requestFocus());
        await("the view has not", () -> !call(disassembly::hasFocus));
        run("debug.stepOver");
        await("another next", () -> session.nexts.size() == 2);
        assertNull(session.nexts.getLast().getGranularity(), "a line step");
        stopAgain(session);
        run("debug.stepOverInstruction");
        await("a third next", () -> session.nexts.size() == 3);
        assertEquals(SteppingGranularity.INSTRUCTION, session.nexts.getLast().getGranularity(), "the command");
        stopAgain(session);
        run("debug.stepIntoInstruction");
        await("a step in", () -> session.requests.contains("stepIn"));
        stopAgain(session);

        // Double-clicking an instruction opens its source line.
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(debug, "openSourceLine", new Class<?>[] {Path.class, int.class}, file, 2));
        assertEquals(2, call(() -> fx.controller.activeBuffer().getFocusedArea().getCurrentParagraph()));

        // View Memory on a variable, then the next page, then an expression with no address and an unreadable one.
        DebugPanel.Actions actions = FxTestSupport.field(debug.panel(), "actions");
        assertTrue(call(actions::canViewMemory));
        FxTestSupport.runOnFx(() -> actions.viewMemory("x"));
        await("the memory rows", () -> !call(() -> memory.rowList().getItems().isEmpty()));
        assertEquals(DebugPanel.AREA_MEMORY, call(() -> debug.panel().shownArea()));
        assertEquals("x", call(memory::addressText));
        String first = call(() -> memory.rowList().getItems().getFirst());
        assertTrue(first.startsWith("0x000000002000") && first.endsWith("hello..........."), first);
        assertEquals(
                DebugMemoryView.PAGE / DebugMemoryView.ROW,
                call(() -> memory.rowList().getItems().size()));
        int before = session.memoryReads.size();
        FxTestSupport.runOnFx(() -> memory.addressField().fireEvent(new javafx.event.ActionEvent()));
        await("read again on Enter", () -> session.memoryReads.size() > before);
        int reads = session.memoryReads.size();
        FxTestSupport.runOnFx(() -> memory.page(1));
        await("the next page", () -> session.memoryReads.size() > reads);
        assertEquals(DebugMemoryView.PAGE, (int) session.memoryReads.getLast().getOffset());
        FxTestSupport.runOnFx(() -> actions.viewMemory("noaddress"));
        await("no address", () -> tr("debugpanel.memory.noAddress", "noaddress").equals(placeholder(memory.rowList())));
        FxTestSupport.runOnFx(() -> actions.viewMemory("0xdead"));
        await(
                "unreadable",
                () -> placeholder(memory.rowList()).startsWith(tr("debugpanel.memory.failed", "0xdead", "")));

        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        run("debug.showDisassembly");
        assertEquals(tr("debugpanel.disassembly.idle"), placeholder(disassembly.list()), "idle again");
    }

    @Test
    void anAdapterThatDoesNotDisassembleOrReadMemorySaysSo() throws Exception {
        adapter.lowLevel = false;
        try {
            Path file = root.resolve("shelf.c");
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
            run("debug.start");
            FakeDebugAdapter.Session session = adapter.awaitSession();
            session.awaitRequest("launch");
            await("running", () -> call(manager::state) == DapManager.State.RUNNING);
            session.stop(1, "breakpoint");
            await(
                    "stopped",
                    () -> call(manager::state) == DapManager.State.SUSPENDED
                            && !call(() -> debug.panel().callStack().isEmpty()));
            DebugDisassemblyView disassembly = call(() -> debug.panel().disassemblyView());
            DebugMemoryView memory = call(() -> debug.panel().memoryView());
            run("debug.showDisassembly");
            assertEquals(tr("debugpanel.disassembly.unsupported"), placeholder(disassembly.list()));
            run("debug.showMemory");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(debug, "memoryGo", new Class<?>[] {String.class}, "0x10"));
            assertEquals(tr("debugpanel.memory.unsupported"), placeholder(memory.rowList()));
            DebugPanel.Actions actions = FxTestSupport.field(debug.panel(), "actions");
            assertTrue(!call(actions::canViewMemory), "no View Memory on a variable");
            run("debug.stepOverInstruction");
            assertEquals(tr("status.debug.noInstructionStepping"), call(() -> {
                StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo")
                        .getText();
            }));
            FxTestSupport.runOnFx(manager::stop);
            await("stopped", () -> !call(manager::isActive));
        } finally {
            adapter.lowLevel = true;
        }
    }
}
