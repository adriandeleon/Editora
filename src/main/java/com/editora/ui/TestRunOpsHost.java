package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What {@link TestRunCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class TestRunOpsHost implements TestRunCoordinator.Ops {

    private final MainController mc;

    TestRunOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openTestResults() {
        if (mc.testResultsToolWindow != null) {
            mc.toolWindows.open(mc.testResultsToolWindow, false);
        }
    }

    @Override
    public void setTestResultsAvailable(boolean available) {
        if (mc.testResultsToolWindow != null) {
            mc.toolWindows.setAvailable(mc.testResultsToolWindow, available);
        }
    }

    @Override
    public void openLink(com.editora.run.StackTraceLinks.Link link) {
        mc.testNavigation.openRunLink(link);
    }

    @Override
    public void jumpToTest(com.editora.test.TestNode node, @Nullable BuildTool tool) {
        mc.testNavigation.jumpToTestSource(node, tool);
    }

    @Override
    public void runTest(@Nullable BuildTool tool, @Nullable Path root, List<String> taskArgs, List<String> toggleArgs) {
        if (tool != null) {
            mc.testNavigation.buildCoordinatorFor(tool).ifPresent(c -> c.runTaskAt(root, taskArgs, toggleArgs));
        }
    }

    @Override
    public void stopTest(BuildTool tool) {
        mc.testNavigation.buildCoordinatorFor(tool).ifPresent(BuildCoordinator::stop);
    }

    @Override
    public boolean debugAvailable() {
        // Already folds in the master gate; a remote file's tests are debugged on its host.
        EditorBuffer active = mc.activeBuffer();
        Path file = active == null ? null : active.getPath();
        return mc.debugCoordinator.debugEffectiveFor(testDebugLanguage(file), file);
    }

    @Override
    public String debugUnavailableMessage() {
        Path file = mc.activeBuffer() == null ? null : mc.activeBuffer().getPath();
        return mc.debugCoordinator.unavailableMessage(testDebugLanguage(file), file);
    }

    /**
     * The debugger a test of {@code file} attaches with: Editora's JVM adapter for a Kotlin or Groovy test (as
     * {@link DebugAttach#attachToPort} routes it), java-debug through jdtls for any other.
     */
    static String testDebugLanguage(@Nullable Path file) {
        String jvm = file == null ? null : DebugCoordinator.jvmAdapterLanguage(file);
        return jvm != null ? jvm : "java";
    }

    @Override
    public void attachDebugger(String className, String host, int port) {
        mc.setStatus(tr("status.testrunner.debugAttaching", port));
        mc.testNavigation.resolveTestClassAnchor(
                className, a -> mc.debugCoordinator.attach.attachTestJvm(a, host, port, mc.testRunCoordinator::stop));
    }

    @Override
    public void setPendingDebugAttach(@Nullable Runnable cancel) {
        mc.debugCoordinator.setPendingAttach(cancel);
    }

    @Override
    public boolean debugSessionActive() {
        return mc.focusedDap().isActive();
    }
}
