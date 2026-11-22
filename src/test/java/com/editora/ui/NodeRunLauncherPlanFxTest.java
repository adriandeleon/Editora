package com.editora.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Node.js run whose planning throws says so, instead of staying "preparing" for ever. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeRunLauncherPlanFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aPlanThatThrowsStillReachesTheLaunchWithItsReason() throws Exception {
        Path file = Path.of("app.js");
        NodeRunLauncher.Spec spec =
                new NodeRunLauncher.Spec(file, null, "", "", List.of(), List.of(), false, Map.of(), null);
        AtomicReference<NodeRunLauncher.Ready> got = new AtomicReference<>();
        AtomicReference<Boolean> onFx = new AtomicReference<>();
        NodeRunLauncher.plan(
                spec,
                ready -> {
                    onFx.set(Platform.isFxApplicationThread());
                    got.set(ready);
                },
                s -> {
                    throw new IllegalStateException("the host went away");
                });
        com.editora.testkit.Await.until(() -> got.get() != null, "the launch heard nothing", Duration.ofSeconds(10));
        assertTrue(onFx.get());
        assertEquals(NodeRunLauncher.PLAN_FAILED, got.get().plan().error());
        assertTrue(got.get().plan().argv().isEmpty());
        assertNotEquals(
                NodeRunLauncher.PLAN_FAILED, Messages.tr(NodeRunLauncher.PLAN_FAILED), "the reason is in the catalogs");
    }
}
