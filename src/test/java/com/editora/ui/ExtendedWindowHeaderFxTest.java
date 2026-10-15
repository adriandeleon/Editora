package com.editora.ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HeaderBar;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The extended window's header, asked the question the OS asks on a press: is this point a drag bar?
 *
 * <p>Goes through the scene's own header-area hit test rather than reading the drag-type flags back, because
 * the bug was in how those flags resolve: the title was {@code DRAGGABLE}, which does not cover the label's
 * text node, so a press on the words was not a drag bar while a press beside them was.
 */
@Tag("fx")
class ExtendedWindowHeaderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theTitleTextMovesTheWindowAndTheMenuBarDoesNot() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage stage = new Stage();
            stage.setTitle("~/src/adl/tests/Main.java — Editora");
            MenuBar menuBar = new MenuBar(new Menu("File"), new Menu("Edit"));
            HeaderBar header = ExtendedWindow.header(menuBar, stage);
            Scene scene = new Scene(new BorderPane(null, header, null, null, null), 1000, 400);
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            Label title = (Label) header.getCenter();
            Text glyphs = (Text) title.lookup(".text");
            assertNotNull(glyphs, "the label's skin draws its text in a child node");

            assertEquals("DRAGBAR", headerArea(scene, glyphs), "a press on the title's words");
            assertEquals("DRAGBAR", headerArea(scene, header.lookup(".window-header-title")), "and on the label");
            assertNotEquals("DRAGBAR", headerArea(scene, menuBar), "the menu bar opens menus instead");
        });
    }

    /** The scene's verdict for a press at the centre of {@code node}, as the platform window would ask it. */
    private static String headerArea(Scene scene, Node node) {
        Bounds b = node.localToScene(node.getBoundsInLocal());
        try {
            // The listener the window peer would call; the peer only exists once a stage is shown, so build one.
            Constructor<?> ctor =
                    Class.forName(Scene.class.getName() + "$ScenePeerListener").getDeclaredConstructor(Scene.class);
            ctor.setAccessible(true);
            Object listener = ctor.newInstance(scene);
            Method pick = listener.getClass().getMethod("pickHeaderArea", double.class, double.class);
            pick.setAccessible(true);
            Object area = pick.invoke(listener, b.getCenterX(), b.getCenterY());
            return area == null ? "none" : ((Enum<?>) area).name();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("JavaFX's header hit test moved; update this probe", e);
        }
    }
}
