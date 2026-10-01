package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning word wrap on must actually wrap, even when long lines were laid out unwrapped earlier and have
 * since scrolled out of view. The virtual flow remembers the width of every paragraph it has measured and
 * lays the viewport out at the widest one, so those stale widths used to keep every line unwrapped (and the
 * horizontal scrollbar showing) until the user happened to scroll back over them.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WordWrapToggleFxTest {

    private static final int LONG_LINE = 400;

    @Test
    void enablingWrapAfterScrollingPastLongLinesWrapsTheViewport() throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("editora-word-wrap");
        Path file = dir.resolve("long-lines.txt");
        String longLine = "word ".repeat(300);
        String content = (longLine + "\n").repeat(30) + "short\n".repeat(LONG_LINE - 30) + longLine + "\n"
                + "short\n".repeat(80);
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            assertFalse(fx.shared.getSettings().isWordWrap(), "word wrap defaults to off");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"),
                    "openPath",
                    new Class<?>[] {Path.class},
                    file));
            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
            assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
            CodeArea area = FxTestSupport.field(buffer, "area");
            // The long lines at the top are measured unwrapped, then scrolled out of view.
            assertTrue(waitUntil(() -> horizontalBar(buffer).isVisible()), "an unwrapped long line scrolls");
            assertTrue(
                    waitUntil(() -> {
                        area.showParagraphAtTop(LONG_LINE - 5);
                        int first = area.firstVisibleParToAllParIndex();
                        return first > 30 && first <= LONG_LINE && area.lastVisibleParToAllParIndex() >= LONG_LINE;
                    }),
                    "did not scroll the trailing long line into view");
            assertEquals(1, FxTestSupport.callOnFx(() -> area.getParagraphLinesCount(LONG_LINE)));

            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));

            assertTrue(
                    waitUntil(() -> area.getParagraphLinesCount(LONG_LINE) > 1),
                    "the visible long line must wrap once word wrap is on");
            assertTrue(
                    waitUntil(() -> !horizontalBar(buffer).isVisible()),
                    "a wrapped document must not keep the horizontal scrollbar");
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static ScrollBar horizontalBar(EditorBuffer buffer) {
        Parent scrollPane = FxTestSupport.field(buffer, "scrollPane");
        for (Node node : scrollPane.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL) {
                return bar;
            }
        }
        throw new AssertionError("the editor scroll pane has no horizontal scrollbar");
    }

    private static boolean waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
