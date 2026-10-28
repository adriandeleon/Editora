package com.editora.ui;

import java.nio.file.Path;

import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without ShellCheck or shfmt, bash-language-server lints nothing and formats nothing, and says so only in its
 * log (shell review C2). The editor says it: with the install banner when a script opens (Bash review C8, see
 * {@code ShellBannersFxTest}), and on Format Document.
 */
@Tag("fx")
class LspBashToolsFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        fx.host.settings.setBashShellcheckPath(
                root.resolve("missing/shellcheck").toString());
        fx.host.settings.setBashShfmtPath(root.resolve("missing/shfmt").toString());
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("bash", true));
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    @Test
    void openingAScriptWithoutShellCheckLeavesTheEchoLineToTheBanner() throws Exception {
        fx.open("deploy.sh", "#!/usr/bin/env bash\necho $1\n");

        // The one-second echo line is gone: the editor's install banner says it and offers Install (Bash review
        // C8/A4, ShellBannersFxTest); the status line is left to the server's own start-up messages.
        assertTrue(
                fx.host.statuses.stream().noneMatch(m -> m.startsWith("ShellCheck")),
                "no ShellCheck echo line: " + fx.host.statuses);
    }

    @Test
    void formatWithoutShfmtSaysShfmtIsMissingRatherThanAlreadyFormatted() throws Exception {
        fx.open("deploy.sh", "#!/usr/bin/env bash\nif true; then\necho hi\nfi\n");

        fx.run(() -> fx.coordinator.formatDocument());

        assertEquals(tr("status.lsp.bashNoShfmt"), fx.host.lastStatus());
    }
}
