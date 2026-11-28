package com.editora.ui;

import org.jspecify.annotations.Nullable;

/**
 * What {@link ExternalToolCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class ExternalToolOpsHost implements ExternalToolCoordinator.Ops {

    private final MainController mc;

    ExternalToolOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public java.nio.file.@Nullable Path projectRoot() {
        return mc.windowProject != null ? mc.windowProject.rootPath() : null;
    }

    @Override
    public void openConsole() {
        mc.toolWindows.open(mc.externalToolToolWindow, true);
    }

    @Override
    public void onOutputLink(com.editora.run.StackTraceLinks.Link link) {
        mc.testNavigation.openRunLink(link);
    }
}
