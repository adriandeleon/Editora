package com.editora.ai;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link AiClient} credential guard (#481): the API key is never put on the wire in cleartext. When a key
 * would be attached to a non-loopback {@code http://} endpoint the client refuses <em>before dialing</em>
 * (the message uses TEST-NET addresses so the guard, not a failed connection, is what's under test).
 */
class AiClientCleartextGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode body = mapper.createObjectNode();

    @Test
    void checkRefusesAnAnthropicKeyOverHttpToARemoteHost() {
        // The reported case: point the Anthropic provider at a plain-http remote host.
        String result = new AiClient()
                .check(
                        AiProvider.ANTHROPIC,
                        "http://198.51.100.9:8732/v1/messages",
                        "sk-ant-api03-SECRET-DO-NOT-LEAK",
                        body,
                        Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.contains("cleartext"), result);
        assertTrue(result.contains("198.51.100.9"), result);
    }

    @Test
    void checkRefusesAnOpenAiBearerKeyOverHttpToARemoteHost() {
        String result = new AiClient()
                .check(
                        AiProvider.OPENAI,
                        "http://openrouter.example:9000/v1/chat/completions",
                        "sk-openrouter-SECRET",
                        body,
                        Duration.ofSeconds(5));
        assertNotNull(result);
        assertTrue(result.contains("cleartext"), result);
    }

    @Test
    void checkDoesNotRefuseAKeylessLocalOpenAiServer() {
        // Keyless local inference over http loopback is the intended path — no credential, nothing to leak,
        // so the guard must not fire (it fails with a plain connection error instead, not "cleartext").
        String result = new AiClient()
                .check(AiProvider.OPENAI, "http://127.0.0.1:1/v1/chat/completions", "", body, Duration.ofSeconds(3));
        // A connection error is expected (nothing is listening); it must not be the cleartext refusal.
        if (result != null) {
            assertTrue(!result.contains("cleartext"), result);
        }
    }

    @Test
    void streamRefusesAndNeverSendsWhenTheKeyWouldCrossTheNetworkInCleartext() {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Boolean> sawText = new AtomicReference<>(false);
        new AiClient()
                .stream(
                        AiProvider.ANTHROPIC,
                        "http://198.51.100.9:8732/v1/messages",
                        "sk-ant-api03-SECRET",
                        body,
                        () -> false,
                        new AiClient.Listener() {
                            @Override
                            public void onText(String delta) {
                                sawText.set(true);
                            }

                            @Override
                            public void onDone(String stopReason) {}

                            @Override
                            public void onError(String message) {
                                error.set(message);
                            }
                        });
        assertNotNull(error.get(), "the stream must refuse via onError");
        assertTrue(error.get().contains("cleartext"), error.get());
        assertTrue(!sawText.get(), "no request should have been sent");
    }

    @Test
    void httpsRemoteWithAKeyIsAllowedThroughTheGuard() {
        // https is safe, so the guard must not refuse it (this dials, so a real connection error is fine —
        // it just must not be the cleartext refusal). TEST-NET so nothing real is contacted.
        String result = new AiClient()
                .check(
                        AiProvider.ANTHROPIC,
                        "https://198.51.100.9:8/v1/messages",
                        "sk-ant",
                        body,
                        Duration.ofSeconds(3));
        // A 200 is impossible against TEST-NET, so a non-null connection error is expected — never the refusal.
        assertNotNull(result);
        assertTrue(!result.contains("cleartext"), result);
    }
}
