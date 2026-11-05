package com.editora.ui;

import javafx.css.PseudoClass;
import javafx.scene.Node;

import com.editora.config.Settings;

/**
 * The Debug and Run consoles under a UI theme and an editor theme that disagree on light vs dark (review 3, C205).
 * A console is an editor-themed area, but its own lines are drawn in the UI theme's colours: stderr in the UI's
 * danger red, Editora's lines in its muted grey, a program's ANSI colours from its palette. On the other theme's
 * background they measured 2.6:1 to 3.3:1. Marked {@code :ui-colours}, the console takes the UI's background and ink
 * as well (syntax.css), so every colour in it is drawn on the background it was made for.
 */
final class ConsoleColours {

    static final PseudoClass UI_COLOURS = PseudoClass.getPseudoClass("ui-colours");

    private ConsoleColours() {}

    /** Whether the consoles need the UI's colours under {@code settings}' themes. */
    static boolean needed(Settings settings) {
        return DebugPanel.themesDisagree(
                Themes.backgroundFor(settings.getTheme()), EditorThemes.editorBackgroundFor(settings.getEditorTheme()));
    }

    /** Marks {@code console} to be drawn in the UI's colours ({@code ui}), or in the editor theme's. */
    static void apply(Node console, boolean ui) {
        console.pseudoClassStateChanged(UI_COLOURS, ui);
    }
}
