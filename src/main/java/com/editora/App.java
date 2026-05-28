package com.editora;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        Parent root = FXMLLoader.load(App.class.getResource("primary.fxml"));
        stage.setScene(new Scene(root, 800, 600));
        stage.setTitle("Editora");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
