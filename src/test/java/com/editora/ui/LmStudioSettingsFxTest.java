package com.editora.ui;

import java.lang.reflect.Field;

import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import com.editora.ai.AiProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("fx")
class LmStudioSettingsFxTest {
    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void switchingAndSyncingProviderFieldsPreservesBothConfigurations() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                var settings = fx.shared.getSettings();
                settings.setAiProvider("anthropic");
                settings.setAiModel("cloud-model");
                settings.setAiEndpoint("https://cloud.example/v1/messages");
                settings.setAiApiKey("cloud-key");
                SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
                // Build just the controls: no Settings window, detection probes, or live requests.
                FxTestSupport.call(window, "buildControls", new Class<?>[] {});
                try {
                    Field built = SettingsWindow.class.getDeclaredField("built");
                    built.setAccessible(true);
                    built.setBoolean(window, true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
                window.syncAiCheck();
                ComboBox<String> providers = FxTestSupport.field(window, "aiProviderCombo");
                TextField model = FxTestSupport.field(window, "aiModelField");
                TextField endpoint = FxTestSupport.field(window, "aiEndpointField");
                TextField token = FxTestSupport.field(window, "aiApiKeyField");
                TextField completion = FxTestSupport.field(window, "aiCompletionModelField");
                assertTrue(providers.getItems().contains("lmstudio"));
                providers.setValue("lmstudio");
                assertEquals("", model.getText());
                assertEquals("", endpoint.getText());
                assertEquals("", token.getText());
                assertFalse(token.getPromptText().contains("ANTHROPIC"));
                model.setText("local/model");
                endpoint.setText("http://localhost:1234");
                token.setText("local-token");
                completion.setText("local/small");
                providers.setValue("anthropic");
                assertEquals("cloud-model", model.getText());
                assertEquals("cloud-key", token.getText());
                assertEquals("https://cloud.example/v1/messages", endpoint.getText());
                assertEquals("local/model", settings.getAiModelFor(AiProvider.LMSTUDIO));
                assertEquals("local/small", settings.getAiCompletionModelFor(AiProvider.LMSTUDIO));
                // Palette commands set Settings first and then synchronize the open controls.
                settings.setAiProvider("lmstudio");
                window.syncAiCheck();
                assertEquals("local/model", model.getText());
                assertEquals("local-token", token.getText());
                assertEquals("http://localhost:1234", endpoint.getText());
                assertEquals("cloud-model", settings.getAiModel());
            });
        }
    }
}
