package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of instruction breakpoints against the real adapters, in a window: a loop stops at a line breakpoint,
 * the Disassembly view puts a breakpoint on an instruction of the loop's body, the line breakpoint goes, and Continue
 * stops at that instruction — with the Disassembly view shown and the instruction marked; taken off, the program runs
 * to its end. On CodeLLDB and on GDB (C), and on Delve (Go). Run with
 * {@code ./mvnw test -Dtest=InstructionBreakpointProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code cc} with
 * CodeLLDB ({@code -Dcodelldb.path=…} or Editora's install) or {@code gdb}, and {@code go} with Delve.
 */
@Tag("probe")
@Tag("fx")
class InstructionBreakpointProbeFxTest {

    private static final String C_LOOP = """
            #include <stdio.h>
            int main(void) {
                int total = 0;
                for (int i = 1; i <= 3; i++) {
                    total += i;
                }
                printf("%d\\n", total);
                return 0;
            }
            """;

    /** The 0-based line of {@code total += i;} in {@link #C_LOOP}. */
    private static final int C_ADD = 4;

    private static final String GO_LOOP = """
            package main

            import "fmt"

            func main() {
            	total := 0
            	for i := 1; i <= 3; i++ {
            		total += i
            	}
            	fmt.Println(total)
            }
            """;

    /** The 0-based line of {@code total += i} in {@link #GO_LOOP}. */
    private static final int GO_ADD = 7;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    @Test
    void codelldbStopsAtAnInstructionBreakpoint(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loop")).toRealPath().resolve("loop.c"), C_LOOP);
        probe(dir, file, "cpp", C_ADD, s -> s.setRustDebugPath(configured));
    }

    @Test
    void gdbStopsAtAnInstructionBreakpoint(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gdb") && onPath("cc"), "needs gdb and cc");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loop")).toRealPath().resolve("loop.c"), C_LOOP);
        probe(dir, file, "cpp", C_ADD, s -> s.setCppDebugger("gdb"));
    }

    @Test
    void delveStopsAtAnInstructionBreakpoint(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        assumeTrue(onPath("go"), "needs go on the PATH");
        Path root = Files.createDirectory(dir.resolve("loop")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/loop\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), GO_LOOP);
        probe(dir, file, "go", GO_ADD, s -> {});
    }

    private static void probe(
            Path dir, Path file, String language, int line, Consumer<com.editora.config.Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            int stops = call(() -> debug.s().stopSerial);
            run(c, "debug.start");
            awaitStop(dap, debug, stops);
            assertEquals(line, call(() -> debug.panel().callStack().getFirst().line()), "the line breakpoint");
            assertTrue(call(dap::supportsInstructionBreakpoints), "the adapter takes instruction breakpoints");

            // The instruction after the one the program is at, still the loop's body (-O0, Delve's build): not where
            // the line breakpoint is.
            run(c, "debug.showDisassembly");
            DebugDisassemblyView view = call(() -> debug.panel().disassemblyView());
            await(
                    "the instructions",
                    60,
                    () -> call(() -> !view.list().getItems().isEmpty()));
            String ip = call(() -> debug.panel().callStack().getFirst().instructionPointer());
            List<DapModels.Instruction> shown =
                    call(() -> List.copyOf(view.list().getItems()));
            int at = DebugDisassemblyView.indexOf(shown, ip);
            assertTrue(at > 0, "the instruction pointer " + ip + " is shown, after the instructions before it");
            int pick = at + 1;
            String address = shown.get(pick).address();
            int row = pick;
            assertTrue(call(() -> {
                view.list().getSelectionModel().clearAndSelect(row);
                return view.toggleSelected();
            }));
            await("the instruction marked", 30, () -> call(() -> view.hasBreakpoint(address)));
            assertEquals(List.of(address), call(dap::instructionBreakpoints));

            // The line breakpoint goes: Continue stops at the instruction, the next time round.
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> debug.panel().showArea(DebugPanel.AREA_CONSOLE)); // the stop brings it back
            stops = call(() -> debug.s().stopSerial);
            run(c, "debug.continue");
            awaitStop(dap, debug, stops);
            String stoppedAt = call(() -> debug.panel().callStack().getFirst().instructionPointer());
            assertEquals(
                    DebugDisassemblyView.parseAddress(address),
                    DebugDisassemblyView.parseAddress(stoppedAt),
                    "stopped at the instruction breakpoint");
            await(
                    "the Disassembly view shown at it",
                    30,
                    () -> call(() ->
                            DebugPanel.AREA_DISASSEMBLY.equals(debug.panel().shownArea())
                                    && view.hasBreakpoint(address)
                                    && DebugDisassemblyView.indexOf(
                                                    List.copyOf(view.list().getItems()), stoppedAt)
                                            >= 0));

            // Taken off (F9 in the view, the margin's click): the loop runs out and the program ends.
            int stoppedRow = call(
                    () -> DebugDisassemblyView.indexOf(List.copyOf(view.list().getItems()), stoppedAt));
            assertTrue(call(() -> {
                view.list().getSelectionModel().clearAndSelect(stoppedRow);
                return view.toggleSelected();
            }));
            await("the mark gone", 30, () -> call(() -> !view.hasBreakpoint(address)));
            assertTrue(call(dap::instructionBreakpoints).isEmpty());
            run(c, "debug.continue");
            await("the program ended, not stopped again", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int stopsBefore) throws Exception {
        await(
                "a stop",
                120,
                () -> call(() -> debug.s().stopSerial > stopsBefore
                        && dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()));
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static boolean onPath(String tool) {
        return Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                .isAbsolute();
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
