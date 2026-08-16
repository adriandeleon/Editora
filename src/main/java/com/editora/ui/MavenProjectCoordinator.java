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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.build.OutputStyle;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.doctor.DoctorProbes;
import com.editora.lsp.JavaRuntimes;
import com.editora.maven.ArchetypeCatalog;
import com.editora.maven.ArchetypeCatalogParser;
import com.editora.maven.ArchetypeGenerate;
import com.editora.maven.CentralVersions;
import com.editora.maven.MavenArchetype;
import com.editora.maven.MavenCoordinates;
import com.editora.maven.MavenProjectExtras;
import com.editora.maven.MavenProjectSpec;
import com.editora.maven.PomEdits;
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

        TextField urlField = field(keymap, 30);
        ComboBox<String> releaseCombo = new ComboBox<>();
        releaseCombo.setEditable(true); // a release level need not be one of the installed JDKs
        releaseCombo.setPrefWidth(120);
        TextInputKeymap.install(releaseCombo.getEditor(), keymap);
        // Empty means "keep whatever the archetype wrote": these values are the archetype's to choose, and
        // prefilling quickstart's would impose them on archetypes that use neither.
        urlField.setPromptText("http://www.example.com");
        releaseCombo.getEditor().setPromptText("17");
        CheckBox updateVersions = new CheckBox(tr("dialog.mavenProject.updateVersions"));
        Label updateHint = new Label(tr("dialog.mavenProject.updateVersionsHint"));
        updateHint.getStyleClass().add("settings-hint");
        updateHint.setWrapText(true);

        GridPane advancedGrid = new GridPane();
        advancedGrid.setHgap(8);
        advancedGrid.setVgap(8);
        advancedGrid.add(new Label(tr("dialog.mavenProject.url")), 0, 0);
        advancedGrid.add(urlField, 1, 0);
        advancedGrid.add(new Label(tr("dialog.mavenProject.javaRelease")), 0, 1);
        advancedGrid.add(releaseCombo, 1, 1);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        VBox advancedBox = new VBox(8, advancedGrid, updateVersions, updateHint);
        TitledPane advanced = new TitledPane(tr("dialog.mavenProject.advanced"), advancedBox);
        // Filled on first expand rather than up front: discovery walks the JDK install roots, and the
        // common path never opens this section at all.
        advanced.expandedProperty().addListener((o, was, now) -> {
            if (now && releaseCombo.getItems().isEmpty()) {
                for (Integer major : JavaRuntimes.majorsDescending(JavaRuntimes.discover())) {
                    releaseCombo.getItems().add(String.valueOf(major));
                }
            }
        });
        advanced.setExpanded(false); // collapsed by default — the common path is the five fields above
        advanced.setAnimated(false);
        advanced.getStyleClass().add("dialog-advanced");

        VBox body = new VBox(10, grid, advanced);

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.mavenProject.title"),
                body,
                nameField,
                tr("dialog.mavenProject.create"),
                valid,
                () -> {
                    MavenProjectSpec spec =
                            specFrom(archetype, nameField, locationField, groupField, versionField, packageField);
                    generate(
                            spec,
                            new MavenProjectExtras(
                                    urlField.getText(),
                                    releaseCombo.getEditor().getText(),
                                    updateVersions.isSelected()));
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
        generate(spec, MavenProjectExtras.NONE);
    }

    void generate(MavenProjectSpec spec, MavenProjectExtras extras) {
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
        // Generating next to an UNRELATED Maven project fails outright: archetype:generate tries to add the
        // new project as a <module> of whatever project it finds in the working directory, and refuses when
        // that one is not packaging=pom. So when the target holds such a project, the run is detached — a
        // pom-less scratch working directory plus -DoutputDirectory — and Maven simply has no current
        // project to register a module with. An aggregator pom is left attached, where the module is wanted.
        Path scratch = ArchetypeGenerate.detachFromExistingProject(packagingOf(parent)) ? scratchDir() : null;
        Path workingDir = scratch != null ? scratch : parent;
        List<String> mvn = absoluteExecutable(mavenExecutable(parent), parent);
        // Generated INSIDE the scratch dir and moved afterwards, rather than generated into `parent` from a
        // scratch working directory: the module check reads the pom in the OUTPUT directory, so pointing
        // outputDirectory at a folder that already holds a project fails exactly as running there did.
        List<String> argv = scratch != null
                ? ArchetypeGenerate.detachedArgv(mvn, spec, scratch)
                : ArchetypeGenerate.argv(mvn, spec);
        Path projectDir = spec.projectDir();
        host.setStatus(tr("status.mavenProject.generating", spec.artifactId()));
        output.started(
                this,
                tr("dialog.mavenProject.outputTab"),
                ArchetypeGenerate.displayCommand(argv),
                OutputStyle.maven(),
                service::stop);
        runner.run(workingDir, argv, new BuildService.Listener() {
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
                boolean placed = scratch == null || moveIntoPlace(scratch, spec, projectDir);
                if (code == 0 && placed && Files.isDirectory(projectDir)) {
                    afterGenerate(projectDir, spec, extras);
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

    /**
     * Applies the Advanced answers to the freshly generated project, then opens it.
     *
     * <p>Ordered so each step sees the last one's work: the pom edits first (so a chosen
     * {@code maven.compiler.release} is in place before anything reads it), then the dependency update,
     * then the plugin versions, then the project opens. Every step is best-effort — a failure leaves what
     * the archetype wrote and says so, because a generated project that exists is worth more than one
     * abandoned half-way through a nicety.
     */
    /** The packaging of the pom in {@code dir}, or null when it has none (or one that cannot be read). */
    private String packagingOf(Path dir) {
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return null;
        }
        // PomEdits rather than PomParser: this needs one element, not a validated model, and a minimal or
        // hand-written aggregator would fail full parsing and be misread as a jar project.
        String text = read(pom);
        String packaging = text == null ? null : PomEdits.packaging(text);
        // A file that exists but yields nothing is still a project — detach rather than risk the failure.
        return packaging == null ? "jar" : packaging;
    }

    /**
     * Moves the generated project out of the scratch directory to where the user asked for it.
     *
     * <p>An atomic rename when both sit on one filesystem, a recursive copy when they do not — the scratch
     * directory is the system temp dir, which is very often a different mount from the user's home.
     *
     * @return false when the project could not be put in place, so the caller reports a failure
     */
    private boolean moveIntoPlace(Path scratch, MavenProjectSpec spec, Path projectDir) {
        Path generated = scratch.resolve(spec.artifactId());
        if (!Files.isDirectory(generated)) {
            return false;
        }
        try {
            Files.createDirectories(projectDir.getParent());
            try {
                Files.move(generated, projectDir);
            } catch (java.nio.file.FileSystemException crossDevice) {
                copyRecursively(generated, projectDir);
            }
            return Files.isDirectory(projectDir);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.moveFailed", String.valueOf(e.getMessage())));
            return false;
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static void copyRecursively(Path from, Path to) throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup of a temp dir
                }
            });
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** A throwaway pom-less directory to run Maven from, or null if one cannot be made. */
    private Path scratchDir() {
        try {
            Path dir = Files.createTempDirectory("editora-archetype");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (Exception e) {
            return null; // fall back to running in place — worst case the old failure, with its own message
        }
    }

    /**
     * Resolves a project-relative wrapper to an absolute path.
     *
     * <p>{@code BuildTool.MAVEN.executable} hands back {@code ./mvnw} on Unix, which is correct only while
     * the working directory is the project. A detached run happens somewhere else entirely, where that
     * relative path would resolve to nothing.
     */
    private static List<String> absoluteExecutable(List<String> executable, Path projectDir) {
        if (executable.isEmpty()) {
            return executable;
        }
        String first = executable.get(0);
        if (!first.startsWith("./")) {
            return executable;
        }
        List<String> out = new ArrayList<>(executable);
        out.set(0, projectDir.resolve(first.substring(2)).toString());
        return List.copyOf(out);
    }

    private void afterGenerate(Path projectDir, MavenProjectSpec spec, MavenProjectExtras extras) {
        if (extras.editsPom()) {
            editPom(projectDir, pom -> {
                String out = PomEdits.setProjectUrl(pom, extras.url());
                return PomEdits.setProperty(out, "maven.compiler.release", extras.javaRelease());
            });
        }
        if (!extras.updateVersions()) {
            onGenerated(projectDir, spec.artifactId());
            return;
        }
        // Dependencies first, through versions-maven-plugin, streamed into the console the generation is
        // already showing. Plugins are NOT covered by it — its in-place goals expose processDependencies,
        // processDependencyManagement and processParent, and for plugins it offers only
        // display-plugin-updates (checked against 2.21.0) — so those are resolved and rewritten below.
        List<String> argv = new ArrayList<>(mavenExecutable(projectDir));
        argv.add("versions:use-latest-releases");
        argv.add("-B");
        argv.add("-DgenerateBackupPoms=false"); // the pom is seconds old; a pom.xml.versionsBackup is litter
        host.setStatus(tr("status.mavenProject.updatingVersions"));
        output.appendOutput(this, ArchetypeGenerate.displayCommand(argv), false);
        runner.run(projectDir, argv, new BuildService.Listener() {
            @Override
            public void onStart(String commandLine) {}

            @Override
            public void onOutput(String line, boolean stderr) {
                output.appendOutput(MavenProjectCoordinator.this, line, stderr);
            }

            @Override
            public void onExit(int code) {
                // Carries on regardless of the exit code: a failed dependency update should not also cost
                // the plugin half, and the project itself is already generated.
                updatePluginVersions(projectDir, spec.artifactId());
            }

            @Override
            public void onError(String message) {
                output.appendOutput(MavenProjectCoordinator.this, message, true);
                updatePluginVersions(projectDir, spec.artifactId());
            }
        });
    }

    /**
     * Resolves each pinned plugin's latest stable version from Maven Central and rewrites the pom.
     *
     * <p>Off the FX thread: it is one HTTPS GET per plugin (seven for a quickstart pom). Failures are
     * silent per artifact — see {@link CentralVersions#latest} — and the whole step degrades to leaving the
     * versions the archetype chose.
     */
    private void updatePluginVersions(Path projectDir, String artifactId) {
        Path pomFile = projectDir.resolve("pom.xml");
        String pom = read(pomFile);
        Map<String, String> current = pom == null ? Map.of() : PomEdits.pluginVersions(pom);
        if (current.isEmpty()) {
            finishUpdate(projectDir, artifactId, 0);
            return;
        }
        fetchExecutor().execute(() -> {
            Map<String, String> latest = CentralVersions.latest(current.keySet(), this::fetchMetadata);
            Map<String, String> upgrades = CentralVersions.upgradesOnly(current, latest);
            Platform.runLater(() -> {
                if (!upgrades.isEmpty()) {
                    editPom(projectDir, text -> PomEdits.setPluginVersions(text, upgrades));
                    upgrades.forEach((ga, v) ->
                            output.appendOutput(this, tr("status.mavenProject.pluginUpdated", ga, v), false));
                }
                finishUpdate(projectDir, artifactId, upgrades.size());
            });
        });
    }

    private void finishUpdate(Path projectDir, String artifactId, int pluginUpdates) {
        host.setStatus(tr("status.mavenProject.versionsUpdated", pluginUpdates));
        onGenerated(projectDir, artifactId);
    }

    /** One {@code maven-metadata.xml}, or null when it cannot be had. HTTPS only, bounded, with a timeout. */
    private String fetchMetadata(String repoPath) {
        String url = CentralVersions.CENTRAL + repoPath;
        if (!PluginRegistry.isHttps(url)) {
            return null;
        }
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response =
                    metadataClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                return null;
            }
            try (java.io.InputStream in = response.body()) {
                return new String(PluginRegistry.readCapped(in, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null; // unreachable, refused, malformed — all just "no answer"
        }
    }

    /** A metadata file is a few KB; this bound is what stops a hostile mirror streaming forever. */
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;

    private java.net.http.HttpClient metadataClient() {
        if (metadataClient == null) {
            metadataClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
        }
        return metadataClient;
    }

    private java.net.http.HttpClient metadataClient;

    /** Reads, transforms and writes the pom, doing nothing at all if any part of that fails. */
    private void editPom(Path projectDir, java.util.function.UnaryOperator<String> edit) {
        Path pomFile = projectDir.resolve("pom.xml");
        String pom = read(pomFile);
        if (pom == null) {
            return;
        }
        String out = edit.apply(pom);
        if (out == null || out.equals(pom)) {
            return;
        }
        try {
            Files.writeString(pomFile, out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            host.setError(tr("status.mavenProject.pomEditFailed", String.valueOf(e.getMessage())));
        }
    }

    private String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
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
