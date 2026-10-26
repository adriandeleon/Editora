package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A project window watches the folders the project's Java style is read from, though the tree hides
 * {@code .settings} and has the rest collapsed: an outside edit to a formatter profile there reached no language
 * server (fifth review DN4).
 */
@Tag("fx")
class ProjectPanelStyleWatchFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void anOutsideEditToAProfileInSettingsIsPassedOn(@TempDir Path root) throws Exception {
        Path profile = Files.createDirectories(root.resolve(".settings")).resolve("eclipse-formatter.xml");
        Files.writeString(profile, "<profiles/>");
        List<Path> changed = new CopyOnWriteArrayList<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p = new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false);
            p.setFsChangeSink(batch -> batch.forEach(c -> changed.add(c.path())));
            p.setRoot(root);
            return p;
        });
        try {
            Thread.sleep(300);
            Files.writeString(profile, "<profiles version=\"2\"/>"); // by git, a teammate's script, another editor
            long end = System.nanoTime() + 15_000_000_000L;
            while (!changed.contains(profile) && System.nanoTime() < end) {
                Thread.sleep(50);
            }
            assertTrue(changed.contains(profile), changed.toString());
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }
}
