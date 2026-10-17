package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8: a breakpoint on a blank or comment-only line was accepted — java-debug even reported it verified — and
 * never stopped. In a real window, by key and by gutter click, such a line now gets no breakpoint and the
 * status bar says why; a code line, and removing a breakpoint that is already there, work as before.
 */
@Tag("fx")
class DebugBreakpointLinesFxTest {

    private static final String SOURCE = String.join(
            "\n",
            "public class Main {", // 0
            "    public static void main(String[] args) {", // 1
            "        // just a comment", // 2
            "", // 3
            "        int x = 1;", // 4
            "    }", // 5
            "}",
            "");

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void caretAndToggle(MainController c, int line) throws Exception {
        FxTestSupport.runOnFx(() -> {
            c.activeBuffer().getFocusedArea().moveTo(line, 0);
            CommandRegistry reg = FxTestSupport.field(c, "registry");
            reg.run("debug.toggleBreakpoint");
        });
    }

    private String lastStatus(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(c, "statusBar");
            MessageLog log = FxTestSupport.field(bar, "messageLog");
            return log.entries().isEmpty() ? "" : log.entries().get(0).text();
        });
    }

    @Test
    void blankAndCommentLinesGetNoBreakpointButCodeLinesDo() throws Exception {
        Path dir = Files.createTempDirectory("editora-bp-lines");
        fx = FxWindowFixture.create(dir, shared -> shared.getSettings().setDebugSupport(true));
        MainController c = fx.controller;
        Path main =
                Files.writeString(Files.createDirectories(dir.resolve("src")).resolve("Main.java"), SOURCE);
        FxTestSupport.runOnFx(() -> c.openAndNavigate(main, 0));
        FxTestSupport.drainFx();

        caretAndToggle(c, 2); // the comment line
        assertFalse(FxTestSupport.callOnFx(
                () -> c.activeBuffer().getBreakpointManager().isBreakpoint(2)));
        assertEquals(com.editora.i18n.Messages.tr("status.debug.noCodeOnLine"), lastStatus(c));

        // the gutter takes the same road
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = c.activeBuffer();
            java.util.function.BiConsumer<EditorBuffer, Integer> click =
                    FxTestSupport.field(b, "gutterBreakpointClick");
            click.accept(b, 3); // the blank line
        });
        assertFalse(FxTestSupport.callOnFx(
                () -> c.activeBuffer().getBreakpointManager().isBreakpoint(3)));

        caretAndToggle(c, 4); // code
        assertTrue(FxTestSupport.callOnFx(
                () -> c.activeBuffer().getBreakpointManager().isBreakpoint(4)));

        // a breakpoint stored on a line that later became a comment can still be removed
        FxTestSupport.runOnFx(() -> c.activeBuffer().toggleBreakpoint(2));
        caretAndToggle(c, 2);
        assertFalse(FxTestSupport.callOnFx(
                () -> c.activeBuffer().getBreakpointManager().isBreakpoint(2)));
    }
}
