package com.editora.ui;

import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A click on a menu bar title opens that menu with its rows, as a person clicks it.
 *
 * <p>The in-window bar builds a menu's rows on its first opening, so until then each menu holds one stand-in
 * row. JavaFX's menu bar skin opens a title only when its menu has a <em>visible</em> row; a hidden stand-in
 * made every click a no-op while {@code Menu.show()} (what the other menu tests call) still worked. So this
 * test clicks through the glass robot and never calls {@code show()} itself.
 */
@Tag("fx")
class MenuBarClickFxTest {

    private static FxWindowFixture fx;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(1500, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
            fx = null;
        }
    }

    @Test
    void clickingEachTitleOpensItsMenuWithItsRows() throws Exception {
        MainMenuBar menuBar = FxTestSupport.field(fx.controller, "menuBar");
        // macOS: the menus are the system's, built whole up front, and have no title buttons in the window.
        assumeFalse(FxTestSupport.callOnFx(menuBar::onSystemMenuBar), "the in-window menu bar");
        MenuBar bar = FxTestSupport.callOnFx(() -> (MenuBar) menuBar.node());
        assertTrue(FxTestSupport.callOnFx(menuBar::rowsUnbuilt), "precondition: no menu has been opened yet");

        int count = FxTestSupport.callOnFx(() -> bar.getMenus().size());
        assertTrue(count > 0, "precondition: the bar has menus");
        for (int i = 0; i < count; i++) {
            int index = i;
            Menu menu = FxTestSupport.callOnFx(() -> bar.getMenus().get(index));
            FxTestSupport.runOnFx(() -> {
                List<Node> titles = List.copyOf(bar.lookupAll(".menu-button"));
                assertEquals(count, titles.size(), "one title button per menu");
                robotClick(titles.get(index));
            });
            String name = FxTestSupport.callOnFx(menu::getText);
            FxTestSupport.awaitFxCondition(menu::isShowing, "a click on \"" + name + "\" opens it");
            assertTrue(
                    FxTestSupport.callOnFx(() -> menu.getItems().size() > 1
                            && menu.getItems().stream().anyMatch(item -> !item.isDisable())),
                    "\"" + name + "\" opened with its real rows, not the stand-in");
            FxTestSupport.runOnFx(menu::hide);
            FxTestSupport.awaitFxCondition(() -> !menu.isShowing(), "\"" + name + "\" closes");
        }
    }

    private static void robotClick(Node node) {
        Bounds at = node.localToScreen(node.getBoundsInLocal());
        Robot robot = new Robot();
        robot.mouseMove(at.getCenterX(), at.getCenterY());
        robot.mouseClick(MouseButton.PRIMARY);
    }
}
