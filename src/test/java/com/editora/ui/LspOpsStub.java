package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;

/**
 * No-op {@link LspCoordinator.Ops} so a test overrides only the hooks it cares about — the same convention as
 * {@link CoordinatorHostStub}, and the reason a new {@code Ops} method doesn't break every LSP test.
 *
 * <p>The two defaults that are <em>not</em> arbitrary: {@link #lspFeatureEnabled()} returns true (the tests
 * are about the per-buffer gating below it, not the master switch) and {@link #canonicalize(Path)} normalizes
 * without resolving symlinks, mirroring {@code MainController.canonicalPath}'s fallback.
 */
class LspOpsStub implements LspCoordinator.Ops {

    @Override
    public String homeCollapsed(String absolutePath) {
        return absolutePath;
    }

    @Override
    public void openAndGoto(Path file, int line0, int col0) {}

    @Override
    public EditorBuffer openReadOnlyDoc(String title, String content, String language) {
        return null;
    }

    @Override
    public boolean selectBufferTab(EditorBuffer buffer) {
        return false;
    }

    @Override
    public boolean activeEditable() {
        return true;
    }

    @Override
    public boolean lspFeatureEnabled() {
        return true;
    }

    @Override
    public void setLspLoading(boolean loading) {}

    @Override
    public EditorBuffer bufferForPath(Path file) {
        return null;
    }

    @Override
    public EditorBuffer openBackgroundBuffer(Path file) {
        return null;
    }

    @Override
    public void fileRenamed(Path from, Path to) {}

    @Override
    public void setStatusBarLsp(String label) {}

    @Override
    public void setProblemsAvailable(boolean available) {}

    @Override
    public void enableNavigationWindowsByDefault() {}

    @Override
    public void openReferencesWindow() {}

    @Override
    public void openHierarchyWindow() {}

    @Override
    public void setStructureSymbols(EditorBuffer buffer, List<SymbolNode> symbols) {}

    @Override
    public void refreshRunButton() {}

    @Override
    public Path jdtlsWorkspaceBase() {
        return null;
    }

    @Override
    public Path lspProjectRoot() {
        return null;
    }

    @Override
    public void onDetectionSettled() {}

    @Override
    public void onServerCapabilitiesReady() {}

    @Override
    public Path canonicalize(Path file) {
        return file == null ? null : file.toAbsolutePath().normalize();
    }
}
