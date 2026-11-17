package com.editora.ui;

import java.nio.file.Path;
import java.time.Duration;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TypewriterMode;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Typewriter mode keeps the caret's line in the middle of an editor that is really on screen. */
@Tag("fx")
class TypewriterModeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * How far the caret's line is from the middle visible line, as a share of the lines on show — read again
     * until two readings agree, since the scroll lands a pulse or two after the caret moved.
     */
    private static double offCentre(EditorBuffer buffer) throws Exception {
        double[] last = {Double.NaN};
        // Read once per FX pulse until two readings in a row agree.
        Await.holdsWithin(
                () -> {
                    Double now = onFx(() -> {
                        var area = buffer.getArea();
                        int first = area.firstVisibleParToAllParIndex();
                        int lastVisible = area.lastVisibleParToAllParIndex();
                        if (lastVisible - first < 5) {
                            return null; // not laid out yet
                        }
                        return (area.getCurrentParagraph() - (first + lastVisible) / 2.0) / (lastVisible - first);
                    });
                    boolean agreed = now != null && Math.abs(now - last[0]) < 0.002;
                    last[0] = now == null ? Double.NaN : now;
                    return agreed;
                },
                Duration.ofSeconds(10));
        return last[0];
    }

    @Test
    void aCaretMovedToAnotherLineIsBroughtToTheMiddleAndLeftAloneWhenTheModeIsOff() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("/tmp/typewriter/a.md").toAbsolutePath());
            b.setContent("line of text\n".repeat(400));
            return b;
        });
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(buffer.getNode()), 700, 500));
            s.show();
            buffer.getArea().moveTo(0, 0);
            return s;
        });
        try {
            // Off: a caret moved down a few lines stays where the scroll left it — near the top.
            FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(5, 0));
            assertTrue(offCentre(buffer) < -0.2, "without the mode the caret is not centred: " + offCentre(buffer));

            FxTestSupport.runOnFx(() -> TypewriterMode.apply(buffer, true));
            for (int line : new int[] {200, 201, 120, 300}) {
                FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(line, 3));
                double off = offCentre(buffer);
                assertTrue(Math.abs(off) < 0.08, "line " + line + " is in the middle: " + off);
            }
            // Typing within a line moves nothing.
            double before = offCentre(buffer);
            FxTestSupport.runOnFx(
                    () -> buffer.getArea().insertText(buffer.getArea().getCaretPosition(), "typed"));
            assertEquals(before, offCentre(buffer), 0.01);

            // Off again: the next line change scrolls only as far as it has to.
            FxTestSupport.runOnFx(() -> {
                TypewriterMode.apply(buffer, false);
                buffer.getArea().moveTo(301, 0);
                buffer.getArea().requestFollowCaret();
            });
            assertTrue(FxTestSupport.callOnFx(() -> !TypewriterMode.isOn(buffer.getArea())));
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
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
