package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Export Configuration command of a test window writes its zip to the window's own temporary folder, never
 * to the real home directory. The command sweep runs {@code config.export} in every state, and each run used
 * to leave an {@code editora-fx-test…-config-….zip} in the developer's home.
 */
@Tag("fx")
class ConfigExportFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void theExportCommandOfATestWindowLeavesNothingInTheHomeDirectory() throws Exception {
        fx = FxWindowFixture.create();
        Path home = Path.of(System.getProperty("user.home"));
        String prefix = fx.configDir.getFileName() + "-config-"; // ConfigExporter.zipName, for this config dir

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> assertTrue(registry.run("config.export")));
        Path zip = awaitOnlyFile(fx.exportDir);
        DialogPane done = awaitDialog(tr("dialog.exportConfig.done"));

        assertTrue(zip.getFileName().toString().startsWith(prefix), zip.toString());
        assertEquals(zip.toString(), done.getContentText(), "the window names where the zip went");
        assertEquals(List.of(), zipsIn(home, prefix), "nothing was exported into " + home);
    }

    private static List<Path> zipsIn(Path dir, String prefix) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> {
                        String name = f.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith(".zip");
                    })
                    .toList();
        }
    }

    /** The one file the background export wrote to {@code dir}. */
    private static Path awaitOnlyFile(Path dir) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> all = files.toList();
                if (!all.isEmpty()) {
                    assertEquals(1, all.size(), all.toString());
                    return all.getFirst();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no export appeared in " + dir);
    }

    /** Waits for the dialog with {@code header} to show, then closes it and returns its pane. */
    private static DialogPane awaitDialog(String header) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            DialogPane pane = FxTestSupport.callOnFx(() -> {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.isShowing()
                            && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane p
                            && header.equals(p.getHeaderText())) {
                        window.hide();
                        return p;
                    }
                }
                return null;
            });
            if (pane != null) {
                return pane;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the \"" + header + "\" dialog never showed");
    }
}
