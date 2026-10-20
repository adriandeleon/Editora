package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.jspecify.annotations.Nullable;

/**
 * Turns JavaFX key events into the bytes a terminal expects.
 *
 * <p>Special keys are delegated to JediTerm's own encoder (via {@code TerminalStarter.getCode}),
 * because the correct bytes depend on emulator state we do not track: in application-cursor-key
 * mode (DECCKM, set by vim, less, and readline's alternate keypad) Up is {@code ESC O A}, and
 * otherwise {@code ESC [ A}. Hard-coding either one breaks half the programs a terminal exists to
 * run.
 *
 * <p>That encoder speaks AWT's key codes and extended modifier masks. Those constants are
 * reproduced here as literals rather than referenced from {@code java.awt.event} on purpose:
 * touching an AWT event class can initialise the AWT toolkit, and on macOS an initialised AWT
 * contends with JavaFX for the single AppKit run loop (see {@code App.main}). The values are fixed
 * by the AWT specification and cannot drift.
 */
public final class KeyEncoding {

    // java.awt.event.InputEvent extended modifier masks.
    private static final int SHIFT_DOWN = 1 << 6; // 64
    private static final int CTRL_DOWN = 1 << 7; // 128
    private static final int META_DOWN = 1 << 8; // 256
    private static final int ALT_DOWN = 1 << 9; // 512

    // java.awt.event.KeyEvent virtual key codes, for the keys a terminal encodes specially.
    private static final Map<KeyCode, Integer> VK = Map.ofEntries(
            Map.entry(KeyCode.ENTER, 10),
            Map.entry(KeyCode.BACK_SPACE, 8),
            Map.entry(KeyCode.TAB, 9),
            Map.entry(KeyCode.ESCAPE, 27),
            Map.entry(KeyCode.PAGE_UP, 33),
            Map.entry(KeyCode.PAGE_DOWN, 34),
            Map.entry(KeyCode.END, 35),
            Map.entry(KeyCode.HOME, 36),
            Map.entry(KeyCode.LEFT, 37),
            Map.entry(KeyCode.UP, 38),
            Map.entry(KeyCode.RIGHT, 39),
            Map.entry(KeyCode.DOWN, 40),
            Map.entry(KeyCode.INSERT, 155),
            Map.entry(KeyCode.DELETE, 127),
            Map.entry(KeyCode.F1, 112),
            Map.entry(KeyCode.F2, 113),
            Map.entry(KeyCode.F3, 114),
            Map.entry(KeyCode.F4, 115),
            Map.entry(KeyCode.F5, 116),
            Map.entry(KeyCode.F6, 117),
            Map.entry(KeyCode.F7, 118),
            Map.entry(KeyCode.F8, 119),
            Map.entry(KeyCode.F9, 120),
            Map.entry(KeyCode.F10, 121),
            Map.entry(KeyCode.F11, 122),
            Map.entry(KeyCode.F12, 123));

    /** ESC, prefixed to a keystroke to express Meta (what readline's M-x bindings read). */
    private static final byte ESC = 0x1b;

    private KeyEncoding() {}

    /** Looks up the emulator's mode-dependent encoding for (awtKeyCode, awtModifiers). */
    @FunctionalInterface
    public interface CodeLookup {
        byte[] apply(int awtKeyCode, int awtModifiers);
    }

    /**
     * Encodes a KEY_PRESSED event, or returns {@code null} to let it fall through to KEY_TYPED.
     *
     * <p>Printable characters are deliberately <em>not</em> handled here: only KEY_TYPED reports the
     * composed character, which is what makes dead keys, AltGr layouts, and IME input work.
     *
     * @param altIsMeta whether Alt/Option prefixes ESC (readline's M-b, M-f) instead of composing a
     *     character. On macOS this is the difference between {@code M-b} and typing {@code ∫}.
     */
    public static byte @Nullable [] encodePressed(KeyEvent e, CodeLookup lookup, boolean altIsMeta) {
        return encodePressed(e, lookup, altIsMeta, false);
    }

    /**
     * As above. {@code ctrlAltIsMeta}: Ctrl+Alt+key is the chord {@code C-M-key} (ESC then the control byte), not
     * AltGr composing a character — true only where AltGr is told apart (Linux, while AltGr is not held). On
     * Windows Ctrl+Alt <em>is</em> AltGr, and it stays the composing key there.
     */
    public static byte @Nullable [] encodePressed(
            KeyEvent e, CodeLookup lookup, boolean altIsMeta, boolean ctrlAltIsMeta) {
        KeyCode code = e.getCode();

        Integer vk = VK.get(code);
        if (vk != null) {
            byte[] encoded = lookup.apply(vk, awtModifiers(e, altIsMeta));
            if (encoded != null) return encoded;
            // The encoder has no entry for this combination; fall back to the unmodified key so a
            // modifier never silently swallows the keystroke itself.
            byte[] plain = lookup.apply(vk, 0);
            if (plain != null) return withMeta(plain, e, altIsMeta);
            // Still nothing: the emulator has no mapping for this key at all. Falling through here
            // is not merely "the key does nothing" — the event goes unconsumed and JavaFX acts on
            // it, and for Tab that means moving focus out of the terminal, so every keystroke
            // afterwards lands somewhere else and the terminal looks frozen.
            byte[] literal = literalFallback(code, e);
            if (literal != null) return withMeta(literal, e, altIsMeta);
            return null;
        }

        // Ctrl+<key> produces a C0 control byte. Handled here rather than in KEY_TYPED because
        // platforms disagree about whether a control combination produces a typed character at all.
        if (e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            byte[] control = controlByte(code);
            if (control != null) return withMeta(control, e, altIsMeta);
        }
        // readline's C-M-e and C-M-y, emacs -nw's C-M-f/b/k.
        if (ctrlAltIsMeta && e.isControlDown() && e.isAltDown() && !e.isMetaDown()) {
            byte[] control = controlByte(code);
            if (control != null) return prefixEsc(control);
        }

        // Meta: ESC then the key's *unmodified* character. Handled from KEY_PRESSED precisely
        // because KEY_TYPED reports the composed one, and composing is what this mode turns off.
        //
        // macOS makes Option a compose key — Option+F is ƒ. Prefixing ESC to that sends the escape
        // plus ƒ's two UTF-8 bytes; a shell reads ESC as the meta prefix, consumes the first byte
        // along with it, and is left holding a lone continuation byte, which is invalid UTF-8 and
        // renders as an unprintable box. So M-f arrived as garbage inserted at the cursor instead
        // of forward-word, and every other M-<letter> with it.
        //
        // Not while Ctrl is held: that combination is AltGr on Windows and Linux, where composing
        // is the only way a keyboard reaches some characters at all.
        if (altIsMeta && e.isAltDown() && !e.isControlDown() && !e.isMetaDown()) {
            byte[] base = metaBase(e);
            if (base != null) return prefixEsc(base);
        }

        return null;
    }

    /**
     * The ASCII byte for Meta-&lt;key&gt;, or null when the key has no plain character.
     *
     * <p>Prefers the event's own text, which is right on layouts where the modifier does not
     * compose, and falls back to the key's character when that text is something the OS invented —
     * {@code ƒ} for Option+F, which is not what {@code M-f} means.
     */
    static byte @Nullable [] metaBase(KeyEvent e) {
        char c = plainAscii(e.getText());
        if (c == 0) c = plainAscii(e.getCode().getChar());
        if (c == 0) return null;
        // A KeyCode's character is upper case; readline's bindings are lower case, and Shift is what
        // asks for the other one — M-u and M-U are different widgets.
        if (Character.isLetter(c)) {
            c = e.isShiftDown() ? Character.toUpperCase(c) : Character.toLowerCase(c);
        }
        return new byte[] {(byte) c};
    }

    /** A single printable ASCII character, or 0 for anything else. */
    private static char plainAscii(String text) {
        if (text == null || text.length() != 1) return 0;
        char c = text.charAt(0);
        return c >= 0x20 && c < 0x7f ? c : 0;
    }

    /**
     * Encodes a KEY_TYPED event — a real character the user produced. Returns {@code null} when the
     * event carries nothing to send.
     */
    public static byte @Nullable [] encodeTyped(KeyEvent e, boolean altIsMeta) {
        return encodeTyped(e, altIsMeta, false);
    }

    /** As above; with {@code ctrlAltIsMeta} a Ctrl+Alt character is the chord {@link #encodePressed} sent. */
    public static byte @Nullable [] encodeTyped(KeyEvent e, boolean altIsMeta, boolean ctrlAltIsMeta) {
        String text = e.getCharacter();
        if (text == null || text.isEmpty() || text.equals(KeyEvent.CHAR_UNDEFINED)) return null;

        char first = text.charAt(0);
        // Control characters and DEL arrive here on some platforms after we already encoded them
        // from KEY_PRESSED. Dropping them prevents a doubled keystroke.
        if (text.length() == 1 && (first < 0x20 || first == 0x7f)) return null;

        // Ctrl held means this is the character form of a chord KEY_PRESSED already emitted.
        if ((e.isControlDown() && (!e.isAltDown() || ctrlAltIsMeta)) || e.isMetaDown()) return null;

        // The same is now true of Alt in Meta mode: KEY_PRESSED has sent ESC and the unmodified
        // key, and this event carries the composed character that mode exists to suppress. Sending
        // it too would both duplicate the keystroke and put the composed bytes on the wire.
        //
        // Alt with Ctrl is AltGr, which is not Meta and does belong here.
        if (altIsMeta && e.isAltDown() && !e.isControlDown()) return null;

        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Wraps text for bracketed paste, which lets the shell tell a paste from typing.
     *
     * <p>The text is cleaned first. A paste is data, never keystrokes, but a clipboard can hold control bytes —
     * text copied from a web page may carry a hidden {@code ESC [201~} that ends the bracket early, after which
     * the rest is typed: {@code \r} runs a command, ESC leaves vim's insert mode, Ctrl+C and Ctrl+D act. So, as
     * xterm does with {@code allowPasteControls} off and as VTE does, every C0 control other than Tab and the
     * line breaks, DEL, and every C1 control are dropped; with ESC gone no escape sequence survives.
     */
    public static byte[] encodePaste(String text, boolean bracketed) {
        String normalised = pasteText(text).replace("\r\n", "\r").replace('\n', '\r');
        if (!bracketed) return normalised.getBytes(StandardCharsets.UTF_8);
        return ("[200~" + normalised + "[201~").getBytes(StandardCharsets.UTF_8);
    }

    /** The pasted text without the control characters a paste must not deliver (see {@link #encodePaste}). */
    public static String pasteText(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean drop = (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c >= 0x7f && c <= 0x9f);
            if (drop && out == null) {
                out = new StringBuilder(text.length()).append(text, 0, i);
            } else if (!drop && out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    private static byte[] withMeta(byte[] bytes, KeyEvent e, boolean altIsMeta) {
        return altIsMeta && e.isAltDown() ? prefixEsc(bytes) : bytes;
    }

    private static byte[] prefixEsc(byte[] bytes) {
        byte[] out = new byte[bytes.length + 1];
        out[0] = ESC;
        System.arraycopy(bytes, 0, out, 1, bytes.length);
        return out;
    }

    private static int awtModifiers(KeyEvent e, boolean altIsMeta) {
        int mods = 0;
        if (e.isShiftDown()) mods |= SHIFT_DOWN;
        if (e.isControlDown()) mods |= CTRL_DOWN;
        if (e.isMetaDown()) mods |= META_DOWN;
        if (e.isAltDown() && altIsMeta) mods |= ALT_DOWN;
        return mods;
    }

    /**
     * The plain byte a key sends when the emulator has no mapping of its own.
     *
     * <p>Tab and Escape are the ones that matter: JediTerm's key encoder covers Enter and
     * Backspace but not these two, and because both are control characters the KEY_TYPED path
     * discards them as well — so without this they reach the shell by no route at all. Escape is
     * how you leave insert mode in vim; Tab is completion.
     *
     * <p>Shift+Tab is {@code ESC [ Z} (CBT), which is what xterm sends and what readline and every
     * TUI expect for a backwards field move.
     */
    static byte @Nullable [] literalFallback(KeyCode code, KeyEvent e) {
        return switch (code) {
            case TAB -> e.isShiftDown() ? new byte[] {ESC, '[', 'Z'} : new byte[] {0x09};
            case ESCAPE -> new byte[] {ESC};
            // Mapped by the emulator today, kept here as insurance: the failure mode of a missing
            // entry is a dead key plus a stolen focus, which is expensive to diagnose.
            case ENTER -> new byte[] {0x0d};
            case BACK_SPACE -> new byte[] {0x7f};
            default -> null;
        };
    }

    /** The C0 control byte for Ctrl+<key>, or null if the key has no control form. */
    static byte @Nullable [] controlByte(KeyCode code) {
        if (code.isLetterKey()) {
            // Ctrl+A..Ctrl+Z -> 0x01..0x1a
            char letter = code.getChar().toUpperCase(java.util.Locale.ROOT).charAt(0);
            return new byte[] {(byte) (letter - 'A' + 1)};
        }
        return switch (code) {
            case SPACE, DIGIT2 -> new byte[] {0x00}; // Ctrl+Space / Ctrl+@ -> NUL
            // xterm's Ctrl+digit row: 3..7 are the controls their US shifted keys name ([ \\ ] ^ _), 8 is DEL.
            case DIGIT3 -> new byte[] {0x1b};
            case DIGIT4 -> new byte[] {0x1c};
            case DIGIT5 -> new byte[] {0x1d};
            case DIGIT7 -> new byte[] {0x1f};
            case DIGIT8 -> new byte[] {0x7f};
            case OPEN_BRACKET -> new byte[] {0x1b}; // ESC
            case BACK_SLASH -> new byte[] {0x1c}; // FS  (Ctrl+\ -> SIGQUIT)
            case CLOSE_BRACKET -> new byte[] {0x1d}; // GS
            case DIGIT6 -> new byte[] {0x1e}; // Ctrl+^ -> RS
            case MINUS, SLASH -> new byte[] {0x1f}; // Ctrl+_ -> US
            default -> null;
        };
    }
}
