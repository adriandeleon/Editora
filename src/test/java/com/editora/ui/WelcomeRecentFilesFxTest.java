package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Button;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WelcomeRecentFilesFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void overflowIsCollapsedAfterFiveRowsAndCanBeExpanded(@TempDir Path configDir) throws Exception {
        RecentFiles recents = recents(configDir, 7);

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = pane(recents);
            new Scene(pane, 900, 700);
            pane.applyCss();

            assertEquals(5, pane.lookupAll(".welcome-recent-row").size());
            Button disclosure = (Button) pane.lookup(".welcome-recent-disclosure");
            assertNotNull(disclosure);
            assertTrue(disclosure.getAccessibleText().contains("2"));

            disclosure.fire();
            assertEquals(7, pane.lookupAll(".welcome-recent-row").size());

            disclosure.fire();
            assertEquals(5, pane.lookupAll(".welcome-recent-row").size());
        });
    }

    @Test
    void fiveRecentFilesNeedNoDisclosure(@TempDir Path configDir) throws Exception {
        RecentFiles recents = recents(configDir, 5);

        FxTestSupport.runOnFx(() -> {
            WelcomePane pane = pane(recents);
            new Scene(pane, 900, 700);
            pane.applyCss();
            assertEquals(5, pane.lookupAll(".welcome-recent-row").size());
            assertNull(pane.lookup(".welcome-recent-disclosure"));
        });
    }

    private static RecentFiles recents(Path configDir, int count) throws Exception {
        RecentFiles recents = new RecentFiles(configDir);
        for (int i = count; i >= 1; i--) {
            Path file = Files.createFile(configDir.resolve("file-" + i + ".txt"));
            recents.add(file);
        }
        return recents;
    }

    private static WelcomePane pane(RecentFiles recents) {
        return new WelcomePane(
                new CommandRegistry(),
                new KeymapManager(),
                recents,
                List::of,
                path -> {},
                url -> {},
                () -> true,
                () -> true,
                List::of,
                connection -> {},
                "");
    }
}
