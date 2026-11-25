package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Every secondary window opens centred on the editor ({@link WindowPlacement#centerOnOwner}). */
@Tag("fx")
class WindowPlacementFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Stage editor(double x, double y) {
        Stage main = new Stage();
        main.setScene(new Scene(new StackPane(), 900, 700));
        main.setX(x);
        main.setY(y);
        main.show();
        return main;
    }

    /** {@code window}, {@code width}×{@code height} as placed (decorations aside), sits centred on {@code over}. */
    private static void assertCentredOn(Window over, Window window, double width, double height) {
        javafx.geometry.Point2D at = WindowPlacement.centered(
                new javafx.geometry.Rectangle2D(over.getX(), over.getY(), over.getWidth(), over.getHeight()),
                width,
                height,
                WindowPlacement.screenOf(over));
        assertEquals(at.getX(), window.getX(), 1, "centred across the editor");
        assertEquals(at.getY(), window.getY(), 1, "centred down the editor");
    }

    @Test
    void aReopenedWindowIsCentredAtTheSizeTheUserGaveIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage main = editor(40, 30);
            Stage window = new Stage();
            window.initOwner(main);
            window.setScene(new Scene(new StackPane(), 400, 300));
            try {
                WindowPlacement.centerOnOwner(window, main, 400, 300);
                window.show();
                window.setWidth(600); // the user makes it bigger, then closes it
                window.setHeight(450);
                window.hide();
                main.setX(80);
                // Reopened: the callers pass the size a new window opens at, not the one it has now.
                WindowPlacement.centerOnOwner(window, main, 400, 300);
                window.show();
                assertEquals(600, window.getWidth(), 1);
                assertCentredOn(main, window, 600, 450);
            } finally {
                window.close();
                main.close();
            }
        });
    }

    @Test
    void aWindowWithoutAnOwnerIsCentredOnTheWindowInFront() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage main = editor(60, 50);
            main.requestFocus();
            Window front = WindowPlacement.frontWindow();
            assertNotNull(front);
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(), 400, 300));
            try {
                WindowPlacement.centerOnOwner(window, null, 400, 300);
                window.show();
                assertCentredOn(front, window, 400, 300);
            } finally {
                window.close();
                main.close();
            }
        });
    }
}
