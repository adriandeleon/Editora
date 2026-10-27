package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.jspecify.annotations.Nullable;

/**
 * One view's keyboard, as the kitty protocol sees it: JavaFX's three events per keystroke turned
 * into the protocol's press, repeat and release, with the bookkeeping that takes.
 *
 * <p>JavaFX says neither when a press is a repeat nor whether a release is owed. A press for a key
 * already down is a repeat — auto-repeat sends presses with no release between. A release is sent
 * only for a key whose press went out as an escape: one whose press a menu chord consumed, or that
 * typed plain text, never reached the program, and a release for it would be a key it never saw go
 * down.
 *
 * <p>The KEY_TYPED that follows a press already reported is dropped, or the key would arrive twice.
 * The exception is a dead key's composition: the press said {@code e} and the typed event says
 * {@code é}, which is text the press could not have carried, and it goes out as a text event.
 *
 * <p>Every method returns null for "not mine, encode it the legacy way", an empty array for "mine,
 * and there is nothing to send", or the bytes.
 */
final class KittyKeyInput {

    private static final byte[] NOTHING = new byte[0];

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /** Keys down now, whatever happened to their presses: what makes the next press a repeat. */
    private final Set<KeyCode> held = new HashSet<>();

    /** Keys whose press went out as an escape, and so are owed a release. */
    private final Set<KeyCode> reported = new HashSet<>();

    /** Set by a reported press, so the KEY_TYPED it produces is not sent a second time. */
    private boolean pressSent;

    /** What the reported press said it typed, to tell its own KEY_TYPED from a composition. */
    private String pressText = "";

    byte @Nullable [] pressed(KeyEvent e, int flags, boolean altIsMeta, boolean ctrlAltIsMeta) {
        boolean repeat = !held.add(e.getCode());
        pressSent = false;
        if (flags == 0) return null;
        KittyKeys.Key key = keyOf(e, altIsMeta, ctrlAltIsMeta);
        String sequence = KittyKeys.encode(key, flags, repeat ? KittyKeys.Type.REPEAT : KittyKeys.Type.PRESS);
        if (sequence == KittyKeys.LEGACY) return null;
        if (sequence == KittyKeys.NOTHING) return NOTHING;
        reported.add(e.getCode());
        pressSent = true;
        pressText = key.text() == null ? "" : key.text();
        return bytes(sequence);
    }

    byte @Nullable [] typed(KeyEvent e, int flags) {
        String text = e.getCharacter();
        if (flags == 0 || text == null || text.isEmpty() || text.equals(KeyEvent.CHAR_UNDEFINED)) return null;
        boolean all = (flags & KittyKeys.ALL_KEYS) != 0;
        if (pressSent) {
            pressSent = false;
            // The press's own character, which it already reported. ASCII after a reported press is
            // always that key's — Shift+1's "!" included, whatever the press itself claimed to type.
            // Short of all keys a reported press is a modified one, and what it typed is not text.
            if (!all || !composedByTheOS(text)) return NOTHING;
        }
        if (!all) return null;
        String sequence = KittyKeys.textEvent(text, flags);
        if (sequence == KittyKeys.LEGACY) return null;
        if (sequence == KittyKeys.NOTHING) return NOTHING;
        return bytes(sequence);
    }

    byte @Nullable [] released(KeyEvent e, int flags, boolean altIsMeta, boolean ctrlAltIsMeta) {
        held.remove(e.getCode());
        boolean owed = reported.remove(e.getCode());
        if (!owed || flags == 0) return null;
        String sequence = KittyKeys.encode(keyOf(e, altIsMeta, ctrlAltIsMeta), flags, KittyKeys.Type.RELEASE);
        if (sequence == KittyKeys.LEGACY || sequence == KittyKeys.NOTHING) return null;
        return bytes(sequence);
    }

    /**
     * Forgets every key held. Focus left the view, and the releases for whatever was down will go to
     * someone else — a later press must not be taken for a repeat, nor a release owed for it.
     */
    void reset() {
        held.clear();
        reported.clear();
        pressSent = false;
    }

    /** Text the reported press could not have known: non-ASCII where it said ASCII, or nothing. */
    private boolean composedByTheOS(String typed) {
        if (typed.chars().allMatch(c -> c < 0x80)) return false;
        return !typed.equals(pressText);
    }

    /**
     * The event in the protocol's terms. Two modifiers are not what they seem: Ctrl+Alt is AltGr
     * composing a character unless {@code ctrlAltIsMeta} says this platform tells the two apart
     * (see {@link KeyEncoding#encodePressed}), and then neither is a modifier — the key typed a
     * character; and Alt composes rather than modifies when Meta is off, as Option does on a Mac.
     */
    private static KittyKeys.Key keyOf(KeyEvent e, boolean altIsMeta, boolean ctrlAltIsMeta) {
        boolean ctrl = e.isControlDown();
        boolean alt = e.isAltDown();
        if (!MAC && ctrl && alt && !e.isMetaDown() && !ctrlAltIsMeta) {
            ctrl = false;
            alt = false;
        }
        if (!altIsMeta) alt = false;
        return new KittyKeys.Key(
                e.getCode(),
                e.getText(),
                e.isShiftDown(),
                alt,
                ctrl,
                e.isMetaDown(),
                locked(KeyCode.CAPS),
                locked(KeyCode.NUM_LOCK));
    }

    /** Whether a lock key is on. False where JavaFX cannot say. */
    private static boolean locked(KeyCode lock) {
        try {
            return Platform.isKeyLocked(lock).orElse(false);
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private static byte[] bytes(String sequence) {
        return sequence.getBytes(StandardCharsets.UTF_8);
    }
}
