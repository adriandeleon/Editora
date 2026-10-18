package com.editora.build;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleHostClasspathTest {

    @Test
    void aModulesTaskPathFollowsItsFolders() {
        assertEquals(":editoraClasspath", GradleHostClasspath.taskPath(""));
        assertEquals(":app:editoraClasspath", GradleHostClasspath.taskPath("app"));
        assertEquals(":libs:core:editoraClasspath", GradleHostClasspath.taskPath("libs/core/"));
    }

    @Test
    void theWrapperIsRunThroughShSoALostExecutableBitDoesNotMatter() {
        List<String> argv = GradleHostClasspath.command(true, "/c/init.gradle", "/p/build/cp.txt", ":editoraClasspath");
        assertEquals(List.of("sh", "./gradlew"), argv.subList(0, 2));
        assertTrue(argv.contains("-PeditoraClasspathOut=/p/build/cp.txt"));
        assertTrue(argv.contains("-Dorg.gradle.configuration-cache=false"));
        assertEquals("gradle", GradleHostClasspath.command(false, "i", "o", "t").get(0));
    }

    @Test
    void whatWentWrongIsTheReasonGiven() {
        assertEquals(
                "Could not resolve all files for configuration ':runtimeClasspath'.",
                GradleHostClasspath.reason(
                        "FAILURE: Build failed with an exception.\n\n* What went wrong:\n"
                                + "Could not resolve all files for configuration ':runtimeClasspath'.\n\n* Try:\n> Run with --stacktrace"));
        assertEquals("gradle: not found", GradleHostClasspath.reason("gradle: not found\n"));
    }
}
