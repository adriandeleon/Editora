package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.editor.EditorBuffer;
import com.editora.maven.MavenArchetype;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Maven: Update Versions" writes what the user is looking at. It used to compute the new pom from the file
 * on disk and then replace the open buffer with it — so a pom with unsaved edits lost them all.
 */
@Tag("fx")
class MavenUpdateVersionsFxTest {

    private static final String POM = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.11.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final Map<String, String> UPGRADE = Map.of("org.junit.jupiter:junit-jupiter-api", "5.13.0");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class RecordingHost extends CoordinatorHostStub {
        final List<String> statuses = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            errors.add(message);
        }
    }

    /** Only the buffer lookup and "open this file" matter to a version update. */
    private static final class Ops implements MavenProjectCoordinator.Ops {
        EditorBuffer open;
        final List<Path> opened = new ArrayList<>();

        @Override
        public EditorBuffer openBuffer(Path file) {
            return open;
        }

        @Override
        public Path defaultParentDir() {
            return null;
        }

        @Override
        public void openProject(Path root, String name, com.editora.maven.GeneratedProject.MainClass main) {}

        @Override
        public void openPath(Path file) {
            opened.add(file);
        }

        @Override
        public KeymapManager keymap() {
            return null;
        }

        @Override
        public boolean confirmArchetype(MavenArchetype archetype) {
            return false;
        }

        @Override
        public void refreshProjectTree() {}
    }

    private static EditorBuffer buffer(Path file, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(content);
            return buffer;
        });
    }

    private static MavenProjectCoordinator coordinator(RecordingHost host, Ops ops) throws Exception {
        return FxTestSupport.callOnFx(() -> new MavenProjectCoordinator(host, ops, new BuildOutputPanel()));
    }

    @Test
    void anOpenDirtyPomKeepsItsUnsavedEditsAndGainsTheNewVersions(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        RecordingHost host = new RecordingHost();
        Ops ops = new Ops();
        ops.open = buffer(pom, POM);
        try {
            // An unsaved edit: the description exists in the buffer only.
            FxTestSupport.runOnFx(() -> ops.open
                    .getArea()
                    .replaceText(ops.open
                            .getContent()
                            .replace("<version>1.0.0</version>", "<version>2.0.0-UNSAVED</version>")));
            assertTrue(FxTestSupport.callOnFx(ops.open::isDirty));
            MavenProjectCoordinator c = coordinator(host, ops);

            FxTestSupport.runOnFx(() -> c.applyUpgrades(pom, UPGRADE));

            String text = FxTestSupport.callOnFx(ops.open::getContent);
            assertTrue(text.contains("<version>2.0.0-UNSAVED</version>"), "the unsaved edit survives:\n" + text);
            assertTrue(text.contains("<version>5.13.0</version>"), "and the dependency is updated");
            assertEquals(POM, Files.readString(pom), "the file is left for the user to save");
            assertTrue(ops.opened.isEmpty());
            assertEquals(List.of(tr("status.mavenVersions.updated", 1)), host.statuses);

            // One edit, so one undo takes the whole update back and leaves the user's own edit in place.
            FxTestSupport.runOnFx(() -> ops.open.getArea().undo());
            String undone = FxTestSupport.callOnFx(ops.open::getContent);
            assertTrue(undone.contains("<version>5.11.0</version>") && undone.contains("2.0.0-UNSAVED"), undone);
        } finally {
            FxTestSupport.runOnFx(ops.open::dispose);
        }
    }

    @Test
    void aReadOnlyBufferIsLeftAloneAndSaysSo(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        RecordingHost host = new RecordingHost();
        Ops ops = new Ops();
        ops.open = buffer(pom, POM);
        try {
            FxTestSupport.runOnFx(() -> ops.open.setLoading(true)); // not editable, like View mode or a huge file
            assertFalse(FxTestSupport.callOnFx(ops.open::isEditable));
            MavenProjectCoordinator c = coordinator(host, ops);

            FxTestSupport.runOnFx(() -> c.applyUpgrades(pom, UPGRADE));

            assertEquals(POM, FxTestSupport.callOnFx(ops.open::getContent));
            assertEquals(POM, Files.readString(pom));
            assertEquals(List.of(tr("status.mavenVersions.readOnly")), host.errors);
        } finally {
            FxTestSupport.runOnFx(ops.open::dispose);
        }
    }

    @Test
    void aClosedPomIsReplacedOnDiskAndOpened(@TempDir Path dir) throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), POM);
        RecordingHost host = new RecordingHost();
        Ops ops = new Ops();
        MavenProjectCoordinator c = coordinator(host, ops);

        FxTestSupport.runOnFx(() -> c.applyUpgrades(pom, UPGRADE));

        assertEquals(POM.replace("5.11.0", "5.13.0"), Files.readString(pom));
        assertEquals(List.of(pom), ops.opened);
        try (var listing = Files.list(dir)) {
            assertEquals(
                    List.of("pom.xml"),
                    listing.map(p -> p.getFileName().toString()).toList(),
                    "no temp file left");
        }
        assertTrue(host.errors.isEmpty());
    }
}
