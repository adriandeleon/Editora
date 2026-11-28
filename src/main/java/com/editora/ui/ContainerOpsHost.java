package com.editora.ui;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * What {@link ContainerCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class ContainerOpsHost implements ContainerCoordinator.Ops {

    private final MainController mc;

    ContainerOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public @Nullable Path projectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public boolean execAllowed(@Nullable String targetId) {
        return targetId != null && mc.config.remoteExecAllowed(targetId);
    }

    @Override
    public void setExecAllowed(@Nullable String targetId, boolean allowed) {
        if (targetId != null) mc.config.setRemoteExecAllowed(targetId, allowed);
    }

    @Override
    public void execChanged() {
        mc.toolsMoved();
    }
}
