package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link WindowMcpBridge} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowMcpHost implements WindowMcpBridge.Host {

    private final MainController mc;

    WindowMcpHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public WindowSessionCoordinator sessions() {
        return mc.sessions;
    }

    @Override
    public FileWorkflowCoordinator fileWorkflows() {
        return mc.fileWorkflows;
    }

    @Override
    public EditorArea editorArea() {
        return mc.editorArea;
    }

    @Override
    public CommandRegistry registry() {
        return mc.registry;
    }

    @Override
    public @Nullable Path projectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public com.editora.lsp.LspManager lspManager() {
        return mc.lspManager;
    }

    @Override
    public NavigationCoordinator navigation() {
        return mc.navigation;
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
    }

    @Override
    public TodoCoordinator todoCoordinator() {
        return mc.todoCoordinator;
    }

    @Override
    public SearchCoordinator searchCoordinator() {
        return mc.searchCoordinator;
    }

    @Override
    public LspCoordinator lspCoordinator() {
        return mc.lspCoordinator;
    }

    @Override
    public boolean lspEnabled() {
        return mc.lspEnabled();
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public @Nullable ImageViewerPane imagePaneOf(Tab tab) {
        return mc.imagePaneOf(tab);
    }

    @Override
    public @Nullable HexViewerPane hexPaneOf(Tab tab) {
        return mc.hexPaneOf(tab);
    }

    @Override
    public @Nullable PdfViewerPane pdfPaneOf(Tab tab) {
        return mc.pdfPaneOf(tab);
    }

    @Override
    public @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public @Nullable EditorBuffer ensureBuffer(Tab tab) {
        return mc.ensureBuffer(tab);
    }

    @Override
    public @Nullable Path tabPath(Tab tab) {
        return mc.tabPath(tab);
    }

    @Override
    public Path canonicalPath(Path p) {
        return mc.canonicalPath(p);
    }

    @Override
    public Path configDirectory() {
        return mc.config.getConfigDir();
    }
}
