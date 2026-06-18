package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.stage.Window;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

/**
 * A no-op {@link CoordinatorHost} for tests: a fake host extends this and overrides only the few methods
 * the coordinator under test exercises, instead of implementing all of the (deliberately broad) interface.
 */
class CoordinatorHostStub implements CoordinatorHost {

    @Override
    public Settings settings() {
        return null;
    }

    @Override
    public boolean simpleModeActive() {
        return false;
    }

    @Override
    public boolean appThemeDark() {
        return false;
    }

    @Override
    public void forEachBuffer(Consumer<EditorBuffer> action) {}

    @Override
    public EditorBuffer activeBuffer() {
        return null;
    }

    @Override
    public boolean isLocalBuffer(EditorBuffer buffer) {
        return true;
    }

    @Override
    public void setStatus(String message) {}

    @Override
    public long fileSize(Path file) {
        return 0;
    }

    @Override
    public String bufferBaseName(EditorBuffer buffer) {
        return "";
    }

    @Override
    public void requestSave() {}

    @Override
    public void save() {}

    @Override
    public void syncSettingsWindow() {}

    @Override
    public void applyAutocomplete() {}

    @Override
    public void ensurePreviewControls(EditorBuffer buffer) {}

    @Override
    public void restoreMarkdownMode(EditorBuffer buffer) {}

    @Override
    public void openExternalUrl(String url) {}

    @Override
    public void promptText(String title, String label, String initial, Consumer<String> onAccept) {}

    @Override
    public OverlayHost overlayHost() {
        return null;
    }

    @Override
    public Window window() {
        return null;
    }
}
