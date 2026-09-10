package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuitDialogFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void quitIsDangerStyledWhileCancelRemainsNeutral() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            ButtonType quit = new ButtonType("Quit");
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(quit, cancel);

            MainController.styleQuitButtonAsDanger(alert, quit);

            Node quitButton = alert.getDialogPane().lookupButton(quit);
            Node cancelButton = alert.getDialogPane().lookupButton(cancel);
            assertTrue(quitButton.getStyleClass().contains("danger"));
            assertFalse(cancelButton.getStyleClass().contains("danger"));
        });
    }
}
