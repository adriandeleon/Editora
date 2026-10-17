package com.editora.command;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javafx.scene.input.KeyCode;

/**
 * Reads a chord the user typed into a search field, in any of the notations Editora shows — {@code Ctrl+Shift+S},
 * {@code C-S-s}, {@code ⇧⌘S}, {@code Cmd+S}, a sequence such as {@code Ctrl+K Ctrl+W} or {@code C-x C-s} — and
 * returns it in keymap tokens ({@link KeyDispatcher#chord}'s form), so a list can find a shortcut whatever the
 * active keymap's notation is. Pure; the key names come from {@link KeyCode}, no toolkit is needed.
 */
public final class ChordQuery {

    /** Lower-cased key name in any notation → its keymap token. */
    private static final Map<String, String> KEYS = new HashMap<>();

    static {
        for (KeyCode code : KeyCode.values()) {
            if (code.isModifierKey() || code == KeyCode.UNDEFINED) {
                continue;
            }
            String token = KeyDispatcher.keyName(code);
            KEYS.putIfAbsent(token, token);
            KEYS.putIfAbsent(
                    ChordFormat.format(token, ChordFormat.Style.PLATFORM).toLowerCase(Locale.ROOT), token);
            KEYS.putIfAbsent(ChordFormat.format(token, ChordFormat.Style.MAC).toLowerCase(Locale.ROOT), token);
        }
        KEYS.put("return", "enter");
        KEYS.put("esc", "escape");
        KEYS.put("del", "delete");
        KEYS.put("spc", "space");
        KEYS.put("pgup", "pageup");
        KEYS.put("pgdn", "pagedown");
    }

    private ChordQuery() {}

    /** The chord sequence {@code text} names, in keymap tokens, or null when it is not one. */
    public static String parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : text.strip().split("\\s+")) {
            String token = token(part);
            if (token == null) {
                return null;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString();
    }

    /** One chord: modifiers as {@code Ctrl+}, {@code C-} or ⌃⌥⇧⌘ glyphs, then a key. */
    private static String token(String part) {
        boolean ctrl = false;
        boolean alt = false;
        boolean cmd = false;
        boolean shift = false;
        String key;
        String lower = part.toLowerCase(Locale.ROOT);
        if (lower.length() > 1 && lower.indexOf('+') > 0) {
            String[] words = lower.split("\\+", -1);
            int last = words.length - 1;
            key = words[last].isEmpty() && last > 0 && words[last - 1].isEmpty() ? "+" : words[last];
            int mods = key.equals("+") ? last - 1 : last;
            for (int i = 0; i < mods; i++) {
                switch (words[i]) {
                    case "ctrl", "control", "ctl", "strg" -> ctrl = true;
                    case "alt", "option", "opt" -> alt = true;
                    case "cmd", "command", "meta", "super", "win" -> cmd = true;
                    case "shift" -> shift = true;
                    default -> {
                        return null;
                    }
                }
            }
        } else {
            int at = 0;
            while (at < lower.length() - 1) {
                char c = lower.charAt(at);
                if (c == '⌃') {
                    ctrl = true;
                } else if (c == '⌥') {
                    alt = true;
                } else if (c == '⇧') {
                    shift = true;
                } else if (c == '⌘') {
                    cmd = true;
                } else if (lower.startsWith("cmd-", at) && lower.length() > at + 4) {
                    cmd = true;
                    at += 3;
                } else if ((c == 'c' || c == 'm' || c == 's')
                        && lower.charAt(at + 1) == '-'
                        && lower.length() > at + 2) {
                    ctrl |= c == 'c';
                    alt |= c == 'm';
                    shift |= c == 's';
                    at++;
                } else {
                    break;
                }
                at++;
            }
            key = lower.substring(at);
        }
        String k = KEYS.get(key);
        if (k == null) {
            return null;
        }
        return (ctrl ? "C-" : "") + (alt ? "M-" : "") + (cmd ? "Cmd-" : "") + (shift ? "S-" : "") + k;
    }
}
