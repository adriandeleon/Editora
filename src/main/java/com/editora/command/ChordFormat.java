package com.editora.command;

import java.util.Locale;

/**
 * Turns a keymap chord sequence — the canonical tokens {@link KeyDispatcher#chord} emits, e.g. {@code C-S-p}
 * or {@code C-x C-s} — into the text shown to the user. The <em>only</em> place a binding is rendered, so a
 * menu, tooltip, palette row, picker legend and status message can never disagree about notation.
 *
 * <p>Emacs notation is the keymap's own vocabulary, so it is used only when the Emacs keymap is active. Every
 * other keymap is read by people who expect their platform's notation: {@code Ctrl+Shift+P} (a multi-key
 * sequence as {@code Ctrl+K Ctrl+W}) on Windows/Linux, and modifier glyphs in Apple's order on macOS
 * ({@code ⇧⌘P}). Pure — no toolkit, no I/O.
 */
public final class ChordFormat {

    /** How a chord is written out. */
    public enum Style {
        /** The raw keymap tokens ({@code C-x C-s}, {@code M-x}). */
        EMACS,
        /** Windows/Linux key names joined by {@code +} ({@code Ctrl+Shift+P}). */
        PLATFORM,
        /** macOS modifier glyphs in Apple's order, Control Option Shift Command ({@code ⇧⌘P}). */
        MAC
    }

    /** Modifier prefixes in the exact order {@link KeyDispatcher#chord} emits them. */
    private static final String[] PREFIXES = {"C-", "M-", "Cmd-", "S-"};

    /** {@link #modifierMask} bit for the {@code Cmd-} prefix. */
    private static final int CMD = 1 << 2;

    private static final String[] PLATFORM_NAMES = {"Ctrl", "Alt", "Meta", "Shift"};
    /** Indices into {@link #PREFIXES} in the order Apple writes modifiers: ⌃ ⌥ ⇧ ⌘. */
    private static final int[] MAC_ORDER = {0, 1, 3, 2};

    private static final String[] MAC_GLYPHS = {"⌃", "⌥", "⌘", "⇧"};

    private ChordFormat() {}

    /** The notation for a keymap on a platform: Emacs tokens for the Emacs keymap, else the platform's own. */
    public static Style styleFor(String keymapName, boolean mac) {
        if (KeymapManager.DEFAULT.equals(KeymapManager.resolveName(keymapName))) {
            return Style.EMACS;
        }
        return mac ? Style.MAC : Style.PLATFORM;
    }

    /**
     * Whether a sequence can be typed on this platform. A {@code Cmd-} chord needs the Command key, which a
     * Windows/Linux keyboard does not have, so it is never the chord to advertise there.
     */
    public static boolean typable(String sequence, boolean mac) {
        if (mac || sequence == null) {
            return sequence != null;
        }
        for (String token : sequence.split(" ")) {
            if ((modifierMask(token) & CMD) != 0) {
                return false;
            }
        }
        return true;
    }

    /** Formats a whole chord sequence; a null or blank sequence formats to {@code ""}. */
    public static String format(String sequence, Style style) {
        if (sequence == null || sequence.isBlank()) {
            return "";
        }
        if (style == Style.EMACS) {
            return sequence;
        }
        StringBuilder out = new StringBuilder(sequence.length() + 8);
        for (String token : sequence.split(" ")) {
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            appendToken(out, token, style == Style.MAC);
        }
        return out.toString();
    }

    private static void appendToken(StringBuilder out, String token, boolean mac) {
        int mask = modifierMask(token);
        String key = token.substring(modifierLength(token));
        if (mac) {
            for (int i : MAC_ORDER) {
                if ((mask & (1 << i)) != 0) {
                    out.append(MAC_GLYPHS[i]);
                }
            }
            out.append(macKey(key));
        } else {
            for (int i = 0; i < PREFIXES.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    out.append(PLATFORM_NAMES[i]).append('+');
                }
            }
            out.append(platformKey(key));
        }
    }

    /** Bit {@code i} is set when the token carries {@link #PREFIXES}{@code [i]}. */
    private static int modifierMask(String token) {
        int mask = 0;
        int at = 0;
        for (int i = 0; i < PREFIXES.length; i++) {
            String p = PREFIXES[i];
            // A prefix only counts while a key is left after it: "C--" is Ctrl + the minus key.
            if (token.startsWith(p, at) && token.length() > at + p.length()) {
                mask |= 1 << i;
                at += p.length();
            }
        }
        return mask;
    }

    private static int modifierLength(String token) {
        int at = 0;
        for (String p : PREFIXES) {
            if (token.startsWith(p, at) && token.length() > at + p.length()) {
                at += p.length();
            }
        }
        return at;
    }

    private static String platformKey(String key) {
        return switch (key) {
            case "escape" -> "Esc";
            case "pageup" -> "PageUp";
            case "pagedown" -> "PageDown";
            case "back-quote" -> "`";
            case "quote" -> "'";
            default -> capitalize(key);
        };
    }

    private static String macKey(String key) {
        return switch (key) {
            case "enter" -> "↩";
            case "tab" -> "⇥";
            case "backspace" -> "⌫";
            case "delete" -> "⌦";
            case "escape" -> "⎋";
            case "left" -> "←";
            case "right" -> "→";
            case "up" -> "↑";
            case "down" -> "↓";
            case "home" -> "↖";
            case "end" -> "↘";
            case "pageup" -> "⇞";
            case "pagedown" -> "⇟";
            default -> platformKey(key);
        };
    }

    /** {@code p} → {@code P}, {@code space} → {@code Space}, {@code f5} → {@code F5}; punctuation is unchanged. */
    private static String capitalize(String key) {
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) {
            return key;
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }
}
