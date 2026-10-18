package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunConfigurationsWindowFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> javafx.stage.Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> tr("run.config.title").equals(stage.getTitle()))
                .forEach(Stage::close));
    }

    @Test
    void editorIsASeparateWindowAndSelectsTheRequestedSessionConfiguration(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        RunConfiguration first = new RunConfiguration("First", "example.First", "", "", "", "");
        RunConfiguration second = new RunConfiguration("Second", "example.Second", "", "", "", "");
        config.getWorkspaceState().setRunConfigurations(java.util.List.of(first, second));

        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("Second", null));

        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        assertTrue(FxTestSupport.callOnFx(stage::isShowing));
        assertEquals(tr("run.config.title"), FxTestSupport.callOnFx(stage::getTitle));
        assertEquals(
                second, FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedItem()));
        @SuppressWarnings("unchecked")
        ComboBox<JdkChoice> jdk = (ComboBox<JdkChoice>) stage.getScene().lookup("#run-config-jdk");
        assertFalse(FxTestSupport.callOnFx(jdk.getItems()::isEmpty));
        assertEquals("", FxTestSupport.callOnFx(() -> jdk.getValue().home()));
        FxTestSupport.runOnFx(stage::close);
    }

    @Test
    void runConfigurationsAreNotASettingsCategory() throws Exception {
        Class<?> category = Class.forName("com.editora.ui.SettingsWindow$Category");
        assertFalse(Arrays.stream(category.getEnumConstants())
                .anyMatch(value -> value.toString().equals("RUN_CONFIGS")));
    }

    private static RunConfiguration persisted(ConfigManager config, String name) {
        return config.getWorkspaceState().getRunConfigurations().stream()
                .filter(c -> name.equals(c.name()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * With a single configuration (always the case while creating the first one), committing a field replaced
     * the list's only element, which cleared the selection: the form was disabled and every field emptied
     * after each Enter.
     */
    @Test
    void committingAFieldOfTheOnlyConfigurationKeepsItSelectedAndTheFormFilled(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(new RunConfiguration("Only", "example.Only", "", "", "", "")));
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("Only", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        javafx.scene.control.TextField args =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-args");

        FxTestSupport.runOnFx(() -> {
            args.setText("--port 8080");
            args.getOnAction().handle(new javafx.event.ActionEvent());
        });

        assertEquals("--port 8080", persisted(config, "Only").args());
        assertEquals(0, FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedIndex()));
        assertEquals("--port 8080", FxTestSupport.callOnFx(args::getText), "the form still shows the row");
        assertFalse(FxTestSupport.callOnFx(() -> args.getParent().isDisabled()));
        FxTestSupport.runOnFx(stage::close);
    }

    /**
     * A field only commits on focus loss, which arrives after a click on another row has already changed the
     * selection: the edit was overwritten by that row's values and silently dropped.
     */
    @Test
    void anUncommittedEditIsKeptWhenAnotherConfigurationIsSelected(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(
                        new RunConfiguration("First", "example.First", "", "", "", ""),
                        new RunConfiguration("Second", "example.Second", "", "", "", "")));
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("First", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        javafx.scene.control.TextField args =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-args");

        FxTestSupport.runOnFx(() -> {
            args.setText("--port 8080"); // typed, not yet committed
            list.getSelectionModel().select(1); // the click on the other row
        });

        assertEquals("--port 8080", persisted(config, "First").args());
        assertEquals("", persisted(config, "Second").args());
        assertEquals("", FxTestSupport.callOnFx(args::getText), "the form now shows Second");
        assertEquals(
                "Second",
                FxTestSupport.callOnFx(
                        () -> list.getSelectionModel().getSelectedItem().name()));
        FxTestSupport.runOnFx(stage::close);
    }

    /** Only the fields the selected type launches with are editable, and they follow the type as it changes. */
    @Test
    void onlyTheFieldsOfTheSelectedTypeAreEnabled(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(
                        new RunConfiguration("App", "example.App", "", "", "-Xmx1g", ""),
                        new RunConfiguration("Other", "example.Other", "", "", "", ""),
                        new RunConfiguration("Script", "python", "tool.py", "", "", "", "", "", "", "")));
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("Script", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        @SuppressWarnings("unchecked")
        ComboBox<String> type = (ComboBox<String>) stage.getScene().lookup("#run-config-type");

        assertEquals(
                java.util.List.of(false, true, true, true, true, false), FxTestSupport.callOnFx(() -> disabled(stage)));

        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
        assertEquals(
                java.util.List.of(true, false, false, false, false, false),
                FxTestSupport.callOnFx(() -> disabled(stage)));

        // Java to Java: the type does not change, the fields must still be right.
        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(1));
        assertEquals(
                java.util.List.of(true, false, false, false, false, false),
                FxTestSupport.callOnFx(() -> disabled(stage)));

        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
        FxTestSupport.runOnFx(() -> type.setValue("npm"));
        assertEquals(
                java.util.List.of(false, true, true, true, true, false), FxTestSupport.callOnFx(() -> disabled(stage)));
        assertEquals("npm", persisted(config, "App").type());
        assertEquals("-Xmx1g", persisted(config, "App").vmArgs(), "a disabled field keeps its value");
        FxTestSupport.runOnFx(stage::close);
    }

    @Test
    void aShellConfigurationHasInterpreterFieldsThatPersist(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(
                        new RunConfiguration("Deploy", "shell", "deploy.sh", "", "", "", "", "", "", ""),
                        new RunConfiguration("Tool", "python", "tool.py", "", "", "", "", "", "", "")));
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("Deploy", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        javafx.scene.control.TextField interpreter =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-interpreter");
        javafx.scene.control.TextField options =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-interpreter-options");

        assertFalse(FxTestSupport.callOnFx(interpreter::isDisabled), "a shell script's interpreter can be set");
        FxTestSupport.runOnFx(() -> {
            interpreter.setText("/opt/homebrew/bin/bash");
            options.setText("-eu");
            options.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("/opt/homebrew/bin/bash", persisted(config, "Deploy").interpreter());
        assertEquals("-eu", persisted(config, "Deploy").interpreterOptions());

        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(1));
        // A Python script names its interpreter too (C1) — its own, kept apart from the shell one.
        assertFalse(FxTestSupport.callOnFx(interpreter::isDisabled), "a Python script's interpreter can be set");
        assertEquals("", FxTestSupport.callOnFx(interpreter::getText));
        assertEquals(
                tr("settings.runConfig.interpreterPromptPython"), FxTestSupport.callOnFx(interpreter::getPromptText));
        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
        assertEquals("/opt/homebrew/bin/bash", FxTestSupport.callOnFx(interpreter::getText));
        FxTestSupport.runOnFx(stage::close);
    }

    @Test
    void runInATerminalTabIsAPersistedChoiceOfEachConfiguration(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(
                        new RunConfiguration("Menu", "python", "menu.py", "", "", "", "", "", "", ""),
                        new RunConfiguration("Other", "shell", "o.sh", "", "", "", "", "", "", "")));
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        FxTestSupport.runOnFx(() -> window.show("Menu", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        javafx.scene.control.CheckBox inTerminal =
                (javafx.scene.control.CheckBox) stage.getScene().lookup("#run-config-in-terminal");

        assertFalse(FxTestSupport.callOnFx(inTerminal::isSelected), "the Run console, by default");
        FxTestSupport.runOnFx(() -> inTerminal.setSelected(true));
        assertTrue(persisted(config, "Menu").runInTerminal());
        assertFalse(persisted(config, "Other").runInTerminal());

        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(1));
        assertFalse(FxTestSupport.callOnFx(inTerminal::isSelected), "each configuration has its own");
        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(0));
        assertTrue(FxTestSupport.callOnFx(inTerminal::isSelected));
        FxTestSupport.runOnFx(stage::close);
    }

    @Test
    void addProposesTheActiveScriptAndTheFormShowsOnlyItsFields(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        Path root = dir.resolve("site");
        RunConfiguration script =
                com.editora.run.RunConfigDefaults.forScript("shell", root.resolve("bin/deploy.sh"), root);
        RunConfigurationsWindow window = FxTestSupport.callOnFx(
                () -> new RunConfigurationsWindow(config, () -> null, () -> script, () -> root, () -> {}));
        FxTestSupport.runOnFx(() -> window.show(null, null));
        Stage stage = FxTestSupport.field(window, "stage");
        Button add = FxTestSupport.callOnFx(() -> stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(n ->
                        n instanceof Button b && tr("settings.runConfig.add").equals(b.getText()))
                .map(n -> (Button) n)
                .findFirst()
                .orElseThrow());
        FxTestSupport.runOnFx(add::fire);
        FxTestSupport.drainFx();

        // Add made an empty Java configuration and focused Main class, whatever was open.
        RunConfiguration added = persisted(config, "deploy.sh");
        assertEquals("shell", added.type());
        assertEquals(Path.of("bin", "deploy.sh").toString(), added.target());
        javafx.scene.Node mainClass = stage.getScene().lookup("#run-config-main-class");
        javafx.scene.Node browse = stage.getScene().lookup("#run-config-browse");
        assertFalse(
                FxTestSupport.callOnFx(() -> mainClass.getParent().isVisible()),
                "Java-only rows are hidden, not just greyed");
        assertTrue(FxTestSupport.callOnFx(browse::isVisible), "a script can be browsed for");
        // No label is cut short ("Program arg…", "Working dire…").
        FxTestSupport.runOnFx(() -> {
            stage.setWidth(760);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        });
        java.util.List<String> cut =
                FxTestSupport.callOnFx(() -> stage.getScene().getRoot().lookupAll(".label").stream()
                        .filter(n -> n instanceof javafx.scene.control.Label l && l.isVisible() && l.isTextTruncated())
                        .map(n -> ((javafx.scene.control.Label) n).getText())
                        .toList());
        assertEquals(java.util.List.of(), cut);

        @SuppressWarnings("unchecked")
        ComboBox<String> type = (ComboBox<String>) stage.getScene().lookup("#run-config-type");
        FxTestSupport.runOnFx(() -> type.setValue("make"));
        assertFalse(FxTestSupport.callOnFx(browse::isVisible), "a make target is a name, not a file");
        assertEquals(
                Path.of("tools", "x.py").toString(),
                RunConfigurationsWindow.chosenTarget("python", root.resolve("tools/x.py"), root));
        FxTestSupport.runOnFx(stage::close);
    }

    /**
     * A main class is picked as its .java file: nobody should have to work out {@code test.App} from
     * {@code src/main/java/test/App.java} by hand. A configuration still called "New Configuration" is renamed
     * after the class; one the user named keeps its name.
     */
    @Test
    void browsingForAJavaFileFillsTheFullyQualifiedMainClass(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("project");
        Path app = root.resolve("src/main/java/test/App.java");
        java.nio.file.Files.createDirectories(app.getParent());
        java.nio.file.Files.writeString(app, """
                package test;

                public class App {
                    public static void main(String[] args) {
                        System.out.println("Hello World!");
                    }
                }
                """);
        ConfigManager config = new ConfigManager(dir.resolve("config"));
        config.getWorkspaceState()
                .setRunConfigurations(java.util.List.of(
                        new RunConfiguration(tr("settings.runConfig.newName"), "", "", "", "", ""),
                        new RunConfiguration("Mine", "", "", "", "", "")));
        RunConfigurationsWindow window = FxTestSupport.callOnFx(
                () -> new RunConfigurationsWindow(config, () -> null, () -> null, () -> root, () -> {}));
        FxTestSupport.runOnFx(() -> window.show(tr("settings.runConfig.newName"), null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ListView<RunConfiguration> list = FxTestSupport.field(window, "list");
        javafx.scene.control.TextField name =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-name");
        javafx.scene.control.TextField mainClass =
                (javafx.scene.control.TextField) stage.getScene().lookup("#run-config-main-class");
        javafx.scene.Node browse = stage.getScene().lookup("#run-config-browse-main-class");
        assertTrue(FxTestSupport.callOnFx(browse::isVisible), "a Java configuration can browse for its class");

        FxTestSupport.runOnFx(() -> {
            window.pickMainClass(app, name, mainClass);
            mainClass.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("test.App", persisted(config, "App").mainClass());

        FxTestSupport.runOnFx(() -> list.getSelectionModel().select(1));
        FxTestSupport.runOnFx(() -> {
            window.pickMainClass(app, name, mainClass);
            mainClass.getOnAction().handle(new javafx.event.ActionEvent());
        });
        assertEquals("test.App", persisted(config, "Mine").mainClass(), "a typed name is kept");
        FxTestSupport.runOnFx(stage::close);
    }

    /** Disabled state of: target, main class, module, VM arguments, JDK, program arguments. */
    private static java.util.List<Boolean> disabled(Stage stage) {
        return java.util.stream.Stream.of(
                        "#run-config-target",
                        "#run-config-main-class",
                        "#run-config-module",
                        "#run-config-vm-args",
                        "#run-config-jdk",
                        "#run-config-args")
                .map(id -> stage.getScene().lookup(id).isDisabled())
                .toList();
    }

    @Test
    void runOnListsTheDeploymentsAndSavesTheChoice(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setDeployments(
                        new java.util.ArrayList<>(java.util.List.of(com.editora.config.Deployment.named("dev"))));
        config.getWorkspaceState()
                .setRunConfigurations(new java.util.ArrayList<>(
                        java.util.List.of(new RunConfiguration("Serve", "example.Serve", "", "", "", ""))));
        java.util.List<String> opened = new java.util.ArrayList<>();
        RunConfigurationsWindow window =
                FxTestSupport.callOnFx(() -> new RunConfigurationsWindow(config, () -> null, () -> {}));
        window.setEditDeployments(opened::add);
        FxTestSupport.runOnFx(() -> window.show("Serve", null));
        Stage stage = FxTestSupport.field(window, "stage");
        @SuppressWarnings("unchecked")
        ComboBox<String> runOn = (ComboBox<String>) stage.getScene().lookup("#run-config-run-on");
        assertEquals(
                java.util.List.of("", "dev"), FxTestSupport.callOnFx(() -> java.util.List.copyOf(runOn.getItems())));
        assertEquals("", FxTestSupport.callOnFx(runOn::getValue), "a configuration runs here by default");

        FxTestSupport.runOnFx(() -> runOn.setValue("dev"));
        assertEquals("dev", persisted(config, "Serve").deployment());

        Button edit = (Button) stage.getScene().lookup("#run-config-edit-deployments");
        FxTestSupport.runOnFx(edit::fire);
        assertEquals(java.util.List.of("dev"), opened, "Deployments… opens the one chosen");
        FxTestSupport.runOnFx(stage::close);
    }
}
