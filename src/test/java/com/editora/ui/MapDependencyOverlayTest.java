package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which rows the Map's dependency overlay tints around the selection (Map code browser round 2). */
class MapDependencyOverlayTest {

    private final Path root = Path.of("/work/app").toAbsolutePath();

    private static ProjectMapImports.Edge edge(String from, String to) {
        return new ProjectMapImports.Edge(from, to, 1, "import");
    }

    /** app uses core's Repo and Service; Repo uses db; db uses Repo back; Service uses Repo. */
    private ProjectMapDependencies sample(boolean truncated) {
        return new ProjectMapDependencies(
                List.of("app/Main.java", "core/Service.java", "core/Repo.java", "db/sql/Db.java"),
                List.of(
                        edge("app/Main.java", "core/Service.java"),
                        edge("app/Main.java", "core/Repo.java"),
                        edge("core/Repo.java", "db/sql/Db.java"),
                        edge("db/sql/Db.java", "core/Repo.java"),
                        edge("core/Service.java", "core/Repo.java")),
                truncated);
    }

    @Test
    void neighboursAreEachFileOnceAndForAFolderOnlyThoseOutsideIt() {
        ProjectMapDependencies deps = sample(false);
        assertEquals(
                new ProjectMapDependencies.Neighbours(
                        Set.of("db/sql/Db.java"), Set.of("app/Main.java", "db/sql/Db.java", "core/Service.java")),
                deps.neighbours("core/Repo.java", false));
        assertEquals(
                new ProjectMapDependencies.Neighbours(
                        Set.of("db/sql/Db.java"), Set.of("app/Main.java", "db/sql/Db.java")),
                deps.neighbours("core", true));
        assertEquals(new ProjectMapDependencies.Neighbours(Set.of(), Set.of()), deps.neighbours("README.md", false));
    }

    @Test
    void aFilesRowsAndTheFoldersAboveThemAreMarkedButNotThoseHoldingTheSelection() {
        MapDependencyOverlay overlay =
                MapDependencyOverlay.of(sample(true), root, root.resolve("core/Repo.java"), false);
        assertEquals(root.resolve("core/Repo.java"), overlay.subject());
        assertTrue(overlay.read());
        assertTrue(overlay.truncated());
        assertEquals(1, overlay.uses());
        assertEquals(3, overlay.users());
        assertEquals(MapDependencyOverlay.BOTH, overlay.mark(root.resolve("db/sql/Db.java")));
        assertEquals(MapDependencyOverlay.BOTH, overlay.mark(root.resolve("db/sql")), "rolled up");
        assertEquals(MapDependencyOverlay.BOTH, overlay.mark(root.resolve("db")));
        assertEquals(MapDependencyOverlay.USED_BY, overlay.mark(root.resolve("app/Main.java")));
        assertEquals(MapDependencyOverlay.USED_BY, overlay.mark(root.resolve("app")));
        assertEquals(MapDependencyOverlay.USED_BY, overlay.mark(root.resolve("core/Service.java")));
        assertEquals(0, overlay.mark(root.resolve("core")), "it holds the selection");
        assertEquals(0, overlay.mark(root), "the root is never marked");
        assertEquals(0, overlay.mark(null));

        MapDependencyOverlay folder = MapDependencyOverlay.of(sample(false), root, root.resolve("db"), true);
        assertTrue(folder.read());
        assertFalse(folder.truncated());
        assertEquals(MapDependencyOverlay.BOTH, folder.mark(root.resolve("core/Repo.java")));
        assertEquals(MapDependencyOverlay.BOTH, folder.mark(root.resolve("core")));
        assertEquals(0, folder.mark(root.resolve("app")), "app does not use db");
    }

    @Test
    void theRootAPathOutsideItAndAFileNotReadHaveNothing() {
        ProjectMapDependencies deps = sample(false);
        assertSame(MapDependencyOverlay.NONE, MapDependencyOverlay.of(deps, root, root, true));
        assertSame(
                MapDependencyOverlay.NONE,
                MapDependencyOverlay.of(deps, root, Path.of("/elsewhere/X.java").toAbsolutePath(), false));
        assertNull(MapDependencyOverlay.NONE.subject());
        MapDependencyOverlay readme = MapDependencyOverlay.of(deps, root, root.resolve("README.md"), false);
        assertFalse(readme.read());
        assertEquals(0, readme.uses() + readme.users());
    }
}
