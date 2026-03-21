package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmacsKeyBindingSupportTest {

    @Test
    void controlChordAcceptsPlainCtrlWithoutAltOrMeta() {
        assertTrue(EmacsKeyBindingSupport.isControlChord(true, false, false, false));
        assertFalse(EmacsKeyBindingSupport.isControlChord(true, false, false, true));
        assertFalse(EmacsKeyBindingSupport.isControlChord(true, true, false, false));
        assertFalse(EmacsKeyBindingSupport.isControlChord(true, false, true, false));
    }

    @Test
    void controlChordAllowingShiftSupportsCtrlSpaceMarkFlow() {
        assertTrue(EmacsKeyBindingSupport.isControlChordAllowingShift(true, false, false));
        assertFalse(EmacsKeyBindingSupport.isControlChordAllowingShift(false, false, false));
        assertFalse(EmacsKeyBindingSupport.isControlChordAllowingShift(true, true, false));
        assertFalse(EmacsKeyBindingSupport.isControlChordAllowingShift(true, false, true));
    }

    @Test
    void metaChordRequiresAltWithoutCtrlOrMeta() {
        assertTrue(EmacsKeyBindingSupport.isMetaChord(true, false, false, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChord(true, true, false, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChord(true, false, true, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChord(true, false, false, true));
    }

    @Test
    void metaChordAllowingShiftSupportsBufferStartEndMoves() {
        assertTrue(EmacsKeyBindingSupport.isMetaChordAllowingShift(true, false, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChordAllowingShift(false, false, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChordAllowingShift(true, true, false));
        assertFalse(EmacsKeyBindingSupport.isMetaChordAllowingShift(true, false, true));
    }
}

