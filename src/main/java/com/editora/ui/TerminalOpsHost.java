package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link TerminalCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class TerminalOpsHost implements TerminalCoordinator.Ops {

    private final MainController mc;

    TerminalOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public java.nio.file.@Nullable Path workingDirectory() {
        java.nio.file.Path root = mc.windowProjectRoot();
        if (root != null) {
            return root; // a remote project's root: its terminals run on the host
        }
        EditorBuffer b = mc.activeBuffer();
        java.nio.file.Path file = b == null ? null : b.getPath();
        return file != null && com.editora.vfs.Vfs.isLocal(file)
                ? file.toAbsolutePath().getParent()
                : null;
    }

    @Override
    public boolean isToolWindowOpen() {
        return mc.terminalToolWindow != null && mc.toolWindows.isOpen(mc.terminalToolWindow);
    }

    @Override
    public void showToolWindow() {
        mc.toolWindows.open(mc.terminalToolWindow);
    }

    @Override
    public void hideToolWindow() {
        mc.toolWindows.close(mc.terminalToolWindow);
    }

    @Override
    public void focusEditor() {
        mc.toolWindows.focusEditor();
    }

    @Override
    public java.nio.file.Path configDir() {
        return mc.config.getConfigDir();
    }

    @Override
    public void openLink(java.nio.file.Path file, int line, int column) {
        if (java.nio.file.Files.isDirectory(file)) {
            mc.openAndNavigate(file, -1); // reveals it in the Project tree
        } else {
            mc.openAndGoto(file, Math.max(0, line - 1), Math.max(0, column - 1));
        }
    }
}
