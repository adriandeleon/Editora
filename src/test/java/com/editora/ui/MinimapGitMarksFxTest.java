package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.GitHunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimap's Git stripe. A scroll repaints the column every pulse, so the marks must not be recomputed
 * (or anything allocated for them) there: they are read again only when the bars or the line mapping change.
 */
@Tag("fx")
class MinimapGitMarksFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theMarksArePaintedAtTheEdgeAndReadOnlyWhenTheyChange() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("line ").append(i).append('\n');
        }
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(text.toString());
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(b.getNode()), 900, 600));
            stage.show();
            return b;
        });
        try {
            Object minimap = FxTestSupport.field(buffer, "minimap");
            waitForRender(minimap);
            FxTestSupport.runOnFx(() -> {
                buffer.setChangeBars(Map.of(100, "git-added", 101, "git-added", 102, "git-added"), Map.of());
                buffer.gitGutter().setHunks(List.of(new GitHunk(100, 3, List.of(), List.of("a", "b", "c"), false)));
            });
            drain();

            // Painted: the left edge of the column, at the rows of lines 100-102, is the "added" green.
            Color edge = FxTestSupport.callOnFx(() -> {
                Canvas canvas = FxTestSupport.field(minimap, "canvas");
                WritableImage image = canvas.snapshot(null, null);
                double rowHeight = Math.min(
                        3.0,
                        canvas.getHeight() / buffer.getArea().getParagraphs().size());
                return image.getPixelReader().getColor(1, (int) (101 * rowHeight));
            });
            assertTrue(
                    edge.getGreen() > edge.getRed() + 0.15 && edge.getGreen() > edge.getBlue() + 0.15, edge::toString);

            // Read once per change, never per scroll repaint.
            AtomicInteger reads = new AtomicInteger();
            Supplier<int[]> counting = () -> {
                reads.incrementAndGet();
                return buffer.gitGutter().marks();
            };
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(minimap, "setGitMarks", new Class<?>[] {Supplier.class}, counting));
            drain();
            assertEquals(1, reads.get(), "read for the repaint that followed the change");
            for (int i = 0; i < 10; i++) {
                FxTestSupport.runOnFx(() -> buffer.getArea().scrollYBy(120));
                drain();
            }
            assertEquals(1, reads.get(), "scrolling repaints from the array it already has");

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "typed\n"));
            drain();
            assertEquals(2, reads.get(), "a line typed above moves the marks: read again, once");
            assertEquals(
                    101,
                    FxTestSupport.callOnFx(
                            () -> ((int[]) FxTestSupport.call(minimap, "gitMarksForTest", new Class<?>[] {}))[0]));
        } finally {
            FxTestSupport.runOnFx(() -> ((Stage) buffer.getNode().getScene().getWindow()).close());
        }
    }

    private static void drain() throws Exception {
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
            Thread.sleep(30);
        }
        FxTestSupport.drainFx();
    }

    private static void waitForRender(Object minimap) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "contentImage")) != null) {
                return;
            }
            Thread.sleep(20);
        }
        assertNotNull(null, "the minimap never rendered");
    }
}
