package org.adriandeleon.editora.settings;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandPaletteShortcut {
    public static final String DEFAULT_VALUE = "ALT+X";

    private static final Set<String> RESERVED_VALUES = Set.of(
            "SHORTCUT+T",
            "SHORTCUT+O",
            "SHORTCUT+S",
            "SHIFT+SHORTCUT+S",
            "SHORTCUT+W",
            "SHORTCUT+X",
            "SHORTCUT+C",
            "SHORTCUT+V",
            "SHORTCUT+Z",
            "SHIFT+SHORTCUT+Z",
            "SHORTCUT+F",
            "ALT+SHORTCUT+F",
            "ALT+SHORTCUT+E",
            "ALT+SHORTCUT+B",
            "SHORTCUT+COMMA",
            "ALT+B",
            "ALT+F",
            "ALT+D",
            "ALT+W",
            "ALT+BACK_SPACE",
            "ALT+DELETE",
            "ALT+SHIFT+COMMA",
            "ALT+SHIFT+PERIOD"
    );

    private CommandPaletteShortcut() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_VALUE;
        }

        boolean alt = false;
        boolean shift = false;
        boolean shortcut = false;
        KeyCode keyCode = null;

        for (String rawToken : value.strip().toUpperCase(Locale.ROOT).split("\\+")) {
            if (rawToken.isBlank()) {
                continue;
            }

            switch (rawToken) {
                case "ALT" -> alt = true;
                case "SHIFT" -> shift = true;
                case "SHORTCUT", "CMD", "COMMAND", "CTRL", "CONTROL", "META" -> shortcut = true;
                default -> {
                    try {
                        KeyCode parsedKeyCode = KeyCode.valueOf(rawToken);
                        if (parsedKeyCode.isModifierKey() || keyCode != null) {
                            return DEFAULT_VALUE;
                        }
                        keyCode = parsedKeyCode;
                    } catch (IllegalArgumentException exception) {
                        return DEFAULT_VALUE;
                    }
                }
            }
        }

        if ((!shortcut && !alt) || keyCode == null) {
            return DEFAULT_VALUE;
        }

        List<String> parts = new ArrayList<>();
        if (alt) {
            parts.add("ALT");
        }
        if (shift) {
            parts.add("SHIFT");
        }
        if (shortcut) {
            parts.add("SHORTCUT");
        }
        parts.add(keyCode.name());
        return String.join("+", parts);
    }

    public static boolean isReserved(String value) {
        return RESERVED_VALUES.contains(normalize(value));
    }

    public static String capture(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == null || code.isModifierKey() || (!event.isShortcutDown() && !event.isAltDown())) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        if (event.isAltDown()) {
            parts.add("ALT");
        }
        if (event.isShiftDown()) {
            parts.add("SHIFT");
        }
        if (event.isShortcutDown()) {
            parts.add("SHORTCUT");
        }
        parts.add(code.name());
        return String.join("+", parts);
    }

    public static KeyCombination keyCombination(String value) {
        String normalized = normalize(value);
        boolean alt = normalized.contains("ALT+");
        boolean shift = normalized.contains("SHIFT+");
        boolean shortcut = normalized.contains("SHORTCUT+");
        String keyToken = normalized.substring(normalized.lastIndexOf('+') + 1);
        KeyCode keyCode = KeyCode.valueOf(keyToken);

        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (alt) {
            modifiers.add(KeyCombination.ALT_DOWN);
        }
        if (shift) {
            modifiers.add(KeyCombination.SHIFT_DOWN);
        }
        if (shortcut) {
            modifiers.add(KeyCombination.SHORTCUT_DOWN);
        }
        return new KeyCodeCombination(keyCode, modifiers.toArray(KeyCombination.Modifier[]::new));
    }

    public static String displayText(String value) {
        String normalized = normalize(value);
        boolean alt = normalized.contains("ALT+");
        boolean shift = normalized.contains("SHIFT+");
        boolean shortcut = normalized.contains("SHORTCUT+");
        KeyCode keyCode = KeyCode.valueOf(normalized.substring(normalized.lastIndexOf('+') + 1));
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

        if (mac) {
            StringBuilder display = new StringBuilder();
            if (alt) {
                display.append('⌥');
            }
            if (shift) {
                display.append('⇧');
            }
            if (shortcut) {
                display.append('⌘');
            }
            display.append(displayKey(keyCode));
            return display.toString();
        }

        List<String> parts = new ArrayList<>();
        if (alt) {
            parts.add("Alt");
        }
        if (shift) {
            parts.add("Shift");
        }
        if (shortcut) {
            parts.add("Ctrl");
        }
        parts.add(displayKey(keyCode));
        return String.join("+", parts);
    }

    private static String displayKey(KeyCode keyCode) {
        return switch (keyCode) {
            case COMMA -> ",";
            case PERIOD -> ".";
            case SLASH -> "/";
            case BACK_SLASH -> "\\";
            case SEMICOLON -> ";";
            case QUOTE -> "'";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case MINUS -> "-";
            case EQUALS -> "=";
            case BACK_QUOTE -> "`";
            default -> keyCode.getName().length() == 1
                    ? keyCode.getName().toUpperCase(Locale.ROOT)
                    : keyCode.getName();
        };
    }
}

