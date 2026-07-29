package com.editora.ui;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Running a Java configuration with no main class must be refused here, not by jdtls.
 *
 * <p>jdtls builds an Eclipse {@code SearchPattern} from the main class and {@code createPattern("")} returns
 * null, so the empty string came back as {@code Cannot invoke "SearchPattern.findIndexMatches(…)" because
 * "pattern" is null} — an internal stack trace in the echo line, with nothing to act on. Settings → Run
 * Configurations → <b>Add</b> creates exactly that shape, so it was one click away.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlankMainClassGuardFxTest {

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

    /** What Settings → Run Configurations → Add produces, verbatim. */
    private static RunConfiguration freshlyAdded() {
        return new RunConfiguration("New configuration", "run", "", "", "", "", "");
    }

    private String runAndReadStatus(RunConfiguration cfg) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            RunCoordinator run = FxTestSupport.field(fx.controller, "runCoordinator");
            FxTestSupport.call(run, "runConfig", new Class[] {RunConfiguration.class}, cfg);
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });
    }

    @Test
    void runningAConfigurationWithNoMainClassSaysSoInsteadOfCallingJdtls() throws Exception {
        String status = runAndReadStatus(freshlyAdded());

        assertTrue(status.contains("New configuration"), "names the configuration, got: " + status);
        assertTrue(status.toLowerCase().contains("main class"), "says what is missing, got: " + status);
        // The symptom this replaces. If any of it resurfaces the guard has stopped running and the empty
        // search string is reaching jdtls again.
        assertFalse(status.contains("SearchPattern"), "no jdtls internals leak through");
        assertFalse(status.contains("Cannot invoke"), "no raw NPE message leaks through");
    }

    /** The debug half takes the same path and must refuse identically, not resolve a class that is not there. */
    @Test
    void debuggingAConfigurationWithNoMainClassSaysSoToo() throws Exception {
        String status = FxTestSupport.callOnFx(() -> {
            DebugCoordinator debug = FxTestSupport.field(fx.controller, "debugCoordinator");
            FxTestSupport.call(debug, "debugConfig", new Class[] {RunConfiguration.class}, freshlyAdded());
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            javafx.scene.control.Label echo = FxTestSupport.field(bar, "echo");
            return echo.getText();
        });

        assertTrue(status.toLowerCase().contains("main class"), "says what is missing, got: " + status);
        assertFalse(status.contains("SearchPattern"), "no jdtls internals leak through");
    }
}
