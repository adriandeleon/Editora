package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;

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
}
