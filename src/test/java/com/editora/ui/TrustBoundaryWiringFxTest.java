package com.editora.ui;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trust-boundary helpers are only as good as their wiring in a real window: a buffer's link handler must
 * go through {@link ExternalLinks} with that buffer's own file as the context, the agent must be told which
 * directory it may never write, and enabling a plugin that cannot be disclosed must fail closed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrustBoundaryWiringFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /**
     * Opens {@code file} and returns once its text has landed. openPath only makes the tab; the file is read
     * on a worker and the open finishes on the FX thread later, where it also says "Opened …" in the status
     * bar — over whatever a test that did not wait for it had put there in the meantime.
     */
    private EditorBuffer open(Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        assertNotNull(buffer);
        assertEquals(file, FxTestSupport.callOnFx(buffer::getPath));
        awaitLoaded(buffer);
        return buffer;
    }

    /** Returns once the asynchronous open of {@code buffer} has finished on the FX thread. */
    private void awaitLoaded(EditorBuffer buffer) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        CountDownLatch loaded = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> workflows.afterBufferLoad(buffer, loaded::countDown));
        assertTrue(loaded.await(30, TimeUnit.SECONDS), "the open of " + buffer.getPath() + " never finished");
    }

    private Tab tabFor(Path file) throws Exception {
        return FxTestSupport.callOnFx(
                () -> (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, file));
    }

    private String echo() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });
    }

    private void click(EditorBuffer buffer, String link) throws Exception {
        Consumer<String> handler = FxTestSupport.field(buffer, "openUrlHandler");
        FxTestSupport.runOnFx(() -> handler.accept(link));
        FxTestSupport.drainFx();
    }

    @Test
    void aRelativeMarkdownLinkOpensTheSiblingFileInTheEditor(@TempDir Path dir) throws Exception {
        Path readme = Files.writeString(dir.resolve("README.md"), "[next](next.md)\n");
        Path next = Files.writeString(dir.resolve("next.md"), "# next\n");
        EditorBuffer buffer = open(readme);
        assertNull(tabFor(next));

        click(buffer, "next.md#intro");

        Tab opened = tabFor(next);
        assertNotNull(opened, "the relative link resolved against README.md's folder and opened as a tab");
        // The link's own open is still reading: let it finish while next.md exists, not into the next test.
        awaitLoaded((EditorBuffer) opened.getUserData());
    }

    @Test
    void aFileLinkOutsideTheDocumentFolderAndAForeignSchemeAreRefusedWithAMessage(@TempDir Path dir) throws Exception {
        Path docs = Files.createDirectories(dir.resolve("docs"));
        Path readme = Files.writeString(docs.resolve("README.md"), "x\n");
        Path outside = Files.writeString(dir.resolve("setup.command"), "#!/bin/sh\n");
        EditorBuffer buffer = open(readme);

        click(buffer, outside.toUri().toString());
        assertNull(tabFor(outside), "a file: link out of the document's folder is not opened");
        assertTrue(echo().contains("outside the project"), echo());

        click(buffer, "smb://attacker/share");
        assertTrue(echo().contains("smb"), echo());

        // The window-level opener (tool output URLs, plugin openUrl, update page) has no document: a
        // scheme-less string is refused there rather than handed to the OS to resolve against the cwd.
        Method openExternalUrl = MainController.class.getDeclaredMethod("openExternalUrl", String.class);
        openExternalUrl.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                openExternalUrl.invoke(fx.controller, "setup.command");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(echo().contains("setup.command"), echo());
    }

    @Test
    void theAgentIsToldNeverToWriteTheEditorsConfigDirectory() {
        AgentCoordinator agent = FxTestSupport.field(fx.controller, "agentCoordinator");
        assertEquals(fx.configDir, agent.writeProtectedDirectory());
    }

    @Test
    void enablingAPluginThatCannotBeDisclosedFailsClosed() throws Exception {
        PluginCoordinator plugins = FxTestSupport.field(fx.controller, "pluginCoordinator");
        Method confirm = PluginCoordinator.class.getDeclaredMethod("confirmEnablePlugin", String.class);
        confirm.setAccessible(true);
        // No descriptor ⇒ nothing to show the user ⇒ not enabled (it used to return true: enabled, undisclosed).
        boolean enabled = FxTestSupport.callOnFx(() -> (Boolean) confirm.invoke(plugins, "no-such-plugin"));
        assertFalse(enabled);
    }
}
