package com.editora.ui;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.ai.AiProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switching the AI provider in Settings must never send one provider's key to another provider's server.
 * The Anthropic and OpenAI-compatible providers used to share one endpoint field, so a switch from a
 * user's OpenAI-compatible server to Anthropic sent the Anthropic key to that server at once (the
 * connection check fires on the switch). Two loopback servers stand in for the two providers; keys are fake.
 */
@Tag("fx")
class AiProviderSwitchKeyFxTest {

    private static final String ANTHROPIC_KEY = "sk-ant-FAKE-anthropic";
    private static final String OPENAI_KEY = "FAKE-openai-token";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A loopback server that records the credential headers of every request it gets. */
    private record Recorder(HttpServer server, List<String> keys) {
        static Recorder start() throws Exception {
            List<String> keys = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                keys.add(String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")) + " | "
                        + ex.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{}".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
            return new Recorder(server, keys);
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        boolean saw(String key) {
            return keys.stream().anyMatch(k -> k.contains(key));
        }
    }

    @Test
    void aProviderSwitchKeepsEachKeyOnItsOwnProvidersServer() throws Exception {
        Recorder openai = Recorder.start();
        Recorder anthropic = Recorder.start();
        String openaiUrl = openai.url("/v1/chat/completions");
        String anthropicUrl = anthropic.url("/v1/messages");
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                var s = fx.shared.getSettings();
                s.setAiEnabled(true);
                s.setAiSupport(true);
                s.setAiEndpointFor(AiProvider.OPENAI, openaiUrl);
                s.setApiKeyFor(AiProvider.OPENAI, OPENAI_KEY);
                s.setAiEndpointFor(AiProvider.ANTHROPIC, anthropicUrl);
                s.setApiKeyFor(AiProvider.ANTHROPIC, ANTHROPIC_KEY);
                s.setAiProvider("openai");
                fx.controller.settingsWindow.show((Stage) FxTestSupport.field(fx.controller, "stage"));
            });
            SettingsWindow sw = fx.controller.settingsWindow;
            ComboBox<String> provider = FxTestSupport.field(sw, "aiProviderCombo");
            TextField endpoint = FxTestSupport.field(sw, "aiEndpointField");

            FxTestSupport.runOnFx(() -> provider.setValue("anthropic"));
            assertEquals(anthropicUrl, FxTestSupport.callOnFx(endpoint::getText), "Anthropic's own endpoint shows");
            await(() -> anthropic.saw(ANTHROPIC_KEY), "the Anthropic check reaches the Anthropic server");

            FxTestSupport.runOnFx(() -> provider.setValue("openai"));
            assertEquals(openaiUrl, FxTestSupport.callOnFx(endpoint::getText), "the OpenAI endpoint comes back");
            int before = openai.keys().size();
            await(() -> openai.keys().size() > before, "the OpenAI check reaches the OpenAI server");

            assertTrue(!openai.saw(ANTHROPIC_KEY), "Anthropic key sent to the OpenAI server: " + openai.keys());
            assertTrue(!anthropic.saw(OPENAI_KEY), "OpenAI key sent to the Anthropic server: " + anthropic.keys());
            assertEquals(anthropicUrl, fx.shared.getSettings().getAiEndpointFor(AiProvider.ANTHROPIC));
            assertEquals(openaiUrl, fx.shared.getSettings().getAiEndpointFor(AiProvider.OPENAI));
        } finally {
            openai.server().stop(0);
            anthropic.server().stop(0);
        }
    }

    private static void await(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            FxTestSupport.drainFx();
            Thread.onSpinWait();
        }
    }
}
