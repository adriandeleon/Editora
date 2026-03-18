package org.adriandeleon.editora;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class EditoraApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(EditoraApplication.class.getResource("editor-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1440, 920);
        EditorController controller = fxmlLoader.getController();
        scene.getStylesheets().add(Objects.requireNonNull(
                EditoraApplication.class.getResource("editora.css")
        ).toExternalForm());

        stage.setTitle("Editora");
        stage.setMinWidth(1024);
        stage.setMinHeight(720);
        controller.applyStageState(stage);
        stage.setOnCloseRequest(event -> {
            if (!controller.requestCloseAllDocuments()) {
                event.consume();
            } else {
                controller.captureWindowState(stage);
                controller.shutdown();
            }
        });
        stage.setScene(scene);
        stage.show();
    }
}

