package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two tool-window flags are deliberately different things, and confusing them loses the user's layout:
 * {@code setVisible} is their persisted show/hide choice, while {@code setAvailable} is a transient,
 * context-driven hide (the Commit window outside a Git repo, a buffer-gated window on the Welcome tab). A
 * transient hide must never be written back as a preference, and restoring availability must not resurrect a
 * window the user had hidden.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowAvailabilityFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, ToolWindow tw, ConfigManager config) {}

    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-test");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        ToolWindowManager m = new ToolWindowManager(new BorderPane(), new Label("editor"), config, new KeymapManager());
        Region content = new Label("content");
        ToolWindow tw =
                new ToolWindow("probe", "Probe", ToolWindow.Side.RIGHT, () -> new Label("i"), content, "tool.probe");
        m.register(tw);
        return new Rig(m, tw, config);
    }

    /** The persisted preference, straight out of the workspace state — null when the user never chose. */
    private static Boolean storedPreference(Rig r) {
        return r.config().getWorkspaceState().getToolWindowVisible().get("probe");
    }

    @Test
    void aTransientUnavailableDoesNotBecomeAPersistedPreference() throws Exception {
        Rig r = FxTestSupport.callOnFx(ToolWindowAvailabilityFxTest::rig);
        assertNull(storedPreference(r), "precondition: the user has expressed no preference yet");

        FxTestSupport.runOnFx(() -> r.manager().setAvailable(r.tw(), false)); // e.g. Git went away

        assertNull(storedPreference(r), "a context-driven hide must not be written back as the user's choice");
        assertTrue(FxTestSupport.callOnFx(() -> r.manager().isVisible(r.tw())), "…nor flip the visible preference");
    }

    @Test
    void becomingAvailableAgainDoesNotResurrectAWindowTheUserHid() throws Exception {
        Rig r = FxTestSupport.callOnFx(ToolWindowAvailabilityFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            r.manager().setVisible(r.tw(), false); // the user hides it
            r.manager().setAvailable(r.tw(), false); // context hides it too
            r.manager().setAvailable(r.tw(), true); // context comes back
        });

        assertFalse(
                FxTestSupport.callOnFx(() -> r.manager().isVisible(r.tw())),
                "the user's hidden preference must survive an availability round-trip");
        assertFalse(Boolean.TRUE.equals(storedPreference(r)), "and must still be persisted as hidden");
    }

    @Test
    void theUserCanStillShowAWindowAfterAnAvailabilityRoundTrip() throws Exception {
        Rig r = FxTestSupport.callOnFx(ToolWindowAvailabilityFxTest::rig);
        FxTestSupport.runOnFx(() -> {
            r.manager().setVisible(r.tw(), false);
            r.manager().setAvailable(r.tw(), false);
            r.manager().setAvailable(r.tw(), true);
            r.manager().setVisible(r.tw(), true); // the user changes their mind
        });

        assertTrue(FxTestSupport.callOnFx(() -> r.manager().isVisible(r.tw())));
        assertTrue(Boolean.TRUE.equals(storedPreference(r)));
    }

    @Test
    void goingUnavailableClosesAnOpenWindowAndDoesNotReopenItOnItsOwn() throws Exception {
        Rig r = FxTestSupport.callOnFx(ToolWindowAvailabilityFxTest::rig);
        FxTestSupport.runOnFx(() -> r.manager().open(r.tw()));
        assertTrue(FxTestSupport.callOnFx(() -> r.manager().isOpen(r.tw())), "precondition: it is open");

        FxTestSupport.runOnFx(() -> r.manager().setAvailable(r.tw(), false));
        assertFalse(FxTestSupport.callOnFx(() -> r.manager().isOpen(r.tw())), "an unavailable window closes");

        FxTestSupport.runOnFx(() -> r.manager().setAvailable(r.tw(), true));
        assertFalse(
                FxTestSupport.callOnFx(() -> r.manager().isOpen(r.tw())),
                "availability restores the button, not the open state — it must not pop open unbidden");
        assertTrue(FxTestSupport.callOnFx(() -> r.manager().isVisible(r.tw())), "…and the preference is intact");
    }
}
