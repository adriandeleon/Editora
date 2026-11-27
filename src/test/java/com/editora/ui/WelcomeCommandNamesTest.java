package com.editora.ui;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression (r/JavaFX dry run, 2026-11-27): the Welcome page called {@code project.open} "Open Folder as a
 * Project…" while the palette names it "Project: Open Folder…", so typing what the Welcome page said into the
 * palette ranked four other commands first. The Welcome row reads as the command's own name, in every language.
 */
class WelcomeCommandNamesTest {

    @Test
    void theOpenFolderRowIsTheCommandsOwnNameInEveryCatalog() throws Exception {
        for (String suffix : List.of("", "_es", "_pt", "_de", "_fr", "_it")) {
            Properties p = catalog(suffix);
            String title = p.getProperty("command.project.open");
            String row = p.getProperty("welcome.openFolder");
            assertNotNull(title, suffix);
            assertEquals(title.substring(title.indexOf(':') + 1).strip(), row, "messages" + suffix);
        }
    }

    private static Properties catalog(String suffix) throws Exception {
        Properties p = new Properties();
        try (InputStream in = WelcomeCommandNamesTest.class.getResourceAsStream(
                "/com/editora/i18n/messages" + suffix + ".properties")) {
            assertNotNull(in, suffix);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }
}
