package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-time report of what the config could not read: what was reset and where the original went. */
class ConfigLoadMessagesTest {

    private static final Path FILE = Path.of("cfg", "settings.json");
    private static final Path BACKUP = Path.of("cfg", "settings.json.corrupt.bak");

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void skippedValuesAreCountedNamedAndPointToTheBackup() {
        ConfigLoadProblem two = new ConfigLoadProblem(
                FILE, ConfigLoadProblem.Kind.VALUES_SKIPPED, List.of("showMinimap", "tabSize"), BACKUP);
        assertEquals(
                "Could not read 2 values in settings.json; reset to the default: showMinimap, tabSize"
                        + " — backup at settings.json.corrupt.bak",
                ConfigLoadMessages.describe(two, false));

        ConfigLoadProblem one =
                new ConfigLoadProblem(FILE, ConfigLoadProblem.Kind.VALUES_SKIPPED, List.of("showMinimap"), BACKUP);
        assertTrue(ConfigLoadMessages.describe(one, false).startsWith("Could not read 1 value in settings.json;"));
    }

    @Test
    void aLongListOfNamesIsElided() {
        List<String> names = IntStream.range(0, ConfigLoadMessages.MAX_NAMES + 3)
                .mapToObj(i -> "k" + i)
                .toList();
        String message = ConfigLoadMessages.describe(
                new ConfigLoadProblem(FILE, ConfigLoadProblem.Kind.VALUES_SKIPPED, names, BACKUP), false);
        assertTrue(message.contains("Could not read 11 values"), message);
        assertTrue(message.contains("k7, … — backup at"), message);
    }

    @Test
    void aFileThatCouldNotBeBackedUpSaysItWillNotBeSaved() {
        ConfigLoadProblem newer = new ConfigLoadProblem(FILE, ConfigLoadProblem.Kind.NEWER_VERSION, List.of(), null);
        assertEquals(
                "settings.json was written by a newer version of Editora; defaults were loaded"
                        + " — no backup could be made, so settings.json will not be saved this session",
                ConfigLoadMessages.describe(newer, true));

        ConfigLoadProblem unreadable =
                new ConfigLoadProblem(FILE, ConfigLoadProblem.Kind.UNREADABLE, List.of(), BACKUP);
        assertEquals(
                "Could not read settings.json; defaults were loaded — backup at settings.json.corrupt.bak",
                ConfigLoadMessages.describe(unreadable, false));
    }
}
