package com.editora.ui;

import java.lang.reflect.Field;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.update.ReleaseInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the update-notice wiring against a real window ({@link FxWindowFixture}): the status-bar "update"
 * segment shows/hides from the shared {@code latestKnownUpdate} + the dismissed-version setting, opening the
 * download page dismisses that version, and the three update commands are registered. The pure compare/parse
 * logic is covered separately by {@code com.editora.update.UpdateCheckTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateNoticeFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        setLatestKnownUpdate(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    @BeforeEach
    void reset() throws Exception {
        setLatestKnownUpdate(null);
        settings().setDismissedUpdateVersion("");
    }

    private static void setLatestKnownUpdate(ReleaseInfo info) throws Exception {
        Field f = MainController.class.getDeclaredField("latestKnownUpdate");
        f.setAccessible(true);
        f.set(null, info);
    }

    private Settings settings() {
        com.editora.config.ConfigManager config = FxTestSupport.field(fx.controller, "config");
        return config.getSettings();
    }

    private Label updateSegment() {
        StatusBar sb = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.field(sb, "update");
    }

    private boolean segmentVisible() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "refreshUpdateNotice"));
        return FxTestSupport.callOnFx(() -> updateSegment().isVisible());
    }

    @Test
    void noKnownUpdateHidesTheSegment() throws Exception {
        assertFalse(segmentVisible(), "no update known → segment hidden");
    }

    @Test
    void aKnownUpdateShowsTheSegmentWithTheVersion() throws Exception {
        setLatestKnownUpdate(new ReleaseInfo("9.9.9", "https://example.test/r/9.9.9", "9.9.9"));
        assertTrue(segmentVisible(), "a newer release → segment shown");
        String text = FxTestSupport.callOnFx(() -> updateSegment().getText());
        assertTrue(text.contains("9.9.9"), "segment shows the version, was: " + text);
    }

    @Test
    void aDismissedVersionStaysHidden() throws Exception {
        setLatestKnownUpdate(new ReleaseInfo("9.9.9", "https://example.test/r/9.9.9", "9.9.9"));
        settings().setDismissedUpdateVersion("9.9.9");
        assertFalse(segmentVisible(), "the dismissed version is not re-shown");
    }

    @Test
    void openingTheDownloadPageDismissesThatVersionAndHidesTheSegment() throws Exception {
        setLatestKnownUpdate(new ReleaseInfo("9.9.9", "https://example.test/r/9.9.9", "9.9.9"));
        assertTrue(segmentVisible());
        // HostServices is null in the test, so openExternalUrl is a no-op — only the dismiss/hide side runs.
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "openUpdateDownloadPage"));
        assertEquals("9.9.9", settings().getDismissedUpdateVersion(), "clicking the notice dismisses that version");
        assertFalse(FxTestSupport.callOnFx(() -> updateSegment().isVisible()), "and hides the segment");
    }

    @Test
    void theUpdateCommandsAreRegistered() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        for (String id : new String[] {"help.checkForUpdates", "update.openDownloadPage", "view.toggleUpdateCheck"}) {
            assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), "command registered: " + id);
        }
    }
}
