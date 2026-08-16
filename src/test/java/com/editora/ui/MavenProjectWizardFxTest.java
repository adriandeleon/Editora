package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.command.CommandRegistry;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenProjectExtras;
import com.editora.maven.MavenProjectSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                    Path src = projectDir.resolve("src/main/java/com/example/demo");
                    Files.createDirectories(src);
                    Files.writeString(
                            src.resolve("App.java"),
                            // conventionally formatted, as an archetype emits — MainMethodScanner only
                            // reports a main at brace depth 1
                            "package com.example.demo;\n\npublic class App\n{\n"
                                    + "    public static void main( String[] args )\n    {\n    }\n}\n");
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

            // ...and it is ready to run on the first click: a configuration named after the project,
            // pre-selected, pointing at the main class the archetype actually wrote.
            com.editora.config.Project created = list.stream()
                    .filter(p -> Path.of(p.root()).equals(projectDir))
                    .findFirst()
                    .orElseThrow();
            com.editora.config.ConfigManager seeded = new com.editora.config.ConfigManager(fx.shared, (Path)
                    FxTestSupport.call(projects, "stateFile", new Class[] {com.editora.config.Project.class}, created));
            seeded.load();
            var configs = seeded.getWorkspaceState().getRunConfigurations();
            assertEquals(1, configs.size(), "exactly one seeded configuration");
            assertEquals("demo", configs.get(0).name(), "named after the project");
            assertEquals("com.example.demo.App", configs.get(0).mainClass());
            assertEquals(projectDir.toString(), configs.get(0).workingDir());
            // jdtls names an imported Maven project by its artifactId; a blank projectName makes
            // resolveClasspath answer with an EMPTY classpath and no error.
            assertEquals("demo", configs.get(0).projectName(), "so the first launch resolves directly");
            // archetype:generate writes sources only and jdtls autobuild is off, so without a compile step
            // the very first Run resolves a correct classpath and then dies with ClassNotFoundException.
            assertEquals("mvn -q compile", configs.get(0).beforeLaunch(), "the first Run must compile first");
            assertEquals("demo", seeded.getWorkspaceState().getSelectedRunConfig(), "pre-selected");
            // The open file is not cosmetic: a Java launch resolves its classpath through an OPEN Java
            // file, so a window that opens on pom.xml alone reports "open a Java file from the project".
            var open = seeded.getWorkspaceState().getOpenFiles();
            assertEquals(1, open.size());
            assertTrue(open.get(0).getPath().endsWith("App.java"), "the class that will run is open");
            assertTrue(seeded.getWorkspaceState().getActiveFile().endsWith("App.java"), "and it is the active tab");
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
        public boolean replaceOpenBuffer(java.nio.file.Path file, String text) {
            return false; // nothing is open in this fixture, so callers fall back to writing the file
        }

        @Override
        public Path defaultParentDir() {
            return null;
        }

        @Override
        public void openProject(Path root, String name, com.editora.maven.GeneratedProject.MainClass main) {}

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

    /** A pom close enough to quickstart's for the post-generation edits to have something to work on. */
    private static final String POM = """
            <project>
              <artifactId>demo</artifactId>
              <name>demo</name>
              <url>http://www.example.com</url>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>
            </project>
            """;

    /**
     * The Advanced answers are applied to the generated pom.
     *
     * <p>They cannot be archetype properties: {@code archetype:generate} takes only the archetype
     * coordinates plus groupId/artifactId/version/package, and quickstart bakes both of these into its pom
     * template. So the wizard writes them afterwards, which is what this pins.
     */
    @Test
    void advancedValuesAreWrittenIntoTheGeneratedPom() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-extras");
        try {
            MavenProjectCoordinator c = coordinator();
            Path projectDir = parent.resolve("demo");
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                try {
                    Files.createDirectories(projectDir);
                    Files.writeString(projectDir.resolve("pom.xml"), POM);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                listener.onExit(0);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(
                    () -> c.generate(spec, new MavenProjectExtras("https://example.org/demo", "21", false)));

            String pom = Files.readString(projectDir.resolve("pom.xml"));
            assertTrue(pom.contains("<url>https://example.org/demo</url>"), pom);
            assertTrue(pom.contains("<maven.compiler.release>21</maven.compiler.release>"), pom);
        } finally {
            deleteRecursively(parent);
        }
    }

    /** An untouched Advanced section must leave the archetype's own pom byte-for-byte alone. */
    @Test
    void anUntouchedAdvancedSectionChangesNothing() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-extras-none");
        try {
            MavenProjectCoordinator c = coordinator();
            Path projectDir = parent.resolve("demo");
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                try {
                    Files.createDirectories(projectDir);
                    Files.writeString(projectDir.resolve("pom.xml"), POM);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                listener.onExit(0);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec, MavenProjectExtras.NONE));

            assertEquals(POM, Files.readString(projectDir.resolve("pom.xml")));
        } finally {
            deleteRecursively(parent);
        }
    }

    /**
     * With the update box ticked, the dependency half runs as a SECOND maven invocation in the project
     * directory — not as extra flags on archetype:generate, which would not accept them.
     */
    @Test
    void updatingVersionsRunsTheVersionsPluginInTheProject() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-versions");
        try {
            MavenProjectCoordinator c = coordinator();
            Path projectDir = parent.resolve("demo");
            List<List<String>> runs = new java.util.ArrayList<>();
            List<Path> dirs = new java.util.ArrayList<>();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                runs.add(command);
                dirs.add(dir);
                if (runs.size() == 1) {
                    try {
                        Files.createDirectories(projectDir);
                        Files.writeString(projectDir.resolve("pom.xml"), POM);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                listener.onExit(0);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec, new MavenProjectExtras("", "", true)));

            assertEquals(2, runs.size(), "the dependency update is a second maven run");
            assertTrue(
                    runs.get(1).contains("versions:use-latest-releases"),
                    runs.get(1).toString());
            assertTrue(runs.get(1).contains("-DgenerateBackupPoms=false"), "a versionsBackup pom is litter");
            assertEquals(projectDir, dirs.get(1), "it must run IN the generated project, not its parent");
        } finally {
            deleteRecursively(parent);
        }
    }

    // --- detaching from an existing project ------------------------------------------------------

    /**
     * Generating next to an unrelated jar project must not try to make the new project its module.
     *
     * <p>Reported from a real run: "Unable to add module to the current project as it is not of packaging
     * type 'pom'". archetype:generate adds a <module> to whatever project it finds in its working
     * directory, so the run has to happen somewhere that has none.
     */
    @Test
    void generatingBesideAJarProjectDetachesTheRun() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-beside-jar");
        try {
            Files.writeString(parent.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            MavenProjectCoordinator c = coordinator();
            AtomicReference<Path> cwd = new AtomicReference<>();
            AtomicReference<List<String>> argv = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                cwd.set(dir);
                argv.set(command);
                listener.onExit(1);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));

            assertNotEquals(parent, cwd.get(), "the run must not happen inside the existing project");
            assertFalse(Files.exists(cwd.get().resolve("pom.xml")), "the working dir must hold no pom");
            // Generated INSIDE the scratch dir, not into `parent`: the module check reads the pom in the
            // OUTPUT directory, so aiming outputDirectory at a folder that already holds a project fails
            // exactly as running there did. The finished project is moved into place afterwards.
            assertTrue(
                    argv.get().contains("-DoutputDirectory=" + cwd.get()),
                    "generation must happen inside the pom-less scratch dir: " + argv.get());
        } finally {
            deleteRecursively(parent);
        }
    }

    /** The finished project is moved out of the scratch directory to where the user asked for it. */
    @Test
    void aDetachedRunMovesTheProjectIntoPlace() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-move");
        try {
            Files.writeString(parent.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            MavenProjectCoordinator c = coordinator();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                try {
                    // Stand in for Maven: write the project where the argv says it will land.
                    Path generated = dir.resolve("demo");
                    Files.createDirectories(generated.resolve("src"));
                    Files.writeString(generated.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                listener.onExit(0);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));

            assertTrue(Files.isRegularFile(parent.resolve("demo/pom.xml")), "the project was not moved into place");
            assertTrue(Files.isDirectory(parent.resolve("demo/src")), "the whole tree must come across");
        } finally {
            deleteRecursively(parent);
        }
    }

    /** An aggregator is left attached — adding the module there is the wanted behaviour. */
    @Test
    void generatingInsideAnAggregatorStaysAttached() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-aggregator");
        try {
            Files.writeString(parent.resolve("pom.xml"), "<project><packaging>pom</packaging></project>");
            MavenProjectCoordinator c = coordinator();
            AtomicReference<Path> cwd = new AtomicReference<>();
            AtomicReference<List<String>> argv = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                cwd.set(dir);
                argv.set(command);
                listener.onExit(1);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));

            assertEquals(parent, cwd.get(), "an aggregator should still get its module");
            assertFalse(
                    argv.get().stream().anyMatch(a -> a.startsWith("-DoutputDirectory=")), "no detaching needed here");
        } finally {
            deleteRecursively(parent);
        }
    }

    /** An empty target directory keeps the original behaviour exactly. */
    @Test
    void anEmptyLocationRunsInPlace() throws Exception {
        Path parent = Files.createTempDirectory("editora-maven-empty");
        try {
            MavenProjectCoordinator c = coordinator();
            AtomicReference<Path> cwd = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> c.setRunnerForTest((dir, command, listener) -> {
                cwd.set(dir);
                listener.onExit(1);
            }));

            MavenProjectSpec spec = new MavenProjectSpec(
                    quickstart(), "com.example", "demo", "1.0-SNAPSHOT", "com.example.demo", parent);
            FxTestSupport.runOnFx(() -> c.generate(spec));

            assertEquals(parent, cwd.get());
        } finally {
            deleteRecursively(parent);
        }
    }
}
