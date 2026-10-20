package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.WorkspaceState;
import com.editora.maven.GeneratedProject;

import static com.editora.i18n.Messages.tr;

/**
 * What a window does for a project the New Maven Project wizard just generated: the consent prompt for an
 * archetype Editora did not vet, and the seeded session (a ready-to-run configuration, the main class open)
 * the new window starts on. Pulled out of {@code MainController} so it stays under the source-size limit.
 */
final class NewMavenProjectSession {

    private static final Logger LOG = Logger.getLogger(NewMavenProjectSession.class.getName());

    private NewMavenProjectSession() {}

    /** Registers the generated project, seeds its session and opens (or focuses) its window. */
    static void open(
            ProjectManager projects,
            ConfigManager config,
            WindowManager windows,
            java.nio.file.Path root,
            String name,
            GeneratedProject.MainClass main) {
        Project project = projects.createOrGet(name, root);
        projects.save();
        seed(projects, config, project, root, name, main);
        if (windows != null) {
            windows.openOrFocus(project);
        }
    }

    /**
     * Gives a freshly generated Maven project a run configuration named after it, so Run works on the first
     * click instead of sending the user to Edit Configurations — and opens it on its main class and its
     * {@code pom.xml}.
     *
     * <p>Written into the <b>new project's</b> session file before {@code openOrFocus} builds its window —
     * that window loads {@code projects/<id>.json} on construction, so seeding afterwards would be a race,
     * and writing it into <em>this</em> window's state would put the configuration in the wrong project.
     * <p><b>Deliberately does not call {@code ConfigManager.load()}.</b> That runs
     * {@code SharedConfig.load()}, which replaces the shared {@code Settings} instance every open window
     * holds <em>by reference</em> and re-reads every shared store from disk — corrupting live windows in
     * order to write one file. A brand-new project needs no load: {@code ConfigManager} starts with a fresh
     * {@code WorkspaceState}, which is exactly the right starting point, and an existing session file is
     * left strictly alone.
     *
     * <p>The open file matters as much as the configuration: a Java launch resolves its classpath through
     * jdtls routed via an <b>open Java file</b>, so a window that opens on {@code pom.xml} alone has nothing
     * to route through and Run reports "open a Java file from the project". The pom is opened beside it
     * (the main class stays the active tab) — it used to be opened in the window the wizard ran from,
     * as a foreign tab in the <em>old</em> project's session.
     *
     * <p>No main class means no configuration: a webapp or plugin archetype has nothing to launch, and a
     * configuration that fails at the click is worse than none at all. The same goes for a JavaFX
     * archetype's main: a plain classpath launch of it dies with "JavaFX runtime components are missing",
     * and the run-configuration model has no "Maven goal" type that could hold {@code javafx:run} — so no
     * configuration is seeded, and the wizard says to run it from the Maven tool window instead.
     */
    static void seed(
            ProjectManager projects,
            ConfigManager config,
            Project project,
            java.nio.file.Path root,
            String name,
            GeneratedProject.MainClass main) {
        boolean runnable = main != null && main.fqn() != null && !main.fqn().isBlank();
        java.nio.file.Path pom = root.resolve("pom.xml");
        boolean hasPom = java.nio.file.Files.isRegularFile(pom);
        if (!runnable && !hasPom) {
            return;
        }
        try {
            java.nio.file.Path stateFile = projects.stateFile(project);
            if (java.nio.file.Files.exists(stateFile)) {
                return; // an existing project reused by name keeps its own session
            }
            ConfigManager seeded = new ConfigManager(config.shared(), stateFile);
            WorkspaceState state = seeded.getWorkspaceState(); // fresh defaults; see the note above
            if (runnable && !GeneratedProject.needsMavenToRun(GeneratedProject.mavenRunGoal(root))) {
                // workingDir = blank, which already means the project root (RunConfigWorkingDirectory), so the
                // configuration survives the folder being moved or renamed; a relative path in the program
                // behaves as it would from a terminal at the root (review D20).
                // projectName = the artifactId, which is what jdtls names an imported Maven project. Blank
                // works too (Run falls back to jdtls's own enumeration), but naming it lets the very first
                // launch resolve directly instead of round-tripping an enumeration first.
                // beforeLaunch = compile. archetype:generate writes SOURCES only, and Editora runs jdtls with
                // autobuild disabled, so nothing has produced target/classes yet: the resolved classpath is
                // correct and the JVM still dies with ClassNotFoundException on the very first Run. A
                // before-launch step is the mechanism for exactly this — a non-zero exit aborts the launch,
                // so a failed compile never runs a stale binary. Symbolic (`maven:compile`) rather than a
                // command line: this is persisted, and Run resolves it through the Maven the tasks tree uses
                // (the project's wrapper once trusted, the command override, else PATH mvn), so nothing rots
                // when Maven moves.
                state.setRunConfigurations(List.of(new com.editora.config.RunConfiguration(
                        name,
                        "java",
                        "",
                        main.fqn(),
                        name,
                        "",
                        "",
                        "",
                        "",
                        com.editora.run.BuildBeforeLaunch.seededStep(BuildTool.MAVEN))));
                state.setSelectedRunConfig(name);
            }
            List<WorkspaceState.OpenFile> open = new ArrayList<>();
            if (runnable) {
                open.add(new WorkspaceState.OpenFile(main.file().toString(), 0, false));
            }
            if (hasPom) {
                open.add(new WorkspaceState.OpenFile(pom.toString(), 0, false));
            }
            state.setOpenFiles(open);
            state.setActiveFile(open.get(0).getPath());
            seeded.save();
        } catch (RuntimeException e) {
            // The project is generated and open either way; a missing configuration is a papercut, not a
            // reason to fail the whole flow.
            LOG.log(java.util.logging.Level.WARNING, "could not seed the new project's session", e);
        }
    }

    /**
     * Modal consent for an archetype Editora did not vet: third-party code that runs while it generates.
     * Names the repository it will be downloaded from when the catalog gave one — the one fact that
     * matters for trust — and offers to remember the answer for the rest of the session.
     */
    static MavenProjectCoordinator.Consent confirmArchetype(Stage stage, com.editora.maven.MavenArchetype archetype) {
        ButtonType proceed = new ButtonType(tr("dialog.mavenProject.trustAccept"), ButtonBar.ButtonData.OK_DONE);
        ButtonType always = new ButtonType(tr("dialog.mavenProject.trustAlways"), ButtonBar.ButtonData.OTHER);
        StringBuilder body = new StringBuilder(tr("dialog.mavenProject.trustBody", archetype.gav()));
        if (archetype.hasRepository()) {
            body.append("\n\n").append(tr("dialog.mavenProject.trustFrom", archetype.repository()));
        }
        body.append("\n\n").append(tr("dialog.mavenProject.trustRemember"));
        Alert confirm = new Alert(Alert.AlertType.WARNING, body.toString(), ButtonType.CANCEL, always, proceed);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.mavenProject.trustTitle"));
        confirm.setHeaderText(tr("dialog.mavenProject.trustHeader", archetype.artifactId()));
        confirm.getDialogPane().setMinWidth(560);
        ButtonType answer = confirm.showAndWait().orElse(ButtonType.CANCEL);
        return answer == proceed
                ? MavenProjectCoordinator.Consent.ONCE
                : answer == always ? MavenProjectCoordinator.Consent.ALWAYS : MavenProjectCoordinator.Consent.CANCEL;
    }
}
