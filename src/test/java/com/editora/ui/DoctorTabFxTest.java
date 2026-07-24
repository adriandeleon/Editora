package com.editora.ui;

import java.util.List;

import javafx.scene.control.TabPane;

import com.editora.command.CommandRegistry;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: {@code view.doctor} in a real window opens a single Doctor tab (the {@code welcomeTab}
 * pattern) whose rows resolve — with all probes stubbed through the coordinator's test seam so no
 * subprocess ever spawns in CI.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorTabFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void doctorCommandOpensASingleTabWithResolvedRows() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            DoctorCoordinator doctor = FxTestSupport.field(fx.controller, "doctorCoordinator");
            doctor.probeOverrideForTest = spec -> spec.placeholder().ok("stubbed");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");

            FxTestSupport.runOnFx(() -> registry.run("view.doctor"));
            int tabsAfterOpen = FxTestSupport.callOnFx(() -> tabPane.getTabs().size());
            Object selected = FxTestSupport.callOnFx(
                    () -> tabPane.getSelectionModel().getSelectedItem().getUserData());
            assertTrue(selected instanceof DoctorPane, "the Doctor tab should be selected");

            // Re-running the command re-selects the existing tab instead of opening a second one.
            FxTestSupport.runOnFx(() -> registry.run("view.doctor"));
            assertEquals(tabsAfterOpen, (int)
                    FxTestSupport.callOnFx(() -> tabPane.getTabs().size()));

            DoctorPane pane = (DoctorPane) selected;
            List<DoctorCheck> rows = FxTestSupport.callOnFx(pane::currentChecks);
            assertFalse(rows.isEmpty());
            // Every probing row resolved through the stub; feature-off rows are terminal DISABLED grays.
            assertTrue(rows.stream().noneMatch(r -> r.status() == DoctorStatus.CHECKING));
            assertTrue(rows.stream().anyMatch(r -> r.id().equals("git")));
        } finally {
            fx.dispose();
        }
    }
}
