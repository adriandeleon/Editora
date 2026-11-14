package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.LoadedSourceEventArgumentsReason;
import org.eclipse.lsp4j.debug.ModuleEventArgumentsReason;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Loaded Sources and Modules through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: what
 * the requests answer and what the events say are listed together, the program's file first; a file opens in its
 * tab and a source the adapter has only by reference read-only with its text; a module's path is copied. An adapter
 * that lists neither says so. {@code LoadedSourcesProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class LoadedSourcesWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("loaded")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "x = 1\nputs x\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
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

    @AfterEach
    void end() throws Exception {
        if (OverlayDriver.showing(fx.controller)) {
            FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(fx.controller));
        }
        if (call(manager::isActive)) {
            FxTestSupport.runOnFx(manager::stop);
            await("ended", () -> !call(manager::isActive));
        }
        adapter.loadedSources = null;
        adapter.modules = null;
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
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private FakeDebugAdapter.Session start() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        return session;
    }

    @Test
    void sourcesAndModulesAreListedAndOpened() throws Exception {
        FxTestSupport.runOnFx(debug::showLoadedSources);
        assertEquals(tr("palette.disabled.needsDebugSession"), status(), "no session");

        adapter.loadedSources = List.of(FakeDebugAdapter.Session.source("zeta.rb", script.toString(), 0));
        adapter.modules =
                List.of(FakeDebugAdapter.Session.module("m1", "libruby.so", "/usr/lib/libruby.so", "Symbols loaded."));
        adapter.sourceTexts.put(7, "puts 'from eval'\n");
        FakeDebugAdapter.Session session = start();
        session.loadedSource(
                FakeDebugAdapter.Session.source("<eval>", "<eval>/1", 7), LoadedSourceEventArgumentsReason.NEW);
        session.loadedSource(
                FakeDebugAdapter.Session.source("gone.rb", "<eval>/2", 8), LoadedSourceEventArgumentsReason.NEW);
        session.loadedSource(
                FakeDebugAdapter.Session.source("gone.rb", "<eval>/2", 8), LoadedSourceEventArgumentsReason.REMOVED);
        session.module(
                FakeDebugAdapter.Session.module("m2", "app.so", "/work/app.so", null), ModuleEventArgumentsReason.NEW);
        session.awaitDelivered();

        LoadedSourcesPicker picker = call(debug::loadedSources);
        run("debug.loadedSources");
        await("the sources", () -> call(() -> picker.sourcesPicker != null && OverlayDriver.showing(fx.controller)));
        List<DapModels.LoadedSource> sources = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(
                List.of("zeta.rb", "<eval>"),
                sources.stream().map(DapModels.LoadedSource::name).toList());
        assertEquals(script, sources.getFirst().file(), "a file, first");
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, sources.get(1)));
        await(
                "the eval source, read-only",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && fx.controller.activeBuffer().getPath() == null
                        && fx.controller.activeBuffer().getArea().getText().equals("puts 'from eval'\n")));
        EditorBuffer opened = call(fx.controller::activeBuffer);
        assertTrue(call(opened::isViewMode));
        FxTestSupport.runOnFx(() -> picker.open(sources.getFirst()));
        await(
                "the file, in its tab",
                () -> call(() -> script.equals(fx.controller.activeBuffer().getPath())));
        FxTestSupport.runOnFx(() -> picker.open(new DapModels.LoadedSource("lost", "<eval>/9", null, 9, null)));
        await("said", () -> tr("status.debug.sourceUnavailable", "lost").equals(status()));
        assertTrue(session.requests.contains("source"));

        run("debug.modules");
        await("the modules", () -> call(() -> picker.modulesPicker != null && OverlayDriver.showing(fx.controller)));
        List<DapModels.ModuleInfo> modules = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(
                List.of("app.so", "libruby.so"),
                modules.stream().map(DapModels.ModuleInfo::name).toList());
        assertEquals("Symbols loaded.  ·  /usr/lib/libruby.so", LoadedSourcesPicker.moduleDetail(modules.get(1)));
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, modules.get(1)));
        assertEquals(tr("status.debug.modulePathCopied", "/usr/lib/libruby.so"), status());
        assertEquals(
                "/usr/lib/libruby.so",
                call(() -> javafx.scene.input.Clipboard.getSystemClipboard().getString()));
    }

    @Test
    void anAdapterThatListsNeitherSaysSo() throws Exception {
        start();
        String label = DebugAdapterPickers.label("ruby");
        run("debug.loadedSources");
        assertEquals(tr("status.debug.noLoadedSources", label), status());
        run("debug.modules");
        assertEquals(tr("status.debug.noModules", label), status());
    }
}
