package com.editora.ui;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.build.OutputStyle;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.doctor.DoctorProbes;
import com.editora.maven.ArchetypeCatalog;
import com.editora.maven.ArchetypeCatalogParser;
import com.editora.maven.ArchetypeGenerate;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenCoordinates;
import com.editora.maven.MavenProjectSpec;
import com.editora.plugin.PluginRegistry;

import static com.editora.i18n.Messages.tr;

/**
 * The <b>New Maven Project</b> wizard: pick an archetype, fill in coordinates, run
 * {@code mvn archetype:generate}, then register the result as an Editora project and open it in its own
 * window. The {@link CoordinatorHost} pattern, like the other feature coordinators.
 *
 * <p>Generation always shells out to Maven — there is no hand-written pom — so the feature is <b>inert
 * without {@code mvn} on PATH</b>. That is checked <em>before</em> the wizard opens rather than after the
 * user has filled in five fields.
 *
 * <p>The pure decisions (coordinate validation, package derivation, the argv) live in {@code com.editora.maven}
 * so they are unit-tested without a toolkit; this class is the wiring.
 */
final class MavenProjectCoordinator {

    /** Cap for the fetched catalog. Maven Central's real one is ~10 MB. */
    private static final long MAX_CATALOG_BYTES = 16L * 1024 * 1024;

    private static final String DEFAULT_VERSION = "1.0-SNAPSHOT";

    /** Window hooks beyond {@link CoordinatorHost} that this feature needs. */
    interface Ops {
        /** Where the wizard starts when no folder was named (active file's dir → project root → home). */
        Path defaultParentDir();

        /**
         * Registers {@code root} as a project and opens it in its own window.
         *
         * @param main the entry point found in the generated sources, or null — when non-null the new
         *     window is seeded with a ready-to-run configuration named after the project, and opens on the
         *     class that will run (jdtls needs it open to resolve a classpath)
         */
        void openProject(Path root, String name, com.editora.maven.GeneratedProject.MainClass main);

        /** Opens a file in the current window (used to land on the generated {@code pom.xml}). */
        void openPath(Path file);

        /** The active keymap, for {@link TextInputKeymap} on the form's fields. */
        KeymapManager keymap();

        /** Modal consent for an archetype we did not vet. {@code true} to proceed. */
        boolean confirmArchetype(MavenArchetype archetype);

        /** Re-lists the project tree after files land on disk. */
        void refreshProjectTree();
    }

    /**
     * How a generation is actually launched. Exists so the FX test can assert the argv without forking
     * Maven (the {@code LspManager.setSessionStarterForTest} idiom).
     */
    @FunctionalInterface
    interface Runner {
        void run(Path workingDir, List<String> argv, BuildService.Listener listener);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final BuildOutputPanel output;
    private final BuildService service = new BuildService();
    private Runner runner = service::run;

    /** Fetched catalog entries, cached for the session (the fetch is ~10 MB). */
    private List<MavenArchetype> fetched;

    private ExecutorService fetchExec;

    MavenProjectCoordinator(CoordinatorHost host, Ops ops, BuildOutputPanel output) {
        this.host = host;
        this.ops = ops;
        this.output = output;
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("maven.newProject", () -> newProject(null)));
        registry.register(Command.of("maven.newProjectHere", this::newProjectHere));
    }

    /** Entry point for the project-tree folder menu — generate into {@code folder}. */
    void newProject(Path parentDir) {
        // The palette gates maven.* off Settings.mavenSupport for free (Chrome's build-tool prefix rule),
        // but the project-tree context menu does not go through the palette — so re-check here rather than
        // let the tree offer an action that would silently do nothing.
        if (!host.settings().isMavenSupport()) {
            host.setError(tr("status.mavenProject.disabled"));
            return;
        }
        Path base = parentDir != null ? parentDir : ops.defaultParentDir();
        if (base == null) {
            base = Path.of(System.getProperty("user.home", "."));
        }
        List<String> mvn = mavenExecutable(base);
        // Probe up front: with no pom writer, a missing mvn means the wizard could never finish, and
        // discovering that after five fields have been typed is the worst possible moment to say so.
        if (!DoctorProbes.onPath(mvn)) {
            host.setError(tr("status.mavenProject.noMaven"));
            return;
        }
        chooseArchetype(base, ArchetypeCatalog.merge(fetched));
    }

    /** Palette variant: asks for the folder first (the tree menu supplies one directly). */
    private void newProjectHere() {
        Path base = ops.defaultParentDir();
        host.promptText(
                tr("dialog.mavenProject.locationTitle"),
                tr("dialog.mavenProject.location"),
                base == null ? "" : base.toString(),
                text -> {
                    if (text != null && !text.isBlank()) {
                        newProject(Path.of(text.strip()));
                    }
                });
    }

    /**
     * The Maven launcher to use. Routed through {@link BuildTool} so a Settings override is honoured; in
     * practice there is no {@code mvnw} in a not-yet-created project, so it resolves to the override or a
     * plain {@code mvn}. {@code root} must be non-null — {@code wrapperArgv} resolves against it.
     */
    private List<String> mavenExecutable(Path root) {
        return BuildTool.MAVEN.executable(
                root,
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"),
                host.settings().getMavenCommand());
    }

    // --- Step 1: pick an archetype ----------------------------------------------------------------

    /**
     * Sentinel rows appended to the picker. Compared by <b>identity</b>, never by label — an archetype the
     * user names "Custom archetype…" must stay an archetype (the lesson already recorded for the
     * run-configuration combo's Edit-Configurations row).
     */
    private static final MavenArchetype CUSTOM_ROW = new MavenArchetype("", "", "", "", "", false);

    private static final MavenArchetype LOAD_CATALOG_ROW = new MavenArchetype("", "", "", "", "", false);

    private void chooseArchetype(Path parentDir, List<MavenArchetype> archetypes) {
        QuickOpen<MavenArchetype> picker = new QuickOpen<>(
                tr("dialog.mavenProject.archetypeTitle"),
                tr("dialog.mavenProject.archetypePrompt"),
                () -> {
                    List<MavenArchetype> rows = new ArrayList<>(archetypes);
                    rows.add(CUSTOM_ROW);
                    if (fetched == null) {
                        rows.add(LOAD_CATALOG_ROW);
                    }
                    return rows;
                },
                this::rowLabel,
                this::rowDetail,
                a -> a.gav(),
                a -> onArchetypeChosen(a, parentDir));
        picker.setPreferredSize(820, 10);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private String rowLabel(MavenArchetype a) {
        if (a == CUSTOM_ROW) {
            return tr("dialog.mavenProject.customRow");
        }
        if (a == LOAD_CATALOG_ROW) {
            return tr("dialog.mavenProject.loadCatalogRow");
        }
        return a.artifactId();
    }

    private String rowDetail(MavenArchetype a) {
        if (a == CUSTOM_ROW || a == LOAD_CATALOG_ROW) {
            return "";
        }
        return a.description().isEmpty() ? a.groupId() : a.description();
    }

    private void onArchetypeChosen(MavenArchetype a, Path parentDir) {
        if (a == CUSTOM_ROW) {
            host.promptText(tr("dialog.mavenProject.customTitle"), tr("dialog.mavenProject.customLabel"), "", text -> {
                MavenArchetype custom = MavenCoordinates.parseGav(text);
                if (custom == null) {
                    host.setError(tr("status.mavenProject.badGav"));
                    return;
                }
                showForm(custom, parentDir);
            });
            return;
        }
        if (a == LOAD_CATALOG_ROW) {
            loadCatalog(parentDir);
            return;
        }
        showForm(a, parentDir);
    }

    // --- The optional full-catalog fetch ----------------------------------------------------------

    private void loadCatalog(Path parentDir) {
        String url = host.settings().getMavenArchetypeCatalogUrl();
        if (!PluginRegistry.isHttps(url)) {
            host.setError(tr("status.mavenProject.catalogNotHttps"));
            return;
        }
        host.setStatus(tr("status.mavenProject.loadingCatalog"));
        AutoCloseable task = host.startBackgroundTask(tr("status.mavenProject.loadingCatalog"));
        fetchExecutor().execute(() -> {
            String error = null;
            List<MavenArchetype> parsed = List.of();
            try {
                parsed = ArchetypeCatalogParser.parse(fetchCatalog(url));
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<MavenArchetype> result = parsed;
            String err = error;
            Platform.runLater(() -> {
                closeQuietly(task);
                if (err != null) {
                    host.setError(tr("status.mavenProject.catalogFailed", err));
                    // Still let the user proceed with the curated list rather than dead-ending.
                    chooseArchetype(parentDir, ArchetypeCatalog.merge(fetched));
                    return;
                }
                fetched = result;
                host.setStatus(tr("status.mavenProject.catalogLoaded", result.size()));
                chooseArchetype(parentDir, ArchetypeCatalog.merge(fetched));
            });
        });
    }

    private static String fetchCatalog(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Editora")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            return new String(PluginRegistry.readCapped(in, MAX_CATALOG_BYTES), StandardCharsets.UTF_8);
        }
    }

    private synchronized ExecutorService fetchExecutor() {
        if (fetchExec == null) {
            fetchExec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "archetype-catalog");
                t.setDaemon(true);
                return t;
            });
        }
        return fetchExec;
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // the background-task handle must be closed on every path; failure here is not actionable
        }
    }

    // --- Step 2: the coordinates form -------------------------------------------------------------

    private void showForm(MavenArchetype archetype, Path parentDir) {
        KeymapManager keymap = ops.keymap();
        TextField nameField = field(keymap, 24);
        TextField locationField = field(keymap, 30);
        TextField groupField = field(keymap, 24);
        TextField versionField = field(keymap, 16);
        TextField packageField = field(keymap, 30);

        locationField.setText(parentDir == null ? "" : parentDir.toString());
        groupField.setText("com.example");
        versionField.setText(DEFAULT_VERSION);

        Label target = new Label();
        target.getStyleClass().add("settings-hint");

        // Name and groupId drive the package until the user edits it by hand — the cloneRepo idiom.
        boolean[] packageEdited = {false};
        boolean[] autoFilling = {false};
        Runnable autoPackage = () -> {
            if (!packageEdited[0]) {
                autoFilling[0] = true;
                packageField.setText(MavenCoordinates.defaultPackage(groupField.getText(), nameField.getText()));
                autoFilling[0] = false;
            }
        };
        packageField.textProperty().addListener((o, a, b) -> {
            if (!autoFilling[0]) {
                packageEdited[0] = true; // user took control of the package
            }
        });

        Button browse = new Button(tr("dialog.mavenProject.browse"));
        browse.setFocusTraversable(false);
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("dialog.mavenProject.locationTitle"));
            File chosen = chooser.showDialog(host.window());
            if (chosen != null) {
                locationField.setText(chosen.toPath().toString());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        int row = 0;
        grid.add(new Label(tr("dialog.mavenProject.archetype")), 0, row);
        Label chosen = new Label(archetype.gav());
        grid.add(chosen, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.name")), 0, row);
        grid.add(nameField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.location")), 0, row);
        grid.add(locationField, 1, row);
        grid.add(browse, 2, row++);
        grid.add(new Label(tr("dialog.mavenProject.groupId")), 0, row);
        grid.add(groupField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.version")), 0, row);
        grid.add(versionField, 1, row++, 2, 1);
        grid.add(new Label(tr("dialog.mavenProject.packageName")), 0, row);
        grid.add(packageField, 1, row++, 2, 1);
        grid.add(target, 1, row, 2, 1);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        GridPane.setHgrow(locationField, Priority.ALWAYS);
        GridPane.setHgrow(groupField, Priority.ALWAYS);
        GridPane.setHgrow(packageField, Priority.ALWAYS);

        BooleanProperty valid = new SimpleBooleanProperty(false);
        Runnable revalidate = () -> {
            MavenProjectSpec spec =
                    specFrom(archetype, nameField, locationField, groupField, versionField, packageField);
            Path dir = spec.projectDir();
            boolean exists = dir != null && Files.exists(dir);
            // Refusing an existing directory matches writeTemplateFile's no-overwrite stance; Maven would
            // otherwise generate into a populated folder and half-merge with whatever is already there.
            valid.set(spec.isValid() && !exists);
            target.setText(
                    dir == null
                            ? ""
                            : exists
                                    ? tr("dialog.mavenProject.exists", dir)
                                    : tr("dialog.mavenProject.willCreate", dir));
        };
        for (TextField f : List.of(nameField, locationField, groupField, versionField, packageField)) {
            f.textProperty().addListener((o, a, b) -> {
                if (f == nameField || f == groupField) {
                    autoPackage.run();
                }
                revalidate.run();
            });
        }
        revalidate.run();

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.mavenProject.title"),
                grid,
                nameField,
                tr("dialog.mavenProject.create"),
                valid,
                () -> {
                    MavenProjectSpec spec =
                            specFrom(archetype, nameField, locationField, groupField, versionField, packageField);
                    generate(spec);
                },
                null,
                false);
    }

    private TextField field(KeymapManager keymap, int columns) {
        TextField f = new TextField();
        f.setPrefColumnCount(columns);
        TextInputKeymap.install(f, keymap);
        return f;
    }

    private static MavenProjectSpec specFrom(
            MavenArchetype archetype,
            TextField name,
            TextField location,
            TextField group,
            TextField version,
            TextField pkg) {
        String loc = location.getText() == null ? "" : location.getText().strip();
        Path parent = loc.isEmpty() ? null : Path.of(loc);
        return new MavenProjectSpec(
                archetype, group.getText(), name.getText(), version.getText(), pkg.getText(), parent);
    }

    // --- Step 3: generate --------------------------------------------------------------------------

    /** Package-visible so the FX test can drive generation past the two cards. */
    void generate(MavenProjectSpec spec) {
        if (!spec.isValid()) {
            host.setError(tr("status.mavenProject.invalid", spec.firstProblem()));
            return;
        }
        // A non-curated archetype is third-party code: archetype:generate downloads and runs its plugin.
        // The workspace-trust gate cannot cover this — it only fires when a repo ships an mvnw, and a
        // brand-new empty directory has none.
        if (!spec.archetype().curated() && !ops.confirmArchetype(spec.archetype())) {
            host.setStatus(tr("status.mavenProject.cancelled"));
            return;
        }
        Path parent = spec.parentDir();
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.mkdirFailed", String.valueOf(e.getMessage())));
            return;
        }
        List<String> argv = ArchetypeGenerate.argv(mavenExecutable(parent), spec);
        Path projectDir = spec.projectDir();
        host.setStatus(tr("status.mavenProject.generating", spec.artifactId()));
        output.started(
                this,
                tr("dialog.mavenProject.outputTab"),
                ArchetypeGenerate.displayCommand(argv),
                OutputStyle.maven(),
                service::stop);
        runner.run(parent, argv, new BuildService.Listener() {
            @Override
            public void onStart(String commandLine) {
                // header already shown by started(...)
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                output.appendOutput(MavenProjectCoordinator.this, line, stderr);
            }

            @Override
            public void onExit(int code) {
                output.finished(MavenProjectCoordinator.this, code);
                if (code == 0 && Files.isDirectory(projectDir)) {
                    onGenerated(projectDir, spec.artifactId());
                } else {
                    host.setError(tr("status.mavenProject.failed", spec.artifactId()));
                }
            }

            @Override
            public void onError(String message) {
                output.failed(MavenProjectCoordinator.this, message);
                host.setError(tr("status.mavenProject.failed", spec.artifactId()));
            }
        });
    }

    private void onGenerated(Path projectDir, String name) {
        host.setStatus(tr("status.mavenProject.created", name));
        ops.refreshProjectTree();
        // Look at what the archetype actually wrote rather than guessing from the coordinates: quickstart
        // gives <package>.App, a webapp archetype gives no main at all. Null simply means no run
        // configuration is seeded.
        ops.openProject(projectDir, name, com.editora.maven.GeneratedProject.findMain(projectDir));
        Path pom = projectDir.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            ops.openPath(pom);
        }
    }

    void stop() {
        service.stop();
    }

    void shutdown() {
        service.stop();
        if (fetchExec != null) {
            fetchExec.shutdownNow();
        }
    }

    /** Test seam: replace the subprocess launcher so a test never forks Maven. */
    void setRunnerForTest(Runner runner) {
        this.runner = runner;
    }
}
