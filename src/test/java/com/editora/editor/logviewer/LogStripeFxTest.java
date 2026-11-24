package com.editora.editor.logviewer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stripe laid out and painted: where its marks are, what hovering one shows, where a click goes. */
@Tag("fx")
class LogStripeFxTest {

    private static final int LINES = 100;
    private static final double HEIGHT = 300;

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static <T> T onFx(Callable<T> task) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(task.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static String log() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            String level = i == 10 ? "WARN " : i == 50 ? "ERROR" : i == 51 ? "FATAL" : "INFO ";
            text.append("2026-11-24 10:00:00 ")
                    .append(level)
                    .append(" line ")
                    .append(i)
                    .append('\n');
        }
        return text.toString();
    }

    @Test
    void marksPaintWhereTheirLinesAreAndAHoverOrClickFindsThem() throws Exception {
        Stage stage = FxToolkit.registerPrimaryStage();
        List<Integer> jumps = new ArrayList<>();
        LogStripe stripe = onFx(() -> {
            CodeArea area = new CodeArea();
            area.replaceText(log());
            LogStripe s = new LogStripe(area);
            s.setOnActivateIn((view, line) -> jumps.add(line));
            stage.setScene(new Scene(new Pane(s), 100, HEIGHT));
            stage.show();
            s.setActive(true);
            s.resizeRelocate(0, 0, LogStripe.WIDTH, HEIGHT);
            s.layout();
            return s;
        });
        onFx(() -> null); // the coalesced redraw has run
        assertArrayEquals(new int[] {10, 50, 51}, onFx(() -> stripe.marks().lines()));

        double errorY = HEIGHT * 50 / (LINES + 1); // the area has 101 paragraphs: the text ends in a newline
        int hit = onFx(() -> stripe.hitAt(errorY + 1));
        assertTrue(hit == 1 || hit == 2, "the ERROR or the FATAL just below it: " + hit);
        assertTrue(onFx(() -> stripe.tooltipText(1)).contains("ERROR line 50"));
        assertEquals(-1, (int) onFx(() -> stripe.hitAt(HEIGHT * 30 / (LINES + 1))), "no mark near line 30");

        onFx(() -> {
            stripe.fireEvent(mouse(MouseEvent.MOUSE_MOVED, errorY + 1));
            stripe.fireEvent(mouse(MouseEvent.MOUSE_MOVED, errorY + 1)); // the same text: the tooltip stays
            stripe.fireEvent(mouse(MouseEvent.MOUSE_MOVED, HEIGHT * 30 / (LINES + 1)));
            stripe.fireEvent(mouse(MouseEvent.MOUSE_EXITED, 0));
            stripe.fireEvent(mouse(MouseEvent.MOUSE_CLICKED, errorY + 1));
            return null;
        });
        assertEquals(1, jumps.size());
        assertTrue(jumps.get(0) == 50 || jumps.get(0) == 51);

        LogStripe twin = onFx(() -> stripe.follower(new CodeArea()));
        assertArrayEquals(new int[] {10, 50, 51}, onFx(() -> twin.marks().lines()), "a split's copy has the marks");
        assertTrue(onFx(twin::isActive));

        onFx(() -> {
            stripe.setActive(false);
            stripe.layout();
            return null;
        });
        assertFalse(onFx(twin::isActive), "and goes with the original");
        assertEquals(0, (int) onFx(() -> stripe.marks().size()));
        onFx(() -> {
            stage.hide();
            return null;
        });
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double y) {
        return new MouseEvent(
                type,
                3,
                y,
                3,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
    }
}
