package com.editora.ui;

import org.jspecify.annotations.Nullable;

/**
 * What {@link MacroCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class MacroOpsHost implements MacroCoordinator.Ops {

    private final MainController mc;

    MacroOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void refreshAllWindows() {
        mc.refreshSavedMacroCommandsAllWindows();
    }

    @Override
    public void setRecordingIndicator(boolean recording) {
        mc.statusBar.setMacroRecording(recording);
    }

    @Override
    public @Nullable Integer prefixArgument() {
        return mc.editing.currentPrefixArg;
    }

    @Override
    public void resetShortcut(String commandId) {
        mc.editorSettings.dropShortcut(commandId); // the macro is deleted: nothing to bind again
    }
}
