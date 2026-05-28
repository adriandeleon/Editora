package com.editora;

import java.io.IOException;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.MainController;

import atlantafx.base.theme.PrimerLight;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        ConfigManager config = new ConfigManager();
        Settings settings = config.load();

        CommandRegistry registry = new CommandRegistry();
        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed(settings.getKeymap());
        keymap.applyOverrides(settings.getKeybindings());

        FXMLLoader loader = new FXMLLoader(App.class.getResource("ui/main.fxml"));
        BorderPane root = loader.load();
        MainController controller = loader.getController();
        controller.init(stage, config, registry, keymap);

        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().addAll(
                App.class.getResource("styles/app.css").toExternalForm(),
                App.class.getResource("styles/syntax.css").toExternalForm());

        new KeyDispatcher(registry, keymap, controller::setStatus).install(scene);

        stage.setScene(scene);
        stage.setTitle("Editora");
        stage.show();

        controller.openInitialBuffer();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
