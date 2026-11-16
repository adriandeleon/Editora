package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog built without an owner opens over the app window in front, centred on it, rather than in the middle of
 * the screen ({@link Dialogs#adoptOwner}).
 */
@Tag("fx")
class DialogsAdoptOwnerFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void anOwnerlessDialogIsCentredOnTheWindowInFront() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage main = new Stage();
            main.setScene(new Scene(new StackPane(), 800, 600));
            main.setX(40);
            main.setY(30);
            main.show();
            main.requestFocus();
            // Other tests in this JVM may leave a stage showing (and focused): the dialog goes over the front one.
            Window front = WindowPlacement.frontWindow();
            assertNotNull(front);
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.ERROR, "It failed.", ButtonType.OK));
            try {
                Dialogs.show(alert);
                assertSame(front, alert.getOwner(), "it took the window in front");
                double dialogCentreX = alert.getX() + alert.getWidth() / 2;
                double frontCentreX = front.getX() + front.getWidth() / 2;
                assertEquals(frontCentreX, dialogCentreX, 2, "centred across the window, not the screen");
                double dialogCentreY = alert.getY() + alert.getHeight() / 2;
                assertTrue(
                        dialogCentreY > front.getY() && dialogCentreY < front.getY() + front.getHeight(),
                        "and inside it top to bottom: " + dialogCentreY);
            } finally {
                alert.close();
                main.close();
            }
        });
    }

    @Test
    void aDialogWithAnOwnerKeepsIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage main = new Stage();
            main.setScene(new Scene(new StackPane(), 400, 300));
            main.show();
            Stage other = new Stage();
            other.setScene(new Scene(new StackPane(), 400, 300));
            other.show();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(other);
            try {
                Dialogs.adoptOwner(alert);
                assertSame(other, alert.getOwner());
            } finally {
                other.close();
                main.close();
            }
        });
    }
}
