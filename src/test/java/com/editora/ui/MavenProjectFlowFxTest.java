package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.io.LoopbackDownloads;
import com.editora.maven.GeneratedProject;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenProjectExtras;
import com.editora.maven.MavenProjectSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The New Maven Project wizard and Update Versions as a user goes through them: the archetype picker, the
 * coordinates form, the optional catalog download, the post-generation steps, and the version preview. Maven
 * is never run (the launcher seam records the command line) and Maven Central is a loopback server.
 */
@Tag("fx")
class MavenProjectFlowFxTest {

    private static final long WAIT_SECONDS = 30;

    private static final String CATALOG_URL = "https://repo.example.test/archetype-catalog.xml";

    private static final String CATALOG = """
            <archetype-catalog><archetypes>
              <archetype>
                <groupId>org.example.archetypes</groupId>
                <artifactId>fetched-service</artifactId>
                <version>2.1</version>
                <description>A service skeleton from the full catalog</description>
              </archetype>
              <archetype>
                <groupId>org.example.archetypes</groupId>
                <artifactId>fetched-plain</artifactId>
                <version>1.0</version>
              </archetype>
            </archetypes></archetype-catalog>
            """;

    private static final String PINNED_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>5.10.0</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                  </plugin>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String metadata(String... versions) {
        StringBuilder sb = new StringBuilder("<metadata><versioning><versions>");
        for (String v : versions) {
            sb.append("<version>").append(v).append("</version>");
        }
        return sb.append("</versions></versioning></metadata>").toString();
    }

    // ---- Update Versions --------------------------------------------------------------------------------

    @Test
    void updateVersionsShowsWhatWouldChangeAndWritesOnlyWhenAccepted(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir);
                LoopbackDownloads central = new LoopbackDownloads()) {
            central.serve(
                            CENTRAL + "org/junit/jupiter/junit-jupiter-api/maven-metadata.xml",
                            metadata("5.10.0", "5.13.0", "6.0.0-M1"))
                    .serve(
                            CENTRAL + "org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml",
                            metadata("3.11.0", "3.14.0"))
                    // Already the newest: no row for it.
                    .serve(
                            CENTRAL + "org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml",
                            metadata("3.2.5"));
            rig.coordinator.setHttpClientsForTest(() -> central.client().get());
            Path pom = Files.writeString(dir.resolve("pom.xml"), PINNED_POM);
            rig.ops.parent = Files.createDirectories(dir.resolve("src/main/java")); // the pom is found above it

            FxTestSupport.runOnFx(rig.coordinator::updateVersions);
            assertEquals(tr("status.mavenVersions.checking", 3), rig.host.awaitStatus());
            Node card = rig.awaitCard();

            assertEquals(
                    List.of(
                            "org.junit.jupiter:junit-jupiter-api    5.10.0  →  5.13.0",
                            "org.apache.maven.plugins:maven-compiler-plugin    3.11.0  →  3.14.0"),
                    rig.texts(card, ".maven-version-row"));
            assertTrue(rig.allText(card).contains(tr("dialog.mavenVersions.summary", 2)), rig.allText(card));
            assertEquals(PINNED_POM, Files.readString(pom), "nothing is written before the preview is accepted");

            rig.press(card, tr("dialog.mavenVersions.apply"));
            String updated = Files.readString(pom);
            assertTrue(updated.contains("<version>5.13.0</version>"), updated);
            assertTrue(updated.contains("<version>3.14.0</version>"), updated);
            assertTrue(updated.contains("<version>3.2.5</version>"), updated);
            assertFalse(updated.contains("6.0.0-M1"), "a milestone is not an upgrade");
            assertEquals(tr("status.mavenVersions.updated", 2), rig.host.lastStatus());
            assertEquals(List.of(pom), rig.ops.openedPaths, "the changed pom is shown");
            assertEquals(3, central.requests().size());
        }
    }

    @Test
    void updateVersionsSaysWhenThereIsNothingNewerOrCentralDoesNotAnswer(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir);
                LoopbackDownloads central = new LoopbackDownloads()) {
            central.serve(
                            CENTRAL + "org/junit/jupiter/junit-jupiter-api/maven-metadata.xml",
                            metadata("5.9.0", "5.10.0"))
                    .status(CENTRAL + "org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml", 404)
                    .cutOff(
                            CENTRAL + "org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml",
                            "<metadata><versioning><versions><version>9".getBytes(),
                            4096);
            rig.coordinator.setHttpClientsForTest(() -> central.client().get());
            Path pom = Files.writeString(dir.resolve("pom.xml"), PINNED_POM);
            rig.ops.parent = dir;

            FxTestSupport.runOnFx(rig.coordinator::updateVersions);
            assertEquals(tr("status.mavenVersions.checking", 3), rig.host.awaitStatus());
            assertEquals(tr("status.mavenVersions.upToDate"), rig.host.awaitStatus());
            assertNull(rig.card());
            assertEquals(PINNED_POM, Files.readString(pom));
        }
    }

    @Test
    void updateVersionsNeedsMavenSupportAPomAndSomethingPinned(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            rig.ops.parent = dir;
            FxTestSupport.runOnFx(rig.coordinator::updateVersions);
            assertEquals(tr("status.mavenVersions.noPom"), rig.host.lastError());

            Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>bare</artifactId></project>");
            FxTestSupport.runOnFx(rig.coordinator::updateVersions);
            assertEquals(tr("status.mavenVersions.nothingPinned"), rig.host.lastStatus());

            rig.host.settings.setMavenSupport(false);
            FxTestSupport.runOnFx(rig.coordinator::updateVersions);
            assertEquals(tr("status.mavenProject.disabled"), rig.host.lastError());
        }
    }

    @Test
    void acceptedUpgradesThatNoLongerApplyChangeNothing(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            Path pom = dir.resolve("pom.xml");
            // The pom is gone by the time the preview is accepted.
            FxTestSupport.runOnFx(() -> rig.coordinator.applyUpgrades(pom, Map.of("a:b", "2.0")));
            assertEquals(tr("status.mavenVersions.noPom"), rig.host.lastError());

            // Nothing in the pom matches the upgrade set any more.
            Files.writeString(pom, PINNED_POM);
            FxTestSupport.runOnFx(() -> rig.coordinator.applyUpgrades(pom, Map.of("a:b", "2.0")));
            assertEquals(tr("status.mavenVersions.upToDate"), rig.host.lastStatus());
            assertEquals(PINNED_POM, Files.readString(pom));
            assertEquals(List.of(), rig.ops.openedPaths);
        }
    }

    // ---- the wizard: preconditions ----------------------------------------------------------------------

    @Test
    void theWizardDoesNotOpenWithoutMavenSupportOrAMavenToRun(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            rig.host.settings.setMavenCommand(dir.resolve("no-such-mvn").toString());
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            assertEquals(tr("status.mavenProject.noMaven"), rig.host.lastError());
            assertNull(rig.card());

            rig.host.settings.setMavenSupport(false);
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            assertEquals(tr("status.mavenProject.disabled"), rig.host.lastError());
            assertNull(rig.card());
        }
    }

    @Test
    void thePaletteCommandAsksForTheFolderFirst(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            rig.ops.parent = dir;
            CommandRegistry registry = new CommandRegistry();
            FxTestSupport.runOnFx(() -> {
                rig.coordinator.registerCommands(registry);
                registry.run("maven.newProjectHere");
            });
            Prompt prompt = rig.host.prompts.get(0);
            assertEquals(tr("dialog.mavenProject.locationTitle"), prompt.title());
            assertEquals(dir.toString(), prompt.initial(), "starts at the folder the window is in");

            // Nothing typed: no wizard.
            FxTestSupport.runOnFx(() -> {
                prompt.onAccept().accept("   ");
                prompt.onAccept().accept(null);
            });
            assertNull(rig.card());

            Path chosen = dir.resolve("workspace");
            FxTestSupport.runOnFx(() -> prompt.onAccept().accept("  " + chosen + "  "));
            rig.chooseArchetype(a -> a.artifactId().equals("maven-archetype-quickstart"));
            assertEquals(chosen.toString(), rig.fieldText(1), "the form starts in the folder that was typed");

            // With no folder to start from the prompt is empty, and the wizard falls back to the home folder.
            rig.ops.parent = null;
            FxTestSupport.runOnFx(() -> registry.run("maven.newProjectHere"));
            assertEquals("", rig.host.prompts.get(1).initial());
            FxTestSupport.runOnFx(() -> registry.run("maven.newProject"));
            rig.chooseArchetype(a -> a.artifactId().equals("maven-archetype-quickstart"));
            assertEquals(System.getProperty("user.home"), rig.fieldText(1));
        }
    }

    // ---- the wizard: picker and form --------------------------------------------------------------------

    @Test
    void theArchetypePickerListsTheCuratedOnesThenCustomAndLoadCatalog(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            List<MavenArchetype> rows = rig.pickerItems();
            List<MavenArchetype> curated = com.editora.maven.ArchetypeCatalog.curated();
            assertFalse(curated.isEmpty());
            assertEquals(curated, rows.subList(0, curated.size()));
            assertEquals(curated.size() + 2, rows.size());

            List<String> shown = rig.pickerRowTexts();
            MavenArchetype first = curated.get(0);
            assertTrue(shown.get(0).startsWith(first.artifactId()), shown.get(0));
            assertTrue(
                    shown.get(0).contains(first.description().isEmpty() ? first.groupId() : first.description()),
                    shown.get(0));
            // The two action rows are named, and carry no detail line.
            assertTrue(shown.contains(tr("dialog.mavenProject.customRow")), shown.toString());
            assertTrue(shown.contains(tr("dialog.mavenProject.loadCatalogRow")), shown.toString());
        }
    }

    @Test
    void theFormDerivesThePackageValidatesAndGeneratesWithTheAdvancedAnswers(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            List<List<String>> runs = new CopyOnWriteArrayList<>();
            Path projectDir = dir.resolve("demo-app");
            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((workingDir, argv, listener) -> {
                runs.add(argv);
                listener.onStart(String.join(" ", argv));
                listener.onOutput("[INFO] Generating project in Batch mode", false);
                listener.onOutput("[WARNING] something on stderr", true);
                write(projectDir.resolve("pom.xml"), """
                        <project>
                          <artifactId>demo-app</artifactId>
                          <url>http://www.example.com</url>
                          <properties>
                            <maven.compiler.release>17</maven.compiler.release>
                          </properties>
                        </project>
                        """);
                write(
                        projectDir.resolve("src/main/java/org/acme/demo_app/App.java"),
                        "package org.acme.demo_app;\npublic class App {\n"
                                + "    public static void main( String[] args )\n    {\n    }\n}\n");
                listener.onExit(0);
            }));
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            rig.chooseArchetype(a -> a.artifactId().equals("maven-archetype-quickstart"));
            Node card = rig.card();
            assertNotNull(card);
            Button create = rig.button(card, tr("dialog.mavenProject.create"));
            TextField name = rig.formField(0);
            TextField location = rig.formField(1);
            TextField group = rig.formField(2);
            TextField version = rig.formField(3);
            TextField pkg = rig.formField(4);

            assertEquals(dir.toString(), FxTestSupport.callOnFx(location::getText));
            assertEquals("com.example", FxTestSupport.callOnFx(group::getText));
            assertEquals("1.0-SNAPSHOT", FxTestSupport.callOnFx(version::getText));
            assertTrue(FxTestSupport.callOnFx(create::isDisabled), "no name yet");

            // The package follows the name and the group …
            FxTestSupport.runOnFx(() -> name.setText("demo-app"));
            assertEquals("com.example.demo_app", FxTestSupport.callOnFx(pkg::getText));
            assertEquals(tr("dialog.mavenProject.willCreate", projectDir), rig.hint(card));
            assertFalse(FxTestSupport.callOnFx(create::isDisabled));
            FxTestSupport.runOnFx(() -> group.setText("org.acme"));
            assertEquals("org.acme.demo_app", FxTestSupport.callOnFx(pkg::getText));

            // … until it is edited by hand; then it is the user's.
            FxTestSupport.runOnFx(() -> pkg.setText("org.acme.custom"));
            FxTestSupport.runOnFx(() -> group.setText("org.acme.tools"));
            assertEquals("org.acme.custom", FxTestSupport.callOnFx(pkg::getText));
            FxTestSupport.runOnFx(() -> {
                group.setText("org.acme");
                pkg.setText("org.acme.demo_app");
            });

            // A bad version, an empty location or an existing folder each block Create.
            FxTestSupport.runOnFx(() -> version.setText("1/0"));
            assertTrue(FxTestSupport.callOnFx(create::isDisabled));
            FxTestSupport.runOnFx(() -> version.setText("0.1.0"));
            FxTestSupport.runOnFx(() -> location.setText("  "));
            assertTrue(FxTestSupport.callOnFx(create::isDisabled));
            assertEquals("", rig.hint(card));
            Files.createDirectories(dir.resolve("taken").resolve("demo-app"));
            FxTestSupport.runOnFx(() -> location.setText(dir.resolve("taken").toString()));
            assertTrue(FxTestSupport.callOnFx(create::isDisabled));
            assertEquals(tr("dialog.mavenProject.exists", dir.resolve("taken").resolve("demo-app")), rig.hint(card));
            FxTestSupport.runOnFx(create::fire);
            assertEquals(List.of(), runs, "a disabled Create does nothing");
            FxTestSupport.runOnFx(() -> location.setText(dir.toString()));
            assertFalse(FxTestSupport.callOnFx(create::isDisabled));

            // Advanced: the installed JDKs are offered as release levels once the section is opened.
            TitledPane advanced = (TitledPane) FxTestSupport.callOnFx(() -> card.lookup(".dialog-advanced"));
            assertFalse(FxTestSupport.callOnFx(advanced::isExpanded));
            @SuppressWarnings("unchecked")
            ComboBox<String> release = (ComboBox<String>)
                    FxTestSupport.callOnFx(() -> advanced.getContent().lookup(".combo-box"));
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> List.copyOf(release.getItems())));
            FxTestSupport.runOnFx(() -> advanced.setExpanded(true));
            List<String> majors = FxTestSupport.callOnFx(() -> List.copyOf(release.getItems()));
            assertTrue(majors.contains(String.valueOf(Runtime.version().feature())), majors.toString());
            List<Integer> asNumbers = majors.stream().map(Integer::valueOf).toList();
            assertEquals(
                    asNumbers.stream()
                            .sorted(java.util.Comparator.reverseOrder())
                            .toList(),
                    asNumbers);
            FxTestSupport.runOnFx(() -> {
                advanced.setExpanded(false);
                advanced.setExpanded(true); // opened again: not filled twice
            });
            assertEquals(majors, FxTestSupport.callOnFx(() -> List.copyOf(release.getItems())));
            TextField url = (TextField)
                    FxTestSupport.callOnFx(() -> advanced.getContent().lookup(".text-field"));
            FxTestSupport.runOnFx(() -> {
                url.setText("https://acme.example/demo");
                release.getEditor().setText("21");
            });

            FxTestSupport.runOnFx(create::fire);

            assertNull(rig.card(), "the form closes on Create");
            assertEquals(1, runs.size());
            List<String> argv = runs.get(0);
            assertEquals(rig.mvn, argv.get(0));
            assertTrue(argv.contains("archetype:generate"), argv.toString());
            assertTrue(argv.contains("-DgroupId=org.acme"), argv.toString());
            assertTrue(argv.contains("-DartifactId=demo-app"), argv.toString());
            assertTrue(argv.contains("-Dversion=0.1.0"), argv.toString());
            assertTrue(argv.contains("-Dpackage=org.acme.demo_app"), argv.toString());

            String pom = Files.readString(projectDir.resolve("pom.xml"));
            assertTrue(pom.contains("<url>https://acme.example/demo</url>"), pom);
            assertTrue(pom.contains("<maven.compiler.release>21</maven.compiler.release>"), pom);
            assertEquals(tr("status.mavenProject.created", "demo-app"), rig.host.lastStatus());
            assertEquals(1, rig.ops.treeRefreshes.get());
            Opened opened = rig.ops.projects.get(0);
            assertEquals(projectDir, opened.root());
            assertEquals("demo-app", opened.name());
            assertEquals("org.acme.demo_app.App", opened.main().fqn());
            assertEquals(List.of(projectDir.resolve("pom.xml")), rig.ops.openedPaths);

            String console = rig.console();
            assertTrue(console.contains("[INFO] Generating project in Batch mode"), console);
            assertTrue(console.contains("[WARNING] something on stderr"), console);
            assertEquals(tr("run.exited", 0), rig.consoleStatus());
        }
    }

    @Test
    void aCustomArchetypeIsTypedAsCoordinatesAndNeedsConsent(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            List<List<String>> runs = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(
                    () -> rig.coordinator.setRunnerForTest((workingDir, argv, listener) -> runs.add(argv)));
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            int customRow = rig.pickerItems().size() - 2;
            rig.choose(customRow);
            Prompt prompt = rig.host.prompts.get(0);
            assertEquals(tr("dialog.mavenProject.customTitle"), prompt.title());
            assertEquals(tr("dialog.mavenProject.customLabel"), prompt.label());

            FxTestSupport.runOnFx(() -> prompt.onAccept().accept("just-a-name"));
            assertEquals(tr("status.mavenProject.badGav"), rig.host.lastError());
            assertNull(rig.card());

            FxTestSupport.runOnFx(() -> prompt.onAccept().accept(" org.example:sketchy-archetype:1.2 "));
            Node card = rig.card();
            assertNotNull(card);
            assertTrue(rig.allText(card).contains("org.example:sketchy-archetype:1.2"), rig.allText(card));
            TextField customName = rig.formField(0);
            FxTestSupport.runOnFx(() -> customName.setText("demo"));

            // Third-party code: declined, nothing runs.
            rig.ops.consent = false;
            rig.press(card, tr("dialog.mavenProject.create"));
            assertEquals(tr("status.mavenProject.cancelled"), rig.host.lastStatus());
            assertEquals(List.of(), runs);
            assertEquals(
                    "org.example:sketchy-archetype:1.2", rig.ops.asked.get(0).gav());
            assertFalse(Files.exists(dir.resolve("demo")));
        }
    }

    // ---- the wizard: the full catalog -------------------------------------------------------------------

    @Test
    void theFullCatalogIsFetchedOnRequestAndItsArchetypesJoinTheList(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir);
                LoopbackDownloads remote = new LoopbackDownloads()) {
            remote.serve(CATALOG_URL, CATALOG);
            rig.coordinator.setHttpClientsForTest(() -> remote.client().get());
            rig.host.settings.setMavenArchetypeCatalogUrl(CATALOG_URL);
            int curated = com.editora.maven.ArchetypeCatalog.curated().size();

            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            rig.choose(rig.pickerItems().size() - 1); // Load full catalog…
            assertEquals(tr("status.mavenProject.loadingCatalog"), rig.host.awaitStatus());
            assertEquals(tr("status.mavenProject.catalogLoaded", 2), rig.host.awaitStatus());
            FxTestSupport.drainFx();

            List<MavenArchetype> rows = rig.pickerItems();
            assertEquals(curated + 2 + 1, rows.size(), "two fetched archetypes and Custom; no second Load row");
            MavenArchetype fetched = rows.stream()
                    .filter(a -> a.artifactId().equals("fetched-service"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(fetched.curated(), "a fetched archetype is not one we vetted");
            assertEquals(List.of(1, 1), List.of(rig.host.tasksStarted.get(), rig.host.tasksClosed.get()));
            List<String> shown = rig.pickerRowTexts();
            assertTrue(
                    shown.stream().anyMatch(s -> s.contains("A service skeleton from the full catalog")),
                    shown.toString());
            assertTrue(
                    shown.stream().anyMatch(s -> s.startsWith("fetched-plain") && s.contains("org.example.archetypes")),
                    "without a description the group is shown: " + shown);

            // The fetched list is kept for the session: opening the wizard again does not download it again.
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            assertEquals(curated + 3, rig.pickerItems().size());
            assertEquals(1, remote.requests().size());
        }
    }

    @Test
    void aCatalogThatCannotBeLoadedIsReportedAndTheCuratedListStaysUsable(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir);
                LoopbackDownloads remote = new LoopbackDownloads()) {
            remote.status(CATALOG_URL, 503);
            rig.coordinator.setHttpClientsForTest(() -> remote.client().get());
            int curated = com.editora.maven.ArchetypeCatalog.curated().size();

            // Not https: refused before anything is requested.
            rig.host.settings.setMavenArchetypeCatalogUrl("http://repo.example.test/archetype-catalog.xml");
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            rig.choose(rig.pickerItems().size() - 1);
            assertEquals(tr("status.mavenProject.catalogNotHttps"), rig.host.lastError());
            assertEquals(List.of(), remote.requests());

            rig.host.errors.clear();
            rig.host.settings.setMavenArchetypeCatalogUrl(CATALOG_URL);
            FxTestSupport.runOnFx(() -> rig.coordinator.newProject(dir));
            rig.choose(rig.pickerItems().size() - 1);
            assertEquals(tr("status.mavenProject.catalogFailed", "HTTP 503"), rig.host.awaitError());
            FxTestSupport.drainFx();
            assertEquals(curated + 2, rig.pickerItems().size(), "the picker is back, with the Load row still offered");
            assertEquals(1, rig.host.tasksClosed.get(), "the background-task indicator is closed on failure too");
        }
    }

    // ---- generation outcomes ----------------------------------------------------------------------------

    @Test
    void aSpecThatIsNotValidIsRefusedByNameOfTheField(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            MavenProjectSpec spec = new MavenProjectSpec(rig.quickstart(), "com.example", "", "1.0", "p", dir);
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertEquals(tr("status.mavenProject.invalid", "artifactId"), rig.host.lastError());
        }
    }

    @Test
    void aTargetFolderThatCannotBeMadeStopsBeforeMavenRuns(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            AtomicInteger launches = new AtomicInteger();
            FxTestSupport.runOnFx(
                    () -> rig.coordinator.setRunnerForTest((w, argv, listener) -> launches.incrementAndGet()));
            Path file = Files.writeString(dir.resolve("a-file"), "x");
            MavenProjectSpec spec = new MavenProjectSpec(
                    rig.quickstart(), "com.example", "demo", "1.0", "com.example.demo", file.resolve("below"));
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertTrue(
                    rig.host
                            .lastError()
                            .startsWith(
                                    tr("status.mavenProject.mkdirFailed", "").strip()),
                    rig.host.lastError());
            assertEquals(0, launches.get());
        }
    }

    @Test
    void aFailedOrUnstartableRunIsReportedAndOpensNothing(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            MavenProjectSpec spec =
                    new MavenProjectSpec(rig.quickstart(), "com.example", "demo", "1.0", "com.example.demo", dir);

            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((w, argv, listener) -> listener.onExit(1)));
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertEquals(tr("status.mavenProject.failed", "demo"), rig.host.awaitError());
            assertEquals(tr("run.exited", 1), rig.consoleStatus());

            // Maven said 0 but left no project behind.
            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((w, argv, listener) -> listener.onExit(0)));
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertEquals(tr("status.mavenProject.failed", "demo"), rig.host.awaitError());

            FxTestSupport.runOnFx(
                    () -> rig.coordinator.setRunnerForTest((w, argv, listener) -> listener.onError("cannot run mvn")));
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertEquals(tr("status.mavenProject.failed", "demo"), rig.host.awaitError());
            assertTrue(rig.host.errors.isEmpty(), "one message per failed run");
            assertEquals(tr("run.failed", "cannot run mvn"), rig.consoleStatus());
            assertEquals(List.of(), rig.ops.projects);
        }
    }

    @Test
    void aDetachedRunThatProducedNothingIsAFailureAndItsScratchFolderIsRemoved(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            // An unrelated jar project in the target folder: the run is detached into a scratch directory.
            Files.writeString(dir.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
            List<Path> workingDirs = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((w, argv, listener) -> {
                workingDirs.add(w);
                listener.onExit(0);
            }));
            MavenProjectSpec spec =
                    new MavenProjectSpec(rig.quickstart(), "com.example", "demo", "1.0", "com.example.demo", dir);
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec));
            assertEquals(tr("status.mavenProject.failed", "demo"), rig.host.lastError());
            assertFalse(workingDirs.get(0).startsWith(dir), "not run beside the other project");
            assertFalse(Files.exists(dir.resolve("demo")));
        }
    }

    @Test
    void updatingVersionsAfterGenerationRewritesThePluginsAndCarriesOnWhenAStepFails(@TempDir Path dir)
            throws Exception {
        try (Rig rig = new Rig(dir);
                LoopbackDownloads central = new LoopbackDownloads()) {
            central.serve(
                            CENTRAL + "org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml",
                            metadata("3.11.0", "3.14.0"))
                    .status(CENTRAL + "org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml", 500);
            rig.coordinator.setHttpClientsForTest(() -> central.client().get());
            Path projectDir = dir.resolve("demo");
            List<List<String>> runs = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((w, argv, listener) -> {
                runs.add(argv);
                if (runs.size() == 1) {
                    write(projectDir.resolve("pom.xml"), PINNED_POM);
                    listener.onExit(0);
                } else {
                    // The dependency half cannot be started; the plugin half still runs.
                    listener.onStart("versions");
                    listener.onOutput("[INFO] checking", false);
                    listener.onError("versions plugin missing");
                }
            }));
            MavenProjectSpec spec =
                    new MavenProjectSpec(rig.quickstart(), "com.example", "demo", "1.0", "com.example.demo", dir);
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec, new MavenProjectExtras("", "", true)));

            String updated = tr("status.mavenProject.versionsUpdated", 1);
            assertEquals(updated, rig.host.awaitStatus(updated::equals));
            FxTestSupport.drainFx();
            String pom = Files.readString(projectDir.resolve("pom.xml"));
            assertTrue(pom.contains("<version>3.14.0</version>"), pom);
            assertTrue(pom.contains("<version>3.2.5</version>"), "no answer for surefire: left as generated");
            assertTrue(pom.contains("<version>5.10.0</version>"), "dependencies are the versions plugin's job");
            String console = rig.console();
            assertTrue(console.contains("versions:use-latest-releases"), console);
            assertTrue(console.contains("versions plugin missing"), console);
            assertTrue(
                    console.contains(tr(
                            "status.mavenProject.pluginUpdated",
                            "org.apache.maven.plugins:maven-compiler-plugin",
                            "3.14.0")),
                    console);
            assertEquals(1, rig.ops.projects.size(), "the project opens once every step is done");
            assertEquals(tr("status.mavenProject.created", "demo"), rig.host.lastStatus());
        }
    }

    @Test
    void aGeneratedPomWithoutPinnedPluginsSkipsTheLookup(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            Path projectDir = dir.resolve("demo");
            FxTestSupport.runOnFx(() -> rig.coordinator.setRunnerForTest((w, argv, listener) -> {
                write(projectDir.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
                listener.onExit(0);
            }));
            MavenProjectSpec spec =
                    new MavenProjectSpec(rig.quickstart(), "com.example", "demo", "1.0", "com.example.demo", dir);
            FxTestSupport.runOnFx(() -> rig.coordinator.generate(spec, new MavenProjectExtras("", "", true)));
            assertTrue(
                    rig.host.statusLog.contains(tr("status.mavenProject.versionsUpdated", 0)),
                    rig.host.statusLog.toString());
            assertEquals(1, rig.ops.projects.size());
            assertNull(rig.ops.projects.get(0).main(), "no main class in what was generated");
        }
    }

    // ---- the Maven menu ---------------------------------------------------------------------------------

    @Test
    void theMavenMenuRunsTheCommandsItNames(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(dir)) {
            Path pom = Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>bare</artifactId></project>");
            List<String> ran = new ArrayList<>();
            CommandRegistry registry = new CommandRegistry();
            for (String id : List.of("maven.showActions", "maven.runCustom", "maven.rerunLast", "maven.stop")) {
                registry.register(Command.of(id, () -> ran.add(id)));
            }
            rig.ops.parent = dir;
            Menu menu = FxTestSupport.callOnFx(() -> {
                rig.coordinator.registerCommands(registry);
                return rig.coordinator.mavenMenu(pom);
            });
            assertEquals(
                    List.of(
                            tr("command.maven.updateVersions"),
                            tr("command.maven.showActions"),
                            tr("command.maven.runCustom"),
                            tr("command.maven.rerunLast"),
                            tr("command.maven.stop")),
                    menu.getItems().stream().map(MenuItem::getText).toList());
            FxTestSupport.runOnFx(() -> menu.getItems().forEach(MenuItem::fire));
            assertEquals(List.of("maven.showActions", "maven.runCustom", "maven.rerunLast", "maven.stop"), ran);
            assertEquals(tr("status.mavenVersions.nothingPinned"), rig.host.lastStatus(), "Update Versions ran too");

            assertNull(FxTestSupport.callOnFx(() -> rig.coordinator.mavenMenu(null)));
            assertNull(FxTestSupport.callOnFx(() -> rig.coordinator.mavenMenu(dir.resolve("effective-pom.xml"))));
            assertFalse(MavenProjectCoordinator.isPomFile(null));
            assertFalse(MavenProjectCoordinator.isPomFile(Path.of("/")));
        }
    }

    // ---- the rig ----------------------------------------------------------------------------------------

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private record Prompt(String title, String label, String initial, Consumer<String> onAccept) {}

    private record Opened(Path root, String name, GeneratedProject.MainClass main) {}

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final OverlayHost overlay = new OverlayHost();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final List<String> statusLog = new CopyOnWriteArrayList<>();
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        final AtomicInteger tasksStarted = new AtomicInteger();
        final AtomicInteger tasksClosed = new AtomicInteger();
        private volatile String lastStatus;
        private volatile String lastError;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
            statusLog.add(message);
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            lastError = message;
            errors.add(message);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            prompts.add(new Prompt(title, label, initial, onAccept));
        }

        @Override
        public AutoCloseable startBackgroundTask(String label) {
            tasksStarted.incrementAndGet();
            return tasksClosed::incrementAndGet;
        }

        String lastStatus() {
            return lastStatus;
        }

        String lastError() {
            return lastError;
        }

        String awaitStatus() throws InterruptedException {
            return awaitStatus(s -> true);
        }

        /** The next status message {@code wanted} accepts, waiting for it. */
        String awaitStatus(java.util.function.Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                String status = statuses.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(status, "no such status message arrived; seen: " + statusLog);
                if (wanted.test(status)) {
                    return status;
                }
            }
        }

        String awaitError() throws InterruptedException {
            String error = errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(error, "no error message arrived");
            return error;
        }
    }

    private static final class Ops implements MavenProjectCoordinator.Ops {
        volatile Path parent;
        volatile boolean consent = true;
        final List<MavenArchetype> asked = new CopyOnWriteArrayList<>();
        final List<Opened> projects = new CopyOnWriteArrayList<>();
        final List<Path> openedPaths = new CopyOnWriteArrayList<>();
        final AtomicInteger treeRefreshes = new AtomicInteger();

        @Override
        public Path defaultParentDir() {
            return parent;
        }

        @Override
        public EditorBuffer openBuffer(Path file) {
            return null;
        }

        @Override
        public void openProject(Path root, String name, GeneratedProject.MainClass main) {
            projects.add(new Opened(root, name, main));
        }

        @Override
        public void openPath(Path file) {
            openedPaths.add(file);
        }

        @Override
        public KeymapManager keymap() {
            return null;
        }

        @Override
        public boolean confirmArchetype(MavenArchetype archetype) {
            asked.add(archetype);
            return consent;
        }

        @Override
        public void refreshProjectTree() {
            treeRefreshes.incrementAndGet();
        }
    }

    /**
     * A coordinator over a recording host, with an overlay in a showing window so pickers and forms lay out
     * as they do in the editor. "Maven" is an executable file that is never started: every test that reaches
     * a launch replaces the launcher first, and the default one fails the test.
     */
    private static final class Rig implements AutoCloseable {
        final Host host = new Host();
        final Ops ops = new Ops();
        final StackPane overlayRoot = new StackPane();
        final BuildOutputPanel output;
        final MavenProjectCoordinator coordinator;
        final String mvn;
        private final Stage stage;

        Rig(Path dir) throws Exception {
            Path fakeMvn = Files.writeString(dir.resolve("mvn-stand-in"), "#!/bin/sh\nexit 99\n");
            assertTrue(fakeMvn.toFile().setExecutable(true));
            mvn = fakeMvn.toString();
            host.settings.setMavenCommand(mvn);
            output = FxTestSupport.callOnFx(BuildOutputPanel::new);
            coordinator = FxTestSupport.callOnFx(() -> new MavenProjectCoordinator(host, ops, output));
            FxTestSupport.runOnFx(() -> coordinator.setRunnerForTest((workingDir, argv, listener) -> {
                throw new AssertionError("this test must not launch Maven: " + argv);
            }));
            stage = FxTestSupport.callOnFx(() -> {
                host.overlay.install(overlayRoot);
                Stage s = new Stage();
                s.setScene(new Scene(overlayRoot, 1000, 800));
                s.show();
                return s;
            });
        }

        MavenArchetype quickstart() {
            return com.editora.maven.ArchetypeCatalog.curated().stream()
                    .filter(a -> a.artifactId().equals("maven-archetype-quickstart"))
                    .findFirst()
                    .orElseThrow();
        }

        /** The overlay card on show (a picker or a form), or null. */
        Node card() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                if (!host.overlay.isShowing()) {
                    return null;
                }
                // Skins put their content into the scene graph on a CSS pass (a ScrollPane's rows, a list's cells).
                overlayRoot.applyCss();
                overlayRoot.layout();
                Node last = null;
                for (Node n : overlayRoot.lookupAll(".command-palette")) {
                    last = n;
                }
                return last;
            });
        }

        /** The card, once a worker's result has put it up. */
        Node awaitCard() throws Exception {
            BlockingQueue<Boolean> shown = new LinkedBlockingQueue<>();
            javafx.beans.value.ChangeListener<Boolean> listener = (obs, was, now) -> {
                if (now) {
                    shown.add(Boolean.TRUE);
                }
            };
            boolean already = FxTestSupport.callOnFx(() -> {
                host.overlay.showingProperty().addListener(listener);
                return host.overlay.isShowing();
            });
            try {
                if (!already) {
                    assertNotNull(shown.poll(WAIT_SECONDS, TimeUnit.SECONDS), "no card appeared");
                }
            } finally {
                FxTestSupport.runOnFx(() -> host.overlay.showingProperty().removeListener(listener));
            }
            return card();
        }

        @SuppressWarnings("unchecked")
        private ListView<MavenArchetype> pickerList() throws Exception {
            Node card = card();
            assertNotNull(card, "no picker is showing");
            return (ListView<MavenArchetype>) FxTestSupport.callOnFx(() -> card.lookup(".list-view"));
        }

        List<MavenArchetype> pickerItems() throws Exception {
            ListView<MavenArchetype> list = pickerList();
            return FxTestSupport.callOnFx(() -> List.copyOf(list.getItems()));
        }

        /** What each visible picker row shows (title then detail), top to bottom. */
        List<String> pickerRowTexts() throws Exception {
            ListView<MavenArchetype> list = pickerList();
            List<String> all = new ArrayList<>();
            int size = pickerItems().size();
            // The list shows a page at a time: walk it page by page.
            for (int from = 0; from < size; from += 5) {
                int at = from;
                all.addAll(FxTestSupport.callOnFx(() -> {
                    list.scrollTo(at);
                    overlayRoot.applyCss();
                    overlayRoot.layout();
                    java.util.TreeMap<Integer, String> rows = new java.util.TreeMap<>();
                    for (Node n : list.lookupAll(".list-cell")) {
                        ListCell<?> cell = (ListCell<?>) n;
                        if (!cell.isEmpty() && cell.getGraphic() != null) {
                            StringBuilder sb = new StringBuilder();
                            collect(cell.getGraphic(), sb, "  ");
                            rows.put(cell.getIndex(), sb.toString().strip());
                        }
                    }
                    return new ArrayList<>(rows.values());
                }));
            }
            return all.stream().distinct().toList();
        }

        void choose(int index) throws Exception {
            ListView<MavenArchetype> list = pickerList();
            Node card = card();
            FxTestSupport.runOnFx(() -> {
                list.getSelectionModel().select(index);
                Event.fireEvent(
                        card.lookup(".text-field"),
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
        }

        void chooseArchetype(java.util.function.Predicate<MavenArchetype> wanted) throws Exception {
            List<MavenArchetype> items = pickerItems();
            for (int i = 0; i < items.size(); i++) {
                if (wanted.test(items.get(i))) {
                    choose(i);
                    return;
                }
            }
            throw new AssertionError("no such archetype among " + items);
        }

        /** The form's text fields in order: name, location, group, version, package. */
        TextField formField(int index) throws Exception {
            Node card = card();
            assertNotNull(card, "no form is showing");
            return FxTestSupport.callOnFx(() -> {
                List<TextField> fields = new ArrayList<>();
                for (Node n : card.lookupAll(".text-field")) {
                    if (n instanceof TextField f && !(f.getParent() instanceof ComboBox)) {
                        fields.add(f);
                    }
                }
                return fields.get(index);
            });
        }

        String fieldText(int index) throws Exception {
            TextField f = formField(index);
            return FxTestSupport.callOnFx(f::getText);
        }

        /** The form's "will create / already exists" line. */
        String hint(Node card) throws Exception {
            return FxTestSupport.callOnFx(() -> ((Label) card.lookup(".settings-hint")).getText());
        }

        Button button(Node card, String text) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                for (Node n : card.lookupAll(".button")) {
                    if (n instanceof Button b && text.equals(b.getText()) && !(n instanceof CheckBox)) {
                        return b;
                    }
                }
                throw new AssertionError("no button \"" + text + "\"");
            });
        }

        void press(Node card, String text) throws Exception {
            Button b = button(card, text);
            FxTestSupport.runOnFx(b::fire);
        }

        List<String> texts(Node card, String selector) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<String> out = new ArrayList<>();
                for (Node n : card.lookupAll(selector)) {
                    out.add(((Labeled) n).getText());
                }
                return out;
            });
        }

        String allText(Node card) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                StringBuilder sb = new StringBuilder();
                collect(card, sb, "\n");
                return sb.toString();
            });
        }

        private BuildToolPanel consolePanel() {
            Map<Object, BuildToolPanel> consoles = FxTestSupport.field(output, "consoles");
            BuildToolPanel console = consoles.get(coordinator);
            assertNotNull(console, "the wizard has no console tab yet");
            return console;
        }

        /** Everything the wizard's console tab shows. */
        String console() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                BuildToolPanel console = consolePanel();
                FxTestSupport.invoke(FxTestSupport.field(console, "appender"), "flush");
                org.fxmisc.richtext.CodeArea area = FxTestSupport.field(console, "output");
                return area.getText();
            });
        }

        String consoleStatus() throws Exception {
            return FxTestSupport.callOnFx(() -> ((Label) FxTestSupport.field(consolePanel(), "status")).getText());
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                host.overlay.hide();
                stage.hide();
                coordinator.shutdown();
            });
            FxTestSupport.drainFx();
        }
    }

    private static void collect(Node node, StringBuilder out, String separator) {
        if (node instanceof Text text) {
            out.append(text.getText()).append(separator);
        } else if (node instanceof Labeled labeled) {
            out.append(labeled.getText()).append(separator);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out, separator);
            }
        }
    }
}
