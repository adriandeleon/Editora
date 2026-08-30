package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching a build tool back on in Settings must re-detect its marker file.
 *
 * <p>The cached detection is cleared the moment {@code refresh()} runs while the tool is off (a tab switch is
 * enough), and every apply path other than a real re-detect only re-derives the stripe from that cache — so
 * the tool stayed "no Maven project detected", with no stripe and no tasks tree, until an unrelated tab switch
 * or focus-regain happened to run one. The palette toggle command always re-detected; the Settings checkbox
 * (which goes through the shared settings-apply broadcast) did not.
 */
@Tag("fx")
class BuildToolReenableFxTest {

    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>demo</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.0.0</version>
            </project>
            """;

    private static FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Path project = Files.createTempDirectory("editora-build-reenable");
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, POM);
        fx = FxWindowFixture.create(
                Files.createTempDirectory("editora-build-reenable-cfg"),
                false,
                false,
                false,
                List.of(new MainController.OpenTarget(pom, -1, -1)),
                true,
                c -> {});
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void reEnablingMavenInSettingsRedetectsThePom() throws Exception {
        Settings settings = fx.shared.getSettings();
        assertTrue(awaitDetected(true), "the opened pom.xml should be detected to begin with");

        // Off, then the kind of refresh a tab switch does: the cached detection is dropped.
        FxTestSupport.runOnFx(() -> {
            settings.setMavenSupport(false);
            FxTestSupport.invoke(fx.controller, "refreshBuildTools");
        });
        assertFalse(awaitDetected(false), "a disabled tool reports nothing detected");

        // Back on, applied the way the Settings checkbox applies it.
        FxTestSupport.runOnFx(() -> {
            settings.setMavenSupport(true);
            fx.controller.reapplyAfterSharedSettingsChange(settings);
        });
        assertTrue(awaitDetected(true), "re-enabling in Settings must re-detect, not wait for a tab switch");
    }

    /** Polls until the coordinator's cached detection reaches {@code want}, then reports what it settled on. */
    private static boolean awaitDetected(boolean want) throws Exception {
        boolean detected = false;
        for (int i = 0; i < 100; i++) {
            detected = FxTestSupport.callOnFx(() -> {
                BuildCoordinator c = (BuildCoordinator)
                        FxTestSupport.invokeWith(fx.controller, "buildCoordinator", BuildTool.class, BuildTool.MAVEN);
                return c != null && c.isDetected();
            });
            if (detected == want) {
                return detected;
            }
            Thread.sleep(50);
        }
        return detected;
    }
}
