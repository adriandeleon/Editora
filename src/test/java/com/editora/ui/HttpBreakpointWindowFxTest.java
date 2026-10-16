package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Breakpoints in a {@code .http} file, in a real window: with JavaScript debugging on, the file has a breakpoint gutter,
 * a script line takes a breakpoint and any other line says why it does not (adl, 2026-10-16).
 */
@Tag("fx")
class HttpBreakpointWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void onlyScriptLinesTakeABreakpoint(@TempDir Path work) throws Exception {
        Path file = work.resolve("api.http");
        Files.writeString(file, "### one\nGET http://h/x\n\n> {%\n  const a = 1;\n  client.log(a);\n%}\n");
        Path cfg = Files.createDirectories(work.resolve("cfg"));
        FxWindowFixture fx = FxWindowFixture.create(
                cfg, false, false, false, List.of(new MainController.OpenTarget(file, 1, 1)), true, c -> {});
        try (fx) {
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setHttpClientSupport(true);
                s.setDebugSupport(true);
                s.setJsDebugEnabled(true);
            });
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    fx.controller,
                    "onSettingsApplied",
                    new Class<?>[] {com.editora.config.Settings.class},
                    fx.shared.getSettings()));
            EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
            DebugCoordinator debug = FxTestSupport.field(fx.controller, "debugCoordinator");
            FxTestSupport.runOnFx(debug::applyGating);
            assertTrue(FxTestSupport.callOnFx(b::isBreakpointsEnabled), "a .http file has the breakpoint gutter");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");

            FxTestSupport.runOnFx(() -> {
                b.getArea().moveTo(1, 0); // the request line
                registry.run("debug.toggleBreakpoint");
            });
            assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(1)));
            String status = FxTestSupport.callOnFx(() -> {
                StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo")
                        .getText();
            });
            assertEquals(Messages.tr("status.debug.httpScriptLinesOnly"), status);

            FxTestSupport.runOnFx(() -> {
                b.getArea().moveTo(4, 0); // "const a = 1;"
                registry.run("debug.toggleBreakpoint");
            });
            assertTrue(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(4)));
        }
    }
}
