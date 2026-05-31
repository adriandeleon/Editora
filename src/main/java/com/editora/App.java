package com.editora;

import java.io.IOException;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.ui.MainController;

import com.editora.ui.Themes;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        com.editora.ui.Fonts.load(); // register bundled fonts before any UI/CSS uses them

        ConfigManager config = new ConfigManager();
        Settings settings = config.load();

        Application.setUserAgentStylesheet(Themes.stylesheetFor(settings.getTheme()));

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
        controller.applyEditorTheme(settings.getEditorTheme());
        restoreWindowBounds(stage, config.getWorkspaceState());
        stage.show();

        controller.openInitialBuffer();
    }

    /**
     * Restores the main window's size, position, and maximized state from the last session. Bounds
     * are only applied when they were saved (non-zero size) and still land on a connected screen, so
     * a disconnected monitor or first launch falls back to the scene's default size and OS centering.
     */
    private void restoreWindowBounds(Stage stage, WorkspaceState state) {
        double w = state.getWindowWidth();
        double h = state.getWindowHeight();
        if (w > 0 && h > 0) {
            double x = state.getWindowX();
            double y = state.getWindowY();
            if (!Screen.getScreensForRectangle(x, y, w, h).isEmpty()) {
                stage.setX(x);
                stage.setY(y);
            }
            stage.setWidth(w);
            stage.setHeight(h);
        }
        if (state.isWindowMaximized()) {
            stage.setMaximized(true);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
