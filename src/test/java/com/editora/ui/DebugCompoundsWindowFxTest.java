package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.RunCompound;
import com.editora.config.RunConfiguration;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The compounds' editor: a new compound, its configurations ticked, Stop All, saved at each change, debugged. */
@Tag("fx")
class DebugCompoundsWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    private static RunConfiguration config(String name) {
        return new RunConfiguration(name, "java", "", "demo." + name, "", "", "", "", "", "", "");
    }

    @Test
    void aCompoundIsMadeTickedSavedAndDebugged(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState()
                .setRunConfigurations(new ArrayList<>(List.of(config("Server"), config("Client"), config("Tool"))));
        List<RunCompound> debugged = new ArrayList<>();
        DebugCompoundsWindow window = FxTestSupport.callOnFx(() -> new DebugCompoundsWindow(config, debugged::add));
        FxTestSupport.runOnFx(() -> window.show(null, null)); // New Compound…
        Stage stage = window.stage();
        assertEquals(1, config.getWorkspaceState().getRunCompounds().size(), "a new one, saved at once");
        assertEquals(
                tr("debug.compound.newName"),
                config.getWorkspaceState().getRunCompounds().getFirst().name());

        TextField name = (TextField) stage.getScene().lookup("#compound-name");
        FxTestSupport.runOnFx(() -> {
            name.setText("Both");
            name.getOnAction().handle(null);
            window.tick("Client", true);
            window.tick("Server", true);
            window.stopAllBox().fire();
        });
        assertEquals(
                List.of(new RunCompound("Both", List.of("Server", "Client"), true)),
                config.getWorkspaceState().getRunCompounds(),
                "in the order the configurations are listed, whatever order they were ticked in");

        Button debug = (Button) stage.getScene().lookup("#compound-debug");
        FxTestSupport.runOnFx(debug::fire);
        assertEquals(List.of(new RunCompound("Both", List.of("Server", "Client"), true)), debugged);

        // A configuration renamed since still shows, ticked, so it can be unticked.
        config.getWorkspaceState().setRunConfigurations(new ArrayList<>(List.of(config("Client"))));
        FxTestSupport.runOnFx(() -> window.show("Both", null));
        FxTestSupport.runOnFx(() -> window.tick("Server", false));
        assertEquals(
                List.of("Client"),
                config.getWorkspaceState().getRunCompounds().getFirst().configurations());

        Button remove = (Button) stage.getScene().lookup("#compound-remove");
        FxTestSupport.runOnFx(remove::fire);
        assertTrue(config.getWorkspaceState().getRunCompounds().isEmpty());
        FxTestSupport.runOnFx(stage::close);
    }

    private static String tr(String key) {
        return Messages.tr(key);
    }
}
