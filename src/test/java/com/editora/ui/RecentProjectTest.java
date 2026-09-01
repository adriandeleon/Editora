package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.Project;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentProjectTest {
    private static final Project OUTER = new Project("outer", "Editora", "/work/editora");
    private static final Project INNER = new Project("inner", "Plugin", "/work/editora/plugins/demo");

    @Test
    void findsProjectContainingRecentFile() {
        assertEquals(
                OUTER,
                RecentProject.containing(Path.of("/work/editora/src/App.java"), List.of(OUTER))
                        .orElseThrow());
    }

    @Test
    void choosesMostSpecificProjectForNestedRoots() {
        assertEquals(
                INNER,
                RecentProject.containing(Path.of("/work/editora/plugins/demo/Plugin.java"), List.of(OUTER, INNER))
                        .orElseThrow());
    }

    @Test
    void doesNotMistakeSiblingPrefixForProjectMembership() {
        assertTrue(RecentProject.containing(Path.of("/work/editora-old/README.md"), List.of(OUTER))
                .isEmpty());
    }
}
