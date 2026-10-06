package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev-build commit line on the Welcome page. Finding the commit runs {@code git}; that used to happen
 * inline on the FX thread while the window was being built. The page is now built without it and the line
 * appears when the background lookup answers.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeDevCommitFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static WelcomePane pane(Path configDir, CompletableFuture<String> commit) throws Exception {
        return FxTestSupport.callOnFx(() -> new WelcomePane(
                new CommandRegistry(),
                new KeymapManager(),
                new RecentFiles(configDir),
                List::of,
                path -> {},
                url -> {},
                () -> true,
                () -> true,
                List::of,
                connection -> {},
                commit));
    }

    private static Label commitLabel(WelcomePane pane) {
        return FxTestSupport.field(pane, "commitLabel");
    }

    @Test
    void thePageIsBuiltWithoutWaitingAndTheCommitAppearsWhenTheLookupAnswers(@TempDir Path configDir) throws Exception {
        CompletableFuture<String> stillRunning = new CompletableFuture<>();

        WelcomePane pane = pane(configDir, stillRunning);
        Label commit = commitLabel(pane);

        assertFalse(FxTestSupport.callOnFx(commit::isVisible), "nothing to show yet");
        assertFalse(FxTestSupport.callOnFx(commit::isManaged), "and it takes no space");

        stillRunning.complete("abc1234");
        FxTestSupport.drainFx();

        assertEquals(tr("about.commit", "abc1234"), FxTestSupport.callOnFx(commit::getText));
        assertTrue(FxTestSupport.callOnFx(commit::isVisible));
        assertTrue(FxTestSupport.callOnFx(commit::isManaged));

        // The page is rebuilt whenever it is shown again; the line must come along.
        FxTestSupport.runOnFx(pane::refresh);
        assertNotNull(FxTestSupport.callOnFx(commit::getParent));
        assertEquals(tr("about.commit", "abc1234"), FxTestSupport.callOnFx(commit::getText));
    }

    @Test
    void aBlankAnswerOrNoLookupLeavesTheLineHidden(@TempDir Path configDir) throws Exception {
        WelcomePane noGit = pane(configDir, CompletableFuture.completedFuture(""));
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(commitLabel(noGit)::isVisible));

        WelcomePane production = pane(configDir, null);
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(commitLabel(production)::isVisible));
    }
}
