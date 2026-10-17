package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in ({@code -Dlsp.probe=true}), real jdtls + java-debug: a saved change reaches the running program (hot
 * code replace), Smart Step Into enters the picked call, and Restart Frame runs a method again from its start.
 * A plain-folder project window, as a user without a build file has it.
 */
@Tag("probe")
@Tag("fx")
class JavaCodeChangesProbeFxTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    for (int i = 0; i < 2000; i++) {
                        System.out.println(greet(i));
                        Thread.sleep(100);
                        if (i == 30) calc();
                    }
                }

                static String greet(int i) {
                    return "hello-v1 " + i;
                }

                static int twice(int x) {
                    return x * 2;
                }

                static int add(int a, int b) {
                    int s = a + b;
                    return s;
                }

                static void calc() {
                    int r = add(twice(1), twice(2));
                    System.out.println("r=" + r);
                }
            }
            """;

    /** 0-based lines of {@link #SOURCE}. */
    private static final int CALC_LINE = 23;

    private static final int ADD_FIRST_LINE = 18;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(FxWindowFixture fx, DebugCoordinator debug, DapManager dap, CommandRegistry registry) {}

    /** A project window with Main.java open, Java debugging ready, a session running (breakpoint on calc if asked). */
    private static Window start(Path dir, boolean breakOnCalc) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null);
        Path cfg = Files.createDirectories(dir.resolve("config"));
        FxWindowFixture fx = FxWindowFixture.create(cfg, shared -> {
            var s = shared.getSettings();
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setDebugSupport(true);
            s.setJavaDebugPluginPath(plugin.toString());
        });
        Path root = fx.useProject();
        Path main = root.resolve("Main.java");
        Files.writeString(main, SOURCE);
        MainController c = fx.controller;
        FxTestSupport.runOnFx(() -> c.openAndNavigate(main, 1));
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        CommandRegistry registry = FxTestSupport.field(c, "registry");
        Window w = new Window(fx, debug, dap, registry);
        await(w, "Java debugging to be ready", 600, () -> FxTestSupport.callOnFx(debug::debugEffective));
        if (breakOnCalc) {
            FxTestSupport.runOnFx(() -> c.activeBuffer().toggleBreakpoint(CALC_LINE));
        }
        Thread.sleep(Long.getLong("importWait", 15000)); // let jdtls import the folder
        FxTestSupport.runOnFx(() -> registry.run("debug.start"));
        await(w, "the program's output", 900, () -> console(w).contains("hello-v1 2"));
        return w;
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private static void await(Window w, String what, int tenths, Check check) throws Exception {
        for (int i = 0; i < tenths; i++) {
            if (check.holds()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(what + " never happened; console:\n" + console(w));
    }

    private static String console(Window w) throws Exception {
        if (w.debug() == null) {
            return "";
        }
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = FxTestSupport.field(w.debug().panel(), "console");
            return a.getText();
        });
    }

    private static void stop(Window w) throws Exception {
        FxTestSupport.runOnFx(w.dap()::stop);
        for (int i = 0; i < 100 && FxTestSupport.callOnFx(w.dap()::isActive); i++) {
            Thread.sleep(100);
        }
        w.fx().dispose();
    }

    private static DapModels.StackFrameInfo topFrame(Window w) throws Exception {
        await(w, "a stop", 300, () -> FxTestSupport.callOnFx(w.dap()::state) == DapManager.State.SUSPENDED);
        CompletableFuture<List<DapModels.StackFrameInfo>> frames = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> w.dap().stackTrace(w.dap().currentThreadId(), frames::complete));
        return frames.get(20, TimeUnit.SECONDS).get(0);
    }

    @Test
    void aSavedChangeReachesTheRunningProgram(@TempDir Path dir) throws Exception {
        Window w = start(dir, false);
        try {
            FxTestSupport.runOnFx(() -> {
                EditorBuffer b = w.fx().controller.activeBuffer();
                int at = b.getArea().getText().indexOf("hello-v1");
                b.getArea().replaceText(at, at + "hello-v1".length(), "hello-v2");
                w.registry().run("file.save");
            });

            await(w, "the new code to run", 300, () -> console(w).contains("hello-v2"));
            assertTrue(console(w).contains(tr("debug.hcr.applied", "Main")), console(w));
            assertEquals(DapManager.State.RUNNING, FxTestSupport.callOnFx(w.dap()::state), "no restart");
        } finally {
            stop(w);
        }
    }

    @Test
    void smartStepIntoEntersThePickedCall(@TempDir Path dir) throws Exception {
        Window w = start(dir, true);
        try {
            assertEquals(CALC_LINE, topFrame(w).line());
            CompletableFuture<List<DapModels.StepTarget>> listed = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> w.dap().stepInTargets(listed::complete));
            List<DapModels.StepTarget> targets = listed.get(20, TimeUnit.SECONDS);
            assertEquals(3, targets.size(), targets.toString());

            FxTestSupport.runOnFx(() -> w.registry().run("debug.smartStepInto"));
            QuickOpen<DapModels.StepTarget>[] picker = new QuickOpen[1];
            await(w, "the picker", 100, () -> (picker[0] = FxTestSupport.field(w.debug(), "smartStepPicker")) != null);
            java.util.function.Consumer<DapModels.StepTarget> choose = FxTestSupport.field(picker[0], "onChoose");
            DapModels.StepTarget add = targets.stream()
                    .filter(t -> t.label().startsWith("add"))
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> choose.accept(add));

            await(
                    w,
                    "the step to end",
                    300,
                    () -> FxTestSupport.callOnFx(w.dap()::state) != DapManager.State.SUSPENDED);
            DapModels.StackFrameInfo top = topFrame(w);
            assertTrue(top.name().contains("add"), "stepped over both twice() calls into add: " + top);
            assertEquals(ADD_FIRST_LINE, top.line());
        } finally {
            stop(w);
        }
    }

    @Test
    void restartFrameRunsTheMethodAgainFromItsStart(@TempDir Path dir) throws Exception {
        Window w = start(dir, true);
        try {
            topFrame(w);
            CompletableFuture<List<DapModels.StepTarget>> listed = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> w.dap().stepInTargets(listed::complete));
            DapModels.StepTarget add = listed.get(20, TimeUnit.SECONDS).stream()
                    .filter(t -> t.label().startsWith("add"))
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> w.dap().stepIntoTarget(add.id()));
            await(w, "add's first line", 300, () -> topFrame(w).line() == ADD_FIRST_LINE);
            FxTestSupport.runOnFx(() -> w.registry().run("debug.stepOver"));
            await(w, "add's second line", 300, () -> topFrame(w).line() == ADD_FIRST_LINE + 1);
            await(w, "the frame to be shown", 100, () -> FxTestSupport.field(w.debug(), "shownFrame") != null);

            FxTestSupport.runOnFx(() -> w.registry().run("debug.restartFrame"));

            await(w, "add to start again", 300, () -> {
                DapModels.StackFrameInfo top = topFrame(w);
                return top.name().contains("add") && top.line() == ADD_FIRST_LINE;
            });
            assertTrue(!console(w).contains("r=6"), "add did not return: it was restarted");
        } finally {
            stop(w);
        }
    }
}
