package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

/**
 * The Project Map's own window: the Map always opens here, never inside the Project tool window, so it can
 * be sized like a document and kept beside (or on another screen from) the editor window.
 *
 * <p>The stage is deliberately <em>not</em> owned by the editor window. An owned stage floats above its owner
 * for good, so a file opened from a large Map would open behind it with no way to bring the editor forward.
 * Unowned, the two windows stack like any others — which means JavaFX no longer closes this one with the
 * editor window, so it closes itself when that window hides.
 *
 * <p>The content (search row and Map) is built and fed by {@link ProjectPanel}; this class only owns the stage:
 * its styling, its place on screen, and the difference between the user closing it and the editor closing it.
 */
final class ProjectMapWindow {

    static final double DEFAULT_WIDTH = 1100;

    static final double DEFAULT_HEIGHT = 720;

    private final Region content;
    /** The window's own in-scene card host, so a name is asked for over the Map, not in the editor window. */
    private final OverlayHost overlay = new OverlayHost();

    private @Nullable Stage stage;
    private @Nullable Window anchor;
    private final ChangeListener<Boolean> anchorShowing = (obs, was, showing) -> {
        if (!showing) {
            closeQuietly(); // the editor window is going: so is its Map, which stays remembered as open
        }
    };
    private Consumer<Scene> sceneSetup = scene -> {};
    private Supplier<@Nullable List<Double>> loadBounds = () -> null;
    private Consumer<List<Double>> storeBounds = bounds -> {};
    private Runnable onUserClosed = () -> {};
    private Runnable onHidden = () -> {};
    private String title = "";

    ProjectMapWindow(Region content) {
        this.content = content;
    }

    /** What the window's new scene gets, as the editor window's did: its key dispatcher. */
    void setSceneSetup(Consumer<Scene> setup) {
        sceneSetup = setup == null ? scene -> {} : setup;
    }

    /** Where the window's bounds are remembered (x, y, width, height), read on every open. */
    void setBoundsMemory(Supplier<@Nullable List<Double>> load, Consumer<List<Double>> store) {
        loadBounds = load == null ? () -> null : load;
        storeBounds = store == null ? bounds -> {} : store;
    }

    /** Told when the user closes the window (its close button, or {@link #closeByUser()}), not the editor. */
    void setOnUserClosed(Runnable callback) {
        onUserClosed = callback == null ? () -> {} : callback;
    }

    /** Told whenever the window stops showing, however it was closed. */
    void setOnHidden(Runnable callback) {
        onHidden = callback == null ? () -> {} : callback;
    }

    void setTitle(String title) {
        this.title = title == null ? "" : title;
        if (stage != null) {
            stage.setTitle(this.title);
        }
    }

    boolean isShowing() {
        return stage != null && stage.isShowing();
    }

    /** Whether the Map window is the one the user is working in (so dialogs and prompts belong over it). */
    boolean isFocused() {
        return stage != null && stage.isShowing() && stage.isFocused();
    }

    /** Where the Map window shows an in-scene card (a name prompt). */
    OverlayHost overlayHost() {
        return overlay;
    }

    /** The stage once the window has been opened (dialog owner, tests). */
    @Nullable
    Stage stage() {
        return stage;
    }

    /**
     * Opens the window, or brings it forward when it is already open. {@code editorWindow} is the editor
     * window it belongs to: its stylesheets and icons are copied, it is centred on that window the first
     * time, and it closes when that window does.
     */
    void show(@Nullable Window editorWindow) {
        Stage s = stage;
        if (s == null) {
            s = build();
            stage = s;
        }
        attachTo(editorWindow, s);
        if (s.isShowing()) {
            if (s.isIconified()) {
                s.setIconified(false);
            }
            s.toFront();
            s.requestFocus();
            return;
        }
        place(s, editorWindow);
        s.show();
        s.toFront();
        rememberBounds(s); // where it opened, before any move: a window never moved is still remembered
    }

    /** The user closed the Map (its own command or toggle): remembered as closed. */
    void closeByUser() {
        if (isShowing()) {
            closeQuietly();
            onUserClosed.run();
        }
    }

    /** Closes the window without recording it as closed — the editor window or the project went away. */
    void closeQuietly() {
        Stage s = stage;
        if (s != null && s.isShowing()) {
            rememberBounds(s);
            s.hide();
        }
    }

    private Stage build() {
        Stage s = new Stage();
        s.setTitle(title);
        s.setMinWidth(480);
        s.setMinHeight(320);
        StackPane root = new StackPane(content);
        overlay.install(root);
        Scene scene = new Scene(root);
        sceneSetup.accept(scene);
        s.setScene(scene);
        s.setOnCloseRequest(e -> {
            e.consume();
            closeByUser();
        });
        s.setOnHidden(e -> onHidden.run());
        // Written into the session object as the window moves, never saved from here: a save per pixel of a
        // drag would be a blocking disk write on the FX thread. Closing the window saves (onUserClosed).
        ChangeListener<Number> onMove = (o, was, now) -> rememberBounds(s);
        s.xProperty().addListener(onMove);
        s.yProperty().addListener(onMove);
        s.widthProperty().addListener(onMove);
        s.heightProperty().addListener(onMove);
        return s;
    }

    /** Follows {@code editorWindow}: its look (stylesheets, icons) and its lifetime. */
    private void attachTo(@Nullable Window editorWindow, Stage s) {
        if (editorWindow != anchor) {
            if (anchor != null) {
                anchor.showingProperty().removeListener(anchorShowing);
            }
            anchor = editorWindow;
            if (editorWindow != null) {
                editorWindow.showingProperty().addListener(anchorShowing);
            }
        }
        if (editorWindow == null) {
            return;
        }
        // A fresh Scene carries no stylesheets, and the app's look is an author stylesheet rather than the
        // user-agent one — without copying these the Map renders as unstyled default controls.
        if (editorWindow.getScene() != null && s.getScene() != null) {
            s.getScene().getStylesheets().setAll(editorWindow.getScene().getStylesheets());
        }
        if (editorWindow instanceof Stage editorStage && s.getIcons().isEmpty()) {
            s.getIcons().setAll(editorStage.getIcons());
        }
    }

    /** Its remembered bounds when they are still on a screen, else a large window centred on the editor. */
    private void place(Stage s, @Nullable Window editorWindow) {
        ToolWindowFloat.Bounds b = ToolWindowFloat.fromList(loadBounds.get());
        List<Rectangle2D> screens = new ArrayList<>();
        for (Screen screen : Screen.getScreens()) {
            screens.add(screen.getVisualBounds());
        }
        if (b != null && ToolWindowFloat.isReachable(b, screens)) {
            s.setX(b.x());
            s.setY(b.y());
            s.setWidth(b.width());
            s.setHeight(b.height());
            return;
        }
        Rectangle2D visual =
                editorWindow == null ? Screen.getPrimary().getVisualBounds() : WindowPlacement.screenOf(editorWindow);
        javafx.geometry.Dimension2D size = WindowPlacement.clampSize(
                b == null ? DEFAULT_WIDTH : b.width(), b == null ? DEFAULT_HEIGHT : b.height(), visual, 0.9);
        Rectangle2D around = editorWindow == null
                ? visual
                : new Rectangle2D(
                        editorWindow.getX(),
                        editorWindow.getY(),
                        Math.max(1, editorWindow.getWidth()),
                        Math.max(1, editorWindow.getHeight()));
        javafx.geometry.Point2D at = WindowPlacement.centered(around, size.getWidth(), size.getHeight(), visual);
        s.setWidth(size.getWidth());
        s.setHeight(size.getHeight());
        s.setX(at.getX());
        s.setY(at.getY());
    }

    private void rememberBounds(Stage s) {
        if (!s.isShowing() || s.getWidth() < ToolWindowFloat.MIN_USABLE || s.getHeight() < ToolWindowFloat.MIN_USABLE) {
            return; // mid-construction, before the stage has been sized
        }
        storeBounds.accept(ToolWindowFloat.toList(s.getX(), s.getY(), s.getWidth(), s.getHeight()));
    }
}
