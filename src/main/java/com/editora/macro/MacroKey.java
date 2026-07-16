package com.editora.macro;

/**
 * The encoding of a {@link MacroStep#KEY} step's value: a bare editing/navigation key press, with its
 * modifiers, as a string — e.g. {@code BACK_SPACE}, {@code S-DOWN} (extend the selection down),
 * {@code C-LEFT} (word left). Pure (no toolkit) and unit-tested, so the recorder side (which reads a
 * JavaFX {@code KeyEvent}) and the replay side (which rebuilds one) cannot drift apart.
 *
 * <p>The key part is the raw {@code KeyCode} name rather than the keymap's lowercase chord token, because
 * that round-trips losslessly through {@code KeyCode.valueOf} — this format only has to be stable in
 * {@code macros.json}, not to match a keymap file. Modifier order mirrors the keymap's canonical
 * {@code C- M- Cmd- S-}.
 */
public final class MacroKey {

    private MacroKey() {}

    /** A decoded key step: the modifier flags plus the {@code KeyCode} name. */
    public record Decoded(boolean ctrl, boolean alt, boolean meta, boolean shift, String keyCodeName) {}

    public static String encode(boolean ctrl, boolean alt, boolean meta, boolean shift, String keyCodeName) {
        if (keyCodeName == null || keyCodeName.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (ctrl) {
            sb.append("C-");
        }
        if (alt) {
            sb.append("M-");
        }
        if (meta) {
            sb.append("Cmd-");
        }
        if (shift) {
            sb.append("S-");
        }
        return sb.append(keyCodeName).toString();
    }

    /** Parses {@link #encode}'s output; null for a blank/marker-only value. */
    public static Decoded decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String s = token.strip();
        boolean ctrl = false;
        boolean alt = false;
        boolean meta = false;
        boolean shift = false;
        // Order-independent so a hand-edited macros.json still parses; each prefix is consumed at most once.
        boolean more = true;
        while (more) {
            more = false;
            if (!ctrl && s.startsWith("C-")) {
                ctrl = true;
                s = s.substring(2);
                more = true;
            } else if (!alt && s.startsWith("M-")) {
                alt = true;
                s = s.substring(2);
                more = true;
            } else if (!meta && s.startsWith("Cmd-")) {
                meta = true;
                s = s.substring(4);
                more = true;
            } else if (!shift && s.startsWith("S-")) {
                shift = true;
                s = s.substring(2);
                more = true;
            }
        }
        return s.isBlank() ? null : new Decoded(ctrl, alt, meta, shift, s);
    }
}
