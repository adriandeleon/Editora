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

/**
 * A command-line {@code FILE} must be the tab on screen from the window's <b>first frame</b>, never after the
 * session restore has shown something else first.
 *
 * <p>The CLI targets used to ride a runnable that fires only once the pulse-paced restore has finished, so
 * opening a file from the desktop (GNOME Files → "Editora Expert Mode", which passes the path as argv) showed
 * the <em>session's</em> active file — fully rendered, LSP starting — for a second or two before the
 * requested file replaced it. {@code openInitialBuffer} now front-loads the requested file: it becomes the
 * selected tab and is filled first, with the rest of the session restoring behind it.
 *
 * <p>The assertions run inside the same FX runnable as the build, so no queued {@code Platform.runLater} has
 * executed yet — asserting after the window settles would pass even with the old deferred code.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliTargetFirstFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The title of the selected tab at build time, before any deferred startup work has run. */
    private String selectedTabOnFirstFrame(Path configDir, Path cliFile) throws Exception {
        List<String> captured = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(cliFile, 0, 0)), controller -> {
                    TabPane tabPane = FxTestSupport.field(controller, "tabPane");
                    Tab sel = tabPane.getSelectionModel().getSelectedItem();
                    captured.add(sel == null ? "<none>" : tabTitle(sel));
                });
        try {
            return captured.get(0);
        } finally {
            fx.dispose();
        }
    }

    /** Tabs carry a graphic header (icon + title label), so the title isn't in {@code Tab.getText()}. */
    private static String tabTitle(Tab tab) {
        Object content = tab.getUserData();
        if (content instanceof com.editora.editor.TabContent tc) {
            return tc.title();
        }
        return tab.getText();
    }

    /** Seeds a session whose open files are {@code names}, with the first one active. */
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

    @Test
    void requestedFileOutsideTheSessionIsSelectedBeforeAnyRestoredTab() throws Exception {
        Path dir = Files.createTempDirectory("editora-cli-target");
        Path sessionFile = Files.writeString(dir.resolve("session.py"), "print('session')\n");
        Path wanted = Files.writeString(dir.resolve("wanted.typ"), "= Wanted\n");
        seedSession(dir, sessionFile);

        assertEquals("wanted.typ", selectedTabOnFirstFrame(dir, wanted));
    }

    @Test
    void requestedFileAlreadyInTheSessionIsSelectedOverTheSessionsActiveFile() throws Exception {
        Path dir = Files.createTempDirectory("editora-cli-target");
        Path sessionActive = Files.writeString(dir.resolve("session.py"), "print('session')\n");
        Path wanted = Files.writeString(dir.resolve("wanted.typ"), "= Wanted\n");
        // Both files are in the session and session.py is the *active* one — the CLI target still wins.
        seedSession(dir, sessionActive, wanted);

        assertEquals("wanted.typ", selectedTabOnFirstFrame(dir, wanted));
    }
}
