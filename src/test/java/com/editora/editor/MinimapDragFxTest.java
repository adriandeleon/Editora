package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real {@link Minimap}'s input handlers against a real {@code CodeArea}. The pure mappings are
 * covered by {@code MinimapDragMappingTest}; this pins the wiring — that a drag reaches a continuous scroll
 * offset rather than a paragraph index, that grabbing the viewport box holds it instead of teleporting it,
 * and that the wheel over the column scrolls at all.
 */
@Tag("fx")
class MinimapDragFxTest {

    private static final int LINES = 600;
    private static final double COLUMN_H = 900;

    private CodeArea area;
    private Minimap minimap;
    private Canvas canvas;

    private static void onFx(Runnable r) throws Exception {
        if (Platform.isFxApplicationThread()) {
            r.run();
            return;
        }
        var err = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out on the FX thread");
        }
        if (err.get() != null) {
            throw new RuntimeException(err.get());
        }
    }

    private static MouseEvent mouseAt(EventType<MouseEvent> type, double y) {
        return new MouseEvent(
                type,
                45,
                y,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static ScrollEvent wheel(double deltaY) {
        return new ScrollEvent(
                ScrollEvent.SCROLL,
                45,
                300,
                0,
                0,
                false,
                false,
                false,
                false,
                true,
                false,
                0,
                deltaY,
                0,
                deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0,
                0,
                null);
    }

    /** Fires an event at the minimap and returns the editor's resulting scroll offset. */
    private double fire(Event e) throws Exception {
        var scrollY = new AtomicReference<Double>();
        onFx(() -> {
            Event.fireEvent(e.getEventType() == ScrollEvent.SCROLL ? minimap : canvas, e);
            area.layout();
            scrollY.set(area.estimatedScrollYProperty().getValue());
        });
        return scrollY.get();
    }

    private double[] box() throws Exception {
        var out = new AtomicReference<double[]>();
        onFx(() -> out.set(minimap.viewportBox()));
        return out.get();
    }

    @BeforeEach
    void buildWindow() throws Exception {
        FxToolkit.registerPrimaryStage();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            text.append("line ").append(i).append(" of a long file\n");
        }
        var areaRef = new AtomicReference<CodeArea>();
        var minimapRef = new AtomicReference<Minimap>();
        var canvasRef = new AtomicReference<Canvas>();
        onFx(() -> {
            CodeArea a = new CodeArea();
            a.setWrapText(false);
            a.replaceText(text.toString());
            a.moveTo(0);
            Minimap m = new Minimap(a);
            var scroll = new VirtualizedScrollPane<>(a);
            HBox.setHgrow(scroll, Priority.ALWAYS);
            Stage stage = new Stage();
            stage.setScene(new Scene(new HBox(scroll, m), 1200, COLUMN_H));
            stage.show();
            areaRef.set(a);
            minimapRef.set(m);
            try {
                var f = Minimap.class.getDeclaredField("canvas");
                f.setAccessible(true);
                canvasRef.set((Canvas) f.get(m));
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        // Let the flow measure itself so totalHeightEstimate is populated.
        for (int i = 0; i < 30; i++) {
            onFx(() -> {});
            Thread.sleep(20);
        }
        area = areaRef.get();
        minimap = minimapRef.get();
        canvas = canvasRef.get();
        // replaceText leaves the viewport at the END of the document (and moveTo doesn't scroll), so park
        // it at the top: otherwise every test starts clamped against the bottom and a scroll down is a no-op.
        onFx(() -> {
            area.estimatedScrollYProperty().setValue(0.0);
            area.layout();
        });
    }

    @Test
    void draggingTheMinimapScrollsTheDocumentSmoothly() throws Exception {
        double previous = fire(mouseAt(MouseEvent.MOUSE_PRESSED, 300));
        assertTrue(previous > 0, "a press partway down the column must scroll the document");

        int stalled = 0;
        for (int px = 301; px <= 340; px++) {
            double now = fire(mouseAt(MouseEvent.MOUSE_DRAGGED, px));
            double delta = now - previous;
            assertTrue(delta >= 0, "a downward drag must never scroll upward (at y=" + px + ")");
            if (delta <= 0.5) {
                stalled++;
            }
            previous = now;
        }
        // The paragraph-indexed jump stalled on roughly a third of these pixels and made up the difference
        // with a whole-line jump on the next one; a continuous mapping stalls on none.
        assertTrue(stalled <= 2, "the drag stair-stepped: " + stalled + " of 40 pixels of travel moved nothing");
    }

    @Test
    void grabbingTheViewportBoxHoldsItInsteadOfTeleportingIt() throws Exception {
        // Scroll somewhere so the box has room above it, then take hold of it well below its top edge.
        double before = fire(mouseAt(MouseEvent.MOUSE_PRESSED, 300));
        double[] b = box();
        double grabAt = b[0] + b[1] * 0.75;
        assertTrue(Minimap.withinBox(grabAt, b[0], b[1]), "the test must grab the box, not miss it");

        double afterPress = fire(mouseAt(MouseEvent.MOUSE_PRESSED, grabAt));
        assertEquals(before, afterPress, 0.5, "taking hold of the box must not move the document");

        // Now drag it down by a known distance: the document must follow by that distance, not jump so the
        // box's TOP lands under the cursor (which would throw it most of a viewport further).
        double travel = 20;
        double afterDrag = fire(mouseAt(MouseEvent.MOUSE_DRAGGED, grabAt + travel));
        double expected = Minimap.documentScrollY(b[0] + travel, rowHeight(), paragraphs(), totalHeight());
        assertEquals(expected, afterDrag, 1.0, "the box must slide by the drag distance");
    }

    @Test
    void aPressOutsideTheBoxStillJumps() throws Exception {
        double[] b = box();
        double below = b[0] + b[1] + 200;
        assertFalse(Minimap.withinBox(below, b[0], b[1]));
        double after = fire(mouseAt(MouseEvent.MOUSE_PRESSED, below));
        double expected = Minimap.documentScrollY(below, rowHeight(), paragraphs(), totalHeight());
        assertEquals(expected, after, 1.0, "clicking the column is a 'go here'");
    }

    @Test
    void theWheelOverTheColumnScrollsTheEditor() throws Exception {
        // The minimap is a sibling of the scroll pane, so without a handler the wheel reaches nothing.
        double start = fire(wheel(0));
        double down = fire(wheel(-120));
        assertTrue(down > start, "a wheel down over the minimap must scroll the document down");
        double up = fire(wheel(120));
        assertEquals(start, up, 1.0, "and a wheel back up must return it");
    }

    private double totalHeight() throws Exception {
        var out = new AtomicReference<Double>();
        onFx(() -> out.set(area.totalHeightEstimateProperty().getValue()));
        return out.get();
    }

    /** The minimap's own row height, read from the live geometry rather than assumed. */
    private double rowHeight() throws Exception {
        var out = new AtomicReference<Double>();
        onFx(() ->
                out.set(Math.min(3.0, minimap.getHeight() / area.getParagraphs().size())));
        return out.get();
    }

    private int paragraphs() throws Exception {
        var out = new AtomicReference<Integer>();
        onFx(() -> out.set(area.getParagraphs().size()));
        return out.get();
    }
}
