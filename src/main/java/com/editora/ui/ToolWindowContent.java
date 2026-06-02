package com.editora.ui;

/**
 * Implemented by a tool window's content panel so that, when the window is opened, keyboard focus
 * moves into it and its first item (folder/file/bookmark/symbol) is selected.
 * {@link ToolWindowManager#open} invokes {@link #focusFirstItem()} once the panel is shown.
 */
public interface ToolWindowContent {

    /** Moves focus into the panel and selects its first item. */
    void focusFirstItem();
}
