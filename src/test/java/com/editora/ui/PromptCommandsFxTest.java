package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.scene.control.Label;

import com.editora.ai.AiProvider;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.git.GitPullMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette commands that ask for a value. {@code CommandSweepFxTest} runs each of them up to its prompt
 * and dismisses it; here the prompt is answered, and the answer must land in the setting the command names
 * — and in no other.
 */
@Tag("fx")
class PromptCommandsFxTest {

    private FxWindowFixture fx;
    private MainController controller;
    private Settings settings;
    private CommandRegistry registry;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        controller = fx.controller;
        settings = fx.shared.getSettings();
        registry = FxTestSupport.field(controller, "registry");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.dispose();
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private void run(String command) {
        assertTrue(registry.run(command), command + " is a registered command");
        assertTrue(OverlayDriver.showing(controller), command + " asks for its value");
    }

    private record Text(String answer, Supplier<Object> read, Object expected) {}

    @Test
    void aTextPromptWritesItsAnswerToTheSettingItsCommandNames() throws Exception {
        Map<String, Text> prompts = new LinkedHashMap<>();
        prompts.put(
                "maven.setArchetypeCatalogUrl",
                new Text(
                        "https://repo.example.org/archetype-catalog.xml",
                        settings::getMavenArchetypeCatalogUrl,
                        "https://repo.example.org/archetype-catalog.xml"));
        prompts.put("git.setCommand", new Text("/opt/git/bin/git", settings::getGitPath, "/opt/git/bin/git"));
        prompts.put("app.setAuthorName", new Text("  Ada Lovelace ", settings::getAuthorNameRaw, "Ada Lovelace"));
        prompts.put("search.setRipgrepCommand", new Text("/opt/rg", settings::getRipgrepCommand, "/opt/rg"));
        prompts.put("mermaid.setMmdcCommand", new Text("/opt/mmdc", settings::getMmdcPath, "/opt/mmdc"));
        prompts.put("mermaid.setMaidCommand", new Text("/opt/maid", settings::getMaidPath, "/opt/maid"));
        prompts.put(
                "plugins.setRegistryUrl",
                new Text(
                        "https://plugins.example.org/index.json",
                        settings::getPluginRegistryUrl,
                        "https://plugins.example.org/index.json"));
        prompts.put("diagram.setDotCommand", new Text("/opt/dot", settings::getDotPath, "/opt/dot"));
        prompts.put(
                "diagram.setPlantumlCommand", new Text("/opt/plantuml", settings::getPlantumlPath, "/opt/plantuml"));
        prompts.put("typst.setCommand", new Text("/opt/typst", settings::getTypstPath, "/opt/typst"));
        prompts.put("agent.setCommand", new Text("/opt/claude-acp", settings::getAgentCommand, "/opt/claude-acp"));
        prompts.put("agent.setLmstudioCommand", new Text("/opt/lms", settings::getLmstudioAgentCommand, "/opt/lms"));
        prompts.put("ai.setModel", new Text("model-x", () -> settings.getAiModelFor(provider()), "model-x"));
        prompts.put(
                "ai.setCompletionModel",
                new Text("small-x", () -> settings.getAiCompletionModelFor(provider()), "small-x"));
        prompts.put(
                "ai.setEndpoint",
                new Text(
                        "http://127.0.0.1:9/v1/messages",
                        () -> settings.getAiEndpointFor(provider()),
                        "http://127.0.0.1:9/v1/messages"));

        FxTestSupport.runOnFx(() -> {
            List<String> before = new ArrayList<>();
            prompts.values().forEach(p -> before.add(String.valueOf(p.read().get())));
            int i = 0;
            for (Map.Entry<String, Text> e : prompts.entrySet()) {
                String command = e.getKey();
                Text prompt = e.getValue();
                run(command);
                assertEquals(
                        before.get(i), OverlayDriver.promptText(controller), command + " opens on the value in force");
                OverlayDriver.answer(controller, prompt.answer());
                assertFalse(OverlayDriver.showing(controller), command);
                assertEquals(prompt.expected(), prompt.read().get(), command);
                assertEquals(
                        tr(
                                "status.settingChanged",
                                tr("command." + command),
                                prompt.answer().trim()),
                        echo(),
                        command);
                i++;
            }
            // Each answer went to its own setting: the earlier ones were not overwritten by the later.
            prompts.forEach((command, prompt) ->
                    assertEquals(prompt.expected(), prompt.read().get(), command));
        });
    }

    private AiProvider provider() {
        return AiProvider.from(settings.getAiProvider());
    }

    private record Number(String answer, Supplier<Integer> read, int expected) {}

    @Test
    void aNumberPromptWritesItsAnswerClampedToWhatTheSettingAllows() throws Exception {
        Map<String, Number> prompts = new LinkedHashMap<>();
        prompts.put("appearance.setFontSize", new Number("18", settings::getFontSize, 18));
        prompts.put("file.setAutoSaveDelay", new Number("5", settings::getAutoSaveDelayMillis, 5000));
        prompts.put("editor.setLargeFileThreshold", new Number("5000", settings::getLargeFileThreshold, 5000));
        prompts.put("git.setAutoFetchInterval", new Number("25", settings::getGitAutoFetchMinutes, 25));
        // Raising a Local History limit deletes nothing, so nothing is asked before it is applied.
        prompts.put("history.setMaxPerFile", new Number("75", settings::getHistoryMaxPerFile, 75));
        prompts.put("history.setMaxAgeDays", new Number("400", settings::getHistoryMaxAgeDays, 400));
        prompts.put("history.setMaxTotalMb", new Number("900", settings::getHistoryMaxTotalMb, 900));

        FxTestSupport.runOnFx(() -> prompts.forEach((command, prompt) -> {
            int before = prompt.read().get();
            run(command);
            OverlayDriver.answer(controller, "not a number");
            assertEquals(before, prompt.read().get(), command + " refuses what is not a number");
            assertEquals(tr("status.setting.invalidNumber", "not a number"), echo(), command);

            run(command);
            OverlayDriver.answer(controller, " " + prompt.answer() + " ");
            assertEquals(prompt.expected(), prompt.read().get(), command);
        }));
        FxTestSupport.runOnFx(() -> {
            run("appearance.setFontSize");
            OverlayDriver.answer(controller, "100000");
            assertEquals(Settings.MAX_FONT_SIZE, settings.getFontSize(), "above the range is the top of it");
            run("appearance.setFontSize");
            OverlayDriver.answer(controller, "-4");
            assertEquals(Settings.MIN_FONT_SIZE, settings.getFontSize());
        });
    }

    @Test
    void theChoicePromptsWriteTheChosenId() throws Exception {
        FxTestSupport.runOnFx(() -> {
            run("ai.setProvider");
            assertEquals(AiProvider.ids(), OverlayDriver.choices(controller));
            OverlayDriver.pick(controller, AiProvider.LMSTUDIO.id());
            assertEquals(AiProvider.LMSTUDIO.id(), settings.getAiProvider());
            assertEquals(
                    tr(
                            "status.settingChanged",
                            tr("command.ai.setProvider"),
                            tr("settings.ai.provider." + AiProvider.LMSTUDIO.id())),
                    echo());

            GitPullMode last = GitPullMode.values()[GitPullMode.values().length - 1];
            run("git.setPullMode");
            assertEquals(
                    GitPullMode.values().length,
                    OverlayDriver.choices(controller).size());
            OverlayDriver.pick(controller, last.id());
            assertEquals(last.id(), settings.getGitPullMode());
        });
    }
}
