package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        onScrolledPastLongLines((fx, buffer, area) -> {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));

            assertTrue(
                    waitUntil(() -> area.getParagraphLinesCount(LONG_LINE) > 1),
                    () -> "the visible long line must wrap once word wrap is on; " + state(area));
            assertTrue(
                    waitUntil(() -> !horizontalBar(buffer).isVisible()),
                    "a wrapped document must not keep the horizontal scrollbar");
        });
    }

    /**
     * The same, again and again in one window. The re-measure could leave a long line's unwrapped width in place: a
     * cell the flow shows is measured again only when its layout is dirty, and reading its line count lays it out. Then
     * nothing wrapped, about one time in four (and on slow runners more often); each round here is another chance.
     */
    @Test
    void enablingWrapWrapsEveryTime() throws Exception {
        onScrolledPastLongLines((fx, buffer, area) -> {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            for (int round = 1; round <= ROUNDS; round++) {
                int r = round;
                FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap")); // on
                assertTrue(
                        waitUntil(
                                () -> area.getParagraphLinesCount(LONG_LINE) > 1 && area.getParagraphLinesCount(0) > 1),
                        () -> "round " + r + ": the long lines must wrap once word wrap is on; " + state(area));
                FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap")); // off again
                // Measure the first long lines unwrapped, then leave them, as before the first round.
                assertTrue(waitUntil(() -> {
                    area.showParagraphAtTop(0);
                    return area.firstVisibleParToAllParIndex() == 0 && area.getParagraphLinesCount(0) == 1;
                }));
                assertTrue(waitUntil(() -> {
                    area.showParagraphAtTop(LONG_LINE - 5);
                    int first = area.firstVisibleParToAllParIndex();
                    return first > 30 && first <= LONG_LINE && area.getParagraphLinesCount(LONG_LINE) == 1;
                }));
            }
        });
    }

    private static final int ROUNDS = 8;

    private interface Scrolled {
        void run(FxWindowFixture fx, EditorBuffer buffer, CodeArea area) throws Exception;
    }

    /**
     * Opens a file whose first 30 lines and line {@link #LONG_LINE} are long, unwrapped, and scrolls to the last long
     * line, so the first ones were measured unwrapped and left the view; then runs {@code then}.
     */
    private static void onScrolledPastLongLines(Scrolled then) throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("editora-word-wrap");
        Path file = dir.resolve("long-lines.txt");
        String longLine = "word ".repeat(300);
        String content = (longLine + "\n").repeat(30) + "short\n".repeat(LONG_LINE - 30) + longLine + "\n"
                + "short\n".repeat(80);
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            // A plain-text file wraps by default; this is about switching wrap on over lines already laid
            // out unwrapped, so it starts with the prose preference off.
            fx.shared.getSettings().setWordWrapProse(false);
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
            then.run(fx, buffer, area);
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    /** What the view looks like, for a failure's message. */
    private static String state(CodeArea area) {
        org.fxmisc.flowless.VirtualFlow<?, ?> flow = FxTestSupport.field(area, "virtualFlow");
        return onFx(() -> "wrapText=" + area.isWrapText() + ", width=" + area.getWidth() + ", cells laid out at="
                + flow.totalBreadthEstimateProperty().getValue() + ", first visible="
                + area.firstVisibleParToAllParIndex());
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
        return Await.holdsWithin(() -> onFx(condition::getAsBoolean), Duration.ofSeconds(10));
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
