package com.editora.ui;

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

import static com.editora.i18n.Messages.tr;

/**
 * What a window does for a project the New Maven Project wizard just generated: the consent prompt for an
 * archetype Editora did not vet, and the seeded session (a ready-to-run configuration, the main class open)
 * the new window starts on. Pulled out of {@code MainController} so it stays under the source-size limit.
 */
final class NewMavenProjectSession {

    private static final Logger LOG = Logger.getLogger(NewMavenProjectSession.class.getName());

    private NewMavenProjectSession() {}

    /**
     * Gives a freshly generated Maven project a run configuration named after it, so Run works on the first
     * click instead of sending the user to Edit Configurations.
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
     * to route through and Run reports "open a Java file from the project".
     *
     * <p>No main class means no configuration: a webapp or plugin archetype has nothing to launch, and a
     * configuration that fails at the click is worse than none at all.
     */
    static void seed(
            ProjectManager projects,
            ConfigManager config,
            Project project,
            java.nio.file.Path root,
            String name,
            com.editora.maven.GeneratedProject.MainClass main) {
        if (main == null || main.fqn() == null || main.fqn().isBlank()) {
            return;
        }
        try {
            java.nio.file.Path stateFile = projects.stateFile(project);
            if (java.nio.file.Files.exists(stateFile)) {
                return; // an existing project reused by name keeps its own session
            }
            ConfigManager seeded = new ConfigManager(config.shared(), stateFile);
            WorkspaceState state = seeded.getWorkspaceState(); // fresh defaults; see the note above
            // workingDir = blank, which already means the project root (RunConfigWorkingDirectory), so the
            // configuration survives the folder being moved or renamed; a relative path in the program behaves
            // as it would from a terminal at the root (review D20).
            // projectName = the artifactId, which is what jdtls names an imported Maven project. Blank works
            // too (Run falls back to jdtls's own enumeration), but naming it lets the very first launch
            // resolve directly instead of round-tripping an enumeration first.
            // beforeLaunch = compile. archetype:generate writes SOURCES only, and Editora runs jdtls with
            // autobuild disabled, so nothing has produced target/classes yet: the resolved classpath is
            // correct and the JVM still dies with ClassNotFoundException on the very first Run. A
            // before-launch step is the mechanism for exactly this — a non-zero exit aborts the launch, so a
            // failed compile never runs a stale binary. Symbolic (`maven:compile`) rather than a command line:
            // this is persisted, and Run resolves it through the Maven the tasks tree uses (the project's
            // wrapper once trusted, the command override, else PATH mvn), so nothing rots when Maven moves.
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
            state.setOpenFiles(List.of(new WorkspaceState.OpenFile(main.file().toString(), 0, false)));
            state.setActiveFile(main.file().toString());
            seeded.save();
        } catch (RuntimeException e) {
            // The project is generated and open either way; a missing configuration is a papercut, not a
            // reason to fail the whole flow.
            LOG.log(java.util.logging.Level.WARNING, "could not seed the new project's session", e);
        }
    }

    /** Modal consent for an archetype Editora did not vet: third-party code that runs while it generates. */
    static boolean confirmArchetype(Stage stage, com.editora.maven.MavenArchetype archetype) {
        ButtonType proceed = new ButtonType(tr("dialog.mavenProject.trustAccept"), ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.WARNING,
                tr("dialog.mavenProject.trustBody", archetype.gav()),
                ButtonType.CANCEL,
                proceed);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.mavenProject.trustTitle"));
        confirm.setHeaderText(tr("dialog.mavenProject.trustHeader", archetype.artifactId()));
        confirm.getDialogPane().setMinWidth(520);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == proceed;
    }
}
