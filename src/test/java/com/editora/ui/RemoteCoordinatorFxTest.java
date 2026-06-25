package com.editora.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the extracted {@link RemoteCoordinator}: a real window builds the coordinator + its
 * {@link RemoteConnectionsPanel}, and driving the connect flow renders the SFTP connect form as an in-scene
 * overlay without touching the network. Pins that the connect-form code (the bulk of the move, not covered by
 * the pure unit tests) runs end-to-end through the {@code CoordinatorHost}/{@code Ops} wiring.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteCoordinatorFxTest {

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

    @Test
    void connectFormRendersAsOverlay() throws Exception {
        Object remote = FxTestSupport.field(fx.controller, "remoteCoordinator");
        assertNotNull(remote, "the coordinator is built when the window is constructed");
        assertNotNull(FxTestSupport.call(remote, "panel", new Class<?>[] {}), "the Remote Sites panel exists");

        Object overlayHost = FxTestSupport.field(fx.controller, "overlayHost");
        assertFalse((boolean) FxTestSupport.call(overlayHost, "isShowing", new Class<?>[] {}), "no overlay up yet");

        // Drive the connect flow (no prefill → a fresh form); it must render the form overlay, not touch SSH.
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(remote, "connect"));
        assertTrue(
                (boolean) FxTestSupport.call(overlayHost, "isShowing", new Class<?>[] {}),
                "the SFTP connect form is shown as an in-scene overlay");

        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(overlayHost, "hide")); // clean up for later tests
    }
}
