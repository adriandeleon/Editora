package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.ConsoleEvaluation;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Edit Bytes… against the real adapters, in a window: a C program stops with a buffer holding
 * {@code hello}, the Memory view shows the buffer, its first row is edited to start with {@code J}, and the program
 * itself then reads {@code Jello}. On CodeLLDB and on GDB. Run with
 * {@code ./mvnw test -Dtest=MemoryWriteProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code cc} with CodeLLDB
 * ({@code -Dcodelldb.path=…} or Editora's install) or {@code gdb}.
 */
@Tag("probe")
@Tag("fx")
class MemoryWriteProbeFxTest {

    private static final String C_BUFFER = """
            #include <stdio.h>
            int main(void) {
                char word[8] = "hello";
                printf("%s\\n", word);
                return 0;
            }
            """;

    /** The 0-based line of the {@code printf}. */
    private static final int PRINT = 3;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    @Test
    void codelldbWritesMemory(@TempDir Path dir) throws Exception {
        String configured = NativeDebugProbeFxTest.assumeToolsAndCodelldb("cc");
        probe(dir, s -> s.setRustDebugPath(configured));
    }

    @Test
    void gdbWritesMemory(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gdb") && onPath("cc"), "needs gdb and cc");
        probe(dir, s -> s.setCppDebugger("gdb"));
    }

    private static void probe(Path dir, Consumer<com.editora.config.Settings> settings) throws Exception {
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("word")).toRealPath().resolve("word.c"), C_BUFFER);
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
            await("C debugging available", 120, () -> call(() -> debug.debugEffectiveFor("cpp")));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(PRINT));
            run(c, "debug.start");
            await(
                    "the stop at the printf",
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            assertTrue(call(dap::supportsWriteMemory), "the adapter writes memory");

            // The buffer in the Memory view, its first row edited: only the first byte changes.
            run(c, "debug.showMemory");
            DebugMemoryView memory = call(() -> debug.panel().memoryView());
            FxTestSupport.runOnFx(() -> {
                memory.addressField().setText("word");
                memory.addressField().fireEvent(new javafx.event.ActionEvent());
            });
            try {
                await(
                        "the buffer's bytes",
                        60,
                        () -> call(() -> !memory.rowList().getItems().isEmpty()
                                && memory.rowList().getItems().getFirst().contains("68 65 6c 6c 6f")));
            } catch (AssertionError e) {
                throw new AssertionError(
                        e.getMessage() + "; the view says: "
                                + call(() -> ((javafx.scene.control.Label)
                                                memory.rowList().getPlaceholder())
                                        .getText())
                                + " " + call(() -> List.copyOf(memory.rowList().getItems())),
                        e);
            }
            FxTestSupport.runOnFx(() -> memory.rowList().getSelectionModel().clearAndSelect(0));
            run(c, DebugMemoryView.EDIT);
            await("the Edit Memory prompt", 30, () -> call(() -> OverlayDriver.showing(c)));
            FxTestSupport.runOnFx(() -> OverlayDriver.answer(c, "\"J\""));
            await(
                    "the row read again",
                    60,
                    () -> call(() -> memory.rowList().getItems().getFirst().contains("4a 65 6c 6c 6f")));

            // The program sees it.
            int frame = call(() -> debug.panel().callStack().getFirst().id());
            CompletableFuture<ConsoleEvaluation> value = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> dap.evaluate("word[0]", frame, "watch", value::complete));
            String shown = value.get(60, TimeUnit.SECONDS).toString();
            assertTrue(shown.contains("74") || shown.contains("'J'"), "word[0] is J now: " + shown);
            run(c, "debug.continue");
            await("the program ended", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
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
