package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import com.editora.process.ConfiguredCommand;
import com.editora.run.ProgramArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1): whether a configured command with a space names a file is never looked at on the FX thread.
 * A value nothing looked at answers "not a file" there, is looked at behind, and its listeners build the command line
 * again; a value the settings' arrival looked at ({@link ConfiguredCommand#prepareAllIn}) answers from memory.
 */
@Tag("fx")
class ConfiguredCommandOffFxTest {

    @TempDir
    Path dir;

    private final List<String> onFx = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void stop() {
        BlockingSites.listenForTest(null);
    }

    private void listen() {
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
    }

    @Test
    void aColdValueIsTokenizedOnFxThenLearnedOffItAndBuiltAgain() throws Exception {
        Path tool = Files.writeString(
                Files.createDirectories(dir.resolve("My Tools")).resolve("git"), "#!/bin/sh\n");
        String raw = tool.toString();
        List<String> heard = new CopyOnWriteArrayList<>();
        Consumer<String> listener = heard::add; // held here: the listener list keeps it weakly
        ConfiguredCommand.onLearned(listener);
        listen();

        List<String> first = FxTestSupport.callOnFx(() -> ConfiguredCommand.tokens(raw, "git"));
        assertTrue(first.size() > 1, "unknown yet: taken as a command line, as a value naming no file is");
        SettingsRig.awaitFx("the look behind", () -> heard.contains(raw));
        assertEquals(List.of(raw), FxTestSupport.callOnFx(() -> ConfiguredCommand.tokens(raw, "git")));
        assertEquals(List.of(raw), FxTestSupport.callOnFx(() -> ProgramArgs.commandLine(raw)));
        assertEquals(List.of(), onFx, "nothing looked at on the FX thread");
    }

    @Test
    void aValueTheSettingsArrivalLookedAtAnswersFromMemory() throws Exception {
        Path tool = Files.writeString(
                Files.createDirectories(dir.resolve("Other Tools")).resolve("dot"), "x");
        String raw = tool.toString();
        var settings = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("dotPath", raw)
                .put("plain", "word");
        ConfiguredCommand.prepareAllIn(settings); // off the FX thread, as the prefetch does
        listen();
        assertEquals(List.of(raw), FxTestSupport.callOnFx(() -> ProgramArgs.commandLine(raw)));
        assertEquals(List.of(), onFx);
    }
}
