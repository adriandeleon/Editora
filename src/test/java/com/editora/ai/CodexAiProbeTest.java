package com.editora.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in smoke test using the installed adapter and current Codex login. */
@Tag("probe")
@EnabledIfSystemProperty(named = "editora.ai.codex.probe", matches = "true")
class CodexAiProbeTest {
    @Test
    void generatesUsingExistingLogin() {
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<String> stop = new AtomicReference<>();
        StringBuilder answer = new StringBuilder();
        new CodexAiClient()
                .run(
                        List.of("codex-acp"),
                        "",
                        "Reply with exactly EDITORA_CODEX_OK. Do not use any tools.",
                        Duration.ofSeconds(90),
                        () -> false,
                        new AiClient.Listener() {
                            @Override
                            public void onText(String text) {
                                answer.append(text);
                            }

                            @Override
                            public void onDone(String reason) {
                                stop.set(reason);
                            }

                            @Override
                            public void onError(String message) {
                                error.set(message);
                            }
                        });
        assertNull(error.get());
        assertEquals("end_turn", stop.get());
        assertEquals("EDITORA_CODEX_OK", answer.toString().strip());
    }
}
