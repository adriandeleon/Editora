package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --no-session} opens only the command line's files, and must leave the saved session alone.
 *
 * <p>It exists for a file-manager "Open With" launch, where restoring the last session's tabs is pure cost:
 * every restored file is a buffer to load and highlight and (once looked at) a language server to run, for
 * files the user didn't ask to see. Measured on an 8-file session opening one file, the restore accounted for
 * 4 extra server processes, roughly double the CPU Editora burns while starting, and ~225 MB.
 *
 * <p>The second test is the important one: because the window's tab list is <em>not</em> the session, writing
 * it back on quit would replace the user's saved tabs with the single file they opened from the file manager.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NoSessionStartupFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Path seedSession(Path dir, Path... files) throws Exception {
        StringBuilder open = new StringBuilder();
        for (Path f : files) {
            if (!open.isEmpty()) {
                open.append(',');
            }
            open.append("{\"path\":\"").append(f.toAbsolutePath()).append("\"}");
        }
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\"" + files[0].toAbsolutePath()
                        + "\"}");
        return dir;
    }

    /** The file names the saved session lists as open, in order. */
    private static List<String> savedOpenFiles(Path dir) throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(dir.resolve("workspace-state.json").toFile());
        List<String> names = new ArrayList<>();
        for (var f : root.path("openFiles")) {
            names.add(Path.of(f.path("path").asText()).getFileName().toString());
        }
        return names;
    }

    @Test
    void opensOnlyTheRequestedFileAndLeavesTheSavedSessionIntact() throws Exception {
        Path dir = Files.createTempDirectory("editora-no-session");
        Path a = Files.writeString(dir.resolve("a.py"), "print('a')\n");
        Path b = Files.writeString(dir.resolve("b.py"), "print('b')\n");
        Path wanted = Files.writeString(dir.resolve("wanted.typ"), "= Wanted\n");
        seedSession(dir, a, b);
        String sessionBefore = Files.readString(dir.resolve("workspace-state.json"));

        List<String> titles = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, true, false, List.of(new MainController.OpenTarget(wanted, 0, 0)), true, controller -> {
                    TabPane tabPane = FxTestSupport.field(controller, "tabPane");
                    for (Tab t : tabPane.getTabs()) {
                        Object content = t.getUserData();
                        titles.add(content instanceof com.editora.editor.TabContent tc ? tc.title() : t.getText());
                    }
                });

        // Only the requested file is open — a.py / b.py were never restored.
        assertEquals(List.of("wanted.typ"), titles);

        // Quitting a --no-session run must not replace the saved tab list with this window's single tab.
        // Driven directly because a programmatic stage.close() doesn't fire the quit handler (and dispose()
        // deletes the config dir out from under us). The file itself may still be rewritten by an ordinary
        // config save (window bounds, tool windows) — what must survive is the open-file list, so that's what
        // this asserts rather than byte-equality.
        assertTrue(sessionBefore.contains("a.py"), "sanity: the seeded session really listed a.py");
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "persistSession"));
        fx.shared.flushWrites();

        assertEquals(List.of("a.py", "b.py"), savedOpenFiles(dir));
        fx.dispose();
    }
}
