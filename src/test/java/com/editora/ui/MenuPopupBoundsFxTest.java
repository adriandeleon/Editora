package com.editora.ui;

import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.effect.DropShadow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open menu's popup must not cover the menu bar it hangs from.
 *
 * <p>A JavaFX popup is a real native window sized to its root's bounds <em>including the effect</em>, and a
 * drop shadow inflates those bounds by {@code radius - offsetY} upward and about {@code radius} sideways —
 * transparent to the eye, but not to the pointer. The UI Kit pass gave every {@code .context-menu} the
 * card shadow ({@code radius 44 / offsetY 18}), i.e. ~26px of invisible window above the menu and ~44px
 * either side, against a menu-bar row 24px tall. Measured, the popup began 2px above the <em>top</em> of
 * the bar and reached 44px past it on either side, so the open menu covered its own neighbours' titles:
 * sliding the mouse from one menu to the next landed on the popup instead of the menu button and the bar
 * did not switch — the "menu to menu is not smooth on Linux" report.
 *
 * <p>Asserted on the popup's <b>screen</b> bounds against the menu bar's, because that is what the window
 * manager routes the pointer by; the visible geometry says nothing about it.
 */
@Tag("fx")
class MenuPopupBoundsFxTest {

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
        }
    }

    @Test
    void anOpenMenusPopupDoesNotOverlapTheMenuBar() throws Exception {
        MenuBar bar = FxTestSupport.callOnFx(() -> (MenuBar)
                FxTestSupport.<MainMenuBar>field(fx.controller, "menuBar").node());
        assertTrue(FxTestSupport.callOnFx(() -> !bar.getMenus().isEmpty()), "precondition: the bar has menus");

        Bounds barOnScreen = FxTestSupport.callOnFx(() -> bar.localToScreen(bar.getBoundsInLocal()));
        assertNotNull(barOnScreen, "the menu bar should be in a shown scene");

        FxTestSupport.runOnFx(() -> {
            Menu first = bar.getMenus().get(0);
            first.show();
        });
        try {
            Window popup = FxTestSupport.callOnFx(() -> Window.getWindows().stream()
                    .filter(w -> w.isShowing() && w instanceof javafx.stage.PopupWindow)
                    .findFirst()
                    .orElse(null));
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    popup != null, "the headless platform did not map a popup window; run this on a desktop");

            double popupTop = FxTestSupport.callOnFx(popup::getY);
            double popupLeft = FxTestSupport.callOnFx(popup::getX);
            double popupRight = FxTestSupport.callOnFx(() -> popup.getX() + popup.getWidth());
            System.out.printf(
                    "menu bar y=[%.1f..%.1f]  popup y=%.1f x=[%.1f..%.1f]%n",
                    barOnScreen.getMinY(), barOnScreen.getMaxY(), popupTop, popupLeft, popupRight);

            assertTrue(
                    popupTop >= barOnScreen.getMaxY(),
                    "the open menu's popup window starts " + (barOnScreen.getMaxY() - popupTop)
                            + "px above the bottom of the menu bar, so it covers the neighbouring menu titles"
                            + " and swallows the hover that should switch menus");
        } finally {
            FxTestSupport.runOnFx(() -> bar.getMenus().get(0).hide());
        }
    }

    /**
     * The rule behind it, stated directly on the computed style: a popup's shadow must not reach above the
     * popup.
     *
     * <p>The upward extent of a drop shadow is {@code radius - offsetY}, and that much invisible window sits
     * over whatever the menu hangs from. Asserted separately from the geometry above because it is the
     * constraint a future restyle has to keep — the number to change is in one CSS rule, and nothing else
     * about the app would complain if it grew again.
     */
    @Test
    void aPopupsShadowNeverReachesAboveThePopup() throws Exception {
        DropShadow shadow = FxTestSupport.callOnFx(() -> {
            javafx.scene.layout.Region r = new javafx.scene.layout.Region();
            r.getStyleClass().add("context-menu");
            r.setPrefSize(300, 400);
            javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane(r);
            Scene s = new Scene(root, 600, 700);
            s.getStylesheets()
                    .add(MenuPopupBoundsFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            root.applyCss();
            root.layout();
            return r.getEffect() instanceof DropShadow d ? d : null;
        });
        assertNotNull(shadow, ".context-menu should carry a drop shadow");

        double up = shadow.getRadius() - shadow.getOffsetY();
        assertTrue(
                up <= 0,
                "a context menu's shadow reaches " + up + "px above it (radius " + shadow.getRadius()
                        + ", offsetY " + shadow.getOffsetY() + "), and that much invisible popup window"
                        + " covers the menu bar / the surface the menu was opened from");
    }
}
