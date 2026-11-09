package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.dap.NativeBuild;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in probe of the Disassembly and Memory views against the real CodeLLDB: a stopped C program's frame has an
 * instruction pointer, the instructions around it are disassembled with their source lines, Step Over Instruction moves
 * it one instruction, a C string's memory reads as its bytes; and in a window both views fill for the stop. Run with
 * {@code ./mvnw test -Dtest=DisassemblyMemoryProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code cc} and CodeLLDB
 * (as {@code NativeDebugProbeFxTest} finds it).
 */
@Tag("probe")
@Tag("fx")
class DisassemblyMemoryProbeFxTest {

    private static final String C_FILE = """
            #include <stdio.h>

            int main(void) {
                const char *greeting = "hello";
                int total = 0;
                for (int i = 0; i < 3; i++) {
                    total += i;
                }
                printf("%s %d\\n", greeting, total);
                return 0;
            }
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final class Session implements DapManager.Listener {
        final BlockingQueue<List<DapModels.StackFrameInfo>> stops = new LinkedBlockingQueue<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

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

        List<DapModels.StackFrameInfo> next(String what) throws InterruptedException {
            List<DapModels.StackFrameInfo> s = stops.poll(120, TimeUnit.SECONDS);
            assertNotNull(s, what + " never happened; errors: " + errors);
            return s;
        }
    }

    /** {@code shelf.c} compiled with {@code cc -g -O0}; its real path, and the program beside it. */
    private static Path[] compiled(Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("shelf.c"), C_FILE).toRealPath();
        Path exe = dir.resolve("shelf");
        ProcessRunner.Result cc = ProcessRunner.run(
                dir,
                Duration.ofMinutes(2),
                ProcessRunner.resolveExecutable(List.of("cc", "-g", "-O0", "-o", exe.toString(), file.toString())));
        assertTrue(cc.ok(), cc.err());
        return new Path[] {file, exe};
    }

    private static <T> T onFx(java.util.function.Consumer<java.util.function.Consumer<T>> ask) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> ask.accept(f::complete));
        return f.get(60, TimeUnit.SECONDS);
    }

    @Test
    void aStoppedFrameIsDisassembledSteppedByInstructionAndItsMemoryRead(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc");
        Path[] built = compiled(dir);
        Path file = built[0];
        Session session = new Session();
        DapManager manager = new DapManager(null);
        manager.setListener(session);
        manager.setBreakpointsSupplier(() ->
                List.of(new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(8, null, null)))));
        CompletableFuture<Boolean> found = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", false, "");
            manager.configureExternal(DebugLanguages.CPP, true, configured, found::complete);
        });
        assertTrue(found.get(30, TimeUnit.SECONDS));
        try {
            FxTestSupport.runOnFx(() -> manager.startExternal(
                    file, DebugLanguages.CPP, NativeBuild.launch(built[1], List.of(), dir, false)));
            DapModels.StackFrameInfo frame =
                    session.next("the printf's breakpoint").getFirst();
            assertEquals(8, frame.line());
            assertNotNull(frame.instructionPointer(), "a native frame has an instruction pointer");
            assertTrue(FxTestSupport.callOnFx(manager::supportsDisassemble));
            assertTrue(FxTestSupport.callOnFx(manager::supportsReadMemory));
            assertTrue(FxTestSupport.callOnFx(manager::supportsInstructionStepping));

            List<DapModels.Instruction> instructions =
                    onFx(cb -> manager.disassemble(frame.instructionPointer(), -10, 30, cb));
            assertEquals(30, instructions.size(), "as many as asked for");
            int at = DebugDisassemblyView.indexOf(instructions, frame.instructionPointer());
            assertEquals(10, at, "the instruction pointer's row, after the ten before it");
            assertEquals(file, instructions.get(at).file().toRealPath(), "with its source file");
            assertEquals(8, instructions.get(at).line(), "and line");
            assertFalse(instructions.get(at).text().isBlank());

            FxTestSupport.runOnFx(manager::stepOverInstruction);
            DapModels.StackFrameInfo stepped =
                    session.next("Step Over Instruction").getFirst();
            assertTrue(
                    !frame.instructionPointer().equals(stepped.instructionPointer()),
                    "one instruction on: " + frame.instructionPointer() + " → " + stepped.instructionPointer());
            assertEquals(
                    DebugDisassemblyView.parseAddress(instructions.get(at + 1).address()),
                    DebugDisassemblyView.parseAddress(stepped.instructionPointer()),
                    "the next instruction");

            String ref = onFx(cb -> manager.memoryReferenceOf("greeting", stepped.id(), cb));
            assertNotNull(ref, "a char pointer's value has an address");
            DapModels.MemoryBlock block = onFx(cb -> manager.readMemory(ref, 0, 16, cb, why -> {}));
            assertTrue(
                    new String(block.data(), java.nio.charset.StandardCharsets.US_ASCII).startsWith("hello"),
                    "the string's bytes: " + DebugMemoryView.rows(block.address(), block.data(), 0));
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }

    @Test
    void inAWindowTheDisassemblyAndMemoryViewsFillForTheStop(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc");
        Path[] built = compiled(dir);
        Path file = built[0];
        Path configDir = Files.createDirectory(dir.resolve("config"));
        com.editora.config.SharedConfig seed = new com.editora.config.SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setRustDebugPath(configured);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C debugging available", () -> call(() -> debug.debugEffectiveFor("cpp")));
            com.editora.editor.EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(8));
            FxTestSupport.runOnFx(() ->
                    ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop",
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));

            FxTestSupport.runOnFx(() -> ((com.editora.command.CommandRegistry) FxTestSupport.field(c, "registry"))
                    .run("debug.showDisassembly"));
            DebugDisassemblyView disassembly = call(() -> debug.panel().disassemblyView());
            await(
                    "the instructions",
                    () -> call(() -> !disassembly.list().getItems().isEmpty()));
            assertEquals(
                    DebugCoordinator.DISASSEMBLY_COUNT,
                    call(() -> disassembly.list().getItems().size()));
            assertEquals(
                    DebugCoordinator.DISASSEMBLY_BEFORE,
                    call(() -> disassembly.list().getSelectionModel().getSelectedIndex()),
                    "the instruction pointer's row is selected");

            FxTestSupport.runOnFx(() -> debugActions(debug).viewMemory("greeting"));
            DebugMemoryView memory = call(() -> debug.panel().memoryView());
            await(
                    "the memory rows",
                    () -> call(() -> !memory.rowList().getItems().isEmpty()));
            assertTrue(
                    call(() -> memory.rowList().getItems().getFirst()).contains("hello"),
                    "the string's text column: "
                            + call(() -> memory.rowList().getItems().getFirst()));
            assertEquals(DebugPanel.AREA_MEMORY, call(() -> debug.panel().shownArea()));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static DebugPanel.Actions debugActions(DebugCoordinator debug) {
        return FxTestSupport.field(debug.panel(), "actions");
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
        long end = System.nanoTime() + 180_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
