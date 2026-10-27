package com.editora.terminal;

import java.util.Map;

import javafx.scene.input.KeyCode;

import org.jspecify.annotations.Nullable;

/**
 * Encodes a key event in the kitty keyboard protocol, once a program has asked for it.
 *
 * <p>Pure: it is handed the key, the modifiers, the event type and the flags, and returns the escape
 * sequence — or {@link #LEGACY} to say "send what you would have sent anyway", which is how plain
 * typing and Enter stay exactly as they were, or {@link #NOTHING} for an event this mode does not
 * report. The specification it follows is kitty's keyboard-protocol document; the numbers in the
 * tables below are copied from it.
 *
 * <p>The progressive-enhancement flags decide what changes:
 *
 * <ul>
 *   <li><b>Disambiguate</b> (1): Escape, and any key with Ctrl, Alt or Super, become {@code CSI u} —
 *       so Escape is no longer the start of every sequence and Ctrl+I is no longer Tab. Text still
 *       arrives as text, and Enter, Tab and Backspace without modifiers keep their bytes, so that
 *       typing {@code reset} still works after a program left the mode on.
 *   <li><b>Event types</b> (2): repeats and releases of the keys that are reported as escapes.
 *   <li><b>Alternate keys</b> (4): the shifted key, for shortcut matching.
 *   <li><b>All keys</b> (8): every key is an escape, text included, and the modifier keys
 *       themselves are reported.
 *   <li><b>Text</b> (16): with all keys, the text a key would have typed rides along.
 * </ul>
 *
 * <p>Two things JavaFX does not say are approximated. It has no left/right distinction for the
 * modifier keys, so they are all reported as the left one. And it has no "base layout key", the
 * key's place on a US keyboard, so that optional field is never sent.
 */
final class KittyKeys {

    static final int DISAMBIGUATE = 1;
    static final int EVENT_TYPES = 2;
    static final int ALTERNATES = 4;
    static final int ALL_KEYS = 8;
    static final int TEXT = 16;

    /** Send what legacy encoding would. Compared by identity. */
    static final String LEGACY = new String("legacy");

    /** Report nothing, and keep the event from anything else. Compared by identity. */
    static final String NOTHING = new String("nothing");

    private KittyKeys() {}

    enum Type {
        PRESS,
        REPEAT,
        RELEASE
    }

    /**
     * One key event, as far as the protocol is concerned.
     *
     * @param text what the key types, as the press reports it — empty when nothing — used for the
     *     text field and to tell a key that types from one that does not
     * @param alt Alt as a modifier: false where it composes instead (Option on macOS with Meta off,
     *     AltGr), in which case what it composes is {@code text}
     */
    record Key(
            KeyCode code,
            String text,
            boolean shift,
            boolean alt,
            boolean ctrl,
            boolean superKey,
            boolean capsLock,
            boolean numLock) {}

    // Modifier bits. The field sent is 1 + these.
    private static final int SHIFT = 1;
    private static final int ALT = 2;
    private static final int CTRL = 4;
    private static final int SUPER = 8;
    private static final int CAPS_LOCK = 64;
    private static final int NUM_LOCK = 128;

    /** A key with a number of its own: the number, and the final byte that ends its sequence. */
    private record Functional(int number, char last) {}

    /** Keys whose sequence is not {@code CSI n u}: the arrows and friends, and the tilde keys. */
    private static final Map<KeyCode, Functional> FUNCTIONAL = Map.ofEntries(
            Map.entry(KeyCode.ESCAPE, new Functional(27, 'u')),
            Map.entry(KeyCode.ENTER, new Functional(13, 'u')),
            Map.entry(KeyCode.TAB, new Functional(9, 'u')),
            Map.entry(KeyCode.BACK_SPACE, new Functional(127, 'u')),
            Map.entry(KeyCode.INSERT, new Functional(2, '~')),
            Map.entry(KeyCode.DELETE, new Functional(3, '~')),
            Map.entry(KeyCode.LEFT, new Functional(1, 'D')),
            Map.entry(KeyCode.RIGHT, new Functional(1, 'C')),
            Map.entry(KeyCode.UP, new Functional(1, 'A')),
            Map.entry(KeyCode.DOWN, new Functional(1, 'B')),
            Map.entry(KeyCode.PAGE_UP, new Functional(5, '~')),
            Map.entry(KeyCode.PAGE_DOWN, new Functional(6, '~')),
            Map.entry(KeyCode.HOME, new Functional(1, 'H')),
            Map.entry(KeyCode.END, new Functional(1, 'F')),
            Map.entry(KeyCode.PRINTSCREEN, new Functional(57361, 'u')),
            Map.entry(KeyCode.PAUSE, new Functional(57362, 'u')),
            Map.entry(KeyCode.CONTEXT_MENU, new Functional(57363, 'u')),
            Map.entry(KeyCode.F1, new Functional(1, 'P')),
            Map.entry(KeyCode.F2, new Functional(1, 'Q')),
            // Not CSI R: that is also the cursor position report.
            Map.entry(KeyCode.F3, new Functional(13, '~')),
            Map.entry(KeyCode.F4, new Functional(1, 'S')),
            Map.entry(KeyCode.F5, new Functional(15, '~')),
            Map.entry(KeyCode.F6, new Functional(17, '~')),
            Map.entry(KeyCode.F7, new Functional(18, '~')),
            Map.entry(KeyCode.F8, new Functional(19, '~')),
            Map.entry(KeyCode.F9, new Functional(20, '~')),
            Map.entry(KeyCode.F10, new Functional(21, '~')),
            Map.entry(KeyCode.F11, new Functional(23, '~')),
            Map.entry(KeyCode.F12, new Functional(24, '~')),
            Map.entry(KeyCode.F13, new Functional(57376, 'u')),
            Map.entry(KeyCode.F14, new Functional(57377, 'u')),
            Map.entry(KeyCode.F15, new Functional(57378, 'u')),
            Map.entry(KeyCode.F16, new Functional(57379, 'u')),
            Map.entry(KeyCode.F17, new Functional(57380, 'u')),
            Map.entry(KeyCode.F18, new Functional(57381, 'u')),
            Map.entry(KeyCode.F19, new Functional(57382, 'u')),
            Map.entry(KeyCode.F20, new Functional(57383, 'u')),
            Map.entry(KeyCode.F21, new Functional(57384, 'u')),
            Map.entry(KeyCode.F22, new Functional(57385, 'u')),
            Map.entry(KeyCode.F23, new Functional(57386, 'u')),
            Map.entry(KeyCode.F24, new Functional(57387, 'u')),
            // The keypad with NumLock off: keys of their own once disambiguated.
            Map.entry(KeyCode.KP_LEFT, new Functional(57417, 'u')),
            Map.entry(KeyCode.KP_RIGHT, new Functional(57418, 'u')),
            Map.entry(KeyCode.KP_UP, new Functional(57419, 'u')),
            Map.entry(KeyCode.KP_DOWN, new Functional(57420, 'u')),
            Map.entry(KeyCode.PLAY, new Functional(57428, 'u')),
            Map.entry(KeyCode.STOP, new Functional(57432, 'u')),
            Map.entry(KeyCode.FAST_FWD, new Functional(57433, 'u')),
            Map.entry(KeyCode.REWIND, new Functional(57434, 'u')),
            Map.entry(KeyCode.TRACK_NEXT, new Functional(57435, 'u')),
            Map.entry(KeyCode.TRACK_PREV, new Functional(57436, 'u')),
            Map.entry(KeyCode.RECORD, new Functional(57437, 'u')),
            Map.entry(KeyCode.VOLUME_DOWN, new Functional(57438, 'u')),
            Map.entry(KeyCode.VOLUME_UP, new Functional(57439, 'u')),
            Map.entry(KeyCode.MUTE, new Functional(57440, 'u')));

    /**
     * The modifier and lock keys themselves, reported only with all keys. JavaFX cannot tell left
     * from right, so each is the left one; Super covers Command and the Windows key.
     */
    private static final Map<KeyCode, Integer> MODIFIER_KEYS = Map.of(
            KeyCode.CAPS, 57358,
            KeyCode.SCROLL_LOCK, 57359,
            KeyCode.NUM_LOCK, 57360,
            KeyCode.SHIFT, 57441,
            KeyCode.CONTROL, 57442,
            KeyCode.ALT, 57443,
            KeyCode.COMMAND, 57444,
            KeyCode.META, 57444,
            KeyCode.WINDOWS, 57444,
            KeyCode.ALT_GRAPH, 57453);

    /** The keypad keys that type with NumLock on: text unless disambiguated by a modifier. */
    private static final Map<KeyCode, Integer> KEYPAD = Map.ofEntries(
            Map.entry(KeyCode.NUMPAD0, 57399),
            Map.entry(KeyCode.NUMPAD1, 57400),
            Map.entry(KeyCode.NUMPAD2, 57401),
            Map.entry(KeyCode.NUMPAD3, 57402),
            Map.entry(KeyCode.NUMPAD4, 57403),
            Map.entry(KeyCode.NUMPAD5, 57404),
            Map.entry(KeyCode.NUMPAD6, 57405),
            Map.entry(KeyCode.NUMPAD7, 57406),
            Map.entry(KeyCode.NUMPAD8, 57407),
            Map.entry(KeyCode.NUMPAD9, 57408),
            Map.entry(KeyCode.DECIMAL, 57409),
            Map.entry(KeyCode.DIVIDE, 57410),
            Map.entry(KeyCode.MULTIPLY, 57411),
            Map.entry(KeyCode.SUBTRACT, 57412),
            Map.entry(KeyCode.ADD, 57413),
            Map.entry(KeyCode.SEPARATOR, 57416));

    /** The unshifted character of the US-layout punctuation keys JavaFX names. */
    private static final Map<KeyCode, Character> PUNCTUATION = Map.ofEntries(
            Map.entry(KeyCode.SPACE, ' '),
            Map.entry(KeyCode.MINUS, '-'),
            Map.entry(KeyCode.EQUALS, '='),
            Map.entry(KeyCode.OPEN_BRACKET, '['),
            Map.entry(KeyCode.CLOSE_BRACKET, ']'),
            Map.entry(KeyCode.BACK_SLASH, '\\'),
            Map.entry(KeyCode.SEMICOLON, ';'),
            Map.entry(KeyCode.QUOTE, '\''),
            Map.entry(KeyCode.BACK_QUOTE, '`'),
            Map.entry(KeyCode.COMMA, ','),
            Map.entry(KeyCode.PERIOD, '.'),
            Map.entry(KeyCode.SLASH, '/'));

    /**
     * The sequence for an event, {@link #LEGACY}, or {@link #NOTHING}.
     *
     * @param flags the current screen's flags; 0 is legacy for everything
     */
    static String encode(Key key, int flags, Type type) {
        if (flags == 0) return LEGACY;
        boolean all = (flags & ALL_KEYS) != 0;
        boolean events = (flags & EVENT_TYPES) != 0;
        // Disambiguation is what turns a press into CSI form; all keys implies it. Without either —
        // event types alone — a press is sent as it always was, and only the release is new.
        boolean disambiguate = all || (flags & DISAMBIGUATE) != 0;
        int mods = modifiers(key);
        int locks = (key.capsLock() ? CAPS_LOCK : 0) | (key.numLock() ? NUM_LOCK : 0);

        Integer modifierKey = MODIFIER_KEYS.get(key.code());
        if (modifierKey != null) {
            if (!all) return LEGACY;
            if (type == Type.RELEASE && !events) return NOTHING;
            // The key's own bit follows the event: set while it goes down, clear as it comes up.
            int own = ownBit(key.code());
            int state = type == Type.RELEASE ? mods & ~own : mods | own;
            return sequence(modifierKey, 'u', state | locks, type, flags, null);
        }

        Functional functional = FUNCTIONAL.get(key.code());
        if (functional != null) {
            boolean legacyBytes =
                    key.code() == KeyCode.ENTER || key.code() == KeyCode.TAB || key.code() == KeyCode.BACK_SPACE;
            // Enter, Tab and Backspace keep their bytes, and report no release, until all keys are
            // asked for: they are how anyone types `reset` at a prompt a program broke.
            if (legacyBytes && !all) {
                if (type == Type.RELEASE) return NOTHING;
                if (mods == 0) return LEGACY;
            }
            if (type == Type.RELEASE && !events) return NOTHING;
            if (type != Type.RELEASE && !disambiguate) return LEGACY;
            return sequence(functional.number(), functional.last(), mods | locks, type, flags, null);
        }

        Integer keypad = KEYPAD.get(key.code());
        int base = keypad != null ? keypad : baseCodepoint(key);
        if (base < 0) return LEGACY;
        boolean typesText = (mods & ~SHIFT) == 0;
        if (!all) {
            // Text stays text, events and all: the protocol reports no repeat or release for it.
            if (typesText || !disambiguate) return type == Type.RELEASE ? NOTHING : LEGACY;
            if (type == Type.RELEASE && !events) return NOTHING;
            // Locks are left out for a key that would type, which this one with Ctrl would not.
            return sequence(base, 'u', mods | locks, type, flags, alternates(key, base, flags));
        }
        if (type == Type.RELEASE && !events) return NOTHING;
        String text = (flags & TEXT) != 0 && type != Type.RELEASE && typesText ? printable(key.text()) : null;
        return sequence(base, 'u', mods | locks, type, flags, alternates(key, base, flags), text);
    }

    /**
     * Text that arrived with no key behind it — a dead key's composition, an input method — while
     * every key is being reported. With the text flag it is a key-0 event carrying the text; without
     * it, the text as it is, which is what kitty sends.
     */
    static String textEvent(String text, int flags) {
        String printable = printable(text);
        if (printable == null) return NOTHING;
        if ((flags & ALL_KEYS) == 0 || (flags & TEXT) == 0) return LEGACY;
        return "\u001b[0;;" + codepoints(printable) + "u";
    }

    private static String sequence(int number, char last, int mods, Type type, int flags, @Nullable String alternates) {
        return sequence(number, last, mods, type, flags, alternates, null);
    }

    /**
     * {@code CSI number[:alternates][;modifiers[:type][;text]] last}, with every trailing default
     * left out — and {@code CSI last} alone for the letter keys with nothing to add, since their
     * number is always 1.
     */
    private static String sequence(
            int number, char last, int mods, Type type, int flags, @Nullable String alternates, @Nullable String text) {
        boolean showType = (flags & EVENT_TYPES) != 0 && type != Type.PRESS;
        String eventType = showType ? ":" + (type == Type.REPEAT ? 2 : 3) : "";
        String modField = mods != 0 || showType ? (1 + mods) + eventType : "";
        StringBuilder out = new StringBuilder("\u001b[");
        boolean letter = last != 'u' && last != '~';
        if (!(letter && modField.isEmpty())) out.append(number);
        if (alternates != null && last == 'u') out.append(':').append(alternates);
        if (text != null) out.append(';').append(modField).append(';').append(codepoints(text));
        else if (!modField.isEmpty()) out.append(';').append(modField);
        return out.append(last).toString();
    }

    private static int modifiers(Key key) {
        int mods = 0;
        if (key.shift()) mods |= SHIFT;
        if (key.alt()) mods |= ALT;
        if (key.ctrl()) mods |= CTRL;
        if (key.superKey()) mods |= SUPER;
        return mods;
    }

    private static int ownBit(KeyCode code) {
        return switch (code) {
            case SHIFT -> SHIFT;
            case CONTROL -> CTRL;
            case ALT -> ALT;
            case COMMAND, META, WINDOWS -> SUPER;
            default -> 0;
        };
    }

    /**
     * The key's unshifted character: {@code a} for the A key with or without Shift, {@code 1} for
     * the 1 key whatever it typed. From the key code where JavaFX names one; otherwise from what the
     * key typed, lower-cased — a key of a non-US layout that JavaFX has no code for. -1 when neither
     * says.
     */
    static int baseCodepoint(Key key) {
        KeyCode code = key.code();
        if (code.isLetterKey()) {
            // A letter key on a layout whose letters are not Latin types its own letter, and that is
            // the key as far as the protocol is concerned: Cyrillic С, not the c on the keycap.
            String text = key.text();
            if (text != null && text.codePointCount(0, text.length()) == 1) {
                int typed = Character.toLowerCase(text.codePointAt(0));
                if (Character.isLetter(typed) && typed > 0x7f) return typed;
            }
            return Character.toLowerCase(code.getChar().charAt(0));
        }
        if (code.isDigitKey() && code.getChar().length() == 1)
            return code.getChar().charAt(0);
        Character punctuation = PUNCTUATION.get(code);
        if (punctuation != null) return punctuation;
        String text = key.text();
        if (text != null && text.codePointCount(0, text.length()) == 1) {
            int typed = text.codePointAt(0);
            if (typed >= 0x20 && typed != 0x7f) return Character.toLowerCase(typed);
        }
        return -1;
    }

    /** The shifted key, when Shift is held and the flag asks for it: {@code A} for {@code a}. */
    private static @Nullable String alternates(Key key, int base, int flags) {
        if ((flags & ALTERNATES) == 0 || !key.shift()) return null;
        int shifted = -1;
        String text = key.text();
        if (text != null && text.codePointCount(0, text.length()) == 1 && text.codePointAt(0) >= 0x20) {
            shifted = text.codePointAt(0);
        }
        if (shifted < 0 || shifted == base) shifted = Character.toUpperCase(base);
        return shifted == base ? null : String.valueOf(shifted);
    }

    /** The text if it is something a key could type, or null: no controls, C0 or C1. */
    private static @Nullable String printable(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f)) return null;
        }
        return text;
    }

    private static String codepoints(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (!out.isEmpty()) out.append(':');
            out.append(cp);
        });
        return out.toString();
    }

    /** For reports and failures: the sequence with ESC spelled {@code \e}. */
    static String visible(String sequence) {
        if (sequence == LEGACY) return "legacy";
        if (sequence == NOTHING) return "nothing";
        return sequence.replace("\u001b", "\\e");
    }
}
