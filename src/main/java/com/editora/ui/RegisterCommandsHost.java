package com.editora.ui;

import java.nio.file.Path;

import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RegisterCommands} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class RegisterCommandsHost implements RegisterCommands.Host {

    private final MainController mc;

    RegisterCommandsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
    }

    @Override
    public boolean activeEditable() {
        return mc.editing.activeEditable();
    }

    @Override
    public @Nullable Integer prefixArgument() {
        return mc.editing.currentPrefixArg;
    }

    @Override
    public void openThen(Path file, Runnable then) {
        mc.fileWorkflows.openThen(file, then);
    }

    @Override
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public Stage stage() {
        return mc.stage;
    }
}
