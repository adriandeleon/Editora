package com.editora;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    // Quiet tm4e/Oniguruma grammar-compile WARNINGs ("']' without escape", "No grammar source for
    // scope …") — benign noise from bundled-grammar regex quirks. Held in a static field so the JUL
    // logger isn't garbage-collected (which would silently drop the configured level). SEVERE still
    // surfaces real errors.
    private static final Logger TM4E_LOG = Logger.getLogger("org.eclipse.tm4e");

    static {
        TM4E_LOG.setLevel(Level.SEVERE);
    }

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
        // Pre-fill with the theme background so the first frame isn't JavaFX's default light gray
        // (which otherwise flashes before the CSS is applied — most visible with a dark theme). The
        // root and editor are transparent until CSS paints them, so this fill shows through. (Scene
        // fill isn't a CSS property, so it doesn't interfere with later theme switches.)
        scene.setFill(Themes.backgroundFor(settings.getTheme()));
        scene.getStylesheets().addAll(
                App.class.getResource("styles/app.css").toExternalForm(),
                App.class.getResource("styles/syntax.css").toExternalForm());

        new KeyDispatcher(registry, keymap, controller::setStatus).install(scene);

        // Ctrl + mouse wheel zooms the editor text (and is consumed so the editor doesn't also scroll).
        scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isControlDown() && e.getDeltaY() != 0) {
                controller.textZoom(e.getDeltaY() > 0 ? 1 : -1);
                e.consume();
            }
        });

        stage.setScene(scene);
        stage.setTitle("Editora");
        loadAppIcons(stage);
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

    /** Adds the app icon (multiple sizes) so it shows in the title bar, dock, and taskbar. */
    private void loadAppIcons(Stage stage) {
        for (int size : new int[]{16, 32, 48, 128, 256, 512}) {
            var in = App.class.getResourceAsStream("/com/editora/icons/icon-" + size + ".png");
            if (in != null) {
                stage.getIcons().add(new javafx.scene.image.Image(in));
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
