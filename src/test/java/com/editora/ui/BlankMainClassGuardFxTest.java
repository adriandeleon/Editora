package com.editora.ui;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Configurations the guard took us to, in order. */
    private final java.util.List<String> openedInEditor = new java.util.ArrayList<>();

    /**
     * Substitutes where the Run Configurations page opens.
     *
     * <p>Opening the real Settings window builds every page: driving it from a test passes alone and times
     * out under the full suite (measured, twice). This keeps the assertion on <em>which</em> configuration we
     * are taken to, which is the part that matters.
     */
    @org.junit.jupiter.api.BeforeEach
    void captureEditorOpens() throws Exception {
        openedInEditor.clear();
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller,
                "setRunConfigEditorForTest",
                new Class[] {java.util.function.Consumer.class},
                (java.util.function.Consumer<String>) openedInEditor::add));
    }

    @org.junit.jupiter.api.AfterEach
    void restoreEditor() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller,
                "setRunConfigEditorForTest",
                new Class[] {java.util.function.Consumer.class},
                (java.util.function.Consumer<String>) null));
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
        // Naming the problem is half the action; the other half is landing on the field that fixes it.
        assertEquals(
                java.util.List.of("New configuration"), openedInEditor, "and takes us to that configuration's form");
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
        assertEquals(
                java.util.List.of("New configuration"), openedInEditor, "and takes us to that configuration's form");
    }

    /** A script configuration missing its script gets the same treatment — the guard that already existed. */
    @Test
    void aScriptConfigurationWithNoScriptAlsoOpensItsForm() throws Exception {
        RunConfiguration script = new RunConfiguration("Deploy", "run", "shell", "", "", "", "", "", "", "", "");
        String status = runAndReadStatus(script);

        assertTrue(status.contains("Deploy"), "names the configuration, got: " + status);
        assertEquals(java.util.List.of("Deploy"), openedInEditor);
    }
}
