package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;

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
        FxTestSupport.runOnFx(stage::close);
    }

    @Test
    void runConfigurationsAreNotASettingsCategory() throws Exception {
        Class<?> category = Class.forName("com.editora.ui.SettingsWindow$Category");
        assertFalse(Arrays.stream(category.getEnumConstants())
                .anyMatch(value -> value.toString().equals("RUN_CONFIGS")));
    }
}
