package com.editora;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class PrimaryController {

    @FXML
    private Label messageLabel;

    @FXML
    private void handleButton() {
        messageLabel.setText("Editora is running");
    }
}
