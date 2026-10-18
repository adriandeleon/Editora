package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.Deployment;
import com.editora.config.RunConfiguration;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class DeploymentsWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @Test
    void addEditAndRenameADeploymentAndItsConfigurationsFollow(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.getWorkspaceState().setDeployments(new ArrayList<>(List.of(Deployment.named("dev"))));
        RunConfiguration onDev =
                new RunConfiguration("Serve", "shell", "s.sh", "", "", "", "", "", "", "", "").withDeployment("dev");
        RunConfiguration here = new RunConfiguration("Local", "shell", "s.sh", "", "", "", "", "", "", "", "");
        config.getWorkspaceState().setRunConfigurations(new ArrayList<>(List.of(onDev, here)));
        List<String> deployed = new ArrayList<>();
        int[] changed = {0};
        DeploymentsWindow window = FxTestSupport.callOnFx(
                () -> new DeploymentsWindow(config, List::of, deployed::add, () -> changed[0]++));
        FxTestSupport.runOnFx(() -> window.show("dev", null));
        Stage stage = window.stage();
        TextField name = (TextField) stage.getScene().lookup("#deploy-name");
        TextField host = (TextField) stage.getScene().lookup("#deploy-host");
        TextField root = (TextField) stage.getScene().lookup("#deploy-root");

        FxTestSupport.runOnFx(() -> {
            host.setText("box.example.com");
            host.getOnAction().handle(null);
            root.setText("/srv/app");
            root.getOnAction().handle(null);
            name.setText("staging");
            name.getOnAction().handle(null);
        });
        Deployment saved = config.getWorkspaceState().getDeployments().get(0);
        assertEquals("staging", saved.name());
        assertEquals("box.example.com", saved.host());
        assertEquals("/srv/app", saved.remoteRoot());
        assertEquals(
                "staging",
                config.getWorkspaceState().getRunConfigurations().get(0).deployment());
        assertEquals(
                "", config.getWorkspaceState().getRunConfigurations().get(1).deployment());
        assertTrue(changed[0] >= 3, "every saved change is told");

        Button add = (Button) stage.getScene().lookup("#deploy-add");
        FxTestSupport.runOnFx(add::fire);
        assertEquals(2, config.getWorkspaceState().getDeployments().size());

        FxTestSupport.runOnFx(() -> window.show("staging", null));
        Button now = (Button) stage.getScene().lookup("#deploy-now");
        FxTestSupport.runOnFx(now::fire);
        assertEquals(List.of("staging"), deployed);
        FxTestSupport.runOnFx(stage::close);
    }
}
