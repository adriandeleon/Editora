package com.editora.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The KEY step's on-disk encoding — the recorder writes it, the replay path reads it back. */
class MacroKeyTest {

    @Test
    void roundTripsBareAndModifiedKeys() {
        for (String token : new String[] {"BACK_SPACE", "S-DOWN", "C-LEFT", "C-M-Cmd-S-HOME", "M-DELETE"}) {
            MacroKey.Decoded d = MacroKey.decode(token);
            assertEquals(token, MacroKey.encode(d.ctrl(), d.alt(), d.meta(), d.shift(), d.keyCodeName()), "round trip");
        }
    }

    @Test
    void encodesModifiersInCanonicalOrder() {
        assertEquals("C-M-Cmd-S-END", MacroKey.encode(true, true, true, true, "END"));
        assertEquals("DOWN", MacroKey.encode(false, false, false, false, "DOWN"));
        assertEquals("S-DOWN", MacroKey.encode(false, false, false, true, "DOWN"));
    }

    @Test
    void decodesTheFlagsAndTheKeyName() {
        MacroKey.Decoded d = MacroKey.decode("S-DOWN");
        assertEquals("DOWN", d.keyCodeName());
        assertEquals(true, d.shift());
        assertEquals(false, d.ctrl());
        MacroKey.Decoded all = MacroKey.decode("C-M-Cmd-S-HOME");
        assertEquals("HOME", all.keyCodeName());
        assertEquals(true, all.ctrl() && all.alt() && all.meta() && all.shift());
    }

    /** A hand-edited macros.json shouldn't be able to produce a nonsense press. */
    @Test
    void rejectsBlankAndMarkerOnlyTokens() {
        assertNull(MacroKey.decode(null));
        assertNull(MacroKey.decode(""));
        assertNull(MacroKey.decode("   "));
        assertNull(MacroKey.decode("C-"));
        assertNull(MacroKey.encode(false, false, false, false, null));
        assertNull(MacroKey.encode(false, false, false, false, "  "));
    }
}
