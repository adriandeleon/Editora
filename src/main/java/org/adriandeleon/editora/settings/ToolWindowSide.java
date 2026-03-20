package org.adriandeleon.editora.settings;

import java.util.Locale;

public enum ToolWindowSide {
    LEFT("Left"),
    RIGHT("Right");

    public static final ToolWindowSide DEFAULT = LEFT;

    private final String displayName;

    ToolWindowSide(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String storedValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ToolWindowSide fromStoredValue(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return DEFAULT;
        }
        for (ToolWindowSide side : values()) {
            if (side.name().equalsIgnoreCase(storedValue) || side.storedValue().equalsIgnoreCase(storedValue)) {
                return side;
            }
        }
        return DEFAULT;
    }
}

