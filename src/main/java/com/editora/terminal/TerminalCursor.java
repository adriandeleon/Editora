package com.editora.terminal;

/**
 * The cursor shape the user prefers. A program can still ask for another (DECSCUSR) while it runs — vim's
 * insert-mode bar against its normal-mode block — and that request wins, since it usually signals a mode.
 */
public enum TerminalCursor {
    BLOCK,
    UNDERLINE,
    BAR
}
