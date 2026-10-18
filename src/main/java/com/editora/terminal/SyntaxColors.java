package com.editora.terminal;

import javafx.scene.paint.Color;

/**
 * The editor-theme colours a terminal palette is derived from: the editor surface, its plain text, the caret,
 * and five token colours ({@code .text.keyword}, {@code escape}, {@code type}, {@code number},
 * {@code function} in the editor themes).
 *
 * <p>Read from the theme rather than restated, so a shell's colours stay in the family of the code beside
 * it whichever of the editor themes is active. See {@link TerminalPalettes#derive}.
 */
public record SyntaxColors(
        Color background,
        Color foreground,
        Color caret,
        Color keyword,
        Color escape,
        Color type,
        Color number,
        Color function) {}
