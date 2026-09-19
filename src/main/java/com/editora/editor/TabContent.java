package com.editora.editor;

import javafx.scene.Node;

/**
 * Something that can live in an editor {@link javafx.scene.control.Tab}. The tab framework stores the
 * content in {@code Tab.userData}, so a tab is no longer assumed to be an {@link EditorBuffer}.
 *
 * <p>This lets non-editor views (the Welcome page, and later a diff/image/help view) be real tabs that
 * the strip activates, switches, and closes for free. Buffer-only operations route through
 * {@code MainController.bufferOf(Tab)}, which returns {@code null} for a non-buffer tab.
 *
 * <p>Lives in {@code com.editora.editor} (not {@code ui}) so {@link EditorBuffer} can implement it
 * without the editor package depending on the UI package — the same independence the completion popup
 * preserves.
 */
public interface TabContent {

    /** The content node shown when the tab is selected. */
    Node node();

    /** The tab's label text. */
    String title();

    /** An optional graphic for the tab header (e.g. an app logo); {@code null} for none. */
    default Node icon() {
        return null;
    }

    /** Whether the tab shows a close affordance and may be closed. */
    default boolean closeable() {
        return true;
    }

    /** Whether this tab owns user edits that have not yet been applied to their durable editor buffer. */
    default boolean hasUnsavedChanges() {
        return false;
    }

    /** Immutable identity for the current draft, used to detect edits made during later close prompts. */
    default Object unsavedStateToken() {
        return hasUnsavedChanges() ? Boolean.TRUE : null;
    }

    /**
     * Applies/preserves the tab's pending edits before close. Returns true only when it is now safe to
     * close. The main tab lifecycle calls this after the user chooses the affirmative action.
     */
    default boolean saveBeforeClose() {
        return !hasUnsavedChanges();
    }

    /** Message-catalog key for the affirmative close action (Save for buffers, Apply for drafts). */
    default String closeSaveActionKey() {
        return "dialog.save";
    }
}
