package org.adriandeleon.editora.theme;

import javafx.application.Application;
import javafx.scene.Parent;

public final class ThemeManager {
    private ThemeManager() {
    }

    public static void apply(EditorTheme theme, Parent root) {
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
        if (root == null) {
            return;
        }

        root.getStyleClass().removeAll("theme-light", "theme-dark");
        root.getStyleClass().add(theme == EditorTheme.DARK ? "theme-dark" : "theme-light");
    }
}

