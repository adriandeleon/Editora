package org.adriandeleon.editora.editor;

public final class EmacsKeyBindingSupport {

    private EmacsKeyBindingSupport() {
    }

    public static boolean isControlChord(boolean controlDown,
                                         boolean altDown,
                                         boolean metaDown,
                                         boolean shiftDown) {
        return controlDown && !altDown && !metaDown && !shiftDown;
    }

    public static boolean isControlChordAllowingShift(boolean controlDown,
                                                      boolean altDown,
                                                      boolean metaDown) {
        return controlDown && !altDown && !metaDown;
    }

    public static boolean isMetaChord(boolean altDown,
                                      boolean controlDown,
                                      boolean metaDown,
                                      boolean shiftDown) {
        return altDown && !controlDown && !metaDown && !shiftDown;
    }

    public static boolean isMetaChordAllowingShift(boolean altDown,
                                                   boolean controlDown,
                                                   boolean metaDown) {
        return altDown && !controlDown && !metaDown;
    }
}
