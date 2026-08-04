package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.command.CommandRegistry;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenProjectSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring for the New Maven Project wizard, against a real window.
 *
 * <p>Generation is driven through {@code MavenProjectCoordinator}'s {@code Runner} seam, so the test asserts
 * the argv Maven <em>would</em> receive without ever forking Maven — which also means it passes on a machine
 * with no {@code mvn} installed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MavenProjectWizardFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private MavenProjectCoordinator coordinator() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "mavenProjectCoordinator"));
    }

    private static MavenArchetype quickstart() {
        return new MavenArchetype("org.apache.maven.archetypes", "maven-archetype-quickstart", "1.5", "", "", true);
    }

    @Test
    void commandsAreRegistered() throws Exception {
        CommandRegistry registry = FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "registry"));
        assertTrue(registry.get("maven.newProject").isPresent());
        assertTrue(registry.get("maven.newProjectHere").isPresent());
        assertTrue(registry.get("maven.setArchetypeCatalogUrl").isPresent());
    }

    @Test
    void generateLaunchesMavenWithTheBatchArgvInTheParentDirectory() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-wizard");
        try {
            MavenProjectCoordinator c = coordinator();
            AtomicReference<Path> cwd = new AtomicReference<>();
            AtomicReference<List<String>> argv = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                cwd.set(dir);
                argv.set(command);
                // Never actually exits 0 here: a "success" would try to register+open a project that has
                // no files on disk. The failure path is asserted separately.
                listener.onExit(1);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));

            assertEquals(parent, cwd.get(), "Maven runs in the PARENT dir; it creates <parent>/artifactId");
            assertNotNull(argv.get());
            assertEquals("archetype:generate", argv.get().get(1));
            assertTrue(argv.get().contains("-B"), "batch mode, or the run hangs on stdin forever");
            assertTrue(argv.get().contains("-DinteractiveMode=false"));
            assertTrue(argv.get().contains("-DarchetypeArtifactId=maven-archetype-quickstart"));
            assertTrue(argv.get().contains("-DartifactId=demo"));
            assertTrue(argv.get().contains("-Dpackage=com.example.demo"));
        } finally {
            deleteRecursively(parent);
        }
    }

    @Test
    void aSuccessfulRunRegistersAndOpensTheGeneratedProject() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-ok");
        try {
            MavenProjectCoordinator c = coordinator();
            Path projectDir = parent.resolve("demo");
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                try {
                    // Stand in for what archetype:generate would lay down.
                    Files.createDirectories(projectDir);
                    Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                listener.onExit(0);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));
            FxTestSupport.runOnFx(() -> {}); // drain anything queued by the open

            assertTrue(Files.isDirectory(projectDir));
            Object projects = FxTestSupport.field(fx.controller, "projects");
            @SuppressWarnings("unchecked")
            List<com.editora.config.Project> list =
                    (List<com.editora.config.Project>) FxTestSupport.call(projects, "list", new Class[] {});
            assertTrue(
                    list.stream().anyMatch(p -> Path.of(p.root()).equals(projectDir)),
                    "a successful generation registers the new folder as a project");
        } finally {
            deleteRecursively(parent);
        }
    }

    @Test
    void aNonCuratedArchetypeIsRefusedWhenConsentIsDeclined() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-consent");
        try {
            MavenProjectCoordinator c = coordinator();
            AtomicBoolean launched = new AtomicBoolean(false);
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> launched.set(true)));

            // The window's Ops shows a modal Alert, which a headless test must not hit — so this asserts the
            // gate through a coordinator wired to a declining Ops instead of the live one.
            MavenProjectCoordinator declining = new MavenProjectCoordinator(
                    new CoordinatorHostStub(),
                    new DecliningOps(),
                    FxTestSupport.field(fx.controller, "buildOutputPanel"));
            FxTestSupport.runOnFx(() -> declining.setRunnerForTest((dir, command, listener) -> launched.set(true)));

            MavenArchetype untrusted = new MavenArchetype("org.example", "sketchy", "1.0", "", "", false);
            MavenProjectSpec spec =
                    new MavenProjectSpec(untrusted, "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> declining.generate(spec));

            assertFalse(launched.get(), "declining consent must not run archetype:generate");
            assertFalse(Files.exists(parent.resolve("demo")));
        } finally {
            deleteRecursively(parent);
        }
    }

    /** Ops that always declines the archetype-consent prompt; everything else is inert. */
    private static final class DecliningOps implements MavenProjectCoordinator.Ops {
        @Override
        public Path defaultParentDir() {
            return null;
        }

        @Override
        public void openProject(Path root, String name) {}

        @Override
        public void openPath(Path file) {}

        @Override
        public com.editora.command.KeymapManager keymap() {
            return new com.editora.command.KeymapManager();
        }

        @Override
        public boolean confirmArchetype(MavenArchetype archetype) {
            return false;
        }

        @Override
        public void refreshProjectTree() {}
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort temp cleanup
                }
            });
        }
    }
}
