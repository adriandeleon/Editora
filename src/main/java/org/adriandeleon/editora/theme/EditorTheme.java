package org.adriandeleon.editora.theme;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

public enum EditorTheme {
    LIGHT("Light", new PrimerLight().getUserAgentStylesheet()),
    DARK("Dark", new PrimerDark().getUserAgentStylesheet());

    private final String displayName;
    private final String userAgentStylesheet;

    EditorTheme(String displayName, String userAgentStylesheet) {
        this.displayName = displayName;
        this.userAgentStylesheet = userAgentStylesheet;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUserAgentStylesheet() {
        return userAgentStylesheet;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

