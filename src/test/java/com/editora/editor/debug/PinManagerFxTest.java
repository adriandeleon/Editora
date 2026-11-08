package com.editora.editor.debug;

import java.util.List;
import java.util.NavigableMap;

import com.editora.config.PinnedLine;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Values pinned to a buffer's lines follow their line as the text changes. */
@Tag("fx")
class PinManagerFxTest {

    @BeforeAll
    static void boot() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    /** Runs {@code body} on the FX thread and rethrows what it threw. */
    private static void onFx(Runnable body) throws Exception {
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                body.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    @Test
    void aPinTogglesAndFollowsItsLineThroughEdits() throws Exception {
        onFx(() -> {
            CodeArea area = new CodeArea("int a = 1;\nint b = 2;\nint c = a + b;\n");
            PinManager pins = new PinManager(area);
            int[] changes = {0};
            pins.setOnChanged(() -> changes[0]++);
            assertTrue(pins.toggle(2, "c"));
            assertTrue(pins.toggle(2, "a + b"));
            assertEquals(List.of("c", "a + b"), pins.expressions(2));
            assertFalse(pins.toggle(2, "c"), "again: unpinned");
            assertEquals(List.of("a + b"), pins.expressions(2));

            area.insertText(0, "// header\n"); // a line above: the pin moves down with its line
            assertEquals(List.of(), pins.expressions(2));
            assertEquals(List.of("a + b"), pins.expressions(3));
            assertEquals("int c = a + b;", pins.snapshot().getFirst().lineText());
            assertTrue(changes[0] >= 4);

            assertTrue(pins.clearLine(3));
            assertTrue(pins.isEmpty());
            assertFalse(pins.clearLine(3));
            assertFalse(pins.toggle(9, "x"), "past the end");
            for (int i = 0; i < PinManager.MAX_PER_LINE; i++) {
                assertTrue(pins.toggle(0, "e" + i));
            }
            assertFalse(pins.toggle(0, "one more"), "a line holds at most " + PinManager.MAX_PER_LINE);
        });
    }

    @Test
    void savedPinsReanchorToTheirLineTextAfterAnOutsideEdit() {
        List<String> lines = List.of("// new first line", "int a = 1;", "int c = a + b;");
        NavigableMap<Integer, PinnedLine> resolved = PinManager.reanchor(
                List.of(
                        new PinnedLine(1, List.of("c"), "int c = a + b;"),
                        new PinnedLine(0, List.of("a"), "int a = 1;"),
                        new PinnedLine(7, List.of("gone"), "deleted line"),
                        new PinnedLine(2, List.of(), "empty")),
                lines.size(),
                lines::get,
                2000);
        assertEquals(List.of("c"), resolved.get(2).expressions(), "followed its text down a line");
        assertEquals(List.of("a"), resolved.get(1).expressions());
        assertEquals(List.of("gone"), resolved.get(0).expressions(), "its text is gone: the nearest free line");
        assertEquals(3, resolved.size(), "a line with no expression is dropped");
    }
}
