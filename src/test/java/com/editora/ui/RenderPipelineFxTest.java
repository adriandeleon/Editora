package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Under software rendering ({@link RenderPipeline}) a menu's popup carries no shadow. A popup with an effect is
 * redrawn whole, blur included, each time a row's hover changes; on the CPU that is what made moving through the
 * menu bar's menus lag.
 */
@Tag("fx")
class RenderPipelineFxTest {

    private static final String APP_CSS = "/com/editora/styles/app.css";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The effect a `.context-menu` gets from app.css under a root with, or without, the software-rendering class. */
    private static @Nullable Effect contextMenuEffect(boolean software) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Region menu = new Region();
            menu.getStyleClass().add("context-menu");
            StackPane root = new StackPane(menu);
            if (software) {
                root.getStyleClass().add(RenderPipeline.SOFTWARE_STYLE);
            }
            Scene scene = new Scene(root, 300, 300);
            scene.getStylesheets()
                    .add(RenderPipelineFxTest.class.getResource(APP_CSS).toExternalForm());
            root.applyCss();
            return menu.getEffect();
        });
    }

    @Test
    void aMenuKeepsItsShadowOnTheGpu() throws Exception {
        assertInstanceOf(DropShadow.class, contextMenuEffect(false));
    }

    @Test
    void aMenuHasNoShadowUnderSoftwareRendering() throws Exception {
        assertNull(contextMenuEffect(true), "a shadowed popup is re-blurred on the CPU at every hover");
    }

    /** The real thing: a menu bar's menu, whose popup takes its style through the button it hangs from. */
    @Test
    void anOpenMenuBarMenuHasNoShadowUnderSoftwareRendering() throws Exception {
        assertInstanceOf(DropShadow.class, openMenuEffect(false), "control: on the GPU the open menu has its shadow");
        assertNull(openMenuEffect(true), "the open menu's popup still draws a shadow under software rendering");
    }

    /** Opens a menu bar's menu in a window with, or without, the software-rendering class: the popup's effect. */
    private static @Nullable Effect openMenuEffect(boolean software) throws Exception {
        Menu file = new Menu("File");
        Stage stage = FxTestSupport.callOnFx(() -> {
            file.getItems().addAll(new MenuItem("One"), new MenuItem("Two"));
            StackPane root = new StackPane(new MenuBar(file));
            if (software) {
                root.getStyleClass().add(RenderPipeline.SOFTWARE_STYLE);
            }
            Scene scene = new Scene(root, 400, 300);
            scene.getStylesheets()
                    .add(RenderPipelineFxTest.class.getResource(APP_CSS).toExternalForm());
            Stage s = new Stage();
            s.setScene(scene);
            s.show();
            file.show();
            return s;
        });
        try {
            FxTestSupport.runOnFx(() -> {}); // a turn for the popup's skin and CSS
            Window popup = FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w instanceof PopupWindow)
                    .findFirst()
                    .orElse(null));
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    popup != null, "the headless platform did not map a popup window; run this on a desktop");
            return FxTestSupport.callOnFx(() -> {
                Parent root = popup.getScene().getRoot();
                root.applyCss();
                return firstEffect(root);
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                file.hide();
                stage.close();
            });
        }
    }

    /** The first effect in a popup's tree, wherever the skin put the `.context-menu` style. */
    private static @Nullable Effect firstEffect(Node node) {
        if (node.getEffect() != null) {
            return node.getEffect();
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Effect found = firstEffect(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
