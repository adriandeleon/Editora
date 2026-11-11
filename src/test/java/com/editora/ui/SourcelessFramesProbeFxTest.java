package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of stops in code the debugger has only in memory, against js-debug in a window: a {@code debugger}
 * statement in a function made by {@code eval} stops in a read-only tab holding the code js-debug gives, its execution
 * line marked; stepping into that function shows the same tab, line after line. (debugpy, with its default
 * {@code justMyCode}, leaves {@code exec}'d code out of the stack altogether.) Run with {@code ./mvnw test
 * -Dtest=SourcelessFramesProbeFxTest -Dgroups=probe -Dlsp.probe=true -Dnode.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class SourcelessFramesProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    /** Line 2 (0-based) of the evaluated function is {@code debugger;} / {@code breakpoint()}. */
    private static final String JS = """
            const code = "function twice(n) {\\n  const r = n * 2;\\n  debugger;\\n  return r;\\n}\\ntwice";
            const fn = eval(code);
            let total = 0;
            total += fn(3);
            console.log('total', total);
            """;

    @Test
    void javascriptStopsInEvalCodeShownReadOnly(@TempDir Path dir) throws Exception {
        assumeNode();
        Path file = write(dir, "evals.js", JS);
        inWindow(dir, file, -1, "javascript", (c, debug, dap) -> {
            EditorBuffer doc = awaitDoc(c, debug, dap, 2);
            assertTrue(call(() -> doc.getArea().getText().contains("debugger;")), "the evaluated code");
        });
    }

    @Test
    void javascriptStepsIntoEvalCode(@TempDir Path dir) throws Exception {
        assumeNode();
        Path file = write(dir, "evals.js", JS.replace("debugger;", "const z = r;"));
        inWindow(dir, file, 3, "javascript", (c, debug, dap) -> {
            awaitStop(dap, debug, 3);
            FxTestSupport.runOnFx(dap::stepInto);
            EditorBuffer doc = awaitDoc(c, debug, dap, 1);
            // Again, and the same tab is shown, not a second one.
            FxTestSupport.runOnFx(dap::stepOver);
            await("the next line", 30, () -> call(() -> marked(doc, 2)));
            assertSame(doc, call(c::activeBuffer));
        });
    }

    @Test
    void javascriptStopsOnABreakpointSetInEvalCode(@TempDir Path dir) throws Exception {
        assumeNode();
        Path file = write(dir, "evals.js", """
                const code = "function twice(n) {\\n  const r = n * 2;\\n  return r;\\n}\\ntwice";
                const fn = eval(code);
                let total = 0;
                total += fn(3);
                console.log('total', total);
                """);
        inWindow(dir, file, 2, "javascript", (c, debug, dap) -> {
            awaitStop(dap, debug, 2);
            LoadedSourcesPicker picker = debug.loadedSources();
            FxTestSupport.runOnFx(debug::showLoadedSources);
            await("the list", 30, () -> call(() -> picker.sourcesPicker != null));
            List<com.editora.dap.DapModels.LoadedSource> listed = call(() -> OverlayDriver.choices(c));
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(c));
            // The evaluated code: by reference, and not one of Node's internals js-debug skips.
            com.editora.dap.DapModels.LoadedSource evaluated = listed.stream()
                    .filter(src -> src.file() == null && src.sourceReference() > 0 && src.origin() == null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no evaluated code listed: " + listed));
            FxTestSupport.runOnFx(() -> picker.open(evaluated));
            await(
                    "its tab",
                    30,
                    () -> call(() -> c.activeBuffer() != null
                            && c.activeBuffer().getPath() == null
                            && c.activeBuffer().getArea().getText().contains("function twice")));
            EditorBuffer doc = call(c::activeBuffer);
            FxTestSupport.runOnFx(() -> doc.toggleBreakpoint(1)); // const r = n * 2;
            await(
                    "bound",
                    30,
                    () -> call(() -> doc.getBreakpointManager().live(1) != null
                            && doc.getBreakpointManager().live(1).state()
                                    == com.editora.editor.debug.BreakpointManager.LiveState.VERIFIED));
            FxTestSupport.runOnFx(dap::resume);
            assertSame(doc, awaitDoc(c, debug, dap, 1), "the same tab, stopped on its breakpoint");
            FxTestSupport.runOnFx(dap::stop);
            await("ended", 30, () -> !call(dap::isActive));
            assertTrue(call(() -> doc.getBreakpointManager().lines().isEmpty()), "gone with the session");
        });
    }

    private static void assumeNode() {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
    }

    private static Path write(Path dir, String name, String text) throws Exception {
        return Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve(name), text);
    }

    /** The read-only tab a stop in code without a file opened, its execution line on (0-based) {@code line}. */
    private static EditorBuffer awaitDoc(MainController c, DebugCoordinator debug, DapManager dap, int line)
            throws InterruptedException {
        try {
            await(
                    "a read-only tab with the execution line on " + (line + 1),
                    60,
                    () -> call(() -> c.activeBuffer() != null
                            && c.activeBuffer().getPath() == null
                            && c.activeBuffer().isViewMode()
                            && marked(c.activeBuffer(), line)));
        } catch (AssertionError e) {
            String top = call(() -> debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : debug.panel().callStack().getFirst().toString());
            String tab = call(
                    () -> c.activeBuffer() == null ? "none" : c.activeBuffer().getDisplayName());
            throw new AssertionError(e.getMessage() + " (" + call(dap::state) + ", " + top + ", tab " + tab + ")", e);
        }
        EditorBuffer doc = call(c::activeBuffer);
        assertNotNull(call(() -> debug.panel().callStack().getFirst().reference()));
        assertEquals(DapManager.State.SUSPENDED, call(dap::state));
        return doc;
    }

    /** Whether (0-based) {@code line} of {@code b} is marked as the execution line. FX thread. */
    private static boolean marked(EditorBuffer b, int line) {
        var area = b.getFocusedArea();
        return line < area.getParagraphs().size()
                && area.getParagraph(line).getParagraphStyle().contains("exec-line");
    }

    private interface AtStop {
        void run(MainController c, DebugCoordinator debug, DapManager dap) throws Exception;
    }

    /** Opens {@code file} in a window, with a breakpoint on {@code line} (none: -1), starts it, and runs {@code then}. */
    private void inWindow(Path dir, Path file, int line, String language, AtStop then) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await(language + " debugging available", 90, () -> call(() -> debug.debugEffectiveFor(language)));
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            if (line >= 0) {
                FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            }
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            then.run(c, debug, dap);
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    static void awaitStop(DapManager dap, DebugCoordinator debug, int line) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                120,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }
}
