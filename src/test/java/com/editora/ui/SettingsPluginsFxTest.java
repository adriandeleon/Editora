package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → Plugins in a real window, with installed (declarative, never enabled) plugins. */
@Tag("fx")
class SettingsPluginsFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A config folder holding one well-formed plugin and one with a broken plugin.json. */
    static Path configWithPlugins() throws Exception {
        Path cfg = Files.createTempDirectory("editora-plugins-test");
        Path hello = Files.createDirectories(cfg.resolve("plugins/hello-fake"));
        Files.writeString(
                hello.resolve("plugin.json"),
                "{\"id\":\"hello-fake\",\"name\":\"Hello Fake\",\"version\":\"0.1\",\"commands\":[]}");
        Path broken = Files.createDirectories(cfg.resolve("plugins/broken-fake"));
        Files.writeString(broken.resolve("plugin.json"), "{ not json");
        return cfg;
    }

    SettingsWindow showPlugins() throws Exception {
        return showPlugins(shared -> {});
    }

    SettingsWindow showPlugins(java.util.function.Consumer<com.editora.config.SharedConfig> before) throws Exception {
        fx = FxWindowFixture.create(configWithPlugins(), before);
        SettingsWindow w = fx.controller.settingsWindow;
        FxTestSupport.runOnFx(() -> w.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        return w;
    }

    static List<CheckBox> pluginRows(SettingsWindow w) {
        VBox list = FxTestSupport.field(w, "pluginListBox");
        return SettingsRemoteSitesFxTest.all(list, CheckBox.class, new ArrayList<>());
    }

    /** The installed plugins' checkboxes used to stay greyed after "Enable plugins" was switched on here. */
    @Test
    void switchingPluginsOnMakesTheInstalledPluginsTickable() throws Exception {
        SettingsWindow w = showPlugins();
        FxTestSupport.runOnFx(() -> {
            CheckBox master = FxTestSupport.field(w, "pluginCheck");
            List<CheckBox> rows = pluginRows(w);
            assertEquals(2, rows.size());
            assertFalse(master.isSelected());
            assertTrue(rows.stream().allMatch(CheckBox::isDisabled), "greyed while plugins are off");
            master.setSelected(true);
            assertTrue(rows.stream().noneMatch(CheckBox::isDisabled), "tickable once they are on");
            master.setSelected(false);
            assertTrue(rows.stream().allMatch(CheckBox::isDisabled));
        });
    }

    /** http:// and plain text used to be saved and only failed at Browse, in untranslated English. */
    @Test
    void aRegistryThatIsNotAnHttpsAddressIsRefusedWithTheReason() throws Exception {
        SettingsWindow w = showPlugins();
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField reg = FxTestSupport.field(w, "pluginRegistryField");
            reg.setText("http://registry.example.invalid/index.json");
            reg.fireEvent(new javafx.event.ActionEvent());
            assertEquals("", fx.shared.getSettings().getPluginRegistryUrlRaw(), "the built-in registry stays");
            assertTrue(reg.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            assertEquals(
                    Messages.tr("settings.plugins.registryNotHttps"),
                    reg.getTooltip().getText());
            reg.setText("https://registry.example.invalid/index.json");
            reg.fireEvent(new javafx.event.ActionEvent());
            assertFalse(reg.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER));
            assertEquals(
                    "https://registry.example.invalid/index.json",
                    fx.shared.getSettings().getPluginRegistryUrlRaw());
        });
    }

    /**
     * Switching a plugin only takes effect at the next start, which the row did not say; and a broken
     * plugin.json showed Jackson's internals ("Source: REDACTED (`StreamReadFeature…`").
     */
    @Test
    void aSwitchedPluginSaysItNeedsARestartAndABrokenOneSaysWhereItBroke() throws Exception {
        SettingsWindow w = showPlugins(shared -> {
            shared.getSettings().setPluginSupport(true);
            shared.getPluginStore().setEnabled("hello-fake", true);
        });
        FxTestSupport.runOnFx(() -> {
            VBox list = FxTestSupport.field(w, "pluginListBox");
            javafx.scene.control.Label pill =
                    SettingsRemoteSitesFxTest.all(list, javafx.scene.control.Label.class, new ArrayList<>()).stream()
                            .filter(l ->
                                    Messages.tr("settings.plugins.restartPill").equals(l.getText()))
                            .findFirst()
                            .orElseThrow();
            CheckBox hello = pluginRows(w).stream()
                    .filter(c -> c.getText().startsWith("Hello Fake"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(pill.isVisible() && pill.getParent() == hello.getParent(), "nothing switched yet");
            hello.setSelected(false); // switching off asks nothing
            assertFalse(fx.shared.getPluginStore().isEnabled("hello-fake"));
            assertTrue(
                    SettingsRemoteSitesFxTest.all(
                                    hello.getParent(), javafx.scene.control.Label.class, new ArrayList<>())
                            .stream()
                            .anyMatch(l -> l.isVisible()
                                    && Messages.tr("settings.plugins.restartPill")
                                            .equals(l.getText())));
            String error =
                    SettingsRemoteSitesFxTest.all(list, javafx.scene.control.Label.class, new ArrayList<>()).stream()
                            .map(javafx.scene.control.Label::getText)
                            .filter(t -> t != null && t.contains("plugin.json"))
                            .findFirst()
                            .orElseThrow();
            // S3: said in the user's language, with where the file stops being valid.
            assertEquals(Messages.tr("settings.plugins.error.manifestInvalidAt", "1", "3"), error);
        });
    }
}
