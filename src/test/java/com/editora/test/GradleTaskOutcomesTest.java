package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleTaskOutcomesTest {

    @Test
    void readsTheTasksGradleReused() {
        assertEquals(":app:test", GradleTaskOutcomes.reusedTask("> Task :app:test UP-TO-DATE"));
        assertEquals(":test", GradleTaskOutcomes.reusedTask("> Task :test FROM-CACHE"));
        assertNull(GradleTaskOutcomes.reusedTask("> Task :app:test"), "it ran");
        assertNull(GradleTaskOutcomes.reusedTask("> Task :app:test FAILED"));
        assertNull(GradleTaskOutcomes.reusedTask("> Task :app:processResources NO-SOURCE"));
        assertNull(GradleTaskOutcomes.reusedTask("BUILD SUCCESSFUL in 1s"));
        assertTrue(GradleTaskOutcomes.isTaskLine("> Task :app:test"));
        assertFalse(GradleTaskOutcomes.isTaskLine("BUILD SUCCESSFUL in 1s"));
    }

    @Test
    void aReportBelongsToTheTaskThatWritesItsDirectory() {
        Path root = Path.of("/p");
        Path app = root.resolve("app/build/test-results/test/TEST-a.xml");
        Path lib = root.resolve("lib/build/test-results/test/TEST-b.xml");
        Path rootReport = root.resolve("build/test-results/test/TEST-c.xml");
        Path integration = root.resolve("app/build/test-results/integrationTest/TEST-d.xml");
        assertTrue(GradleTaskOutcomes.reportBelongsTo(root, app, List.of(":app:test")));
        assertFalse(GradleTaskOutcomes.reportBelongsTo(root, lib, List.of(":app:test")));
        assertFalse(GradleTaskOutcomes.reportBelongsTo(root, app, List.of(":app:integrationTest")));
        assertTrue(GradleTaskOutcomes.reportBelongsTo(root, integration, List.of(":app:integrationTest")));
        assertTrue(GradleTaskOutcomes.reportBelongsTo(root, rootReport, List.of(":test")));
        assertFalse(GradleTaskOutcomes.reportBelongsTo(root, app, List.of(":test")));
        assertTrue(GradleTaskOutcomes.reportBelongsTo(
                root, root.resolve("a/b/build/test-results/test/TEST-e.xml"), List.of(":a:b:test")));
        assertFalse(GradleTaskOutcomes.reportBelongsTo(root, app, List.of()));
    }
}
