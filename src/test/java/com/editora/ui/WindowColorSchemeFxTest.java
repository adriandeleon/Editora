package com.editora.ui;

import javafx.application.ColorScheme;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every window is told the app theme's light or dark scheme — the open ones when the theme changes, and each
 * new one as it is shown — so the frame and window buttons JavaFX draws follow the app, not the desktop.
 */
@Tag("fx")
class WindowColorSchemeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void openWindowsFollowAThemeChangeAndNewWindowsOpenWithIt() throws Exception {
        boolean before = WindowColorScheme.isDark();
        FxTestSupport.runOnFx(() -> {
            WindowColorScheme.install(false);
            Stage open = new Stage();
            open.setScene(new Scene(new BorderPane(), 100, 80));
            open.show();
            try {
                assertEquals(ColorScheme.LIGHT, open.getScene().getPreferences().getColorScheme());

                WindowColorScheme.apply(true);
                assertEquals(ColorScheme.DARK, open.getScene().getPreferences().getColorScheme(), "a theme change");

                Stage later = new Stage();
                later.setScene(new Scene(new BorderPane(), 100, 80));
                later.show();
                try {
                    assertEquals(
                            ColorScheme.DARK,
                            later.getScene().getPreferences().getColorScheme(),
                            "a window opened after");
                    later.setScene(new Scene(new BorderPane(), 100, 80));
                    assertEquals(
                            ColorScheme.DARK, later.getScene().getPreferences().getColorScheme(), "a swapped scene");
                } finally {
                    later.hide();
                }
            } finally {
                open.hide();
            }
        });
        FxTestSupport.runOnFx(() -> WindowColorScheme.apply(before));
    }

    @Test
    void popupsAreLeftAlone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new BorderPane(), 100, 80));
            owner.show();
            ContextMenu menu = new ContextMenu(new MenuItem("x"));
            try {
                menu.show(owner);
                ColorScheme untouched = menu.getScene().getPreferences().getColorScheme();
                WindowColorScheme.apply(untouched != ColorScheme.DARK);
                assertEquals(untouched, menu.getScene().getPreferences().getColorScheme());
            } finally {
                menu.hide();
                owner.hide();
            }
        });
    }
}
