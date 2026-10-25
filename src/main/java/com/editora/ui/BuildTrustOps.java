package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import com.editora.build.BuildTrust;
import com.editora.config.ConfigManager;
import org.jspecify.annotations.Nullable;

/**
 * The half of {@link BuildCoordinator.Ops} that is the configuration store and the trust prompt: whether a
 * folder is trusted, asking (a modal with Cancel as the default — a security decision must not be
 * dismissible by clicking past it), recording and revoking trust durably, the "Run custom…" history, and
 * opening the build file. A window supplies the rest (its project root, its tool windows).
 */
abstract class BuildTrustOps implements BuildCoordinator.Ops {
    /** Read per call: the coordinators are built while the window is still being constructed. */
    private final Supplier<ConfigManager> config;

    private final Supplier<Window> owner;
    /** Persists a trust change and tells the other windows (durable: it must survive a crash). */
    private final Runnable trustSaved;
    /** The Settings window's trusted-folders list, restated after a revoke (a no-op without one). */
    private final Runnable trustedFoldersChanged;

    private final Runnable requestSave;
    private final Consumer<Path> opener;

    BuildTrustOps(
            Supplier<ConfigManager> config,
            Supplier<Window> owner,
            Runnable trustSaved,
            Runnable trustedFoldersChanged,
            Runnable requestSave,
            Consumer<Path> opener) {
        this.config = config;
        this.owner = owner;
        this.trustSaved = trustSaved;
        this.trustedFoldersChanged = trustedFoldersChanged;
        this.requestSave = requestSave;
        this.opener = opener;
    }

    @Override
    public boolean isTrusted(@Nullable Path root) {
        return root != null && config.get().getTrustStore().isTrusted(root);
    }

    @Override
    public boolean confirmTrust(@Nullable Path root, BuildTrust.Summary summary) {
        TrustPrompts.Prompt prompt = TrustPrompts.buildProject(owner.get(), root, summary);
        return prompt.alert().showAndWait().orElse(ButtonType.CANCEL) == prompt.trust();
    }

    @Override
    public void trust(@Nullable Path root) {
        if (root != null) {
            config.get().getTrustStore().trust(root);
        }
        trustSaved.run();
    }

    @Override
    public void revokeTrust(@Nullable Path root) {
        if (root != null) {
            config.get().getTrustStore().revoke(root);
        }
        trustSaved.run();
        trustedFoldersChanged.run();
    }

    @Override
    public void openFile(Path file) {
        opener.accept(file);
    }

    @Override
    public List<String> customHistory(String toolId) {
        return config.get().getWorkspaceState().buildCustomHistory(toolId);
    }

    @Override
    public void rememberCustom(String toolId, String text) {
        config.get().getWorkspaceState().rememberBuildCustom(toolId, text);
        requestSave.run();
    }
}
