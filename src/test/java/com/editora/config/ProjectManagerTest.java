package com.editora.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
