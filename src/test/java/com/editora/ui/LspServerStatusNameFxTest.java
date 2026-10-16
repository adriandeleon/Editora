package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A server's status message names that server; every one used to read "Java LSP: …" (shell review C13). */
@Tag("fx")
class LspServerStatusNameFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("bash", true));
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    @Test
    void theBashServersProgressIsShownUnderItsOwnName() throws Exception {
        EditorBuffer b = fx.open("deploy.sh", "#!/usr/bin/env bash\necho hi\n");

        LspTestHooks.progress(fx.manager, b.getPath(), "Analyzing workspace");
        fx.settle();

        assertEquals(tr("status.lsp.server", "bash-language-server", "Analyzing workspace"), fx.host.lastStatus());
    }

    @Test
    void theJavaServerKeepsItsName() throws Exception {
        EditorBuffer b = fx.open("A.java", "class A {}\n");

        LspTestHooks.progress(fx.manager, b.getPath(), "Importing");
        fx.settle();

        assertEquals(tr("status.lsp.server", "jdtls", "Importing"), fx.host.lastStatus());
    }
}
