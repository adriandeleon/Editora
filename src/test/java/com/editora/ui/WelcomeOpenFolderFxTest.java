package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Hyperlink;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression (r/JavaFX dry run, 2026-11-27): the Welcome page's Open Folder opened the typed folder prompt while the
 * toolbar's folder button opened the system's dialog. A click runs the dialog, as Open File's does; the chord shown
 * is still the prompt's.
 */
@Tag("fx")
class WelcomeOpenFolderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void clickingOpenFolderRunsTheSystemDialogAndShowsTheFindersChord(@TempDir Path dir) throws Exception {
        List<String> ran = new ArrayList<>();
        String[] chord = {null};
        FxTestSupport.runOnFx(() -> {
            CommandRegistry registry = new CommandRegistry();
            registry.register(Command.of("project.open", () -> ran.add("project.open")));
            registry.register(Command.of("project.openDialog", () -> ran.add("project.openDialog")));
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed("emacs");
            chord[0] = keymap.displayChord("project.open");
            WelcomePane pane = new WelcomePane(
                    registry,
                    keymap,
                    new RecentFiles(dir),
                    List::of,
                    p -> {},
                    url -> {},
                    () -> true,
                    () -> true,
                    List::of,
                    c -> {},
                    "");
            new javafx.scene.Scene(pane, 1200, 900);
            pane.refresh();
            pane.applyCss();
            pane.layout();
            Hyperlink open = pane.lookupAll(".welcome-action").stream()
                    .map(Hyperlink.class::cast)
                    .filter(h -> h.getText().equals(tr("welcome.openFolder")))
                    .findFirst()
                    .orElseThrow();
            open.fire();
            javafx.scene.Node row = open.getParent();
            String shown = row.lookupAll(".welcome-key").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .findFirst()
                    .orElse(null);
            assertEquals(chord[0], shown, "the finder's chord, to learn");
        });
        assertEquals(List.of("project.openDialog"), ran, "a click opens the system's folder dialog");
    }
}
