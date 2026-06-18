package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

/**
 * The window services a {@link LogViewerCoordinator}-style feature coordinator may need, in one shared
 * interface. {@code MainController} provides a single implementation (its inner {@code Services} adapter,
 * delegating to its private helpers) and hands the same instance to every coordinator, instead of a
 * bespoke anonymous adapter per feature. Each coordinator uses only the subset of methods it needs.
 *
 * <p>For tests, {@code CoordinatorHostStub} (test sources) is a no-op implementation a fake can extend,
 * overriding just the methods a given coordinator exercises.
 */
interface CoordinatorHost {

    Settings settings();

    boolean simpleModeActive();

    /** Whether the app/editor theme is dark (e.g. Mermaid renders a dark diagram to match). */
    boolean appThemeDark();

    /** Applies {@code action} to every open editor buffer (skipping non-buffer/Welcome tabs). */
    void forEachBuffer(Consumer<EditorBuffer> action);

    /** The active editor buffer, or {@code null} for a non-buffer tab. */
    EditorBuffer activeBuffer();

    /** Whether {@code buffer} is a local-filesystem file (some features can't serve a remote file). */
    boolean isLocalBuffer(EditorBuffer buffer);

    void setStatus(String message);

    long fileSize(Path file);

    String bufferBaseName(EditorBuffer buffer);

    /** Coalesced, off-thread config save (the frequent path). */
    void requestSave();

    /** Durable config save — blocks until written (persists a one-off choice like the last-used browser). */
    void save();

    /** Re-syncs the open Settings window's controls after a setting flips via a palette command. */
    void syncSettingsWindow();

    /** Re-pushes the autocomplete settings to every buffer (a feature gate may have changed). */
    void applyAutocomplete();

    /** (Un)wires the floating preview toggle on a markdown/diagram buffer (preview machinery). */
    void ensurePreviewControls(EditorBuffer buffer);

    /** Restores a buffer's saved preview mode (EDITOR/SPLIT/PREVIEW). */
    void restoreMarkdownMode(EditorBuffer buffer);

    /** Opens {@code url} via the platform's default handler. */
    void openExternalUrl(String url);

    /** Shows the in-scene single-line input prompt (Emacs caret keys installed). */
    void promptText(String title, String label, String initial, Consumer<String> onAccept);

    OverlayHost overlayHost();

    Window window();
}
