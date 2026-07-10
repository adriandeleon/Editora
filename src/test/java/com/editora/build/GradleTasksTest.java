package com.editora.build;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of parsing `gradle tasks --all` output + the Gradle provider's on-demand task merge. */
class GradleTasksTest {

    private static final String OUTPUT = """
            > Task :tasks

            ------------------------------------------------------------
            Tasks runnable from root project 'demo'
            ------------------------------------------------------------

            Build tasks
            -----------
            assemble - Assembles the outputs of this project.
            build - Assembles and tests this project.
            classes - Assembles main classes.

            Application tasks
            -----------------
            run - Runs this project as a JVM application.

            Help tasks
            ----------
            dependencies - Displays all dependencies declared in root project 'demo'.

            To see all tasks and more detail, run gradle tasks --all

            BUILD SUCCESSFUL in 1s
            """;

    @Test
    void parsesTaskNamesInOrderAndIgnoresHeadersAndNoise() {
        List<String> tasks = GradleTasks.parse(OUTPUT);
        assertEquals(List.of("assemble", "build", "classes", "run", "dependencies"), tasks);
        assertFalse(tasks.contains("Build"), "group headers are not tasks");
        assertFalse(tasks.stream().anyMatch(t -> t.startsWith("BUILD")), "the build result line is not a task");
    }

    @Test
    void handlesEmptyOrNullOutput() {
        assertTrue(GradleTasks.parse("").isEmpty());
        assertTrue(GradleTasks.parse(null).isEmpty());
    }

    @Test
    void providerMergesLoadedTasksDedupedAgainstTheCommonSet() {
        GradleActionsProvider p = new GradleActionsProvider();
        // Before loading: only the common tasks.
        assertFalse(loadedTaskLabels(p).contains("dependencies"));

        p.addLoadedTasks(List.of("dependencies", "build", "dependencies")); // 'build' is common; dup dropped
        List<String> loaded = loadedTaskLabels(p);
        assertTrue(loaded.contains("dependencies"));
        assertEquals(1, loaded.stream().filter(l -> l.equals("dependencies")).count());
        assertFalse(loaded.contains("build"), "a task already in the common set isn't duplicated into All tasks");
    }

    /** Labels of tasks in every section past the first (the common section is always first). */
    private static List<String> loadedTaskLabels(GradleActionsProvider p) {
        var sections = p.sections(Set.of());
        return sections.stream()
                .skip(1)
                .flatMap(s -> s.rows().stream())
                .map(r -> ((BuildAction.Task) r).label())
                .toList();
    }
}
