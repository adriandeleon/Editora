package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A settings file with a value that cannot be read tells the user so, once, in the first window: the status
 * bar shows it and the message log keeps it flagged. It used to load silently with every later setting reset.
 */
@Tag("fx")
class ConfigLoadProblemReportFxTest {

    @Test
    void theFirstWindowReportsValuesThatCouldNotBeRead() throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("editora-fx-config-problem");
        Files.writeString(
                dir.resolve("settings.json"),
                "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + ", \"showMinimap\": \"yes\", \"tabSize\": 8}");

        try (FxWindowFixture fx = FxWindowFixture.create(dir, false, false, false, List.of(), c -> {})) {
            assertEquals(8, fx.shared.getSettings().getTabSize(), "the setting after the bad one was kept");
            FxTestSupport.drainFx(); // the report is deferred past startup's own status messages

            String expected = "Could not read 1 value in settings.json; reset to the default: showMinimap"
                    + " — backup at settings.json.corrupt.bak";
            List<MessageLog.Entry> errors = FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                MessageLog log = FxTestSupport.field(statusBar, "messageLog");
                return log.entries().stream()
                        .filter(e -> e.severity() == MessageLog.Severity.ERROR)
                        .toList();
            });
            assertEquals(1, errors.size(), "reported once, as an error so it stays flagged: " + errors);
            assertEquals(expected, errors.get(0).text());
            assertTrue(Files.exists(dir.resolve("settings.json.corrupt.bak")));
            assertTrue(fx.shared.takeLoadProblems().isEmpty(), "a later window has nothing left to repeat");
        }
    }
}
