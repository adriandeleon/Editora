package com.editora.editor;

/**
 * A per-buffer surface backed by a GPU texture (an overlay {@code Canvas}) that must not outlive its tab's
 * time on screen: retained VRAM would otherwise grow with the number of open files. {@code EditorBuffer}
 * tells every such child of its code pane when the tab is shown or goes to the background.
 */
interface TabSurface {

    /** {@code false} releases the texture; {@code true} lets the surface size itself and repaint again. */
    void setRenderingActive(boolean active);
}
