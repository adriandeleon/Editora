package com.editora.editor;

import javafx.scene.input.KeyEvent;

/**
 * Whether a {@code KEY_TYPED} event is text the editor will insert — RichTextFX's own rule
 * ({@code GenericStyledAreaBehavior.controlKeysFilter}), which the typing assists must share. They used to
 * stand down for any character typed with Control, Alt or Meta held, yet the area still inserted it: on
 * layouts where {@code { [ ] } | \ ~ @} need Option (macOS German, Spanish) or AltGr (Windows, delivered as
 * Control+Alt), those characters were typed without auto-close, closer re-alignment or on-type formatting.
 */
final class TypedText {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    private TypedText() {}

    static boolean isText(KeyEvent e) {
        return isText(WINDOWS, e.isControlDown(), e.isAltDown(), e.isMetaDown());
    }

    /**
     * Windows: no modifier, or Control+Alt together (AltGr). Elsewhere: anything without Control or Meta —
     * Option composes characters on macOS, and AltGr is not reported as Alt on Linux. Pure — tested.
     */
    static boolean isText(boolean windows, boolean control, boolean alt, boolean meta) {
        if (windows) {
            return !meta && control == alt;
        }
        return !control && !meta;
    }
}
