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
 * log (shell review C2). The editor now says it: once when a script opens, and on Format Document.
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
    void openingAScriptWithoutShellCheckSaysWhyItHasNoDiagnostics() throws Exception {
        fx.open("deploy.sh", "#!/usr/bin/env bash\necho $1\n");

        assertTrue(
                fx.host.statuses.contains(tr("status.lsp.bashNoShellcheck")),
                "told once, when the script opened: " + fx.host.statuses);
        long times = fx.host.statuses.stream()
                .filter(tr("status.lsp.bashNoShellcheck")::equals)
                .count();
        fx.open("other.sh", "#!/bin/sh\necho hi\n");
        assertEquals(
                times,
                fx.host.statuses.stream()
                        .filter(tr("status.lsp.bashNoShellcheck")::equals)
                        .count(),
                "and only once per window");
    }

    @Test
    void formatWithoutShfmtSaysShfmtIsMissingRatherThanAlreadyFormatted() throws Exception {
        fx.open("deploy.sh", "#!/usr/bin/env bash\nif true; then\necho hi\nfi\n");

        fx.run(() -> fx.coordinator.formatDocument());

        assertEquals(tr("status.lsp.bashNoShfmt"), fx.host.lastStatus());
    }
}
