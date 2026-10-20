package com.editora.terminal;

import java.nio.charset.StandardCharsets;

import javafx.scene.input.KeyEvent;

import org.jspecify.annotations.Nullable;

/**
 * Records what a keystroke actually was and what went to the shell.
 *
 * <p>Off unless {@code -Deditora.terminal.keyTrace} is set, and when off it costs one boolean read per key —
 * so it ships inert.
 *
 * <p>It exists because keyboard bugs on this path cannot be reasoned about. What the OS delivers for
 * a chord is a question about AppKit, the input method and JavaFX's Glass layer together: macOS
 * binds Ctrl+A and Ctrl+F to its own text commands before an application sees them, and what arrives
 * — a KEY_PRESSED, a KEY_TYPED, both, or one carrying a character nobody typed — differs by chord
 * and by platform. A synthetic event proves nothing here, because the thing under suspicion is the
 * translation that produces the real one.
 */
final class KeyTrace {

    private static final boolean ENABLED =
            System.getProperty("editora.terminal.keyTrace") != null || Boolean.getBoolean("editora.terminal.keyTrace");

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("com.editora.terminal.keys");

    private KeyTrace() {}

    static boolean enabled() {
        return ENABLED;
    }

    /** Logs one event and the bytes it produced. {@code sent} may be null for "nothing sent". */
    static void log(KeyEvent e, byte @Nullable [] sent) {
        if (!ENABLED) return;
        LOG.info(describe(e, sent));
    }

    /**
     * The one line a traced keystroke produces.
     *
     * <p>The character is printed as code points rather than as itself: the whole point is the case
     * where it is something unprintable, which written literally would be invisible in the log —
     * exactly as it was invisible on screen.
     */
    static String describe(KeyEvent e, byte @Nullable [] sent) {
        StringBuilder out = new StringBuilder();
        out.append(e.getEventType().getName())
                .append(" code=")
                .append(e.getCode())
                .append(" chars=")
                .append(codePoints(e.getCharacter()))
                .append(" text=")
                .append(codePoints(e.getText()))
                .append(" mods=[")
                .append(modifiers(e))
                .append("] sent=")
                .append(hex(sent));
        return out.toString();
    }

    /**
     * The keys actually held.
     *
     * <p>Deliberately not {@code isShortcutDown}, which is derived rather than held — it is Ctrl on
     * Windows and Linux and Meta on macOS, so reporting it prints one physical key twice and reads
     * as two. In a diagnostic about which modifier arrived, that is the one confusion to avoid.
     */
    static String modifiers(KeyEvent e) {
        StringBuilder mods = new StringBuilder();
        if (e.isShiftDown()) mods.append("shift ");
        if (e.isControlDown()) mods.append("ctrl ");
        if (e.isAltDown()) mods.append("alt ");
        if (e.isMetaDown()) mods.append("meta ");
        return mods.toString().trim();
    }

    /** {@code "a"} becomes {@code U+0061}; empty stays visibly empty rather than blank. */
    static String codePoints(String text) {
        if (text == null) return "null";
        if (text.isEmpty()) return "<empty>";
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (!out.isEmpty()) out.append(',');
            out.append(String.format("U+%04X", cp));
        });
        return out.toString();
    }

    /**
     * The bytes as they went to the pseudo-terminal, and whether they are valid UTF-8.
     *
     * <p>The validity note is the point of the whole class: a shell renders an invalid byte as an
     * unprintable box, which looks identical to a character it merely has no glyph for. One is a bug
     * here and the other is a font.
     */
    static String hex(byte @Nullable [] sent) {
        if (sent == null) return "<nothing>";
        StringBuilder out = new StringBuilder();
        for (byte b : sent) {
            out.append(String.format("%02x ", b));
        }
        String bytes = out.toString().trim();
        return bytes + (isUtf8(sent) ? "" : " INVALID-UTF8");
    }

    static boolean isUtf8(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        return java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8));
    }
}
