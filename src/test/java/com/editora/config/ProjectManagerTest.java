package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectManagerTest {

    @Test
    void noProjectsByDefault(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);
        assertTrue(pm.list().isEmpty());
        assertNull(pm.active());
    }

    @Test
    void createReuseAndActiveRoundTrip(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);
        Path root = dir.resolve("myrepo");
        Project a = pm.createOrGet("MyRepo", root);
        // Same root returns the same project (no duplicate).
        Project again = pm.createOrGet("Whatever", root);
        assertEquals(a.id(), again.id());
        assertEquals(1, pm.list().size());

        pm.setActive(a.id());
        pm.save();

        // Reload from disk and verify the index round-trips (records (de)serialize correctly).
        ProjectManager reloaded = new ProjectManager(dir);
        assertEquals(1, reloaded.list().size());
        Project active = reloaded.active();
        assertNotNull(active);
        assertEquals("MyRepo", active.name());
        assertEquals(root.toAbsolutePath().normalize().toString(), active.root());

        // Per-project state file lives under projects/<id>.json.
        assertTrue(reloaded.stateFile(active).endsWith(Path.of("projects", active.id() + ".json")));
    }

    @Test
    void clearingActiveYieldsNone(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);
        Project p = pm.createOrGet("p", dir.resolve("p"));
        pm.setActive(p.id());
        pm.setActive("");
        assertNull(pm.active());
    }

    @Test
    void deleteRemovesProjectClearsActiveAndDropsStateFile(@TempDir Path dir) throws Exception {
        ProjectManager pm = new ProjectManager(dir);
        Project a = pm.createOrGet("A", dir.resolve("a"));
        Project b = pm.createOrGet("B", dir.resolve("b"));
        pm.setActive(a.id());

        // Simulate a saved per-project session file for A.
        Path stateA = pm.stateFile(a);
        java.nio.file.Files.createDirectories(stateA.getParent());
        java.nio.file.Files.writeString(stateA, "{}");

        assertTrue(pm.delete(a.id()));
        assertEquals(1, pm.list().size());
        assertEquals("B", pm.list().get(0).name());
        assertNull(pm.active()); // deleting the active project clears it
        assertTrue(java.nio.file.Files.notExists(stateA)); // its session file is removed

        // Survives a reload.
        pm.save();
        ProjectManager reloaded = new ProjectManager(dir);
        assertEquals(1, reloaded.list().size());
        assertNull(reloaded.active());
    }

    @Test
    void openWindowSetTracksAndPersists(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);
        Project a = pm.createOrGet("A", dir.resolve("a"));
        pm.markOpen(""); // the global window
        pm.markOpen(a.id()); // a project window
        pm.markOpen(a.id()); // idempotent
        assertEquals(List.of("", a.id()), pm.openProjectIds());
        assertTrue(pm.isOpen(a.id()));
        pm.save();

        ProjectManager reloaded = new ProjectManager(dir);
        assertEquals(List.of("", a.id()), reloaded.openProjectIds());

        reloaded.markClosed("");
        assertEquals(List.of(a.id()), reloaded.openProjectIds());
        // Deleting a project also drops it from the open set.
        reloaded.delete(a.id());
        assertFalse(reloaded.isOpen(a.id()));
        assertTrue(reloaded.openProjectIds().isEmpty());
    }

    @Test
    void setOpenWindowsReplacesTheSetDedupedAndPersists(@TempDir Path dir) {
        ProjectManager pm = new ProjectManager(dir);
        pm.markOpen("");
        pm.markOpen("old-1");
        // Reconcile to a brand-new live set: the stale "old-1" is dropped, nulls become "", dups collapse.
        pm.setOpenWindows(java.util.Arrays.asList("", "a-1", "a-1", null));
        assertEquals(List.of("", "a-1"), pm.openProjectIds());
        pm.save();

        ProjectManager reloaded = new ProjectManager(dir);
        assertEquals(List.of("", "a-1"), reloaded.openProjectIds());

        // An empty live set leaves the persisted set empty (callers skip persisting on quit instead).
        reloaded.setOpenWindows(List.of());
        assertTrue(reloaded.openProjectIds().isEmpty());
    }

    @Test
    void migratesV1ActiveProjectIntoOpenSet(@TempDir Path dir) throws Exception {
        // A pre-multi-window (v1) projects.json: a single activeProjectId, no openProjectIds.
        Files.writeString(dir.resolve("projects.json"), """
                {"schemaVersion":1,"activeProjectId":"myrepo-1a2b3c",
                 "projects":[{"id":"myrepo-1a2b3c","name":"MyRepo","root":"/tmp/myrepo"}]}""");
        ProjectManager pm = new ProjectManager(dir);
        // The active project is seeded into the open-window set so it reopens as its own window.
        assertEquals(List.of("myrepo-1a2b3c"), pm.openProjectIds());
        assertNotNull(pm.active());
        assertEquals("myrepo-1a2b3c", pm.active().id());
    }
    /**
     * The id is a name slug plus a 32-bit hash of the root, so two same-named folders whose paths collide
     * got one id — and with it one session file and one bookmark/note/breakpoint bucket.
     */
    @Test
    void twoProjectsWhoseIdsCollideGetDistinctIds(@TempDir Path dir) {
        // "Aa" and "BB" have the same String.hashCode, so these two roots hash identically.
        Path first = dir.resolve("Aa").resolve("app");
        Path second = dir.resolve("BB").resolve("app");
        assertEquals(
                first.toAbsolutePath().normalize().toString().hashCode(),
                second.toAbsolutePath().normalize().toString().hashCode(),
                "fixture: the two roots collide");

        ProjectManager pm = new ProjectManager(dir);
        Project a = pm.createOrGet("app", first);
        Project b = pm.createOrGet("app", second);

        assertFalse(a.id().equals(b.id()), "each project needs its own id");
        assertEquals(a.id() + "-2", b.id());
        assertFalse(pm.stateFile(a).equals(pm.stateFile(b)), "and so its own session file");
        assertEquals(a.id(), pm.createOrGet("app", first).id(), "an existing project keeps its id");
        assertEquals(b.id(), pm.createOrGet("app", second).id());
    }
}
